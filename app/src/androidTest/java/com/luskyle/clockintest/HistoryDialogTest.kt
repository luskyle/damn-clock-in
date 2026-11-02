package com.luskyle.clockintest

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.DatePicker
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDialogTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun defaultsToTodayCanShowAllAndRefreshWithoutChangingStoredHistory() {
        check(InstrumentationRegistry.getArguments().getString("barkTestDevice") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val store = Store(context)
        val original = store.history()
        val today = TestPolicy.day(System.currentTimeMillis())
        val yesterday = today.minusDays(1)
        var dialog: AlertDialog? = null
        try {
            check(store.prefs.edit().putString("history",
                "$today 12:00:00  今天的测试记录\n$yesterday 12:00:00  昨天的测试记录").commit())
            val before = store.prefs.all
            lateinit var views: List<View>
            lateinit var content: TextView
            lateinit var chooseDate: Button
            instrumentation.runOnMainSync {
                dialog = HistoryDialog.show(activity)
                views = descendants(requireNotNull(dialog).window!!.decorView)
                content = views.filterIsInstance<TextView>().single {
                    it.contentDescription == "筛选后的执行记录"
                }
                assertTrue(content.text.contains("今天的测试记录"))
                assertFalse(content.text.contains("昨天的测试记录"))
                assertTrue(content.isTextSelectable)
                chooseDate = views.filterIsInstance<Button>().single { it.text.toString() == today.toString() }
            }
            fun choose(day: java.time.LocalDate) {
                instrumentation.runOnMainSync {
                    chooseDate.performClick()
                    val pickerViews = WindowInspector.getGlobalWindowViews().map { descendants(it) }
                        .single { nodes -> nodes.any { it is DatePicker } }
                    pickerViews.filterIsInstance<DatePicker>().single()
                        .updateDate(day.year, day.monthValue - 1, day.dayOfMonth)
                    pickerViews.filterIsInstance<Button>().single { it.id == android.R.id.button1 }.performClick()
                }
                instrumentation.waitForIdleSync()
            }
            choose(yesterday)
            instrumentation.runOnMainSync {
                assertTrue(content.text.contains("昨天的测试记录"))
                assertFalse(content.text.contains("今天的测试记录"))
            }
            choose(today.plusDays(2))
            instrumentation.runOnMainSync {
                assertEquals("所选日期暂无保留的执行记录", content.text.toString())
                views.filterIsInstance<Button>().single { it.text == "全部" }.performClick()
                assertTrue(content.text.contains("今天的测试记录"))
                assertTrue(content.text.contains("昨天的测试记录"))
                views.filterIsInstance<Button>().single { it.text == "今天" }.performClick()
                assertFalse(content.text.contains("昨天的测试记录"))
                requireNotNull(dialog).getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                assertTrue(content.text.contains("今天的测试记录"))
                assertEquals(before, store.prefs.all)
            }
        } finally {
            check(store.prefs.edit().putString("history", original).commit())
            instrumentation.runOnMainSync { dialog?.dismiss(); activity.finish() }
        }
    }
}
