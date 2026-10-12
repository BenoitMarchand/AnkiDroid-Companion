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
        decksSelected: Boolean = true,
        started: Boolean = true,
        displayed: CardKey? = cardA,
        displayedQuestion: String = "Question A",
        next: CardKey? = cardA,
        nextQuestion: String = "Question A",
        visible: Boolean = true,
        hidden: Boolean = false,
        inRotation: Boolean = false,
    ) = CardSync.decide(decksSelected, started, displayed, displayedQuestion, next, nextQuestion, visible, hidden, inRotation)

    @Test
    fun keepsARotatedCardThatIsNotAnkiDroidsNext() {
        assertEquals(Action.NONE, decide(displayed = cardB, displayedQuestion = "Question B", inRotation = true))
    }

    @Test
    fun postsARotatedCardAgainWhenItsNotificationIsGone() {
        assertEquals(Action.REPOST, decide(displayed = cardB, displayedQuestion = "Question B", inRotation = true, visible = false))
    }

    @Test
    fun replacesARotatedCardThatLeftTheRotation() {
        assertEquals(Action.SHOW_NEXT, decide(displayed = cardB, displayedQuestion = "Question B", inRotation = false))
    }

    @Test
    fun nothingToDoWhenTheDisplayedCardIsStillNext() {
        assertEquals(Action.NONE, decide())
    }

    @Test
    fun nothingWithoutASelectedDeck() {
        assertEquals(Action.NONE, decide(decksSelected = false, started = false, displayed = null, next = null, visible = false))
    }

    @Test
    fun selectingADeckShowsItsFirstCard() {
        assertEquals(Action.SHOW_NEXT, decide(started = false, displayed = null, next = cardB, visible = false))
    }

    @Test
    fun selectingADeckWithNothingDueShowsBravo() {
        assertEquals(Action.SHOW_DONE, decide(started = false, displayed = null, next = null, visible = false))
    }

    @Test
    fun unselectingEveryDeckStops() {
        assertEquals(Action.STOP, decide(decksSelected = false))
        assertEquals(Action.STOP, decide(decksSelected = false, displayed = null, displayedQuestion = ""))
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
