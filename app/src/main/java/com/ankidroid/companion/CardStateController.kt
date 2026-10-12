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
     * the notification again if it disappeared (reboot, swiped away). Reviewing starts when the first deck is
     * selected (the first card comes with an alert) and stops when no deck is selected any more.
     *
     * @param alertOnNewCards whether to alert (sound) when a card shows up after "Bravo"
     */
    @JvmStatic
    fun syncWithAnkiDroid(context: Context, alertOnNewCards: Boolean = false): CardSync.Action = synchronized(lock) {
        val anki = AnkiDroidHelper(context)
        val state = anki.storedState
        val deckIds = DeckSelection.load(context)
        val displayed = state?.takeIf { it.hasCard() }
        val firstCard = state == null
        // With "Change the card at every unlock", the displayed card may be any card of the rotation set.
        val rotation = if (deckIds.isNotEmpty() && ReviewSettings.isRotating(context)) {
            anki.queryRotation(deckIds, ReviewSettings.rotationSize(context))
        } else {
            null
        }
        val next = when {
            rotation != null -> rotation.firstOrNull()
            deckIds.isNotEmpty() -> anki.queryNextCard(deckIds)
            else -> null
        }

        val action = CardSync.decide(
            decksSelected = deckIds.isNotEmpty(),
            started = state != null,
            displayed = displayed?.let { CardSync.CardKey(it.noteID, it.cardOrd) },
            displayedQuestion = displayed?.question.orEmpty(),
            next = next?.let { CardSync.CardKey(it.noteID, it.cardOrd) },
            nextQuestion = next?.q.orEmpty(),
            notificationVisible = isNotificationVisible(context),
            notificationHidden = LockSession.isHidden(context),
            displayedInRotation = displayed != null && rotation.orEmpty().any {
                it.noteID == displayed.noteID && it.cardOrd == displayed.cardOrd && it.q == displayed.question
            },
        )
        Log.i(TAG, "sync with AnkiDroid: $action")
        when (action) {
            CardSync.Action.SHOW_NEXT ->
                showCard(context, anki, next!!, silent = !(firstCard || alertOnNewCards && displayed == null))
            CardSync.Action.SHOW_DONE -> showDone(context, anki, deckIds, silent = !firstCard)
            CardSync.Action.REPOST -> post(context, displayed!!.toCardInfo(), displayed.deckName, silent = true)
            CardSync.Action.STOP -> stop(context, anki)
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

    /**
     * "Change the card at every unlock": the displayed card was seen but not answered during the lock session.
     * Stores the next card of the rotation set in the background; it is posted at the next lock.
     */
    @JvmStatic
    fun rotateOnUnlock(context: Context) {
        val appContext = context.applicationContext
        background.execute {
            try {
                rotate(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "card rotation failed", e)
            }
        }
    }

    /** Shows the card after the displayed one in the rotation set, back to the first after the last. */
    private fun rotate(context: Context) {
        synchronized(lock) {
            val anki = AnkiDroidHelper(context)
            val displayed = anki.storedState?.takeIf { it.hasCard() } ?: return
            val deckIds = DeckSelection.load(context)
            if (deckIds.isEmpty()) return
            val rotation = anki.queryRotation(deckIds, ReviewSettings.rotationSize(context))
            val key = CardSync.CardKey(displayed.noteID, displayed.cardOrd)
            val next = CardRotation.next(rotation, key) { CardSync.CardKey(it.noteID, it.cardOrd) } ?: return
            Log.i(TAG, "unlocked without an answer: card ${rotation.indexOf(next) + 1} of ${rotation.size} in the rotation")
            if (next.noteID != displayed.noteID || next.cardOrd != displayed.cardOrd) {
                showCard(context, anki, next, silent = true)
            }
        }
    }

    private fun showCard(context: Context, anki: AnkiDroidHelper, card: CardInfo, silent: Boolean) {
        Log.i(TAG, "next card found in deck ${card.deckId}")
        anki.storeState(card.deckId, card)
        ScheduledReview.onCardShown(context, card)
        if (post(context, card, card.deckName, silent)) {
            ReviewTimer.onCardShown(context, CardSync.CardKey(card.noteID, card.cardOrd))
        }
    }

    private fun showDone(context: Context, anki: AnkiDroidHelper, deckIds: List<Long>, silent: Boolean) {
        Log.i(TAG, "no card to review in the selected decks")
        val emptyCard = CardInfo()
        emptyCard.cardOrd = -1
        emptyCard.noteID = -1
        anki.storeState(deckIds.firstOrNull() ?: -1L, emptyCard)
        ScheduledReview.cancel(context)
        post(context, null, "", silent)
    }

    /** No deck selected: back to "not started", without a notification. */
    private fun stop(context: Context, anki: AnkiDroidHelper) {
        Log.i(TAG, "no deck selected, stopping")
        anki.clearState()
        ScheduledReview.cancel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(Notifications.NOTIFICATION_ID)
    }

    /**
     * Posts the card (or "Bravo", when [card] is null) notification, unless it must wait for the next lock
     * (phone unlocked, or this session's card already answered): it is then posted by the next lock.
     *
     * @return whether the notification was posted
     */
    private fun post(context: Context, card: CardInfo?, deckName: String, silent: Boolean): Boolean {
        if (LockSession.mayPostNow(context)) {
            Notifications.create().showNotification(context, card, deckName, silent)
            return true
        }
        Log.i(TAG, "notification held back until the next lock")
        LockSession.markHidden(context)
        cancelNotification(context)
        return false
    }

    private fun cancelNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Keep the short "next card at your next unlock" message: it times out by itself.
        if (!LockSession.isWaitingForNextLock(context)) manager.cancel(Notifications.NOTIFICATION_ID)
    }

    private fun isNotificationVisible(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.activeNotifications.any { it.id == Notifications.NOTIFICATION_ID }
    }
}
