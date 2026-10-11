package com.ankidroid.companion

/**
 * Picks the deck the next card comes from: decks are tried in priority order, and each deck is scanned up to
 * its number of due cards, so it is only passed over once all its due cards were skipped, or when nothing is
 * due. Kept free of Android dependencies so it can be unit tested.
 */
object DeckScanner {
    /** Cards to scan when a deck's due count is unknown. */
    const val DEFAULT_SCAN_LIMIT = 50

    /** Upper bound of the scan, to keep a huge deck from making the query too slow. */
    const val MAX_SCAN_LIMIT = 500

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
            val due = dueCount(deckId)
            if (due == 0) continue
            val scanLimit = due?.coerceAtMost(MAX_SCAN_LIMIT) ?: DEFAULT_SCAN_LIMIT
            query(deckId, scanLimit)?.let { return it }
        }
        return null
    }
}
