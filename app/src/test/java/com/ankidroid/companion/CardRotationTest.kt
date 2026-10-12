package com.ankidroid.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardRotationTest {
    /** Due cards by deck, in AnkiDroid's order; cards starting with "sound" can't be displayed. */
    private val decks = mapOf(
        1L to listOf("a1", "sound", "a2", "a3"),
        2L to (1..30).map { "b$it" },
        3L to emptyList(),
    )
    private val queries = mutableListOf<Pair<Long, Int>>()

    private fun collect(deckIds: List<Long>, size: Int, dueDecks: Map<Long, List<String>> = decks) =
        CardRotation.collect(
            deckIds,
            size,
            dueCount = { dueDecks[it]?.size },
            query = { deckId, scanLimit ->
                queries += deckId to scanLimit
                dueDecks.getValue(deckId).take(scanLimit).filter { !it.startsWith("sound") }
            },
            key = { it },
        )

    @Test
    fun takesTheFirstCardsOfTheFirstDeck() {
        assertEquals(listOf("b1", "b2", "b3"), collect(listOf(2L), 3))
    }

    @Test
    fun fillsFromTheNextDecks() {
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2"), collect(listOf(1L, 3L, 2L), 5))
    }

    @Test
    fun scansEnoughCardsForWhatIsMissing() {
        collect(listOf(2L), 10)
        // Not 1 card first: 10 are needed.
        assertEquals(listOf(2L to 10), queries)
    }

    @Test
    fun scansFurtherWhenCardsCantBeDisplayed() {
        val dueDecks = mapOf(1L to listOf("sound") + (1..20).map { "c$it" })
        assertEquals((1..10).map { "c$it" }, collect(listOf(1L), 10, dueDecks))
        assertEquals(listOf(1L to 10, 1L to 21), queries)
    }

    @Test
    fun aCardInTwoSelectedDecksAppearsOnce() {
        val dueDecks = mapOf(1L to listOf("x", "y"), 2L to listOf("y", "z"))
        assertEquals(listOf("x", "y", "z"), collect(listOf(1L, 2L), 5, dueDecks))
    }

    @Test
    fun emptyWhenNothingIsDue() {
        assertEquals(emptyList<String>(), collect(listOf(3L), 10))
    }

    @Test
    fun nextIsTheCardAfterTheDisplayedOne() {
        assertEquals("b", CardRotation.next(listOf("a", "b", "c"), "a") { it })
    }

    @Test
    fun nextGoesBackToTheFirstAfterTheLast() {
        assertEquals("a", CardRotation.next(listOf("a", "b", "c"), "c") { it })
    }

    @Test
    fun nextIsTheFirstWhenTheDisplayedCardLeftTheRotation() {
        assertEquals("a", CardRotation.next(listOf("a", "b", "c"), "gone") { it })
    }

    @Test
    fun noNextInAnEmptyRotation() {
        assertNull(CardRotation.next(emptyList<String>(), "a") { it })
    }

    private fun shouldRotate(
        enabled: Boolean = true,
        wasLocked: Boolean = true,
        answered: Boolean = false,
        visibleSince: Long = 1_000,
        now: Long = 10_000,
    ) = CardRotation.shouldRotate(enabled, wasLocked, answered, visibleSince, now)

    @Test
    fun rotatesWhenTheCardWasSeenAndNotAnswered() {
        assertTrue(shouldRotate())
    }

    @Test
    fun doesNotRotateWhenOff() {
        assertFalse(shouldRotate(enabled = false))
    }

    @Test
    fun doesNotRotateAfterAnAnswer() {
        assertFalse(shouldRotate(answered = true))
    }

    @Test
    fun rotatesOncePerSession() {
        assertFalse(shouldRotate(wasLocked = false))
    }

    @Test
    fun doesNotRotateWhenTheCardWasNeverVisible() {
        assertFalse(shouldRotate(visibleSince = 0))
    }

    @Test
    fun doesNotRotateOnAnInstantUnlock() {
        assertFalse(shouldRotate(visibleSince = 10_000 - CardRotation.MIN_VISIBLE_MS + 1))
        assertTrue(shouldRotate(visibleSince = 10_000 - CardRotation.MIN_VISIBLE_MS))
    }
}
