package com.ankidroid.companion

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.ZonedDateTime

/**
 * "Review at set times": at each time chosen in the settings, a notification separate from the lock screen one
 * offers the displayed card. It shows "Time for a card" and **Start** first; Start shows the question and starts
 * the review timer, then come the usual "Show answer" and answer buttons. It is the same card as the lock screen
 * notification: answering it in one place moves both to the next card.
 *
 * The alarms are inexact (no special permission): a notification may come a few minutes late. Only the next alarm
 * is set; each one sets the following. They are set again after a reboot, an app update or a clock change.
 * Does ContentProvider I/O when an alarm fires: call [onAlarm] off the main thread.
 */
object ScheduledReview {
    private const val TAG = "Notifications"
    private const val PREFS = "com.ankidroid.companion.scheduled_review"

    /** The card shown after Start, as "noteId:ord". Absent: the notification shows "Time for a card". */
    private const val KEY_STARTED_CARD = "started_card"

    const val NOTIFICATION_ID = 2
    const val ACTION_ALARM = "ACTION_SCHEDULED_ALARM"
    const val ACTION_START = "ACTION_SCHEDULED_START"
    const val ACTION_SHOW_ANSWER = "ACTION_SCHEDULED_SHOW_ANSWER"

    /** Followed by the button number, 1 to 4 ([AnswerButtons]). */
    const val ACTION_BUTTON_PREFIX = "ACTION_SCHEDULED_BUTTON_"

    /** Sets the alarm for the next time after [after], or cancels it (and the notification) when turned off. */
    fun schedule(context: Context, after: ZonedDateTime = ZonedDateTime.now()) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val next = if (ScheduledReviewSettings.isEnabled(context)) {
            ReviewSchedule.next(ScheduledReviewSettings.times(context), after)
        } else {
            null
        }
        if (next == null) {
            Log.i(TAG, "no scheduled review")
            alarms.cancel(alarmIntent(context))
            if (!ScheduledReviewSettings.isEnabled(context)) cancel(context)
            return
        }
        Log.i(TAG, "next scheduled review at $next")
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), alarmIntent(context))
    }

    /** A scheduled time came: posts "Time for a card" with the displayed card, replacing an unanswered one. */
    fun onAlarm(context: Context) {
        // A minute later: an alarm delivered slightly early must not be set again for the same time.
        schedule(context, ZonedDateTime.now().plusMinutes(1))
        if (!ScheduledReviewSettings.isEnabled(context)) return
        CardStateController.syncWithAnkiDroid(context)
        val state = displayedCard(context)
        if (state == null) {
            Log.i(TAG, "scheduled review: no card to review")
            cancel(context)
            return
        }
        prefs(context).edit().remove(KEY_STARTED_CARD).apply()
        post(context, state, alert = true)
    }

    /** Start: shows the question and starts the review timer now. */
    fun start(context: Context) {
        val state = displayedCard(context) ?: return cancel(context)
        val card = CardSync.CardKey(state.noteID, state.cardOrd)
        ReviewTimer.restart(context, card)
        prefs(context).edit().putString(KEY_STARTED_CARD, key(card)).apply()
        post(context, state, alert = false)
    }

    /** Show answer: reveals the answer, in the lock screen notification too. */
    fun revealAnswer(context: Context) {
        val state = startedCard(context) ?: return cancel(context)
        CardReview.revealAnswer(context)
        post(context, state, alert = false)
    }

    /** An answer button: reviews the card like the lock screen notification, then removes this notification. */
    fun answer(context: Context, button: Int) {
        // Not the started card any more: it was answered or changed elsewhere, don't answer the new one blindly.
        if (startedCard(context) != null) CardReview.answer(context, button)
        cancel(context)
    }

    /**
     * The displayed card changed. A started notification shows another card: it is removed. One not started yet
     * is updated silently with the new card's deck.
     */
    fun onCardShown(context: Context, card: CardInfo) {
        if (!isPosted(context)) return
        val started = prefs(context).getString(KEY_STARTED_CARD, null)
        if (started == null) {
            post(context, AnkiDroidHelper(context).storedState ?: return, alert = false)
        } else if (started != key(CardSync.CardKey(card.noteID, card.cardOrd))) {
            cancel(context)
        }
    }

    /** Removes the notification (no card left, reviewing stopped, turned off). */
    fun cancel(context: Context) {
        prefs(context).edit().remove(KEY_STARTED_CARD).apply()
        notificationManager(context).cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, state: StoredState, alert: Boolean) {
        val card = state.toCardInfo()
        val started = prefs(context).getString(KEY_STARTED_CARD, null) == key(CardSync.CardKey(state.noteID, state.cardOrd))
        val revealed = started && CardReview.isAnswerRevealed(context, card)
        Notifications.create().showScheduledReview(context, card, state.deckName, started, revealed, alert)
    }

    private fun displayedCard(context: Context): StoredState? = AnkiDroidHelper(context).storedState?.takeIf { it.hasCard() }

    /** The displayed card, if it is the one started in this notification. */
    private fun startedCard(context: Context): StoredState? {
        val state = displayedCard(context) ?: return null
        return state.takeIf { prefs(context).getString(KEY_STARTED_CARD, null) == key(CardSync.CardKey(it.noteID, it.cardOrd)) }
    }

    private fun isPosted(context: Context): Boolean =
        notificationManager(context).activeNotifications.any { it.id == NOTIFICATION_ID }

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java).setAction(ACTION_ALARM)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun key(card: CardSync.CardKey) = "${card.noteId}:${card.ord}"

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
