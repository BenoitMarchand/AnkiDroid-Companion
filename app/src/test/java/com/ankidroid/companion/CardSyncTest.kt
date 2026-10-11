package com.ankidroid.companion

import com.ankidroid.companion.CardSync.Action
import com.ankidroid.companion.CardSync.CardKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSyncTest {
    private val cardA = CardKey(noteId = 1, ord = 0)
    private val cardB = CardKey(noteId = 2, ord = 0)

    private fun decide(
        started: Boolean = true,
        displayed: CardKey? = cardA,
        displayedQuestion: String = "Question A",
        next: CardKey? = cardA,
        nextQuestion: String = "Question A",
        visible: Boolean = true,
        hidden: Boolean = false,
    ) = CardSync.decide(started, displayed, displayedQuestion, next, nextQuestion, visible, hidden)

    @Test
    fun nothingToDoWhenTheDisplayedCardIsStillNext() {
        assertEquals(Action.NONE, decide())
    }

    @Test
    fun nothingBeforeStartReviewing() {
        assertEquals(Action.NONE, decide(started = false, displayed = null, next = cardB, visible = false))
    }

    @Test
    fun cardReviewedInAnkiDroidIsReplacedByTheNextOne() {
        assertEquals(Action.SHOW_NEXT, decide(next = cardB, nextQuestion = "Question B"))
    }

    @Test
    fun cardEditedInAnkiDroidIsShownAgainWithItsNewText() {
        assertEquals(Action.SHOW_NEXT, decide(nextQuestion = "Question A, corrected"))
    }

    @Test
    fun decksFinishedInAnkiDroidShowBravo() {
        assertEquals(Action.SHOW_DONE, decide(next = null))
    }

    @Test
    fun bravoStaysWhenThereIsStillNoCard() {
        assertEquals(Action.NONE, decide(displayed = null, displayedQuestion = "", next = null, nextQuestion = ""))
    }

    @Test
    fun newCardsAfterBravoAreShown() {
        assertEquals(Action.SHOW_NEXT, decide(displayed = null, displayedQuestion = "", next = cardB))
    }

    @Test
    fun missingNotificationIsPostedAgain() {
        // After a reboot, or swiped away.
        assertEquals(Action.REPOST, decide(visible = false))
    }

    @Test
    fun notificationHiddenAtUnlockWaitsForTheNextLock() {
        assertEquals(Action.NONE, decide(visible = false, hidden = true))
    }

    @Test
    fun lockCheckIsThrottled() {
        val tenMinutes = CardSync.LOCK_CHECK_INTERVAL_MS
        assertTrue(CardSync.isLockCheckDue(now = tenMinutes, lastCheck = 0))
        assertFalse(CardSync.isLockCheckDue(now = tenMinutes - 1, lastCheck = 0))
        // The clock went backwards (time zone or manual change): check rather than wait.
        assertTrue(CardSync.isLockCheckDue(now = 0, lastCheck = tenMinutes))
    }
}
