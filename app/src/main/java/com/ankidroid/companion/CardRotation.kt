package com.ankidroid.companion

/**
 * "Change the card at every unlock": when a lock session ends without an answer, another due card is shown at
 * the next lock. AnkiDroid's API can't skip a card without a review, so the app rotates through a set of due
 * cards itself (the first ones of the selected decks, in review order); the skipped cards stay due in
 * AnkiDroid. After an answer, the first card comes back, and the next due card joins the set.
 * Kept free of Android dependencies so it can be unit tested.
 */
object CardRotation {
    /** Default number of cards in the rotation. */
    const val DEFAULT_SIZE = 10
    const val MIN_SIZE = 2
    const val MAX_SIZE = 50

    /** Above this size, the most urgent cards come back less often and the check at lock takes longer. */
    const val WARNING_ABOVE = 20

    /** How long the card must have been visible on the lock screen to count as seen. */
    const val MIN_VISIBLE_MS = 1500L

    /**
     * The rotation set: the first [size] displayable due cards of the decks, in order, filled from the next decks
     * when one has fewer. Each deck is scanned up to its due count, starting with enough cards for what is
     * missing (the query cost grows with the scan size, see [DeckScanner]).
     *
     * @param dueCount the deck's number of due cards, or null if unknown
     * @param query the displayable cards among the next `scanLimit` due cards of the deck, in order
     * @param key identifies a card, so a card selected twice (a parent deck and its subdeck) appears once
     */
    @JvmStatic
    fun <T : Any, K> collect(
        deckIds: List<Long>,
        size: Int,
        dueCount: (deckId: Long) -> Int?,
        query: (deckId: Long, scanLimit: Int) -> List<T>,
        key: (T) -> K,
    ): List<T> {
        val cards = mutableListOf<T>()
        val seen = mutableSetOf<K>()
        for (deckId in deckIds) {
            if (cards.size >= size) break
            val limits = DeckScanner.scanLimits(dueCount(deckId))
            if (limits.isEmpty()) continue
            val missing = size - cards.size
            val steps = limits.filter { it >= missing }.ifEmpty { listOf(limits.last()) }
            for (scanLimit in steps) {
                val found = query(deckId, scanLimit).filter { key(it) !in seen }
                if (found.size >= missing || scanLimit == steps.last()) {
                    found.take(missing).forEach {
                        cards += it
                        seen += key(it)
                    }
                    break
                }
            }
        }
        return cards
    }

    /** The card after [displayed] in the rotation, back to the first one after the last; null if it is empty. */
    @JvmStatic
    fun <T, K> next(rotation: List<T>, displayed: K?, key: (T) -> K): T? {
        if (rotation.isEmpty()) return null
        val index = rotation.indexOfFirst { key(it) == displayed }
        return rotation[(index + 1) % rotation.size]
    }

    /**
     * Whether to change the card at this unlock.
     *
     * @param wasLocked whether a lock session was running (the unlock is detected several ways: only the first counts)
     * @param answered whether a card was answered during the session
     * @param visibleSince when the card first became visible on the lock screen in this session, or 0 if it didn't
     */
    @JvmStatic
    fun shouldRotate(enabled: Boolean, wasLocked: Boolean, answered: Boolean, visibleSince: Long, now: Long): Boolean =
        enabled && wasLocked && !answered && visibleSince > 0 && now - visibleSince >= MIN_VISIBLE_MS
}
