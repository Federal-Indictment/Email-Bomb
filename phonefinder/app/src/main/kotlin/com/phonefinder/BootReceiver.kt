package com.phonefinder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Restarts PhoneFinderService after the device boots — but only if the user
 * had previously enabled the listener (stored in SharedPreferences).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        val prefs = context.getSharedPreferences("phonefinder_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("service_enabled", false)) return

        val serviceIntent = Intent(context, PhoneFinderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
