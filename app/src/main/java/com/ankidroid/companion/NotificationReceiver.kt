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
        val button = when (intent?.action) {
            "ACTION_BUTTON_1" -> AnswerButtons.AGAIN
            "ACTION_BUTTON_2" -> AnswerButtons.HARD
            "ACTION_BUTTON_3" -> AnswerButtons.GOOD
            "ACTION_BUTTON_4" -> AnswerButtons.EASY
            else -> return
        }

        // Looking through several decks can take a while: don't block the main thread.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread {
            try {
                respondCard(appContext, button)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun respondCard(context: Context, button: Int) {
        Log.i("Notifications", "respondCard called")
        val anki = AnkiDroidHelper(context)
        val localState = anki.storedState

        if (localState != null && localState.noteID != -1L) {
            Log.i("Notifications", "localState.cardOrd: ${localState.cardOrd}, localState.noteID: ${localState.noteID}")
            val ease = AnswerButtons.easeFor(button, localState.buttonCount)
            if (ease == null) {
                Log.w("Notifications", "button $button is not valid for a card with ${localState.buttonCount} buttons")
                return
            }
            anki.reviewCard(localState.noteID, localState.cardOrd, localState.cardStartTime, ease)
            LockSession.onAnswered(context)
        }

        CardStateController.showNextCard(context, silent = true, notifyIfNone = true)
    }
}
