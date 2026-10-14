package com.ankidroid.companion

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * Proof of concept for issue #60: the displayed card in a home screen / lock screen widget (Pixel lock screen
 * widgets, Android 16 QPR2). Its main question: do taps on the widget's buttons reach the app while the phone is
 * locked? Each tap is logged (tag "PocCardWidget") and shown at the bottom of the widget with the lock state.
 *
 * The answer buttons record real reviews in AnkiDroid, through [CardReview] like the notification buttons.
 * The widget isn't updated when the card changes elsewhere (notification, sync): tap Refresh.
 *
 * Self-contained so it can be removed easily: this file, the `poc_*` resources and its receiver in the manifest.
 */
class PocCardWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context, manager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (!action.startsWith(ACTION_PREFIX)) return

        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val name = action.removePrefix(ACTION_PREFIX)
        Log.i(TAG, "tap $name, phone ${if (locked) "locked" else "unlocked"}")
        prefs(context).edit()
            .putString(KEY_LAST_TAP, name)
            .putLong(KEY_LAST_TAP_TIME, System.currentTimeMillis())
            .putBoolean(KEY_LAST_TAP_LOCKED, locked)
            .apply()

        val task: (Context) -> Unit = when (action) {
            ACTION_SHOW_ANSWER -> CardReview::revealAnswer
            ACTION_ANSWER + AnswerButtons.AGAIN -> { c -> CardReview.answer(c, AnswerButtons.AGAIN) }
            ACTION_ANSWER + AnswerButtons.HARD -> { c -> CardReview.answer(c, AnswerButtons.HARD) }
            ACTION_ANSWER + AnswerButtons.GOOD -> { c -> CardReview.answer(c, AnswerButtons.GOOD) }
            ACTION_ANSWER + AnswerButtons.EASY -> { c -> CardReview.answer(c, AnswerButtons.EASY) }
            else -> { _ -> }
        }
        // Answering queries AnkiDroid: off the main thread.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread {
            try {
                task(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "could not handle $action", e)
            } finally {
                updateAll(appContext)
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PocCardWidget"
        private const val PREFS = "com.ankidroid.companion.poc_widget"
        private const val KEY_LAST_TAP = "last_tap"
        private const val KEY_LAST_TAP_TIME = "last_tap_time"
        private const val KEY_LAST_TAP_LOCKED = "last_tap_locked"

        private const val ACTION_PREFIX = "com.ankidroid.companion.poc.WIDGET_"
        private const val ACTION_SHOW_ANSWER = ACTION_PREFIX + "SHOW_ANSWER"
        private const val ACTION_REFRESH = ACTION_PREFIX + "REFRESH"
        private const val ACTION_ANSWER = ACTION_PREFIX + "ANSWER_"

        private val answerButtonIds = mapOf(
            AnswerButtons.AGAIN to R.id.pocButton1,
            AnswerButtons.HARD to R.id.pocButton2,
            AnswerButtons.GOOD to R.id.pocButton3,
            AnswerButtons.EASY to R.id.pocButton4,
        )

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PocCardWidget::class.java))
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        private fun update(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
            manager.updateAppWidget(appWidgetIds, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.poc_card_widget)
            val card = AnkiDroidHelper(context).storedState?.takeIf { it.hasCard() }?.toCardInfo()
            // Like the notification: after the session's answer, the next card waits for the next lock.
            val waiting = LockSession.isWaitingForNextLock(context)

            views.setOnClickPendingIntent(R.id.pocShowAnswer, broadcast(context, ACTION_SHOW_ANSWER, 0))
            views.setOnClickPendingIntent(R.id.pocRefresh, broadcast(context, ACTION_REFRESH, 1))
            answerButtonIds.forEach { (button, id) ->
                views.setOnClickPendingIntent(id, broadcast(context, ACTION_ANSWER + button, 1 + button))
            }

            if (card == null || waiting) {
                views.setTextViewText(R.id.pocDeck, "")
                views.setTextViewText(
                    R.id.pocQuestion,
                    context.getString(if (waiting) R.string.next_card_at_next_unlock else R.string.review_no_card),
                )
                views.setViewVisibility(R.id.pocAnswer, View.GONE)
                views.setViewVisibility(R.id.pocShowAnswer, View.GONE)
                views.setViewVisibility(R.id.pocAnswerButtons, View.GONE)
            } else {
                val revealed = CardReview.isAnswerRevealed(context, card)
                views.setTextViewText(R.id.pocDeck, card.deckName)
                views.setTextViewText(R.id.pocQuestion, CardText.clean(card.q))
                views.setTextViewText(R.id.pocAnswer, CardText.clean(card.a))
                views.setViewVisibility(R.id.pocAnswer, if (revealed) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.pocShowAnswer, if (revealed) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.pocAnswerButtons, if (revealed) View.VISIBLE else View.GONE)
                val visible = AnswerButtons.visibleButtons(card.buttonCount)
                answerButtonIds.forEach { (button, id) ->
                    views.setViewVisibility(id, if (button in visible) View.VISIBLE else View.GONE)
                }
            }
            views.setTextViewText(R.id.pocStatus, status(context))
            return views
        }

        private fun status(context: Context): String {
            val prefs = prefs(context)
            val tap = prefs.getString(KEY_LAST_TAP, null) ?: return context.getString(R.string.poc_widget_no_tap)
            val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(prefs.getLong(KEY_LAST_TAP_TIME, 0)))
            val lock = context.getString(
                if (prefs.getBoolean(KEY_LAST_TAP_LOCKED, false)) R.string.poc_widget_locked else R.string.poc_widget_unlocked,
            )
            return context.getString(R.string.poc_widget_last_tap, tap, time, lock)
        }

        private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, PocCardWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
