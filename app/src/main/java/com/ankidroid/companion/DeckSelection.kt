package com.ankidroid.companion

import android.content.Context

/** The decks to review, in priority order: cards are taken from the first deck that has one. */
object DeckSelection {
    private const val PREFS = "com.ankidroid.companion.deck_selection"
    private const val KEY_DECK_IDS = "deck_ids"

    @JvmStatic
    fun load(context: Context): List<Long> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DECK_IDS, null)
        if (stored == null) {
            // Before multi-deck support, only the deck of the current card was remembered.
            val legacyDeckId = AnkiDroidHelper(context).storedState?.deckId
            return if (legacyDeckId != null && legacyDeckId != -1L) listOf(legacyDeckId) else emptyList()
        }
        return stored.split(',').mapNotNull { it.toLongOrNull() }
    }

    @JvmStatic
    fun save(context: Context, deckIds: List<Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DECK_IDS, deckIds.joinToString(","))
            .apply()
    }
}
