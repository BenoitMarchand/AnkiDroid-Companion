package com.ankidroid.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/** Notifications don't survive a reboot: show the current card again, checked against AnkiDroid. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("Notifications", "boot completed, restoring the card notification")
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread {
            try {
                CardStateController.syncWithAnkiDroid(appContext)
            } catch (e: Exception) {
                Log.e("Notifications", "could not restore the notification after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
