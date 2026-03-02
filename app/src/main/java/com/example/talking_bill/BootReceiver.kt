package com.example.talking_bill

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                val prefs = context.getSharedPreferences("NotificationPrefs", Context.MODE_PRIVATE)
                val shouldStart = prefs.getBoolean("service_state", false)
                if (shouldStart) {
                    val serviceIntent = Intent(context, NotificationListenerService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error starting service after boot", e)
            }
        }
    }
} 