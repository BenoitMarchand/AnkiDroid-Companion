package com.ankidroid.companion

import android.content.Context
import android.content.SharedPreferences

/**
 * Measures how long the displayed card was looked at before being answered: AnkiDroid records it with the review
 * (`ReviewInfo.TIME_TAKEN`, capped by the deck's "Maximum answer seconds").
 *
 * The timer starts when the card is seen: with the lock screen wallpaper, when the screen turns on on the lock
 * screen, or when a new card is shown while it runs. It is cleared when the card is no longer seen without an
 * answer (screen off, unlock). Without the wallpaper there are no screen events: it starts when the card
 * notification is posted. It belongs to one card, so a timer left from another card is never used.
 */
object ReviewTimer {
    private const val PREFS = "com.ankidroid.companion.review_timer"
    private const val KEY_CARD = "card"
    private const val KEY_STARTED_AT = "started_at"

    /** A running timer: [card] seen since [startedAt]. */
    data class Started(val card: CardSync.CardKey, val startedAt: Long)

    /** Time taken to answer [card], in milliseconds: 0 when no timer runs for it (unknown, rather than wrong). */
    fun timeTaken(started: Started?, card: CardSync.CardKey, now: Long): Long =
        if (started != null && started.card == card) (now - started.startedAt).coerceAtLeast(0) else 0

    /**
     * Whether a newly shown card restarts the timer: always without the wallpaper (posting is all we know), and with
     * it only while the timer runs (screen on, lock screen shown); otherwise the next screen on starts it.
     */
    fun startsOnCardShown(wallpaperActive: Boolean, running: Boolean): Boolean = !wallpaperActive || running

    /** The card is seen: starts the timer, unless it already runs for this card. */
    fun onCardSeen(context: Context, card: CardSync.CardKey, now: Long = System.currentTimeMillis()) {
        if (load(context)?.card != card) start(context, card, now)
    }

    /** A new card was shown in place of the previous one. */
    fun onCardShown(context: Context, card: CardSync.CardKey, now: Long = System.currentTimeMillis()) {
        if (startsOnCardShown(CardWallpaperService.isActive(context), running = load(context) != null)) {
            start(context, card, now)
        }
    }

    /** The card is no longer seen and wasn't answered (screen off, unlock): the time spent on it doesn't count. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Time taken to answer [card] now ([timeTaken] with the stored timer). */
    fun timeTaken(context: Context, card: CardSync.CardKey, now: Long = System.currentTimeMillis()): Long =
        timeTaken(load(context), card, now)

    private fun start(context: Context, card: CardSync.CardKey, now: Long) {
        prefs(context).edit()
            .putString(KEY_CARD, "${card.noteId}:${card.ord}")
            .putLong(KEY_STARTED_AT, now)
            .apply()
    }

    private fun load(context: Context): Started? {
        val prefs = prefs(context)
        val card = prefs.getString(KEY_CARD, null)?.split(':') ?: return null
        val noteId = card.getOrNull(0)?.toLongOrNull() ?: return null
        val ord = card.getOrNull(1)?.toIntOrNull() ?: return null
        return Started(CardSync.CardKey(noteId, ord), prefs.getLong(KEY_STARTED_AT, 0))
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
