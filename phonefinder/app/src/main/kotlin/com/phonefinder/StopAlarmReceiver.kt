package com.phonefinder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the "com.phonefinder.STOP_ALARM" broadcast sent from MainActivity
 * and stops the alarm via AlarmController.
 *
 * The service holds the singleton AlarmController, so we simply tell the
 * service to stop its alarm via a dedicated action.
 */
class StopAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.phonefinder.STOP_ALARM") return
        val stop = Intent(context, PhoneFinderService::class.java).apply {
            action = "com.phonefinder.ACTION_STOP_ALARM"
        }
        context.startService(stop)
    }
}
