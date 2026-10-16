package com.ankidroid.companion

import android.app.AlarmManager
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
import java.util.concurrent.Executors

/**
 * The home screen card widget: the displayed card (the same as the lock screen notification and wallpaper). It
 * shows "Start review", then the question and "Show answer", then the answer and the answer buttons; every answer
 * shows the next card right away. Back to "Start review" when the screen turns off (with the card wallpaper, which
 * receives the screen events) or after [CardWidgetContent.TIMEOUT_MS] without a tap ([CardWidgetContent]).
 *
 * Reviewing goes through [CardReview], like the notification, but an answer from the widget doesn't count as the
 * lock session's review. The review timer starts at "Start review" and again with each new card.
 *
 * Updated by [CardStateController] and [CardReview] whenever the displayed card or its revealed answer changes.
 */
class StandardCardWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val task: (Context) -> Unit = when (intent.action) {
            ACTION_START -> ::start
            ACTION_SHOW_ANSWER -> ::revealAnswer
            ACTION_ANSWER_PREFIX + AnswerButtons.AGAIN -> { c -> answer(c, AnswerButtons.AGAIN) }
            ACTION_ANSWER_PREFIX + AnswerButtons.HARD -> { c -> answer(c, AnswerButtons.HARD) }
            ACTION_ANSWER_PREFIX + AnswerButtons.GOOD -> { c -> answer(c, AnswerButtons.GOOD) }
            ACTION_ANSWER_PREFIX + AnswerButtons.EASY -> { c -> answer(c, AnswerButtons.EASY) }
            ACTION_TIMEOUT -> ::onTimeout
            else -> return
        }
        Log.i(TAG, "widget: ${intent.action}")

        // Answering queries AnkiDroid: don't block the main thread.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        background.execute {
            try {
                task(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "could not handle ${intent.action}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "Notifications"
        private const val PREFS = "com.ankidroid.companion.card_widget"
        private const val KEY_ACTIVE_UNTIL = "active_until"

        const val ACTION_START = "com.ankidroid.companion.WIDGET_START"
        const val ACTION_SHOW_ANSWER = "com.ankidroid.companion.WIDGET_SHOW_ANSWER"
        const val ACTION_ANSWER_PREFIX = "com.ankidroid.companion.WIDGET_ANSWER_"
        private const val ACTION_TIMEOUT = "com.ankidroid.companion.WIDGET_TIMEOUT"

        /** Updates and taps, in order: building the widget may query AnkiDroid (due counts). */
        private val background = Executors.newSingleThreadExecutor()

        private val answerButtonIds = mapOf(
            AnswerButtons.AGAIN to R.id.widgetButton1,
            AnswerButtons.HARD to R.id.widgetButton2,
            AnswerButtons.GOOD to R.id.widgetButton3,
            AnswerButtons.EASY to R.id.widgetButton4,
        )

        /** Redraws every card widget placed on the home screen, in the background. */
        @JvmStatic
        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            background.execute {
                try {
                    redraw(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "could not update the widget", e)
                }
            }
        }

        /** The screen turned off: the card is no longer seen, back to "Start review". */
        @JvmStatic
        fun onScreenOff(context: Context) {
            if (activeUntil(context) == 0L) return
            endReview(context)
            updateAll(context)
        }

        private fun start(context: Context) {
            val state = AnkiDroidHelper(context).storedState?.takeIf { it.hasCard() } ?: return redraw(context)
            continueReview(context)
            ReviewTimer.restart(context, CardSync.CardKey(state.noteID, state.cardOrd))
            redraw(context)
        }

        private fun revealAnswer(context: Context) {
            continueReview(context)
            // Redraws the widget.
            CardReview.revealAnswer(context)
        }

        /** The home screen always moves on to the next card: the answer doesn't count as the lock session's review. */
        private fun answer(context: Context, button: Int) {
            CardReview.answer(context, button, countsForSession = false)
            val next = AnkiDroidHelper(context).storedState?.takeIf { it.hasCard() } ?: return
            if (isReviewing(context)) {
                continueReview(context)
                ReviewTimer.restart(context, CardSync.CardKey(next.noteID, next.cardOrd))
            }
        }

        private fun onTimeout(context: Context) {
            // A tap since the alarm was set postponed it.
            if (isReviewing(context)) return
            Log.i(TAG, "widget: no tap for a while, back to Start review")
            endReview(context)
            redraw(context)
        }

        /** A tap: the review goes on for [CardWidgetContent.TIMEOUT_MS] more. */
        private fun continueReview(context: Context) {
            val until = CardWidgetContent.activeUntil(System.currentTimeMillis())
            prefs(context).edit().putLong(KEY_ACTIVE_UNTIL, until).apply()
            // Not exact (no permission needed): a little late is fine, the widget is drawn from the time anyway.
            context.getSystemService(AlarmManager::class.java)
                .setWindow(AlarmManager.RTC, until, 30_000, timeoutIntent(context))
        }

        private fun endReview(context: Context) {
            prefs(context).edit().putLong(KEY_ACTIVE_UNTIL, 0).apply()
            context.getSystemService(AlarmManager::class.java).cancel(timeoutIntent(context))
            // The card was left unanswered: the time spent on it doesn't count. While locked, the timer belongs to
            // the lock screen.
            if (!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) ReviewTimer.clear(context)
        }

        private fun isReviewing(context: Context) =
            CardWidgetContent.isReviewing(activeUntil(context), System.currentTimeMillis())

        private fun activeUntil(context: Context) = prefs(context).getLong(KEY_ACTIVE_UNTIL, 0)

        private fun redraw(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, StandardCardWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.card_widget)
            val anki = AnkiDroidHelper(context)
            val state = anki.storedState
            val card = state?.takeIf { it.hasCard() }?.toCardInfo()
            val kind = CardWidgetContent.kind(
                started = state != null,
                hasCard = card != null,
                reviewing = isReviewing(context),
                answerRevealed = card != null && CardReview.isAnswerRevealed(context, card),
            )

            views.setOnClickPendingIntent(R.id.widgetStart, broadcast(context, ACTION_START, 0))
            views.setOnClickPendingIntent(R.id.widgetShowAnswer, broadcast(context, ACTION_SHOW_ANSWER, 0))
            answerButtonIds.forEach { (button, id) ->
                views.setOnClickPendingIntent(id, broadcast(context, ACTION_ANSWER_PREFIX + button, button))
            }

            when (kind) {
                CardWidgetContent.Kind.NOT_STARTED, CardWidgetContent.Kind.DONE -> {
                    views.setTextViewText(R.id.widgetDeck, "")
                    views.setTextViewText(
                        R.id.widgetQuestion,
                        context.getString(if (kind == CardWidgetContent.Kind.DONE) R.string.all_cards_done else R.string.widget_not_started),
                    )
                    // Selecting decks happens in the app.
                    views.setOnClickPendingIntent(R.id.widgetQuestion, openApp(context))
                }
                CardWidgetContent.Kind.IDLE -> {
                    // In all the selected decks; the deck of the displayed card if a count is unknown.
                    val due = dueTotal(context, anki)
                    views.setTextViewText(
                        R.id.widgetDeck,
                        if (due == null) card!!.deckName else context.resources.getQuantityString(R.plurals.widget_due, due, due),
                    )
                }
                CardWidgetContent.Kind.QUESTION, CardWidgetContent.Kind.ANSWER -> {
                    views.setTextViewText(R.id.widgetDeck, card!!.deckName)
                    views.setTextViewText(R.id.widgetQuestion, CardText.clean(card.q))
                    views.setTextViewText(R.id.widgetAnswer, CardText.clean(card.a))
                    val visible = AnswerButtons.visibleButtons(card.buttonCount)
                    answerButtonIds.forEach { (button, id) ->
                        views.setViewVisibility(id, if (button in visible) View.VISIBLE else View.GONE)
                    }
                }
            }
            val answer = kind == CardWidgetContent.Kind.ANSWER
            val idle = kind == CardWidgetContent.Kind.IDLE
            views.setViewVisibility(R.id.widgetQuestion, if (idle) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widgetStart, if (idle) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetShowAnswer, if (kind == CardWidgetContent.Kind.QUESTION) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetDivider, if (answer) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetAnswer, if (answer) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetAnswerButtons, if (answer) View.VISIBLE else View.GONE)
            return views
        }

        private fun dueTotal(context: Context, anki: AnkiDroidHelper): Int? {
            val counts = anki.getDeckCounts().mapValues { (_, c) -> c[0] + c[1] + c[2] }
            return CardWidgetContent.dueTotal(DeckSelection.load(context), anki.getDeckNames(), counts)
        }

        private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, StandardCardWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun timeoutIntent(context: Context) = broadcast(context, ACTION_TIMEOUT, 0)

        private fun openApp(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
