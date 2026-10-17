package com.ankidroid.companion

import com.ankidroid.companion.CardWidgetContent.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardWidgetContentTest {

    @Test
    fun notStarted_whateverTheRest() {
        assertEquals(Kind.NOT_STARTED, kind(started = false, hasCard = false, reviewing = true, answerRevealed = true))
    }

    @Test
    fun startedWithoutCard_isBravo() {
        assertEquals(Kind.DONE, kind(started = true, hasCard = false, reviewing = false, answerRevealed = false))
        // A stale "revealed" flag doesn't matter without a card.
        assertEquals(Kind.DONE, kind(started = true, hasCard = false, reviewing = true, answerRevealed = true))
    }

    @Test
    fun cardNotStarted_isIdle() {
        assertEquals(Kind.IDLE, kind(started = true, hasCard = true, reviewing = false, answerRevealed = false))
        assertEquals(Kind.IDLE, kind(started = true, hasCard = true, reviewing = false, answerRevealed = true))
    }

    @Test
    fun reviewing_questionUntilTheAnswerIsRevealed() {
        assertEquals(Kind.QUESTION, kind(started = true, hasCard = true, reviewing = true, answerRevealed = false))
        assertEquals(Kind.ANSWER, kind(started = true, hasCard = true, reviewing = true, answerRevealed = true))
    }

    @Test
    fun reviewing_endsAfterTheTimeoutWithoutTap() {
        val tap = 1_000_000L
        val until = CardWidgetContent.activeUntil(tap)
        assertTrue(CardWidgetContent.isReviewing(until, tap + CardWidgetContent.TIMEOUT_MS - 1))
        assertFalse(CardWidgetContent.isReviewing(until, tap + CardWidgetContent.TIMEOUT_MS))
        // Never started.
        assertFalse(CardWidgetContent.isReviewing(0, tap))
    }

    @Test
    fun dueTotal_addsTheSelectedDecks() {
        val names = mapOf(1L to "Spanish", 2L to "German")
        assertEquals(7, CardWidgetContent.dueTotal(listOf(1, 2), names, mapOf(1L to 3, 2L to 4)))
    }

    @Test
    fun dueTotal_subdeckOfASelectedParentIsCountedOnce() {
        val names = mapOf(1L to "Languages", 2L to "Languages::Spanish", 3L to "Languages Extra")
        val counts = mapOf(1L to 10, 2L to 4, 3L to 2)
        // "Languages Extra" isn't a subdeck of "Languages".
        assertEquals(12, CardWidgetContent.dueTotal(listOf(2, 1, 3), names, counts))
    }

    @Test
    fun dueTotal_unknownCount_isNull() {
        assertNull(CardWidgetContent.dueTotal(listOf(1, 2), mapOf(1L to "A", 2L to "B"), mapOf(1L to 3)))
    }

    private fun kind(started: Boolean, hasCard: Boolean, reviewing: Boolean, answerRevealed: Boolean) =
        CardWidgetContent.kind(started, hasCard, reviewing, answerRevealed)

    @Test
    fun backgroundSize_smallWidgetKeepsItsSize() {
        val size = CardWidgetContent.backgroundSize(widthDp = 200, heightDp = 100, density = 2f)
        assertEquals(CardWidgetContent.BackgroundSize(400, 200, 1f), size)
    }

    @Test
    fun backgroundSize_largeWidgetIsScaledDownKeepingItsShape() {
        val size = CardWidgetContent.backgroundSize(widthDp = 400, heightDp = 300, density = 3f)
        assertTrue(size.width * size.height <= CardWidgetContent.MAX_BACKGROUND_PIXELS)
        assertTrue(size.scale < 1f)
        assertEquals(4f / 3, size.width.toFloat() / size.height, 0.01f)
    }

    @Test
    fun backgroundSize_unknownSizeUsesTheMinimum() {
        val size = CardWidgetContent.backgroundSize(widthDp = 0, heightDp = 0, density = 1f)
        assertEquals(CardWidgetContent.DEFAULT_WIDTH_DP, size.width)
        assertEquals(CardWidgetContent.DEFAULT_HEIGHT_DP, size.height)
    }
}
