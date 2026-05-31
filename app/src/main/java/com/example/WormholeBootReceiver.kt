package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class WormholeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        SystemLogRepository.log("BOOT_RECEIVER", "Power up intent caught: $action")
        
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON" || action == "android.net.conn.CONNECTIVITY_CHANGE") {
            SystemLogRepository.log("BOOT_RECEIVER", "Triggering Wormhole Client service setup check. Action: $action")
            
            try {
                val serviceIntent = Intent(context, WormholeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context?.startForegroundService(serviceIntent)
                } else {
                    context?.startService(serviceIntent)
                }
                SystemLogRepository.log("BOOT_RECEIVER", "WormholeService starter broadcast complete.")
            } catch (e: Exception) {
                SystemLogRepository.log("BOOT_RECEIVER_ERR", "Failed silent auto-start: ${e.message}")
            }
        }
    }
}
