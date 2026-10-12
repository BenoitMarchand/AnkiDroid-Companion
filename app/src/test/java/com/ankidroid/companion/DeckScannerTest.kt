package com.ankidroid.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun scansProgressively() {
        assertEquals(listOf(1, 10, 120), DeckScanner.scanLimits(120))
        assertEquals(listOf(1, 10), DeckScanner.scanLimits(10))
        assertEquals(listOf(1, 3), DeckScanner.scanLimits(3))
        assertEquals(listOf(1), DeckScanner.scanLimits(1))
        assertEquals(emptyList<Int>(), DeckScanner.scanLimits(0))
        assertEquals(listOf(1, 10, DeckScanner.MAX_SCAN_LIMIT), DeckScanner.scanLimits(10_000))
        assertEquals(listOf(1, 10, DeckScanner.DEFAULT_SCAN_LIMIT), DeckScanner.scanLimits(null))
    }

    @Test
    fun takesTheCardFromTheFirstDeckThatHasOne() {
        val anki = FakeAnki(dueCounts = mapOf(1L to 5, 2L to 5), cards = mapOf(1L to "deck 1 card", 2L to "deck 2 card"))

        assertEquals("deck 1 card", anki.find(listOf(1L, 2L)))
        // A single card is asked for: the cheapest query.
        assertEquals(listOf(1L to 1), anki.queries)
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
        assertEquals(listOf(1L to 1, 1L to 3, 2L to 1), anki.queries)
    }

    @Test
    fun skipsDecksWithNothingDueWithoutQueryingThem() {
        val anki = FakeAnki(dueCounts = mapOf(1L to 0, 2L to 2), cards = mapOf(1L to "never", 2L to "deck 2 card"))

        assertEquals("deck 2 card", anki.find(listOf(1L, 2L)))
        assertEquals(listOf(2L to 1), anki.queries)
    }

    @Test
    fun scansTheWholeDeckUpToTheMaximum() {
        val anki = FakeAnki(dueCounts = mapOf(1L to 120, 2L to 10_000), cards = emptyMap())

        anki.find(listOf(1L, 2L))
        assertEquals(
            listOf(1L to 1, 1L to 10, 1L to 120, 2L to 1, 2L to 10, 2L to DeckScanner.MAX_SCAN_LIMIT),
            anki.queries,
        )
    }

    @Test
    fun usesTheDefaultScanWhenTheDueCountIsUnknown() {
        val anki = FakeAnki(dueCounts = mapOf(1L to null), cards = emptyMap())

        assertNull(anki.find(listOf(1L)))
        assertEquals(listOf(1L to 1, 1L to 10, 1L to DeckScanner.DEFAULT_SCAN_LIMIT), anki.queries)
    }

    @Test
    fun returnsNullWhenNoDeckHasACard() {
        val anki = FakeAnki(dueCounts = mapOf(1L to 2, 2L to 0), cards = emptyMap())

        assertNull(anki.find(listOf(1L, 2L)))
        assertNull(anki.find(emptyList()))
    }

    // --- isDue: is the displayed card still due in AnkiDroid?

    private fun dueList(vararg ids: String) = { limit: Int -> ids.take(limit) }

    @Test
    fun firstDueCardIsFoundWithASingleQuery() {
        val limits = mutableListOf<Int>()
        val due = DeckScanner.isDue("a", dueCount = 40) { limit -> limits += limit; listOf("a", "b").take(limit) }

        assertTrue(due)
        assertEquals(listOf(1), limits)
    }

    @Test
    fun cardFurtherInTheQueueIsStillDue() {
        assertTrue(DeckScanner.isDue("c", dueCount = 3, dueList("a", "b", "c")))
    }

    @Test
    fun cardReviewedElsewhereIsNoLongerDue() {
        assertFalse(DeckScanner.isDue("x", dueCount = 3, dueList("a", "b", "c")))
        assertFalse(DeckScanner.isDue("x", dueCount = 0, dueList()))
    }

    @Test
    fun stopsAsSoonAsTheWholeDueListWasSeen() {
        val limits = mutableListOf<Int>()
        // AnkiDroid's count says 120, but only 2 cards are actually due.
        DeckScanner.isDue("x", dueCount = 120) { limit -> limits += limit; listOf("a", "b").take(limit) }

        assertEquals(listOf(1, 10), limits)
    }
}
