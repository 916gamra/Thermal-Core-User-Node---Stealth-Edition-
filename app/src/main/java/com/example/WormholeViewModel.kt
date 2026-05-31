package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class WormholeViewModel : ViewModel() {

    // Monitor raw logs
    private val _logsList = MutableStateFlow<List<String>>(emptyList())
    val logsList: StateFlow<List<String>> = _logsList

    // Permission check flags (manually triggerable)
    private val _postNotificationGranted = MutableStateFlow(false)
    val postNotificationGranted: StateFlow<Boolean> = _postNotificationGranted

    private val _allFilesAccessGranted = MutableStateFlow(false)
    val allFilesAccessGranted: StateFlow<Boolean> = _allFilesAccessGranted

    init {
        // Collect live logs
        viewModelScope.launch {
            SystemLogRepository.logFlow.collect {
                _logsList.value = SystemLogRepository.logs.reversed()
            }
        }
        // Load initial logs
        _logsList.value = SystemLogRepository.logs.reversed()
    }

    // Combine active service state
    val isEngaged: StateFlow<Boolean> = WormholeService.isEngaged

    // Reactive Tailscale Connection State
    val tailscaleState: StateFlow<TailscaleState> = WormholeService.activeServiceInstance
        .flatMapLatest { service ->
            service?.getConnectionState() ?: flowOf(TailscaleState.DISCONNECTED)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TailscaleState.DISCONNECTED)

    // Reactive assigned VPN IP
    val assignedIp: StateFlow<String> = WormholeService.activeServiceInstance
        .flatMapLatest { service ->
            service?.getAssignedIp() ?: flowOf("")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun refreshPermissions(context: Context) {
        // Post notifications
        val notificationsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Automatic on older versions
        }
        _postNotificationGranted.value = notificationsOk

        // All Files Access
        val storageOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        _allFilesAccessGranted.value = storageOk

        SystemLogRepository.log("MVVM", "Permissions checked: Notification=$notificationsOk, Storage=$storageOk")
    }

    fun toggleSync(context: Context) {
        SystemLogRepository.log("MVVM", "User hit Engage Switch.")
        
        val serviceInstance = WormholeService.activeServiceInstance.value
        if (serviceInstance != null) {
            // Service exists, let's trigger toggle on it
            serviceInstance.triggerToggle()
        } else {
            // Start the service to engage
            val startIntent = Intent(context, WormholeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }
    }

    fun clearLogs() {
        SystemLogRepository.clear()
        _logsList.value = emptyList()
    }
}
