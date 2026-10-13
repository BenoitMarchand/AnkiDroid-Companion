package com.ankidroid.companion

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/**
 * Tracks a lock session (from locking the phone to unlocking it). With the lock screen wallpaper set, the card
 * notification is only visible while the phone is locked: it is removed at every unlock and posted again at the
 * next lock. By default, one card is reviewed per session: after the answer, the next card waits for the next
 * lock ("Next card at your next unlock"), unless "Review several cards in a row" is on.
 *
 * With "Open after unlock" ([ReviewSettings.ReviewMode]), there is no card notification: the card is only on the
 * wallpaper, and the review screen opens when the phone is unlocked.
 *
 * Driven by the lock screen wallpaper service, the component that receives the screen events. Without the
 * wallpaper there are no screen events: the notification stays posted and cards follow each other, as before.
 */
object LockSession {
    private const val PREFS = "com.ankidroid.companion.lock_session"
    private const val KEY_LOCKED = "locked"
    private const val KEY_HIDDEN = "notification_hidden"
    const val KEY_WAITING = "waiting_for_next_lock"
    private const val KEY_ANSWERED = "answered"
    private const val KEY_VISIBLE_SINCE = "card_visible_since"

    enum class AfterAnswer {
        /** Show the next card right away. */
        SHOW_NEXT,

        /** Show "Next card at your next unlock"; the next card is posted at the next lock. */
        WAIT_FOR_NEXT_LOCK,
    }

    /** What to do after a card is answered. Without the wallpaper, the next lock can't be detected. */
    fun afterAnswer(wallpaperActive: Boolean, severalInARow: Boolean): AfterAnswer =
        if (wallpaperActive && !severalInARow) AfterAnswer.WAIT_FOR_NEXT_LOCK else AfterAnswer.SHOW_NEXT

    /**
     * Whether the card notification may be posted now; otherwise it waits for the next lock. With "Open after
     * unlock" ([openAfterUnlock], which needs the wallpaper) it is never posted.
     */
    fun mayPost(wallpaperActive: Boolean, locked: Boolean, waitingForNextLock: Boolean, openAfterUnlock: Boolean = false): Boolean =
        !wallpaperActive || (!openAfterUnlock && locked && !waitingForNextLock)

    /**
     * Whether to open the review screen at unlock ("Open after unlock"): once per unlock (the unlock is reported
     * several times), when there is a card and this session's review isn't done.
     */
    fun shouldOpenReview(openAfterUnlock: Boolean, wasLocked: Boolean, hasCard: Boolean, waitingForNextLock: Boolean): Boolean =
        openAfterUnlock && wasLocked && hasCard && !waitingForNextLock

    /** Whether to remove the notification at unlock: always, when there is something displayed. */
    fun shouldHideOnUnlock(started: Boolean) = started

    /**
     * The phone is being locked. Returns whether the notification was removed (at the last unlock, or held back
     * while unlocked) and must be posted now.
     */
    fun onLocked(context: Context): Boolean {
        val prefs = prefs(context)
        // The screen can turn off and on several times before an unlock: that's still the same session.
        if (prefs.getBoolean(KEY_LOCKED, false)) return false
        prefs.edit()
            .putBoolean(KEY_LOCKED, true)
            .putBoolean(KEY_WAITING, false)
            .putBoolean(KEY_ANSWERED, false)
            .putLong(KEY_VISIBLE_SINCE, 0)
            .apply()
        return prefs.getBoolean(KEY_HIDDEN, false)
    }

