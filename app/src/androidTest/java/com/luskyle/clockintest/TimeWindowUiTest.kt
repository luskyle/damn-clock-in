package com.luskyle.clockintest

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeWindowUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = Store(instrumentation.targetContext)
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
    }
    private var originals: Map<ScheduleSlot, Pair<TimeWindow, Long>> = emptyMap()
    private var editingSlot = ScheduleSlot.MORNING

    @Before fun open() {
        check(ScheduleSlot.entries.none { store.enabled(it) }) { "Disable schedules before UI tests" }
        originals = ScheduleSlot.entries.associateWith { store.window(it) to store.next(it) }
        openHome()
    }

    @After fun restore() {
        for ((slot, original) in originals) {
            store.saveWindow(slot, original.first)
            store.setNext(slot, original.second)
        }
        if (originals.isNotEmpty()) openHome()
    }

    @Test fun morningCanBeEditedAndSurvivesReopening() {
        clickText("设置上班时间段")
        setTime("开始", 7, 55)
        setTime("结束", 8, 15)
        clickText("保存")
        await { store.window(ScheduleSlot.MORNING) == TimeWindow(475, 495) }
        openHome()
        find { it.text?.toString() == "07:55 – 08:15" }
        assertEquals(originals.getValue(ScheduleSlot.EVENING).first, store.window(ScheduleSlot.EVENING))
        assertFalse(store.enabled(ScheduleSlot.MORNING))
    }

    @Test fun eveningCanBeEditedIndependently() {
        clickText("设置下班时间段")
        setTime("开始", 18, 5)
        setTime("结束", 18, 25)
        clickText("保存")
        await { store.window(ScheduleSlot.EVENING) == TimeWindow(1085, 1105) }
        find { it.text?.toString() == "18:05 – 18:25" }
        assertEquals(originals.getValue(ScheduleSlot.MORNING).first, store.window(ScheduleSlot.MORNING))
    }

    @Test fun reversedWindowShowsErrorAndDoesNotSave() {
        clickText("设置上班时间段")
        setTime("开始", 9, 0)
        clickText("保存")
        find { it.text?.toString() == "开始时间必须早于结束时间，暂不支持跨午夜" }
        assertEquals(originals.getValue(ScheduleSlot.MORNING).first, store.window(ScheduleSlot.MORNING))
        clickText("取消")
    }

    @Test fun overlappingWindowShowsErrorAndDoesNotSave() {
        clickText("设置上班时间段")
        setTime("开始", 17, 0)
        setTime("结束", 17, 45)
        clickText("保存")
        find { it.text?.toString() == "上下班时间段不能重叠或共用端点" }
        assertEquals(originals.getValue(ScheduleSlot.MORNING).first, store.window(ScheduleSlot.MORNING))
        clickText("取消")
    }

    private fun openHome() {
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        find { it.text?.toString() == "每日计划" }
    }

    private fun setTime(prefix: String, hour: Int, minute: Int) {
        val window = store.window(editingSlot)
        val old = if (prefix == "开始") window.startMinute else window.endMinute
        fun roll(unit: String, from: Int, to: Int, count: Int) {
            val forward = (to - from + count) % count
            val steps = if (forward <= count / 2) forward else forward - count
            repeat(kotlin.math.abs(steps)) {
                val index = (if (prefix == "开始") 0 else 2) + (if (unit == "小时") 0 else 1)
                val node = wheel(index)
                assertTrue(node.performAction(
                    if (steps > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                ))
                SystemClock.sleep(350)
            }
        }
        roll("小时", old / 60, hour, 24)
        roll("分钟", old % 60, minute, 60)
    }

    private fun wheel(index: Int): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(pending::add)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (node.className?.toString() == "android.widget.NumberPicker" && node.isVisibleToUser) nodes.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
            }
            if (nodes.size != 4) return@await false
            result = nodes.sortedBy { Rect().apply { it.getBoundsInScreen(this) }.left }[index]
            true
        }
        return checkNotNull(result)
    }

    private fun clickText(value: String) {
        if (value == "设置上班时间段") editingSlot = ScheduleSlot.MORNING
        if (value == "设置下班时间段") editingSlot = ScheduleSlot.EVENING
        click { it.text?.toString() == value }
    }

    private fun click(predicate: (AccessibilityNodeInfo) -> Boolean) {
        var node: AccessibilityNodeInfo? = find(predicate)
        repeat(5) {
            val current = node ?: error("No clickable ancestor")
            if (current.isClickable) {
                assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                SystemClock.sleep(350)
                return
            }
            node = current.parent
        }
        error("No clickable control")
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await {
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(pending::add)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (node.isVisibleToUser && predicate(node)) {
                    found = node
                    return@await true
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
            }
            false
        }
        return checkNotNull(found)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Timed out waiting for expected UI or saved window")
    }
}
