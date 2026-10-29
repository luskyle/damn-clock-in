package com.luskyle.clockintest

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ChinaWorkdaysTest {
    private fun date(value: String) = LocalDate.parse(value)
    private val morning = TimeWindow.morning
    private val evening = TimeWindow(1050, 1052)

    @Test fun allOfficialMakeupDaysAreWorkingIncludingSunday() {
        for (day in listOf("01-04", "02-14", "02-28", "05-09", "09-20", "10-10")) {
            assertTrue(day, ChinaWorkdays.isWorkday(date("2026-$day")))
        }
    }

    @Test fun everyDayOfOfficialHolidayRangesIsSkipped() {
        for ((start, end) in listOf("01-01" to "01-03", "02-15" to "02-23",
            "04-04" to "04-06", "05-01" to "05-05", "06-19" to "06-21",
            "09-25" to "09-27", "10-01" to "10-07")) {
            var day = date("2026-$start")
            while (day <= date("2026-$end")) {
                assertFalse(day.toString(), ChinaWorkdays.isWorkday(day))
                day = day.plusDays(1)
            }
        }
    }

    @Test fun ordinaryWeekdaysAndWeekendsAreDistinguished() {
        assertTrue(ChinaWorkdays.isWorkday(date("2026-10-29")))
        assertFalse(ChinaWorkdays.isWorkday(date("2026-10-31")))
        assertFalse(ChinaWorkdays.isWorkday(date("2026-11-01")))
        assertEquals(date("2026-11-02"), ChinaWorkdays.nextWorkday(date("2026-10-31")))
    }

    @Test fun nationalHolidaySchedulesNextWorkdayAndRetainsRandomOffset() {
        val now = TestPolicy.start(date("2026-10-01"), morning) - 1000
        val result = ChinaWorkdays.plan(now, 0, "", 77, morning)
        assertEquals(TestPolicy.choose(date("2026-10-08"), 77, morning), result)
    }

    @Test fun makeupSaturdayIsNotSkipped() {
        val now = TestPolicy.end(date("2026-10-09"), evening) + 1
        assertEquals(TestPolicy.choose(date("2026-10-10"), 50, morning),
            ChinaWorkdays.plan(now, 0, "", 50, morning))
    }

    @Test fun weekendOldPlanIsDiscardedOnUpgrade() {
        val saturday = date("2026-10-31")
        val old = TestPolicy.choose(saturday, 100, morning)
        assertEquals(TestPolicy.choose(date("2026-11-02"), 10, morning),
            ChinaWorkdays.plan(TestPolicy.start(saturday) - 1000, old, "", 10, morning))
    }

    @Test fun validWorkdayPlanIsStableAndLateRecoveryStaysInWindow() {
        val day = date("2026-10-30")
        val old = TestPolicy.choose(day, 50, evening)
        assertEquals(old, ChinaWorkdays.plan(old - 1000, old, "", 60, evening))
        assertEquals(old + 2000, ChinaWorkdays.plan(old + 1000, old, "", 60, evening))
        assertEquals(TestPolicy.choose(date("2026-11-02"), 60, evening),
            ChinaWorkdays.plan(TestPolicy.end(day, evening) + 1, old, "", 60, evening))
    }

    @Test fun missingYearNeverFallsBackToWeekdayGuess() {
        assertThrows(CalendarUnavailable::class.java) { ChinaWorkdays.isWorkday(date("2027-01-04")) }
        assertThrows(CalendarUnavailable::class.java) { ChinaWorkdays.nextWorkday(date("2027-01-01")) }
        assertThrows(CalendarUnavailable::class.java) {
            ChinaWorkdays.plan(TestPolicy.end(date("2026-12-31")) + 1, 0, "", 0, morning)
        }
    }

    @Test fun eveningDefaultHasExactly121WholeSecondCandidates() {
        assertEquals(1052, ScheduleSlot.EVENING.defaultEnd)
        assertEquals(120, evening.seconds)
        for (second in 0..120) assertTrue(TestPolicy.inWindow(
            TestPolicy.choose(date("2026-10-30"), second, evening), evening
        ))
        assertEquals("17:30 – 17:32", evening.toString())
    }

    @Test fun newPlanInsideWorkdayWindowIsEffectiveToday() {
        val today = date("2026-10-29")
        val now = TestPolicy.end(today, evening) - 10_500
        for (offset in 0..10) {
            val planned = ChinaWorkdays.plan(now, 0, "", offset, evening)
            assertEquals(today, TestPolicy.day(planned))
            assertTrue(planned > now)
            assertTrue(TestPolicy.inWindow(planned, evening))
        }
        repeat(200) {
            val planned = ChinaWorkdays.plan(now, 0, "", null, evening)
            assertTrue(planned > now && planned <= TestPolicy.end(today, evening))
        }
    }

    @Test fun attemptedTodayOrEndedWindowGoesToNextWorkday() {
        val friday = date("2026-10-30")
        val next = date("2026-11-02")
        val now = TestPolicy.end(friday, evening) - 10_000
        assertEquals(TestPolicy.choose(next, 10, evening),
            ChinaWorkdays.plan(now, 0, friday.toString(), 10, evening))
        assertEquals(TestPolicy.choose(next, 10, evening),
            ChinaWorkdays.plan(TestPolicy.end(friday, evening), 0, "", 10, evening))
    }
}
