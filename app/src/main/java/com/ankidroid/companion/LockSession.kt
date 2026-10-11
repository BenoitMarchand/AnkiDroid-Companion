package com.ankidroid.companion

import android.content.Context

/**
 * Tracks a lock session (from locking the phone to unlocking it), to remove the notification on unlock when
 * the card wasn't answered, and post it again at the next lock. Driven by the lock screen wallpaper engine,
 * which is the component that receives the screen events.
 */
object LockSession {
    private const val PREFS = "com.ankidroid.companion.lock_session"
    private const val KEY_LOCKED = "locked"
    private const val KEY_ANSWERED = "answered"
    private const val KEY_HIDDEN = "notification_hidden"

    /** Whether to remove the notification on unlock. */
    fun shouldHideOnUnlock(hasCard: Boolean, answered: Boolean) = hasCard && !answered

    /** The phone is being locked. Returns whether the notification was hidden and must be posted again. */
    fun onLocked(context: Context): Boolean {
        val prefs = prefs(context)
        val wasHidden = prefs.getBoolean(KEY_HIDDEN, false)
        // The screen can turn off and on several times before an unlock: that's still the same session.
        if (!prefs.getBoolean(KEY_LOCKED, false)) {
            prefs.edit().putBoolean(KEY_LOCKED, true).putBoolean(KEY_ANSWERED, false).apply()
        }
        return wasHidden
    }

    /** The phone was unlocked. Returns whether the notification must be removed. */
    fun onUnlocked(context: Context, hasCard: Boolean): Boolean {
        val prefs = prefs(context)
        val hide = prefs.getBoolean(KEY_LOCKED, false) && shouldHideOnUnlock(hasCard, prefs.getBoolean(KEY_ANSWERED, false))
        prefs.edit().putBoolean(KEY_LOCKED, false).putBoolean(KEY_HIDDEN, hide).apply()
        return hide
    }

    /** A card was answered from the notification. */
    fun onAnswered(context: Context) {
        prefs(context).edit().putBoolean(KEY_ANSWERED, true).apply()
    }

    /** Whether the notification was removed at unlock and waits for the next lock to be posted again. */
    fun isHidden(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDDEN, false)

    /** A card notification was posted: it is visible again. */
    fun onNotificationPosted(context: Context) {
        prefs(context).edit().putBoolean(KEY_HIDDEN, false).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
