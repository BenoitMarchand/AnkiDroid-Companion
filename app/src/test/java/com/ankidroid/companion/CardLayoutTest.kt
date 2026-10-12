package com.ankidroid.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardLayoutTest {
    private val density = 2.5f

    @Test
    fun textAreaAvoidsTheClockAndTheBottomOfTheScreen() {
        val area = CardLayout.textArea(width = 1080, height = 2400, density = density)

        assertEquals(80f, area.left, 0.01f)
        assertEquals(1000f, area.right, 0.01f)
        assertEquals(2400 * CardLayout.AREA_TOP, area.top, 0.01f)
        assertEquals(2400 * CardLayout.AREA_BOTTOM, area.bottom, 0.01f)
    }

    @Test
    fun questionAndAnswerThatFitKeepTheirHeight() {
        assertEquals(300f to 200f, CardLayout.shareHeight(available = 1000f, questionHeight = 300f, answerHeight = 200f))
    }

    @Test
    fun aShortAnswerLeavesTheRestToALongQuestion() {
        assertEquals(800f to 200f, CardLayout.shareHeight(available = 1000f, questionHeight = 1500f, answerHeight = 200f))
    }

    @Test
    fun aShortQuestionLeavesTheRestToALongAnswer() {
        assertEquals(100f to 900f, CardLayout.shareHeight(available = 1000f, questionHeight = 100f, answerHeight = 3000f))
    }

    @Test
    fun aLongQuestionAndALongAnswerGetHalfEach() {
        assertEquals(500f to 500f, CardLayout.shareHeight(available = 1000f, questionHeight = 2000f, answerHeight = 3000f))
    }

    @Test
    fun withoutAnswerTheQuestionGetsEverything() {
        assertEquals(1000f to 0f, CardLayout.shareHeight(available = 1000f, questionHeight = 2000f, answerHeight = 0f))
    }

    @Test
    fun shortTextKeepsTheMaximumSize() {
        val size = CardLayout.fitTextSize(availableHeight = 900f, density = density, scale = 1f) { 100f }

        assertEquals(CardLayout.MAX_TEXT_SIZE_DP * density, size, 0.01f)
    }

    @Test
    fun longTextShrinksUntilItFits() {
        // Pretend the text takes 20 lines at any size: height = 20 * size.
        val size = CardLayout.fitTextSize(availableHeight = 1000f, density = density, scale = 1f) { 20 * it }

        assertTrue(20 * size <= 1000f)
        assertTrue(size < CardLayout.MAX_TEXT_SIZE_DP * density)
    }

    @Test
    fun textThatNeverFitsStopsAtTheMinimumSize() {
        val size = CardLayout.fitTextSize(availableHeight = 10f, density = density, scale = 1f) { 10_000f }

        assertEquals(CardLayout.MIN_TEXT_SIZE_DP * density, size, 0.01f)
    }

    @Test
    fun textSizeScalesWithTheChosenSize() {
        val large = CardLayout.fitTextSize(availableHeight = 900f, density = density, scale = 1.25f) { 100f }

        assertEquals(CardLayout.MAX_TEXT_SIZE_DP * density * 1.25f, large, 0.01f)
    }

    @Test
    fun blockIsCenteredVerticallyOrStartsAtTheTop() {
        val area = CardLayout.Area(0f, 100f, 500f, 500f)

        assertEquals(250f, CardLayout.centeredTop(area, blockHeight = 100f), 0.01f)
        assertEquals(100f, CardLayout.centeredTop(area, blockHeight = 1000f), 0.01f)
    }
}
