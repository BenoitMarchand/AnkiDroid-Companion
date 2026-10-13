package com.ankidroid.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockSessionTest {

    @Test
    fun oneReviewPerSessionByDefault() {
        assertEquals(LockSession.AfterAnswer.WAIT_FOR_NEXT_LOCK, LockSession.afterAnswer(wallpaperActive = true, severalInARow = false))
    }

    @Test
    fun severalInARowShowsTheNextCardRightAway() {
        assertEquals(LockSession.AfterAnswer.SHOW_NEXT, LockSession.afterAnswer(wallpaperActive = true, severalInARow = true))
    }

    @Test
    fun withoutTheWallpaperTheNextCardIsShownRightAway() {
        // Without screen events, the next lock can't be detected.
        assertEquals(LockSession.AfterAnswer.SHOW_NEXT, LockSession.afterAnswer(wallpaperActive = false, severalInARow = false))
    }

    @Test
    fun notificationIsOnlyPostedWhileLocked() {
        assertTrue(LockSession.mayPost(wallpaperActive = true, locked = true, waitingForNextLock = false))
        assertFalse(LockSession.mayPost(wallpaperActive = true, locked = false, waitingForNextLock = false))
    }

    @Test
    fun nextCardWaitsForTheNextLockAfterTheSessionsReview() {
        assertFalse(LockSession.mayPost(wallpaperActive = true, locked = true, waitingForNextLock = true))
    }

    @Test
    fun withoutTheWallpaperTheNotificationIsAlwaysPosted() {
        assertTrue(LockSession.mayPost(wallpaperActive = false, locked = false, waitingForNextLock = false))
        assertTrue(LockSession.mayPost(wallpaperActive = false, locked = true, waitingForNextLock = true))
    }

    @Test
    fun notificationIsRemovedAtEveryUnlockOnceStarted() {
        assertTrue(LockSession.shouldHideOnUnlock(started = true))
        assertFalse(LockSession.shouldHideOnUnlock(started = false))
    }

    @Test
    fun openAfterUnlockNeverPostsTheNotification() {
        assertFalse(LockSession.mayPost(wallpaperActive = true, locked = true, waitingForNextLock = false, openAfterUnlock = true))
        assertFalse(LockSession.mayPost(wallpaperActive = true, locked = false, waitingForNextLock = false, openAfterUnlock = true))
    }

    @Test
    fun openAfterUnlockWithoutTheWallpaperFallsBackToTheNotification() {
        assertTrue(LockSession.mayPost(wallpaperActive = false, locked = false, waitingForNextLock = false, openAfterUnlock = true))
    }

    @Test
    fun reviewScreenOpensOnceAtUnlockWithACard() {
        assertTrue(LockSession.shouldOpenReview(openAfterUnlock = true, wasLocked = true, hasCard = true, waitingForNextLock = false))
        // The same unlock reported again (USER_PRESENT, then the wallpaper hidden).
        assertFalse(LockSession.shouldOpenReview(openAfterUnlock = true, wasLocked = false, hasCard = true, waitingForNextLock = false))
    }

    @Test
    fun reviewScreenDoesNotOpenWithoutACardOrOnceTheSessionsCardIsAnswered() {
        assertFalse(LockSession.shouldOpenReview(openAfterUnlock = true, wasLocked = true, hasCard = false, waitingForNextLock = false))
        assertFalse(LockSession.shouldOpenReview(openAfterUnlock = true, wasLocked = true, hasCard = true, waitingForNextLock = true))
    }

    @Test
    fun reviewScreenDoesNotOpenInNotificationMode() {
        assertFalse(LockSession.shouldOpenReview(openAfterUnlock = false, wasLocked = true, hasCard = true, waitingForNextLock = false))
    }
}
