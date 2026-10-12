package com.ankidroid.companion

import android.content.Context
import java.time.ZonedDateTime

/**
 * Pure logic of the scheduled review notifications: the times of day, in minutes since midnight, and the next one.
 */
object ReviewSchedule {
    const val MINUTES_PER_DAY = 24 * 60

    /** The times offered before the user changes them. */
    val DEFAULT_TIMES = listOf(9 * 60, 13 * 60, 19 * 60)

    /** The first time of [times] strictly after [now], today or tomorrow, or null when there is no time. */
    fun next(times: List<Int>, now: ZonedDateTime): ZonedDateTime? {
        val sorted = normalize(times)
        if (sorted.isEmpty()) return null
        for (day in 0L..1L) {
            val date = now.toLocalDate().plusDays(day)
            // A time skipped by a daylight saving change is moved forward by the length of the gap.
            sorted.map { date.atTime(it / 60, it % 60).atZone(now.zone) }
                .firstOrNull { it.isAfter(now) }
                ?.let { return it }
        }
        return null
    }

    /** Valid times, without duplicates, in the order of the day. */
    fun normalize(times: List<Int>): List<Int> = times.filter { it in 0 until MINUTES_PER_DAY }.distinct().sorted()

    /** [times] with [time] added, or [old] replaced by [time] when given. */
    fun put(times: List<Int>, time: Int, old: Int? = null): List<Int> = normalize(times.filter { it != old } + time)

    fun parse(value: String): List<Int> = normalize(value.split(',').mapNotNull { it.trim().toIntOrNull() })

    fun format(times: List<Int>): String = normalize(times).joinToString(",")
}

/** "Review at set times": off by default, with the times of day ([ReviewSchedule]). */
object ScheduledReviewSettings {
    private const val PREFS = "com.ankidroid.companion.scheduled_review_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TIMES = "times"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun times(context: Context): List<Int> =
        prefs(context).getString(KEY_TIMES, null)?.let(ReviewSchedule::parse) ?: ReviewSchedule.DEFAULT_TIMES

    fun setTimes(context: Context, times: List<Int>) {
        prefs(context).edit().putString(KEY_TIMES, ReviewSchedule.format(times)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
