package com.luskyle.clockintest

import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val fixtureName = "schedule-test-fixture"
    private var prepared = false
    private val context = object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(fixtureName, mode)
    }

    @Before fun prepare() {
        check(ScheduleSlot.entries.none { Store(target).enabled(it) }) {
            "Disable production plans before running instrumented tests"
        }
        check(Store(target).prefs.getLong("wakeTestAt", 0) == 0L && !WakeSession.active()) {
            "Cancel the production wake test before running instrumented tests"
        }
        target.deleteSharedPreferences(fixtureName)
        prepared = true
    }

    @After fun cleanup() {
        if (!prepared) return
        ScheduleSlot.entries.forEach { Scheduler(context).disableSlot(it) }
        target.deleteSharedPreferences(fixtureName)
    }

    @Test fun legacyPlanMigratesOnlyToMorningOnce() {
        context.getSharedPreferences("navigation-test", 0).edit()
            .putBoolean("locationOnlyV1", true)
            .putBoolean("enabled", true).putLong("next", 123456)
            .putString("attempted", "2026-10-29").commit()
        val store = Store(context)
        assertTrue(store.enabled(ScheduleSlot.MORNING))
        assertFalse(store.enabled(ScheduleSlot.EVENING))
        assertEquals(123456L, store.next(ScheduleSlot.MORNING))
        assertEquals("2026-10-29", store.attempted(ScheduleSlot.MORNING))
        store.setEnabled(ScheduleSlot.MORNING, false)
        assertFalse(Store(context).enabled(ScheduleSlot.MORNING))
        assertFalse(store.prefs.contains("enabled"))
    }

    @Test fun attendanceClaimAndBudgetSurviveReopening() {
        val store = Store(context)
        assertFalse(store.prefs.contains("attendanceMode"))
        assertTrue(store.claimAttendance(ScheduleSlot.EVENING, AttendanceMode.LOCATION))
        assertFalse(Store(context).claimAttendance(ScheduleSlot.EVENING, AttendanceMode.LOCATION))
        assertFalse(Store(context).claimAttendance(ScheduleSlot.EVENING, AttendanceMode.LOCATION))
        assertFalse(store.attendanceClaimed(ScheduleSlot.MORNING))
        repeat(AttendanceAttempt.MAX_CLICKS) {
            assertTrue(Store(context).reserveAttendanceClick(ScheduleSlot.EVENING))
        }
        assertFalse(Store(context).reserveAttendanceClick(ScheduleSlot.EVENING))
        assertFalse(Store(context).canRetryAttendance(ScheduleSlot.EVENING))
        assertTrue(Store(context).attendanceClaimed(ScheduleSlot.EVENING))
        assertFalse(store.enabled(ScheduleSlot.MORNING))
        assertFalse(store.enabled(ScheduleSlot.EVENING))
    }

    @Test fun fixedLocationMigrationClosesOldNavigationPlansWithoutResettingGuards() {
        context.getSharedPreferences("navigation-test", 0).edit()
            .putBoolean("dualSchedule", true).putBoolean("workdayV3", true)
            .putString("attendanceMode", "NAVIGATION")
            .putBoolean("EVENING.enabled", true).putLong("EVENING.next", 123456)
            .putString("EVENING.attempted", "2026-10-29")
            .putInt("EVENING.attendanceClicks", 2).commit()
        val store = Store(context)
        assertFalse(store.enabled(ScheduleSlot.EVENING))
        assertEquals(0L, store.next(ScheduleSlot.EVENING))
        assertEquals("2026-10-29", store.attempted(ScheduleSlot.EVENING))
        assertEquals(2, store.prefs.getInt("EVENING.attendanceClicks", 0))
        assertFalse(store.prefs.contains("attendanceMode"))
        store.setEnabled(ScheduleSlot.EVENING, true)
        assertTrue(Store(context).enabled(ScheduleSlot.EVENING))
    }

    @Test fun fixedLocationMigrationPreservesPreviouslyConfirmedLocationPlan() {
        context.getSharedPreferences("navigation-test", 0).edit()
            .putBoolean("dualSchedule", true).putBoolean("workdayV3", true)
            .putString("attendanceMode", "LOCATION")
            .putBoolean("EVENING.enabled", true).putLong("EVENING.next", 123456).commit()
        val store = Store(context)
        assertTrue(store.enabled(ScheduleSlot.EVENING))
        assertEquals(123456L, store.next(ScheduleSlot.EVENING))
        assertFalse(store.prefs.contains("attendanceMode"))
    }

    @Test fun successfulAttendanceCannotBeResumedEvenWithUnusedBudget() {
        val store = Store(context)
        assertTrue(store.claimAttendance(ScheduleSlot.EVENING, AttendanceMode.LOCATION))
        assertTrue(store.reserveAttendanceClick(ScheduleSlot.EVENING))
        assertTrue(Store(context).canRetryAttendance(ScheduleSlot.EVENING))
        store.attendanceResult(ScheduleSlot.EVENING, "已核验 BOE 本次新增定位打卡记录", success = true)
        assertFalse(Store(context).canRetryAttendance(ScheduleSlot.EVENING))
        assertEquals(1, Store(context).attendanceClicks(ScheduleSlot.EVENING))
    }

    @Test fun windowsAndAttemptsPersistIndependently() {
        val store = Store(context)
        val custom = TimeWindow(9 * 60, 9 * 60 + 15)
        store.saveWindow(ScheduleSlot.MORNING, custom)
        store.setAttempted(ScheduleSlot.MORNING, "2026-10-30")
        val reopened = Store(context)
        assertEquals(custom, reopened.window(ScheduleSlot.MORNING))
        assertEquals(TimeWindow(1050, 1052), reopened.window(ScheduleSlot.EVENING))
        assertEquals("", reopened.attempted(ScheduleSlot.EVENING))
        assertEquals("2026-10-30", reopened.attempted(ScheduleSlot.MORNING))
    }

    @Test fun editingCancelsOnlyEditedPlanAndRetainsAttemptGuard() {
        val store = Store(context)
        store.setEnabled(ScheduleSlot.MORNING, true)
        store.setNext(ScheduleSlot.MORNING, 123456)
        store.setAttempted(ScheduleSlot.MORNING, "2026-10-30")
        store.setEnabled(ScheduleSlot.EVENING, true)
        store.setNext(ScheduleSlot.EVENING, 987654)
        assertTrue(Scheduler(context).changeWindow(ScheduleSlot.MORNING, TimeWindow(540, 550)))
        assertFalse(store.enabled(ScheduleSlot.MORNING))
        assertEquals(0L, store.next(ScheduleSlot.MORNING))
        assertEquals("2026-10-30", store.attempted(ScheduleSlot.MORNING))
        assertTrue(store.enabled(ScheduleSlot.EVENING))
        assertEquals(987654L, store.next(ScheduleSlot.EVENING))
    }

    @Test fun overlappingChangeDoesNotModifyEitherPlan() {
        val store = Store(context)
        val before = store.window(ScheduleSlot.MORNING)
        assertFalse(Scheduler(context).changeWindow(ScheduleSlot.MORNING, TimeWindow(1040, 1060)))
        assertEquals(before, store.window(ScheduleSlot.MORNING))
        assertEquals(TimeWindow(1050, 1052), store.window(ScheduleSlot.EVENING))
    }

    @Test fun oldEveningDefaultMigratesAndDisablesOnlyEvening() {
        context.getSharedPreferences("navigation-test", 0).edit()
            .putBoolean("locationOnlyV1", true)
            .putBoolean("dualSchedule", true)
            .putInt("EVENING.start", 1050).putInt("EVENING.end", 1080)
            .putBoolean("EVENING.enabled", true).putLong("EVENING.next", 123456)
            .putString("EVENING.attempted", "2026-10-29")
            .putBoolean("MORNING.enabled", true).commit()
        val store = Store(context)
        assertEquals(TimeWindow(1050, 1052), store.window(ScheduleSlot.EVENING))
        assertFalse(store.enabled(ScheduleSlot.EVENING))
        assertEquals(0L, store.next(ScheduleSlot.EVENING))
        assertTrue(store.enabled(ScheduleSlot.MORNING))
        assertEquals("2026-10-29", store.attempted(ScheduleSlot.EVENING))
    }

    @Test fun customEveningWindowIsNotOverwrittenOnUpgrade() {
        context.getSharedPreferences("navigation-test", 0).edit()
            .putBoolean("dualSchedule", true)
            .putInt("EVENING.start", 1080).putInt("EVENING.end", 1090).commit()
        assertEquals(TimeWindow(1080, 1090), Store(context).window(ScheduleSlot.EVENING))
    }

    @Test fun calendarResourceIsAvailableOnAndroidAndMissingYearStops() {
        assertTrue(ChinaWorkdays.isWorkday(java.time.LocalDate.of(2026, 9, 20)))
        assertFalse(ChinaWorkdays.isWorkday(java.time.LocalDate.of(2026, 10, 1)))
        assertThrows(CalendarUnavailable::class.java) {
            ChinaWorkdays.isWorkday(java.time.LocalDate.of(2027, 1, 1))
        }
    }

    @Test fun expiredOneShotIsConsumedWithoutWakingOrChangingDailyPlans() {
        val store = Store(context)
        store.prefs.edit().putLong("wakeTestAt", System.currentTimeMillis() - 70_000).commit()
        Scheduler(context).fireWakeTest()
        assertFalse(store.prefs.contains("wakeTestAt"))
        assertFalse(WakeSession.active())
        assertFalse(store.enabled(ScheduleSlot.MORNING))
        assertFalse(store.enabled(ScheduleSlot.EVENING))
        assertTrue(store.history().contains("单次息屏测试已过期"))
        Scheduler(context).fireWakeTest()
        assertFalse(WakeSession.active())
    }

    @Test fun prematureOneShotDoesNotConsumeRequest() {
        val time = System.currentTimeMillis() + 120_000
        val store = Store(context)
        store.prefs.edit().putLong("wakeTestAt", time).commit()
        Scheduler(context).fireWakeTest()
        assertEquals(time, store.prefs.getLong("wakeTestAt", 0))
        assertFalse(WakeSession.active())
    }

    @Test fun stopClearsOneShotAndWakeSession() {
        val store = Store(context)
        store.prefs.edit().putLong("wakeTestAt", System.currentTimeMillis() + 120_000).commit()
        Scheduler(context).disable()
        assertFalse(store.prefs.contains("wakeTestAt"))
        assertFalse(WakeSession.active())
        assertNull(WakeSession.takeDeadline())
    }

    @Test fun twoRealAlarmsRestoreAndCancelIndependently() {
        val alarms = target.getSystemService(AlarmManager::class.java)
        check(Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            "Grant exact-alarm permission through Settings before this test"
        }
        val store = Store(context)
        val tomorrow = ChinaWorkdays.nextWorkday(TestPolicy.day(System.currentTimeMillis()).plusDays(1))
        val morning = TestPolicy.choose(tomorrow, 100, store.window(ScheduleSlot.MORNING))
        val evening = TestPolicy.choose(tomorrow, 100, store.window(ScheduleSlot.EVENING))
        store.setEnabled(ScheduleSlot.MORNING, true)
        store.setEnabled(ScheduleSlot.EVENING, true)
        store.setNext(ScheduleSlot.MORNING, morning)
        store.setNext(ScheduleSlot.EVENING, evening)
        val scheduler = Scheduler(context)
        scheduler.restore()
        assertEquals(morning, store.next(ScheduleSlot.MORNING))
        assertEquals(evening, store.next(ScheduleSlot.EVENING))
        assertTrue(dumpAlarms().contains("origWhen $morning "))
        assertTrue(dumpAlarms().contains("origWhen $evening "))
        scheduler.disableSlot(ScheduleSlot.MORNING)
        assertFalse(dumpAlarms().contains("origWhen $morning "))
        assertTrue(dumpAlarms().contains("origWhen $evening "))
        scheduler.disableSlot(ScheduleSlot.EVENING)
        assertFalse(dumpAlarms().contains("origWhen $evening "))
    }

    private fun dumpAlarms(): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("dumpsys alarm")
    ).bufferedReader().use { it.readText() }
}
