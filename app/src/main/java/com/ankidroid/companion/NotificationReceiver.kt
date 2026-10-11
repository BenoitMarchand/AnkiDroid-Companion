package com.ankidroid.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i("Notifications", "onReceive called")
        if (context == null)
            return
        val ease = when (intent?.action) {
            "ACTION_BUTTON_1" -> AnkiDroidHelper.EASE_1
            "ACTION_BUTTON_2" -> AnkiDroidHelper.EASE_2
            "ACTION_BUTTON_3" -> AnkiDroidHelper.EASE_3
            "ACTION_BUTTON_4" -> AnkiDroidHelper.EASE_4
            else -> return
        }

        // Looking through several decks can take a while: don't block the main thread.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread {
            try {
                respondCard(appContext, ease)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun respondCard(context: Context, ease: Int) {
        Log.i("Notifications", "respondCard called")
        val anki = AnkiDroidHelper(context)
        val localState = anki.storedState

        if (localState != null && localState.noteID != -1L) {
            Log.i("Notifications", "localState.cardOrd: ${localState.cardOrd}, localState.noteID: ${localState.noteID}")
            anki.reviewCard(localState.noteID, localState.cardOrd, localState.cardStartTime, ease)
        }

        CardStateController.showNextCard(context, silent = true, notifyIfNone = true)
    }
}