    /** The screen is on with the lock screen shown: the card is visible. Only the first time of the session counts. */
    fun onCardVisible(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_LOCKED, false) && prefs.getLong(KEY_VISIBLE_SINCE, 0) == 0L) {
            prefs.edit().putLong(KEY_VISIBLE_SINCE, now).apply()
        }
    }

    /** What to do when the phone is unlocked. */
    data class Unlock(
        /** Remove the notification. */
        val hide: Boolean,
        /** Change the card ("Change the card at every unlock"): it is posted at the next lock. */
        val rotate: Boolean,
        /** First report of this unlock: the phone was locked until now. */
        val wasLocked: Boolean,
        /** Open the review screen ("Open after unlock"). */
        val openReview: Boolean,
    )

    /** The phone was unlocked. [hasCard]: a card is displayed (not "Bravo"). */
    fun onUnlocked(context: Context, started: Boolean, hasCard: Boolean, now: Long = System.currentTimeMillis()): Unlock {
        val prefs = prefs(context)
        val hide = shouldHideOnUnlock(started)
        val wasLocked = prefs.getBoolean(KEY_LOCKED, false)
        val openAfterUnlock = ReviewSettings.opensAfterUnlock(context)
        val openReview = shouldOpenReview(openAfterUnlock, wasLocked, hasCard, prefs.getBoolean(KEY_WAITING, false))
        val rotate = CardRotation.shouldRotate(
            // With "Open after unlock", the card is reviewed after the unlock: the review screen rotates it if
            // it is closed without an answer.
            enabled = started && ReviewSettings.rotateOnUnlock(context) && !openAfterUnlock,
            wasLocked = wasLocked,
            answered = prefs.getBoolean(KEY_ANSWERED, false),
            visibleSince = prefs.getLong(KEY_VISIBLE_SINCE, 0),
            now = now,
        )
        prefs.edit().putBoolean(KEY_LOCKED, false).putBoolean(KEY_HIDDEN, hide).apply()
        return Unlock(hide, rotate, wasLocked, openReview)
    }

    /** A card was answered from the notification. Returns what to do next. */
    fun onAnswered(context: Context): AfterAnswer {
        val after = afterAnswer(CardWallpaperService.isActive(context), ReviewSettings.severalInARow(context))
        prefs(context).edit()
            .putBoolean(KEY_ANSWERED, true)
            .apply { if (after == AfterAnswer.WAIT_FOR_NEXT_LOCK) putBoolean(KEY_WAITING, true) }
            .apply()
        return after
    }

    /** Whether the card notification may be posted now ([mayPost] with the current state). */
    fun mayPostNow(context: Context): Boolean {
        val prefs = prefs(context)
        return mayPost(
            wallpaperActive = CardWallpaperService.isActive(context),
            locked = prefs.getBoolean(KEY_LOCKED, false),
            waitingForNextLock = prefs.getBoolean(KEY_WAITING, false),
            openAfterUnlock = ReviewSettings.reviewMode(context) == ReviewSettings.ReviewMode.OPEN_AFTER_UNLOCK &&
                ReviewSettings.canOpenAfterUnlock(context),
        )
    }

    /** Whether the phone is locked, as seen by the lock screen wallpaper. */
    fun isLocked(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCKED, false)

    /** Whether this session's card was answered and the next one waits for the next lock. */
    fun isWaitingForNextLock(context: Context): Boolean = prefs(context).getBoolean(KEY_WAITING, false)

    /** Whether the notification was removed or held back, and waits for the next lock to be posted. */
    fun isHidden(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDDEN, false)

    /** The notification couldn't be posted now: post it at the next lock. */
    fun markHidden(context: Context) {
        prefs(context).edit().putBoolean(KEY_HIDDEN, true).apply()
    }

    /** A card notification was posted: it is visible again. */
    fun onNotificationPosted(context: Context) {
        prefs(context).edit().putBoolean(KEY_HIDDEN, false).apply()
    }

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** User choices about reviewing. */
object ReviewSettings {
    private const val PREFS = "com.ankidroid.companion.review_settings"
    private const val KEY_SEVERAL_IN_A_ROW = "several_in_a_row"
    private const val KEY_ROTATE_ON_UNLOCK = "rotate_on_unlock"
    private const val KEY_ROTATION_SIZE = "rotation_size"
    private const val KEY_REVIEW_MODE = "review_mode"

    /** How the card is reviewed from the lock screen. */
    enum class ReviewMode {
        /** The lock screen notification, expanded in place, without unlocking. */
        NOTIFICATION,

        /** No notification: the card is on the wallpaper, and the review screen opens at unlock. */
        OPEN_AFTER_UNLOCK,
    }

    /** "How to review": the notification by default. */
    fun reviewMode(context: Context): ReviewMode =
        prefs(context).getString(KEY_REVIEW_MODE, null)
            ?.let { name -> ReviewMode.entries.firstOrNull { it.name == name } }
            ?: ReviewMode.NOTIFICATION

    fun setReviewMode(context: Context, mode: ReviewMode) {
        prefs(context).edit().putString(KEY_REVIEW_MODE, mode.name).apply()
    }

    /**
     * Whether the review screen opens at unlock: "Open after unlock" is chosen, the wallpaper gives the unlock
     * events, and "Display over other apps" is allowed. Otherwise the notification is used.
     */
    fun opensAfterUnlock(context: Context): Boolean =
        reviewMode(context) == ReviewMode.OPEN_AFTER_UNLOCK && CardWallpaperService.isActive(context) &&
            canOpenAfterUnlock(context)

    /**
     * Whether the review screen may be opened from the background: Android only lets the wallpaper start it with
     * "Display over other apps" (the wallpaper doesn't count as a visible window).
     */
    fun canOpenAfterUnlock(context: Context): Boolean = Settings.canDrawOverlays(context)


    /** "Review several cards in a row": off by default, one card per lock session. */
    fun severalInARow(context: Context): Boolean = prefs(context).getBoolean(KEY_SEVERAL_IN_A_ROW, false)

    fun setSeveralInARow(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEVERAL_IN_A_ROW, enabled).apply()
    }

    /** "Change the card at every unlock": off by default. Only works with the lock screen wallpaper. */
    fun rotateOnUnlock(context: Context): Boolean = prefs(context).getBoolean(KEY_ROTATE_ON_UNLOCK, false)

    fun setRotateOnUnlock(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ROTATE_ON_UNLOCK, enabled).apply()
    }

    /** Whether the displayed card rotates: the option is on and the wallpaper gives the unlock events. */
    fun isRotating(context: Context): Boolean = rotateOnUnlock(context) && CardWallpaperService.isActive(context)

    /** Number of due cards the card rotates through ([CardRotation.DEFAULT_SIZE] by default). */
    fun rotationSize(context: Context): Int =
        prefs(context).getInt(KEY_ROTATION_SIZE, CardRotation.DEFAULT_SIZE)
            .coerceIn(CardRotation.MIN_SIZE, CardRotation.MAX_SIZE)

    fun setRotationSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_ROTATION_SIZE, size).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
