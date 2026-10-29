package com.luskyle.clockintest

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class AttendancePolicyTest {
    private val page = setOf("考勤打卡", "打卡记录", "蓝牙打卡", "定位打卡")
    private val pkg = TestPolicy.targetPackage

    @Test fun navigationNeverBecomesSubmissionReady() {
        assertFalse(AttendanceAttempt(AttendanceMode.NAVIGATION, false).ready(pkg, page, 1))
    }

    @Test fun locationRequiresKnownPageAndUniqueButton() {
        for (mode in listOf(AttendanceMode.LOCATION)) {
            val run = AttendanceAttempt(mode, false)
            assertTrue(run.ready(pkg, page, 1))
            assertFalse(run.ready("other.app", page, 1))
            assertFalse(run.ready(pkg, page - "考勤打卡", 1))
            assertFalse(run.ready(pkg, page - "打卡记录", 1))
            assertFalse(run.ready(pkg, page - mode.button!!, 1))
            assertFalse(run.ready(pkg, page, 0))
            assertFalse(run.ready(pkg, page, 2))
        }
    }

    @Test fun dryRunCanInspectButCannotMarkSubmission() {
        val run = AttendanceAttempt(AttendanceMode.LOCATION, true)
        assertTrue(run.ready(pkg, page, 1))
        assertThrows(IllegalStateException::class.java) { run.markClick(page) }
        assertFalse(run.clicked)
        assertFalse(run.hasNewSuccess(page + "打卡成功"))
    }

    @Test fun clickCanOnlyBeClaimedOnce() {
        val run = AttendanceAttempt(AttendanceMode.LOCATION, false)
        run.markClick(page)
        assertTrue(run.clicked)
        assertFalse(run.ready(pkg, page, 1))
        assertThrows(IllegalStateException::class.java) { run.markClick(page) }
    }

    @Test fun retryRequiresWaitingAndStopsAtThreeClicks() {
        val run = AttendanceAttempt(AttendanceMode.LOCATION, false)
        assertFalse(run.prepareRetry())
        repeat(3) { index ->
            assertTrue(run.ready(pkg, page, 1))
            run.markClick(page)
            assertEquals(index + 1, run.clicks)
            assertTrue(run.waitingForResult)
            assertEquals(index < 2, run.prepareRetry())
        }
        assertFalse(run.ready(pkg, page, 1))
        assertThrows(IllegalStateException::class.java) { run.markClick(page) }
    }

    @Test fun resumedAttemptPreservesBudgetAndOriginalSuccessBaselineAcrossRetry() {
        val resumed = AttendanceAttempt(AttendanceMode.LOCATION, false, 2)
        resumed.markClick(page)
        assertEquals(3, resumed.clicks)
        assertFalse(resumed.prepareRetry())
        val run = AttendanceAttempt(AttendanceMode.LOCATION, false)
        run.markClick(page)
        assertTrue(run.prepareRetry())
        run.markClick(page)
        assertTrue(run.hasNewSuccess(page + "打卡成功"))
    }

    @Test fun delayedFirstResultStillMatchesAfterAnotherClick() {
        val run = AttendanceAttempt(AttendanceMode.LOCATION, false)
        val firstTime = time("2026-10-29T17:35:00")
        val delayedRecord = "2026-10-29 17:35:01定位打卡"
        run.markClick(page)
        assertTrue(run.prepareRetry())
        run.markClick(page)
        assertTrue(run.hasNewRecord(page + delayedRecord, firstTime, firstTime + 12_000))
        assertThrows(IllegalArgumentException::class.java) {
            AttendanceAttempt(AttendanceMode.LOCATION, false, 4)
        }
    }

    @Test fun successRequiresClickAndNewExactVisibleMarker() {
        val run = AttendanceAttempt(AttendanceMode.LOCATION, false)
        assertFalse(run.hasNewSuccess(page + "打卡成功"))
        run.markClick(page)
        assertFalse(run.hasNewSuccess(page))
        assertFalse(run.hasNewSuccess(page + "打卡失败"))
        assertFalse(run.hasNewSuccess(page + "上次打卡成功"))
        assertTrue(run.hasNewSuccess(page + "打卡成功"))
    }

    @Test fun oldSuccessCannotBeMistakenForThisRequest() {
        val run = AttendanceAttempt(AttendanceMode.LOCATION, false)
        run.markClick(page + "打卡成功")
        assertFalse(run.hasNewSuccess(page + "打卡成功"))
    }

    @Test fun onlyConfirmedActualSuccessRequestsScreenLock() {
        val actual = AttendanceAttempt(AttendanceMode.LOCATION, false)
        assertFalse(actual.shouldLockScreen(true))
        actual.markClick(page)
        assertTrue(actual.shouldLockScreen(true))
        assertFalse(actual.shouldLockScreen(false))
        assertFalse(AttendanceAttempt(AttendanceMode.NAVIGATION, false).shouldLockScreen(true))
        assertFalse(AttendanceAttempt(AttendanceMode.LOCATION, true).shouldLockScreen(true))
        assertFalse(AttendanceAttempt(AttendanceMode.LOCATION, true, 1).shouldLockScreen(true))
    }

    private fun time(value: String): Long = LocalDateTime.parse(value)
        .atZone(TestPolicy.zone).toInstant().toEpochMilli()

    @Test fun newMethodRecordWithinRequestWindowConfirmsResult() {
        val run = AttendanceAttempt(AttendanceMode.LOCATION, false)
        val requestedAt = time("2026-10-29T17:35:00")
        val record = "2026-10-29 17:35:01定位打卡"
        assertFalse(run.hasNewRecord(page + record, requestedAt, requestedAt + 3000))
        run.markClick(page)
        assertTrue(run.hasNewRecord(page + record, requestedAt, requestedAt + 3000))
        assertFalse(run.hasNewRecord(page + "2026-10-29 17:35:01蓝牙打卡", requestedAt, requestedAt + 3000))
        assertFalse(run.hasNewRecord(page + "2026-10-29 17:34:00定位打卡", requestedAt, requestedAt + 3000))
        assertFalse(run.hasNewRecord(page + "2026-10-29 17:40:00定位打卡", requestedAt, requestedAt + 3000))
        assertFalse(run.hasNewRecord(page + record, requestedAt, requestedAt - 1000))
    }

    @Test fun preexistingAndMalformedRecordsCannotConfirmResult() {
        val requestedAt = time("2026-10-29T17:35:00")
        val record = "2026-10-29 17:35:01定位打卡"
        val run = AttendanceAttempt(AttendanceMode.LOCATION, false)
        run.markClick(page + record)
        assertFalse(run.hasNewRecord(page + record, requestedAt, requestedAt + 3000))
        assertFalse(run.hasNewRecord(page + "2026-02-30 17:35:01定位打卡", requestedAt, requestedAt + 3000))
        assertFalse(run.hasNewRecord(page + "2026-10-29 17:35:01定位打卡失败", requestedAt, requestedAt + 3000))
    }
}
