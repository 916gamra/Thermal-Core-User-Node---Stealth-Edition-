package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class WormholeService : Service() {

    private val fileSystemRepository = FileSystemRepository()
    private val tailscaleNetworkManager = TailscaleNetworkManager()
    private val ktorServer = WormholeKtorServer(fileSystemRepository)
    private val ftpServer = WormholeFtpServer(fileSystemRepository)
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance.value = this
        SystemLogRepository.log("SERVICE", "Background Service onCreate()")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        SystemLogRepository.log("SERVICE", "onStartCommand action: $action")

        if (action == ACTION_STOP) {
            SystemLogRepository.log("SERVICE", "Stop command received via intent.")
            disengage()
            stopSelf()
            return START_NOT_STICKY
        }

        engage()
        return START_STICKY
    }

    private fun engage() {
        val currentIp = tailscaleNetworkManager.assignedIp.value
        val initialStatusText = if (currentIp.isNotEmpty()) "Active • Listening on http://$currentIp:8080" else "Engaging secure link..."
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(initialStatusText),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(initialStatusText))
        }

        if (_isEngaged.value) {
            SystemLogRepository.log("SERVICE", "Already engaged, verified foreground notification registration.")
            return
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        if (caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            SystemLogRepository.log("WORMHOLE", "No internet connection. Standby.")
            _isEngaged.value = false
            updateNotification("Standby: Waiting for Internet")
            return
        }

        _isEngaged.value = true

        SystemLogRepository.log("WORMHOLE", "Engaging Wormhole Client background host...")

        // Connect tailscale tunnel
        tailscaleNetworkManager.connect(
            onConnected = {
                val ip = tailscaleNetworkManager.assignedIp.value
                SystemLogRepository.log("WORMHOLE", "Tailscale secure interface is active. Virtual IP: $ip")
                
                // Start Ktor server listening inside tailnet
                // Bind Ktor specifically to tailscale IP to prevent exposure on open interfaces
                ktorServer.start(host = ip, port = 8080)

                // Start FTP server silently inside tailnet bound only to tailscale IP
                ftpServer.start(host = ip, port = 2121)

                updateNotification("Active • Listening on http://$ip:8080")
            },
            onError = { errMsg ->
                SystemLogRepository.log("WORMHOLE_ERR", "Link connection error: $errMsg")
                _isEngaged.value = false
                updateNotification("Connection Failed: $errMsg")
                stopSelf()
            }
        )
    }

    private fun disengage() {
        if (!_isEngaged.value) return
        SystemLogRepository.log("WORMHOLE", "Disengaging host...")
        ktorServer.stop()
        ftpServer.stop()
        tailscaleNetworkManager.disconnect {
            _isEngaged.value = false
            SystemLogRepository.log("WORMHOLE", "Server disconnected safely. Status: DISENGAGED.")
            stopForeground(true)
        }
    }

    fun triggerToggle() {
        if (_isEngaged.value) {
            disengage()
            stopSelf()
        } else {
            // Re-trigger start command
            val startIntent = Intent(this, WormholeService::class.java)
            startService(startIntent)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val channelId = "remote_admin_node"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Remote Administration Services",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Alerts indicating active secure administration sessions."
            }
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, WormholeService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Remote Admin Node Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Admin Node", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        SystemLogRepository.log("SERVICE", "Background Service onDestroy()")
        ktorServer.stop()
        ftpServer.stop()
        tailscaleNetworkManager.disconnect {}
        _isEngaged.value = false
        activeServiceInstance.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // Direct access helper
    fun getConnectionState(): StateFlow<TailscaleState> = tailscaleNetworkManager.connectionState
    fun getAssignedIp(): StateFlow<String> = tailscaleNetworkManager.assignedIp

    companion object {
        private const val NOTIFICATION_ID = 8086
        const val ACTION_STOP = "com.example.STOP_SERVICE"

        // Single process quick state access to MVVM
        val activeServiceInstance = MutableStateFlow<WormholeService?>(null)

        private val _isEngaged = MutableStateFlow(false)
        val isEngaged: StateFlow<Boolean> = _isEngaged
    }
}
