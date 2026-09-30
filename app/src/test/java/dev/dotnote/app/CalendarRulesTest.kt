package dev.dotnote.app

import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class CalendarRulesTest {
    @Test
    fun mondayFirstGridRetainsWholeSixWeekMonth() {
        val weeks = calendarWeeks(YearMonth.of(2026, 11))
        assertEquals(6, weeks.size)
        assertTrue(weeks.first().take(6).all { it == null })
        assertEquals(LocalDate.of(2026, 11, 1), weeks.first()[6])
        assertEquals(LocalDate.of(2026, 11, 30), weeks.last()[0])
        assertTrue(weeks.last().drop(1).all { it == null })
        assertEquals((1..30).toList(), weeks.flatten().filterNotNull().map { it.dayOfMonth })
    }

    @Test
    fun leapYearAndYearRolloverKeepAllDates() {
        assertEquals(29, calendarWeeks(YearMonth.of(2024, 2)).flatten().count { it != null })
        assertEquals(28, calendarWeeks(YearMonth.of(2025, 2)).flatten().count { it != null })
        val january = YearMonth.from(LocalDate.of(2026, 12, 31)).plusMonths(1)
        assertEquals(
            LocalDate.of(2027, 1, 31),
            calendarWeeks(january).flatten().filterNotNull().last(),
        )
    }

    @Test
    fun midnightFollowsLocalCalendarAcrossDaylightSaving() {
        val zone = ZoneId.of("America/Toronto")
        val spring = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, zone)
        val fall = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, zone)
        assertEquals(23L, Duration.between(spring, nextCalendarMidnight(spring)).toHours())
        assertEquals(25L, Duration.between(fall, nextCalendarMidnight(fall)).toHours())
        assertEquals(
            LocalDate.of(2027, 1, 1),
            nextCalendarMidnight(ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, zone)).toLocalDate(),
        )
    }
}
