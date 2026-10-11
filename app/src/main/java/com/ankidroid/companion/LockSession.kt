package com.ankidroid.companion

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks a lock session (from locking the phone to unlocking it). With the lock screen wallpaper set, the card
 * notification is only visible while the phone is locked: it is removed at every unlock and posted again at the
 * next lock. By default, one card is reviewed per session: after the answer, the next card waits for the next
 * lock ("Next card at your next unlock"), unless "Review several cards in a row" is on.
 *
 * Driven by the lock screen wallpaper service, the component that receives the screen events. Without the
 * wallpaper there are no screen events: the notification stays posted and cards follow each other, as before.
 */
object LockSession {
    private const val PREFS = "com.ankidroid.companion.lock_session"
    private const val KEY_LOCKED = "locked"
    private const val KEY_HIDDEN = "notification_hidden"
    const val KEY_WAITING = "waiting_for_next_lock"

    enum class AfterAnswer {
        /** Show the next card right away. */
        SHOW_NEXT,

        /** Show "Next card at your next unlock"; the next card is posted at the next lock. */
        WAIT_FOR_NEXT_LOCK,
    }

    /** What to do after a card is answered. Without the wallpaper, the next lock can't be detected. */
    fun afterAnswer(wallpaperActive: Boolean, severalInARow: Boolean): AfterAnswer =
        if (wallpaperActive && !severalInARow) AfterAnswer.WAIT_FOR_NEXT_LOCK else AfterAnswer.SHOW_NEXT

    /** Whether the card notification may be posted now; otherwise it waits for the next lock. */
    fun mayPost(wallpaperActive: Boolean, locked: Boolean, waitingForNextLock: Boolean): Boolean =
        !wallpaperActive || (locked && !waitingForNextLock)

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
        prefs.edit().putBoolean(KEY_LOCKED, true).putBoolean(KEY_WAITING, false).apply()
        return prefs.getBoolean(KEY_HIDDEN, false)
    }

    /** The phone was unlocked. Returns whether the notification must be removed. */
    fun onUnlocked(context: Context, started: Boolean): Boolean {
        val hide = shouldHideOnUnlock(started)
        prefs(context).edit().putBoolean(KEY_LOCKED, false).putBoolean(KEY_HIDDEN, hide).apply()
        return hide
    }

    /** A card was answered (from the notification or the review screen). Returns what to do next. */
    fun onAnswered(context: Context): AfterAnswer {
        val after = afterAnswer(CardWallpaperService.isActive(context), ReviewSettings.severalInARow(context))
        if (after == AfterAnswer.WAIT_FOR_NEXT_LOCK) {
            prefs(context).edit().putBoolean(KEY_WAITING, true).apply()
        }
        return after
    }

    /** Whether the card notification may be posted now ([mayPost] with the current state). */
    fun mayPostNow(context: Context): Boolean {
        val prefs = prefs(context)
        return mayPost(
            wallpaperActive = CardWallpaperService.isActive(context),
            locked = prefs.getBoolean(KEY_LOCKED, false),
            waitingForNextLock = prefs.getBoolean(KEY_WAITING, false),
        )
    }

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

    /** "Review several cards in a row": off by default, one card per lock session. */
    fun severalInARow(context: Context): Boolean = prefs(context).getBoolean(KEY_SEVERAL_IN_A_ROW, false)

    fun setSeveralInARow(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEVERAL_IN_A_ROW, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
