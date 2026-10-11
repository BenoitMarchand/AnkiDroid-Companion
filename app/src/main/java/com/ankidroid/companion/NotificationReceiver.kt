package com.ankidroid.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/** Handles the notification buttons: "Show answer" and the answer buttons. */
class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i("Notifications", "onReceive called: ${intent?.action}")
        if (context == null)
            return
        val task: (Context) -> Unit = when (intent?.action) {
            Notifications.ACTION_SHOW_ANSWER -> CardReview::revealAnswer
            "ACTION_BUTTON_1" -> { c -> CardReview.answer(c, AnswerButtons.AGAIN) }
            "ACTION_BUTTON_2" -> { c -> CardReview.answer(c, AnswerButtons.HARD) }
            "ACTION_BUTTON_3" -> { c -> CardReview.answer(c, AnswerButtons.GOOD) }
            "ACTION_BUTTON_4" -> { c -> CardReview.answer(c, AnswerButtons.EASY) }
            else -> return
        }

        // Looking through several decks can take a while: don't block the main thread.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread {
            try {
                task(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
