package com.ankidroid.companion

import com.ankidroid.companion.AnswerButtons.AGAIN
import com.ankidroid.companion.AnswerButtons.EASY
import com.ankidroid.companion.AnswerButtons.GOOD
import com.ankidroid.companion.AnswerButtons.HARD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerButtonsTest {

    @Test
    fun fourButtonsMapOneToOne() {
        assertEquals(listOf(AGAIN, HARD, GOOD, EASY), AnswerButtons.visibleButtons(4))
        assertEquals(1, AnswerButtons.easeFor(AGAIN, 4))
        assertEquals(2, AnswerButtons.easeFor(HARD, 4))
        assertEquals(3, AnswerButtons.easeFor(GOOD, 4))
        assertEquals(4, AnswerButtons.easeFor(EASY, 4))
    }

    @Test
    fun threeButtonsHaveNoHard() {
        assertEquals(listOf(AGAIN, GOOD, EASY), AnswerButtons.visibleButtons(3))
        assertEquals(1, AnswerButtons.easeFor(AGAIN, 3))
        assertNull(AnswerButtons.easeFor(HARD, 3))
        assertEquals(2, AnswerButtons.easeFor(GOOD, 3))
        assertEquals(3, AnswerButtons.easeFor(EASY, 3))
    }

    @Test
    fun twoButtonsAreAgainAndGood() {
        assertEquals(listOf(AGAIN, GOOD), AnswerButtons.visibleButtons(2))
        assertEquals(1, AnswerButtons.easeFor(AGAIN, 2))
        assertEquals(2, AnswerButtons.easeFor(GOOD, 2))
        assertNull(AnswerButtons.easeFor(HARD, 2))
        assertNull(AnswerButtons.easeFor(EASY, 2))
    }

    @Test
    fun unknownCountShowsAllButtons() {
        assertEquals(listOf(AGAIN, HARD, GOOD, EASY), AnswerButtons.visibleButtons(0))
        assertEquals(3, AnswerButtons.easeFor(GOOD, 0))
    }
}
