package com.ankidroid.companion

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Single entry point for the displayed card: "move to the next card" and "check it's still in sync with
 * AnkiDroid". Both do ContentProvider I/O: call them off the main thread.
 */
object CardStateController {
    private const val TAG = "Notifications"
    private const val PREFS = "com.ankidroid.companion.card_sync"
    private const val KEY_LAST_LOCK_CHECK = "last_lock_check"

    /** Serializes the updates: an answer and a check at lock must not store their cards at the same time. */
    private val lock = Any()
    private val background = Executors.newSingleThreadExecutor()

    /**
     * Shows the next card from the selected decks.
     *
     * @param notifyIfNone whether to show the "deck finished" notification when there is no card
     * @return the card now shown, or null if there is none
     */
    @JvmStatic
    fun showNextCard(context: Context, silent: Boolean, notifyIfNone: Boolean): CardInfo? = synchronized(lock) {
        val anki = AnkiDroidHelper(context)
        val deckIds = DeckSelection.load(context)
        val card = anki.queryNextCard(deckIds)

        if (card != null) {
            showCard(context, anki, card, silent)
        } else if (notifyIfNone) {
            showDone(context, anki, deckIds, silent)
        }
        card
    }

    /**
     * Brings the displayed card in line with AnkiDroid: shows the actual next card if the displayed one was
     * reviewed, edited or removed in AnkiDroid (or the deck order changed), "Bravo" if no card is left, and posts
     * the notification again if it disappeared (reboot, swiped away). Does nothing before "Start reviewing".
     *
     * @param alertOnNewCards whether to alert (sound) when a card shows up after "Bravo"
     */
    @JvmStatic
    fun syncWithAnkiDroid(context: Context, alertOnNewCards: Boolean = false): CardSync.Action = synchronized(lock) {
        val anki = AnkiDroidHelper(context)
        val state = anki.storedState
        val deckIds = DeckSelection.load(context)
        val next = if (state != null) anki.queryNextCard(deckIds) else null
        val displayed = state?.takeIf { it.hasCard() }

        val action = CardSync.decide(
            started = state != null,
            displayed = displayed?.let { CardSync.CardKey(it.noteID, it.cardOrd) },
            displayedQuestion = displayed?.question.orEmpty(),
            next = next?.let { CardSync.CardKey(it.noteID, it.cardOrd) },
            nextQuestion = next?.q.orEmpty(),
            notificationVisible = isNotificationVisible(context),
            notificationHidden = LockSession.isHidden(context),
        )
        Log.i(TAG, "sync with AnkiDroid: $action")
        when (action) {
            CardSync.Action.SHOW_NEXT -> showCard(context, anki, next!!, silent = !(alertOnNewCards && displayed == null))
            CardSync.Action.SHOW_DONE -> showDone(context, anki, deckIds, silent = true)
            CardSync.Action.REPOST -> Notifications.create().showNotification(context, displayed!!.toCardInfo(), displayed.deckName, true)
            CardSync.Action.NONE -> Unit
        }
        action
    }

    /** Runs [syncWithAnkiDroid] in the background when the phone is locked, at most every 10 minutes. */
    @JvmStatic
    fun syncOnLock(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!CardSync.isLockCheckDue(now, prefs.getLong(KEY_LAST_LOCK_CHECK, 0))) return
        prefs.edit().putLong(KEY_LAST_LOCK_CHECK, now).apply()
        background.execute {
            try {
                syncWithAnkiDroid(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "sync on lock failed", e)
            }
        }
    }

    private fun showCard(context: Context, anki: AnkiDroidHelper, card: CardInfo, silent: Boolean) {
        Log.i(TAG, "next card found in deck ${card.deckId}")
        anki.storeState(card.deckId, card)
        Notifications.create().showNotification(context, card, card.deckName, silent)
    }

    private fun showDone(context: Context, anki: AnkiDroidHelper, deckIds: List<Long>, silent: Boolean) {
        Log.i(TAG, "no card to review in the selected decks")
        val emptyCard = CardInfo()
        emptyCard.cardOrd = -1
        emptyCard.noteID = -1
        anki.storeState(deckIds.firstOrNull() ?: -1L, emptyCard)
        Notifications.create().showNotification(context, null, "", silent)
    }

    private fun isNotificationVisible(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.activeNotifications.any { it.id == Notifications.NOTIFICATION_ID }
    }
}
