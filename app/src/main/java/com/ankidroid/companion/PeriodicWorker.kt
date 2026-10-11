package com.ankidroid.companion

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class PeriodicWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i("BackgroundService", "Periodic worker RAN")
        val localState = AnkiDroidHelper(applicationContext).storedState

        if (localState == null) {
            Log.i("BackgroundService", "Periodic worker - Local state is null")
            return Result.success()
        }

        // A card is already shown, don't do anything.
        if (localState.cardOrd != -1 || localState.noteID != -1L) {
            return Result.success()
        }

        Log.i("BackgroundService", "LocalState card was empty, trying to get a new card")
        CardStateController.showNextCard(applicationContext, silent = false, notifyIfNone = false)
        return Result.success()
    }
}
