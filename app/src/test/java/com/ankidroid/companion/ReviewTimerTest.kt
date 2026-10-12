package com.ankidroid.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTimerTest {
    private val card = CardSync.CardKey(10, 0)

    @Test
    fun timeTakenIsTheTimeSinceTheCardWasSeen() {
        assertEquals(4_500L, ReviewTimer.timeTaken(ReviewTimer.Started(card, 1_000), card, now = 5_500))
    }

    @Test
    fun noTimerGivesZero() {
        assertEquals(0L, ReviewTimer.timeTaken(null, card, now = 5_500))
    }

    @Test
    fun timerOfAnotherCardIsNotUsed() {
        val other = ReviewTimer.Started(CardSync.CardKey(10, 1), 1_000)
        assertEquals(0L, ReviewTimer.timeTaken(other, card, now = 5_500))
    }

    @Test
    fun clockGoingBackGivesZero() {
        assertEquals(0L, ReviewTimer.timeTaken(ReviewTimer.Started(card, 9_000), card, now = 5_500))
    }

    @Test
    fun withoutTheWallpaperANewCardStartsTheTimer() {
        assertTrue(ReviewTimer.startsOnCardShown(wallpaperActive = false, running = false))
    }

    @Test
    fun withTheWallpaperANewCardRestartsTheTimerOnlyWhileItRuns() {
        // Screen on, lock screen shown: the new card is seen right away.
        assertTrue(ReviewTimer.startsOnCardShown(wallpaperActive = true, running = true))
        // Screen off or unlocked: the next screen on starts it.
        assertFalse(ReviewTimer.startsOnCardShown(wallpaperActive = true, running = false))
    }
}
