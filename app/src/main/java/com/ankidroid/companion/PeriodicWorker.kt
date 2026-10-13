package com.ankidroid.companion

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Hourly check that the displayed card is still in sync with AnkiDroid: catches new cards after "Bravo", and
 * cards reviewed in AnkiDroid while the phone stays unlocked.
 */
class PeriodicWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i("BackgroundService", "Periodic worker RAN")
        // A safety net: alarms can be lost (app force-stopped).
        ScheduledReview.schedule(applicationContext)
        if (DeckSelection.load(applicationContext).isEmpty() && AnkiDroidHelper(applicationContext).storedState == null) {
            Log.i("BackgroundService", "Periodic worker - no deck selected")
            return Result.success()
        }
        CardStateController.syncWithAnkiDroid(applicationContext, alertOnNewCards = true)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "WORKER_ANKI"
        private const val INTERVAL_HOURS = 1L

        /** Schedules the hourly check, or updates an existing schedule (it used to be every 8 hours). */
        fun schedule(context: Context) {
            Log.i("BackgroundService", "scheduling the periodic worker")
            val request = PeriodicWorkRequest.Builder(PeriodicWorker::class.java, INTERVAL_HOURS, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
