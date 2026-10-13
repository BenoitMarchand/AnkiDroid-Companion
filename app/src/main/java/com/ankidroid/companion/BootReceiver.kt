package com.ankidroid.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * Notifications and alarms don't survive a reboot: shows the current card again, checked against AnkiDroid, and
 * sets the scheduled review alarm again. After an app update or a clock or time zone change, only the alarm.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Unit
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                Log.i("Notifications", "${intent.action}: setting the scheduled review alarm again")
                ScheduledReview.schedule(context.applicationContext)
                return
            }
            else -> return
        }
        Log.i("Notifications", "boot completed, restoring the card notification")
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread {
            try {
                ScheduledReview.schedule(appContext)
                CardStateController.syncWithAnkiDroid(appContext)
            } catch (e: Exception) {
                Log.e("Notifications", "could not restore the notification after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
