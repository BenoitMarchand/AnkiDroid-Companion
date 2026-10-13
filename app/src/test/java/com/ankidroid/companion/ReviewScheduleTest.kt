package com.ankidroid.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReviewScheduleTest {
    private val paris = ZoneId.of("Europe/Paris")

    private fun at(day: Int, hour: Int, minute: Int, month: Int = 10) = ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, paris)

    private fun time(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test
    fun next_isTheFirstTimeLaterToday() {
        assertEquals(at(12, 13, 0), ReviewSchedule.next(listOf(time(9), time(13), time(19)), at(12, 10, 30)))
    }

    @Test
    fun next_afterTheLastTime_isTheFirstTimeTomorrow() {
        assertEquals(at(13, 9, 0), ReviewSchedule.next(listOf(time(9), time(13), time(19)), at(12, 20, 0)))
    }

    @Test
    fun next_exactlyAtATime_isTheFollowingOne() {
        assertEquals(at(12, 19, 0), ReviewSchedule.next(listOf(time(9), time(13), time(19)), at(12, 13, 0)))
    }

    @Test
    fun next_withOneTimeAlreadyPassed_isTomorrow() {
        assertEquals(at(13, 7, 45), ReviewSchedule.next(listOf(time(7, 45)), at(12, 7, 46)))
    }

    @Test
    fun next_ignoresTheOrderOfTheTimes() {
        assertEquals(at(12, 13, 0), ReviewSchedule.next(listOf(time(19), time(9), time(13)), at(12, 10, 0)))
    }

    @Test
    fun next_withoutTimes_isNull() {
        assertNull(ReviewSchedule.next(emptyList(), at(12, 10, 0)))
    }

    @Test
    fun next_inADaylightSavingGap_isMovedForward() {
        // 2026-03-29 in Paris: 02:00 → 03:00.
        val next = ReviewSchedule.next(listOf(time(2, 30)), at(29, 1, 0, month = 3))
        assertEquals(at(29, 3, 30, month = 3), next)
    }

    @Test
    fun put_addsSortsAndRemovesDuplicates() {
        assertEquals(listOf(time(9), time(13)), ReviewSchedule.put(listOf(time(13), time(9)), time(9)))
        assertEquals(listOf(time(8), time(9), time(13)), ReviewSchedule.put(listOf(time(13), time(9)), time(8)))
    }

    @Test
    fun put_withAnOldTime_replacesIt() {
        assertEquals(listOf(time(9), time(14)), ReviewSchedule.put(listOf(time(9), time(13)), time(14), old = time(13)))
    }

    @Test
    fun parseAndFormat_roundTrip() {
        val times = listOf(time(9), time(13, 30))
        assertEquals(times, ReviewSchedule.parse(ReviewSchedule.format(times)))
        assertEquals(emptyList<Int>(), ReviewSchedule.parse(""))
        assertEquals(listOf(time(9)), ReviewSchedule.parse("540, x, 9999"))
    }
}
