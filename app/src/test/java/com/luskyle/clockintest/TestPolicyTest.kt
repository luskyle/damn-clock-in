package com.luskyle.clockintest

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TestPolicyTest {
    private val day = LocalDate.of(2026, 10, 30)

    @Test fun everyPossibleRandomTimeIsInsideWindow() {
        for (second in 0..1200) {
            val chosen = TestPolicy.choose(day, second)
            assertTrue(TestPolicy.inWindow(chosen))
            assertEquals(day, TestPolicy.day(chosen))
        }
        assertFalse(TestPolicy.inWindow(TestPolicy.start(day) - 1))
        assertFalse(TestPolicy.inWindow(TestPolicy.end(day) + 1))
    }

    @Test fun enablingInsideOrAfterWindowStartsTomorrow() {
        assertEquals(day, TestPolicy.firstDay(TestPolicy.start(day) - 1))
        assertEquals(day.plusDays(1), TestPolicy.firstDay(TestPolicy.start(day)))
        assertEquals(day.plusDays(1), TestPolicy.firstDay(TestPolicy.end(day) + 1))
    }

    @Test fun onlyTwoNavigationTargetsAreAllowed() {
        val pkg = TestPolicy.targetPackage
        assertTrue(TestPolicy.allowedNavigation("$pkg:id/tab_apps", ""))
        assertTrue(TestPolicy.allowedNavigation("$pkg:id/name_tv", "考勤打卡"))
        for (text in listOf("定位打卡", "蓝牙打卡", "提交", "确认", "打卡", "更新打卡")) {
            assertFalse(TestPolicy.allowedNavigation("$pkg:id/name_tv", text))
            assertFalse(TestPolicy.allowedNavigation("$pkg:id/tab_apps", text))
        }
        assertFalse(TestPolicy.allowedNavigation("other:id/name_tv", "考勤打卡"))
        assertFalse(TestPolicy.allowedNavigation("", "考勤打卡"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRandomOffsetIsRejected() {
        TestPolicy.choose(day, 1201)
    }

    @Test fun rebootDoesNotResampleExistingPlan() {
        val plan = TestPolicy.choose(day, 400)
        assertEquals(plan, TestPolicy.restoredPlan(TestPolicy.start(day) - 1000, plan, "", 900))
        assertEquals(plan, TestPolicy.restoredPlan(TestPolicy.start(day) + 1000, plan, "", 0))
    }

    @Test fun missedAlarmOnlyResumesInsideWindow() {
        val plan = TestPolicy.choose(day, 400)
        val now = TestPolicy.choose(day, 500)
        assertEquals(now + 1000, TestPolicy.restoredPlan(now, plan, "", 900))
        assertEquals(
            TestPolicy.choose(day.plusDays(1), 900),
            TestPolicy.restoredPlan(TestPolicy.end(day) + 1, plan, "", 900)
        )
    }

    @Test fun repeatedOrLateAttemptIsBlocked() {
        val plan = TestPolicy.choose(day, 400)
        assertTrue(TestPolicy.canAttempt(plan, plan, ""))
        assertFalse(TestPolicy.canAttempt(plan - 1, plan, ""))
        assertFalse(TestPolicy.canAttempt(plan, plan, day.toString()))
        assertFalse(TestPolicy.canAttempt(TestPolicy.end(day) + 1, plan, ""))
        assertFalse(TestPolicy.canAttempt(TestPolicy.start(day.plusDays(1)), plan, ""))
        assertFalse(TestPolicy.canAttempt(plan, 0, ""))
        assertFalse(TestPolicy.canAttempt(plan, TestPolicy.start(day) - 1, ""))
        assertEquals(
            TestPolicy.choose(day.plusDays(1), 900),
            TestPolicy.restoredPlan(plan + 1000, plan, day.toString(), 900)
        )
    }

    @Test fun timezoneIsAlwaysBeijingAndSupportsYearRollover() {
        assertEquals("Asia/Shanghai", TestPolicy.zone.id)
        val lastDay = LocalDate.of(2026, 12, 31)
        assertEquals(LocalDate.of(2027, 1, 1), TestPolicy.firstDay(TestPolicy.end(lastDay)))
        assertEquals("2026-10-29T23:50:00Z", java.time.Instant.ofEpochMilli(TestPolicy.start(day)).toString())
    }

    @Test fun clockRollbackDoesNotScheduleAnotherAttemptToday() {
        assertEquals(
            TestPolicy.choose(day.plusDays(1), 900),
            TestPolicy.restoredPlan(TestPolicy.start(day) - 1000, 0, day.toString(), 900)
        )
    }
}
