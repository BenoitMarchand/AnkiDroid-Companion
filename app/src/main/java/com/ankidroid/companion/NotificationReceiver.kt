package com.ankidroid.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * Handles the notification buttons ("Show answer" and the answer buttons, of the lock screen notification and of
 * the scheduled review) and the scheduled review alarm.
 */
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
            ScheduledReview.ACTION_ALARM -> ScheduledReview::onAlarm
            ScheduledReview.ACTION_START -> ScheduledReview::start
            ScheduledReview.ACTION_SHOW_ANSWER -> ScheduledReview::revealAnswer
            ScheduledReview.ACTION_BUTTON_PREFIX + 1 -> { c -> ScheduledReview.answer(c, AnswerButtons.AGAIN) }
            ScheduledReview.ACTION_BUTTON_PREFIX + 2 -> { c -> ScheduledReview.answer(c, AnswerButtons.HARD) }
            ScheduledReview.ACTION_BUTTON_PREFIX + 3 -> { c -> ScheduledReview.answer(c, AnswerButtons.GOOD) }
            ScheduledReview.ACTION_BUTTON_PREFIX + 4 -> { c -> ScheduledReview.answer(c, AnswerButtons.EASY) }
            else -> return
        }

        // Looking through several decks can take a while: don't block the main thread.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread {
            try {
                task(appContext)
            } catch (e: Exception) {
                Log.e("Notifications", "could not handle ${intent.action}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
