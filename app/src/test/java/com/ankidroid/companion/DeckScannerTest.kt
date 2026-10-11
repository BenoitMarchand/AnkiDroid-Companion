package com.ankidroid.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeckScannerTest {

    /** Records the queries made, and returns a card for the decks in [cards]. */
    private class FakeAnki(val dueCounts: Map<Long, Int?>, val cards: Map<Long, String>) {
        val queries = mutableListOf<Pair<Long, Int>>()

        fun find(deckIds: List<Long>): String? = DeckScanner.findNextCard(
            deckIds,
            dueCount = { dueCounts[it] },
            query = { deckId, scanLimit ->
                queries += deckId to scanLimit
                cards[deckId]
            },
        )
    }

    @Test
    fun takesTheCardFromTheFirstDeckThatHasOne() {
        val anki = FakeAnki(dueCounts = mapOf(1L to 5, 2L to 5), cards = mapOf(1L to "deck 1 card", 2L to "deck 2 card"))

        assertEquals("deck 1 card", anki.find(listOf(1L, 2L)))
        assertEquals(listOf(1L to 5), anki.queries)
    }

    @Test
    fun followsThePriorityOrderNotTheDeckIds() {
        val anki = FakeAnki(dueCounts = mapOf(1L to 5, 2L to 5), cards = mapOf(1L to "deck 1 card", 2L to "deck 2 card"))

        assertEquals("deck 2 card", anki.find(listOf(2L, 1L)))
    }

    @Test
    fun movesToTheNextDeckWhenAllDueCardsWereSkipped() {
        // Deck 1 has 3 due cards, all with sound: the query finds nothing displayable.
        val anki = FakeAnki(dueCounts = mapOf(1L to 3, 2L to 4), cards = mapOf(2L to "deck 2 card"))

        assertEquals("deck 2 card", anki.find(listOf(1L, 2L)))
        assertEquals(listOf(1L to 3, 2L to 4), anki.queries)
    }

    @Test
    fun skipsDecksWithNothingDueWithoutQueryingThem() {
        val anki = FakeAnki(dueCounts = mapOf(1L to 0, 2L to 2), cards = mapOf(1L to "never", 2L to "deck 2 card"))

        assertEquals("deck 2 card", anki.find(listOf(1L, 2L)))
        assertEquals(listOf(2L to 2), anki.queries)
    }

    @Test
    fun scansTheWholeDeckUpToTheMaximum() {
        val anki = FakeAnki(dueCounts = mapOf(1L to 120, 2L to 10_000), cards = emptyMap())

        anki.find(listOf(1L, 2L))
        assertEquals(listOf(1L to 120, 2L to DeckScanner.MAX_SCAN_LIMIT), anki.queries)
    }

    @Test
    fun usesTheDefaultScanWhenTheDueCountIsUnknown() {
        val anki = FakeAnki(dueCounts = mapOf(1L to null), cards = mapOf(1L to "card"))

        assertEquals("card", anki.find(listOf(1L)))
        assertEquals(listOf(1L to DeckScanner.DEFAULT_SCAN_LIMIT), anki.queries)
    }

    @Test
    fun returnsNullWhenNoDeckHasACard() {
        val anki = FakeAnki(dueCounts = mapOf(1L to 2, 2L to 0), cards = emptyMap())

        assertNull(anki.find(listOf(1L, 2L)))
        assertNull(anki.find(emptyList()))
    }
}
