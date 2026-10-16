package com.ankidroid.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudyQueueTest {
    @Test
    fun otherDeck_isADeckOtherThanTheSelectedOne() {
        assertEquals(20L, StudyQueue.otherDeck(10, listOf(10, 20, 30)))
    }

    @Test
    fun otherDeck_withOnlyTheSelectedDeck_isTheDefaultDeck() {
        assertEquals(StudyQueue.DEFAULT_DECK_ID, StudyQueue.otherDeck(10, listOf(10)))
    }

    @Test
    fun otherDeck_withOnlyTheDefaultDeckSelected_isNone() {
        assertNull(StudyQueue.otherDeck(StudyQueue.DEFAULT_DECK_ID, listOf(StudyQueue.DEFAULT_DECK_ID)))
    }
}
