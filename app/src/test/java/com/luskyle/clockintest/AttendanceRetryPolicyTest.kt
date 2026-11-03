package com.luskyle.clockintest

import org.junit.Assert.*
import org.junit.Test

class AttendanceRetryPolicyTest {
    @Test fun loginRequiresAttendanceOrExplicitUserOperationNotPassiveNavigation() {
        assertFalse(AttendanceRetryPolicy.mayLogin(false, false))
        assertTrue(AttendanceRetryPolicy.mayLogin(true, false))
        assertTrue(AttendanceRetryPolicy.mayLogin(false, true))
        assertTrue(AttendanceRetryPolicy.mayLogin(false, false, userInitiated = true))
        assertFalse(AttendanceRetryPolicy.mayLogin(false, false, userInitiated = false))
    }

    @Test fun retryHasNoRoundCountLimitButStopsOnDeadlineSuccessOrCancellation() {
        repeat(100) {
            assertTrue(AttendanceRetryPolicy.mayRetry(true, false, false, false, 0, 120_000))
        }
        assertFalse(AttendanceRetryPolicy.mayRetry(false, false, false, false, 0, 120_000))
        assertFalse(AttendanceRetryPolicy.mayRetry(true, true, false, false, 0, 120_000))
        assertFalse(AttendanceRetryPolicy.mayRetry(true, false, true, false, 0, 120_000))
        assertFalse(AttendanceRetryPolicy.mayRetry(true, false, false, true, 0, 120_000))
        assertFalse(AttendanceRetryPolicy.mayRetry(true, false, false, false, 90_000, 120_000))
    }

    @Test fun lateWindowStartStillHasFullVerificationAndRetryTime() {
        val planned = 1_000_000L
        val end = AttendanceRetryPolicy.verificationEnd(planned)
        assertEquals(planned + 1_800_000, end)
        assertTrue(end - (planned + 8_000) > AttendanceRetryPolicy.SESSION_MS)
    }

    @Test fun fullFlowRestartRetainsOriginalEvidenceAndIgnoresOldSuccess() {
        val attempt = AttendanceAttempt(AttendanceMode.LOCATION, false)
        val time = java.time.LocalDateTime.of(2026, 11, 3, 7, 51, 59)
            .atZone(TestPolicy.zone).toInstant().toEpochMilli()
        val old = "2026-11-03 07:50:00定位打卡"
        val fresh = "2026-11-03 07:52:05定位打卡"
        attempt.markClick(setOf(old, "打卡成功"))
        attempt.restartFlow()
        assertTrue(attempt.clicked)
        assertEquals(0, attempt.clicks)
        assertFalse(attempt.waitingForResult)
        assertFalse(attempt.hasNewRecord(setOf(old), time, time + 60_000))
        assertFalse(attempt.hasNewSuccess(setOf("打卡成功")))
        assertTrue(attempt.hasNewRecord(setOf(old, fresh), time, time + 60_000))
        attempt.markClick(setOf(old, fresh))
        assertEquals(1, attempt.clicks)
        assertTrue(attempt.hasNewRecord(setOf(old, fresh), time, time + 60_000))
    }

    @Test fun previewsCannotRestartIntoRealAttendance() {
        assertThrows(IllegalStateException::class.java) {
            AttendanceAttempt(AttendanceMode.LOCATION, true).restartFlow()
        }
        assertThrows(IllegalStateException::class.java) {
            AttendanceAttempt(AttendanceMode.NAVIGATION, false).restartFlow()
        }
    }
}
