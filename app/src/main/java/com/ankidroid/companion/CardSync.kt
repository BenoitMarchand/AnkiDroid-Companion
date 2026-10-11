package com.ankidroid.companion

/**
 * Decides how to bring the displayed card in line with AnkiDroid, which doesn't tell other apps when a card is
 * reviewed, edited or removed. Kept free of Android dependencies so it can be unit tested.
 */
object CardSync {
    /** Identifies a card: a note and the card's ordinal in that note. */
    data class CardKey(val noteId: Long, val ord: Int)

    enum class Action {
        /** The displayed card is still the right one. */
        NONE,

        /** Show AnkiDroid's actual next card. */
        SHOW_NEXT,

        /** No card left: show "Bravo". */
        SHOW_DONE,

        /** Same card, but its notification is gone (reboot, swiped away): post it again. */
        REPOST,
    }

    /** Minimum time between two checks triggered by locking the phone. */
    const val LOCK_CHECK_INTERVAL_MS = 10 * 60 * 1000L

    /**
     * @param started whether the user started reviewing (there is a stored state)
     * @param displayed the card currently displayed, or null if "Bravo" is displayed
     * @param next AnkiDroid's actual next card in the selected decks, or null if there is none
     * @param notificationVisible whether the card notification is currently posted
     * @param notificationHidden whether it was removed on purpose at unlock (posted again at the next lock)
     */
    fun decide(
        started: Boolean,
        displayed: CardKey?,
        displayedQuestion: String,
        next: CardKey?,
        nextQuestion: String,
        notificationVisible: Boolean,
        notificationHidden: Boolean,
    ): Action = when {
        !started -> Action.NONE
        next == null -> if (displayed != null) Action.SHOW_DONE else Action.NONE
        next != displayed || nextQuestion != displayedQuestion -> Action.SHOW_NEXT
        !notificationVisible && !notificationHidden -> Action.REPOST
        else -> Action.NONE
    }

    /** Whether enough time passed since the last check triggered by a lock. */
    fun isLockCheckDue(now: Long, lastCheck: Long): Boolean = now - lastCheck >= LOCK_CHECK_INTERVAL_MS || now < lastCheck
}
