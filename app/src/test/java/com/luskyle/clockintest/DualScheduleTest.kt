package com.luskyle.clockintest

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DualScheduleTest {
    private val day = LocalDate.of(2026, 10, 30)
    private val morning = TimeWindow.morning
    private val evening = TimeWindow(17 * 60 + 30, 18 * 60)

    @Test fun allEveningCandidatesAreInsideConfiguredWindow() {
        for (second in 0..evening.seconds) {
            val chosen = TestPolicy.choose(day, second, evening)
            assertTrue(TestPolicy.inWindow(chosen, evening))
            assertFalse(TestPolicy.inWindow(chosen, morning))
        }
        assertEquals(1800, evening.seconds)
        assertFalse(TestPolicy.inWindow(TestPolicy.start(day, evening) - 1, evening))
        assertFalse(TestPolicy.inWindow(TestPolicy.end(day, evening) + 1, evening))
    }

    @Test fun customWindowAndSingleMinuteWork() {
        val custom = TimeWindow(9 * 60 + 5, 9 * 60 + 6)
        assertEquals("09:05 – 09:06", custom.toString())
        assertEquals(61, (0..custom.seconds).count())
        repeat(200) { assertTrue(TestPolicy.inWindow(TestPolicy.choose(day, window = custom), custom)) }
    }

    @Test fun overlapIncludesSharedBoundaryButNotSeparateWindows() {
        assertFalse(morning.overlaps(evening))
        assertTrue(morning.overlaps(TimeWindow(8 * 60, 9 * 60)))
        assertTrue(morning.overlaps(TimeWindow(8 * 60 + 10, 9 * 60)))
        assertTrue(morning.overlaps(TimeWindow(7 * 60, 9 * 60)))
        assertFalse(morning.overlaps(TimeWindow(8 * 60 + 11, 9 * 60)))
    }

    @Test fun invalidWindowsAreRejected() {
        for ((start, end) in listOf(480 to 480, 1080 to 480, -1 to 480, 480 to 1440)) {
            assertThrows(IllegalArgumentException::class.java) { TimeWindow(start, end) }
        }
    }

    @Test fun morningAttemptDoesNotBlockIndependentEveningAttempt() {
        val morningPlan = TestPolicy.choose(day, 100, morning)
        val eveningPlan = TestPolicy.choose(day, 100, evening)
        val morningAttempt = day.toString()
        val eveningAttempt = ""
        assertFalse(TestPolicy.canAttempt(morningPlan, morningPlan, morningAttempt, morning))
        assertTrue(TestPolicy.canAttempt(eveningPlan, eveningPlan, eveningAttempt, evening))
        assertFalse(TestPolicy.canAttempt(eveningPlan, morningPlan, eveningAttempt, evening))
        assertFalse(TestPolicy.canAttempt(eveningPlan, eveningPlan, day.toString(), evening))
        assertNotEquals(ScheduleSlot.MORNING.alarmId, ScheduleSlot.EVENING.alarmId)
    }

    @Test fun eachWindowStartsTodayOnlyBeforeItsOwnStart() {
        val noon = day.atTime(12, 0).atZone(TestPolicy.zone).toInstant().toEpochMilli()
        assertEquals(day.plusDays(1), TestPolicy.firstDay(noon, morning))
        assertEquals(day, TestPolicy.firstDay(noon, evening))
        assertEquals(day.plusDays(1), TestPolicy.firstDay(TestPolicy.start(day, evening), evening))
    }

    @Test fun eveningRestorePreservesPlanAndSkipsExpiredWindow() {
        val planned = TestPolicy.choose(day, 100, evening)
        assertEquals(planned, TestPolicy.restoredPlan(planned - 1, planned, "", 0, evening))
        assertEquals(planned + 2000, TestPolicy.restoredPlan(planned + 1000, planned, "", 0, evening))
        assertEquals(
            TestPolicy.choose(day.plusDays(1), 200, evening),
            TestPolicy.restoredPlan(TestPolicy.end(day, evening) + 1, planned, "", 200, evening)
        )
    }

    @Test fun changedWindowRejectsOldRandomPlan() {
        val oldPlan = TestPolicy.choose(day, 100, morning)
        val custom = TimeWindow(9 * 60, 9 * 60 + 20)
        assertEquals(
            TestPolicy.choose(day, 200, custom),
            TestPolicy.restoredPlan(oldPlan - 1000, oldPlan, "", 200, custom)
        )
    }

    @Test fun midnightBoundaryIsSameDayAndNeverCrossesMidnight() {
        val firstMinute = TimeWindow(0, 1)
        val lastMinute = TimeWindow(1438, 1439)
        assertEquals(day, TestPolicy.day(TestPolicy.choose(day, 60, firstMinute)))
        assertEquals(day, TestPolicy.day(TestPolicy.choose(day, 60, lastMinute)))
        assertFalse(firstMinute.overlaps(lastMinute))
    }
}
