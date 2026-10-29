package com.luskyle.clockintest

import android.app.UiAutomation
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SuccessCleanupTest {
    @Test fun verifiedResultCleanupLeavesBoeForegroundThenSleepsWithoutSubmitting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val store = Store(context)
        check(ScheduleSlot.entries.none { store.enabled(it) } && !WakeSession.active())
        check(store.prefs.getLong("wakeTestAt", 0) == 0L)
        check(ScheduleSlot.entries.any {
            store.attendanceClaimed(it) && store.prefs.getBoolean("${it.name}.attendanceSucceeded", false)
        }) { "This focused live cleanup test requires an already verified real attendance result" }
        await(120_000) { NavigationService.instance != null }
        val originals = store.prefs.all
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(TestPolicy.targetPackage))
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await { automation.rootInActiveWindow?.packageName?.toString() == TestPolicy.targetPackage }
        instrumentation.runOnMainSync {
            requireNotNull(NavigationService.instance)
                .exitBoeAndLockScreen("真机验证既有成功结果的退出与息屏收尾；本次不提交")
        }
        await { !context.getSystemService(PowerManager::class.java).isInteractive }
        val history = store.history()
        assertTrue(history.contains("已确认 BOE 不在前台，接下来锁屏息屏"))
        assertTrue(history.indexOf("已请求系统锁屏息屏") < history.indexOf("已确认 BOE 不在前台"))
        assertFalse(WakeSession.active())
        assertEquals(originals - "history", store.prefs.all - "history")
    }

    private fun await(timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for live cleanup condition" }
            SystemClock.sleep(100)
        }
    }
}
