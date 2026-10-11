package com.ankidroid.companion

/**
 * Picks the deck the next card comes from: decks are tried in priority order, and each deck is scanned up to
 * its number of due cards, so it is only passed over once all its due cards were skipped, or when nothing is
 * due. Kept free of Android dependencies so it can be unit tested.
 *
 * The cost of AnkiDroid's due-card query grows with the number of cards asked for (about 30 ms for 1 card,
 * 85 ms for 10, 200 ms for 40, measured in #21), so each deck is scanned progressively: 1 card, then 10, then
 * all its due cards.
 */
object DeckScanner {
    /** Cards to scan when a deck's due count is unknown. */
    const val DEFAULT_SCAN_LIMIT = 50

    /** Upper bound of the scan, to keep a huge deck from making the query too slow. */
    const val MAX_SCAN_LIMIT = 500

    private val PROGRESSIVE_STEPS = listOf(1, 10)

    /** The successive scan sizes for a deck with [dueCount] due cards (null if unknown). */
    @JvmStatic
    fun scanLimits(dueCount: Int?): List<Int> {
        val max = dueCount?.coerceAtMost(MAX_SCAN_LIMIT) ?: DEFAULT_SCAN_LIMIT
        if (max <= 0) return emptyList()
        return (PROGRESSIVE_STEPS + max).map { it.coerceAtMost(max) }.distinct()
    }

    /**
     * @param dueCount the deck's number of due cards (learning + review + new), or null if unknown
     * @param query the first displayable card among the next `scanLimit` due cards of the deck, or null
     * @return the first card found, or null when no deck has one
     */
    @JvmStatic
    fun <T : Any> findNextCard(
        deckIds: List<Long>,
        dueCount: (deckId: Long) -> Int?,
        query: (deckId: Long, scanLimit: Int) -> T?,
    ): T? {
        for (deckId in deckIds) {
            for (scanLimit in scanLimits(dueCount(deckId))) {
                query(deckId, scanLimit)?.let { return it }
            }
        }
        return null
    }

    /**
     * Whether a card is still among the deck's due cards, scanning progressively.
     *
     * @param dueCardIds the identifiers of the next `scanLimit` due cards of the deck, in order
     */
    @JvmStatic
    fun <K> isDue(card: K, dueCount: Int?, dueCardIds: (scanLimit: Int) -> List<K>): Boolean {
        for (scanLimit in scanLimits(dueCount)) {
            val ids = dueCardIds(scanLimit)
            if (card in ids) return true
            // Fewer cards than asked for: the whole due list was seen.
            if (ids.size < scanLimit) return false
        }
        return false
    }
}
