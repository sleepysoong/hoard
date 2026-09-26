package com.sleepysoong.hoard

import com.sleepysoong.hoard.data.formatRelativeTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Session list timestamps. Calendar-boundary cases where naive day-of-year math breaks:
 * New Year, same day-of-year one year apart, leap years, and "a few hours ago but yesterday".
 */
class RelativeTimeTest {
    private val original = TimeZone.getDefault()

    @Before fun seoul() = TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    @After fun restore() = TimeZone.setDefault(original)

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    @Test fun sameDay() {
        assertEquals("방금", formatRelativeTime(at(2026, 9, 26, 12, 0), at(2026, 9, 26, 12, 0)))
        assertEquals("5분 전", formatRelativeTime(at(2026, 9, 26, 11, 55), at(2026, 9, 26, 12, 0)))
        assertEquals("3시간 전", formatRelativeTime(at(2026, 9, 26, 9, 0), at(2026, 9, 26, 12, 0)))
    }

    @Test fun lateLastNightIsYesterdayNotHoursAgo() {
        assertEquals("어제", formatRelativeTime(at(2026, 9, 25, 23, 0), at(2026, 9, 26, 1, 0)))
    }

    @Test fun newYearsEveSeenOnNewYearsDayIsYesterday() {
        assertEquals("어제", formatRelativeTime(at(2025, 12, 31, 20), at(2026, 1, 1, 9)))
    }

    @Test fun sameDayOfYearMinusOneButAYearEarlierIsNotYesterday() {
        assertEquals("2025.3.4", formatRelativeTime(at(2025, 3, 4), at(2026, 3, 5)))
    }

    @Test fun leapDayBoundary() {
        assertEquals("어제", formatRelativeTime(at(2028, 2, 29, 22), at(2028, 3, 1, 8)))
    }

    @Test fun olderDates() {
        assertEquals("9월 20일", formatRelativeTime(at(2026, 9, 20), at(2026, 9, 26)))
        assertEquals("2025.12.30", formatRelativeTime(at(2025, 12, 30), at(2026, 1, 1)))
    }
}
