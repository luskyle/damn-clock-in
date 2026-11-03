package com.luskyle.clockintest

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttendanceRetryServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun field(service: NavigationService, name: String) =
        NavigationService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun fixture(action: (NavigationService) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("barkTestDevice") == "true")
        check(!BoeLoginSettings(context).configured() && !BarkSettings(context).configured())
        check(!WakeSession.active() && NavigationService.instance == null)
        instrumentation.runOnMainSync {
            val service = NavigationService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, context)
            try {
                field(service, "instance").set(null, service)
                field(service, "running").setBoolean(service, true)
                field(service, "automatic").setBoolean(service, true)
                field(service, "retryDeadline").setLong(service, System.currentTimeMillis() + 120_000)
                field(service, "retryElapsedDeadline").setLong(service, SystemClock.elapsedRealtime() + 120_000)
                action(service)
            } finally {
                service.cancelCurrent("模拟器测试清理；不执行 BOE")
                field(service, "instance").set(null, null)
            }
        }
    }

    private fun queued(service: NavigationService): Boolean =
        (field(service, "handler").get(service) as Handler)
            .hasCallbacks(field(service, "retryFlow").get(service) as Runnable)

    @Test fun homepageHasOnlyOpenAndRealAttendanceManualActions() {
        check(InstrumentationRegistry.getArguments().getString("barkTestDevice") == "true")
        check(!WakeSession.active() && NavigationService.instance == null)
        val store = Store(context)
        ScheduleSlot.entries.forEach { store.startAttendance(it, AttendanceMode.LOCATION) }
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                fun labels(view: View): List<String> =
                    (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
                        (if (view is ViewGroup) (0 until view.childCount).flatMap {
                            labels(view.getChildAt(it))
                        } else emptyList())
                val text = labels(activity.window.decorView)
                assertTrue("立即打卡 · 只到打卡页面" in text)
                assertTrue("立即打卡 · 执行一次真实打卡" in text)
                assertFalse(text.any { it.contains("停止本次自动任务") || it.contains("保留计划") ||
                    it.contains("打卡预检") || it.contains("停止测试并关闭全部计划") ||
                    it.contains("立即测试 · 只到页面") || it.contains("立即打卡 · 需再次确认") ||
                    it.contains("最近真实打卡尝试") || it.contains("立即打开 · 只到打卡页面") })
                ScheduleSlot.entries.forEach { assertNotNull(store.lastAttendance(it)) }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun transientFailureQueuesOnlyOneRetryAndManualTakeoverRemovesIt() = fixture { service ->
        service.stop("模拟临时页面不可读")
        assertTrue(field(service, "running").getBoolean(service))
        assertTrue(field(service, "retryWaiting").getBoolean(service))
        assertTrue(queued(service))
        service.prepareManualOperation()
        assertFalse(field(service, "running").getBoolean(service))
        assertFalse(queued(service))
        assertFalse(WakeSession.active())
    }

    @Test fun explicitStopAndPermissionFailureDoNotQueueAnotherRound() = fixture { service ->
        service.stop("模拟权限阻断", terminal = true)
        assertFalse(field(service, "running").getBoolean(service))
        assertFalse(queued(service))
    }

    @Test fun anotherWakeRequestCannotCreateATimeoutThatKillsTheActiveRetryTask() = fixture { service ->
        service.stop("模拟自动重试等待")
        WakeSession.request(context, System.currentTimeMillis() + 60_000, ScheduleSlot.EVENING)
        assertFalse(WakeSession.active())
        assertTrue(field(service, "running").getBoolean(service))
        assertTrue(queued(service))
    }

    @Test fun newFlowBudgetPreservesOriginalSubmissionTimeAndPendingResult() = fixture {
        val store = Store(context)
        store.startAttendance(ScheduleSlot.MORNING, AttendanceMode.LOCATION)
        assertTrue(store.reserveAttendanceClick(ScheduleSlot.MORNING))
        store.attendanceResult(ScheduleSlot.MORNING, "模拟待核验", false)
        val started = store.attendanceStartedAt(ScheduleSlot.MORNING)
        val result = store.lastAttendance(ScheduleSlot.MORNING)
        store.resetAttendanceBudget(ScheduleSlot.MORNING)
        assertEquals(0, store.attendanceClicks(ScheduleSlot.MORNING))
        assertEquals(started, store.attendanceStartedAt(ScheduleSlot.MORNING))
        assertEquals(result, store.lastAttendance(ScheduleSlot.MORNING))
        assertTrue(store.reserveAttendanceClick(ScheduleSlot.MORNING))
    }

    @Test fun closingOtherSlotDoesNotCancelButClosingActiveSlotDoes() = fixture { service ->
        field(service, "attendanceSlot").set(service, ScheduleSlot.MORNING)
        service.stop("模拟等待重试")
        service.cancelAutomaticSlot(ScheduleSlot.EVENING)
        assertTrue(queued(service))
        service.cancelAutomaticSlot(ScheduleSlot.MORNING)
        assertFalse(queued(service))
    }

    @Test fun successAndExpiredDeadlineNeverRetry() = fixture { service ->
        // No clicks or BOE page: exercise termination without success cleanup or external notification.
        service.stop("模拟终止分支（非真实打卡）", success = true)
        assertFalse(queued(service))
        field(service, "running").setBoolean(service, true)
        field(service, "automatic").setBoolean(service, true)
        field(service, "retryDeadline").setLong(service, System.currentTimeMillis())
        service.stop("模拟截止")
        assertFalse(queued(service))
    }

    @Test fun idleBoeLoginEventsCannotCreateAnAttendanceOrLoginTask() = fixture { service ->
        service.cancelCurrent("转为空闲")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = TestPolicy.targetPackage
            className = "com.meicloud.login.activity.LoginActivity"
            text.add("此账号在其他地方登陆")
        }
        try {
            repeat(10) { service.onAccessibilityEvent(event) }
            assertFalse(field(service, "running").getBoolean(service))
            assertFalse(queued(service))
        } finally { event.recycle() }
    }
}
