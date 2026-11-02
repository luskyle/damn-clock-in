package com.luskyle.clockintest

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate

object HistoryDialog {
    fun show(activity: Activity): AlertDialog {
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        var date: LocalDate? = TestPolicy.day(System.currentTimeMillis())
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, padding / 2)
        }
        val controls = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val chooseDate = Button(activity).apply { isAllCaps = false }
        val today = Button(activity).apply { text = "今天" }
        val all = Button(activity).apply { text = "全部" }
        controls.addView(chooseDate, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(today)
        controls.addView(all)
        layout.addView(controls)
        val summary = TextView(activity).apply {
            textSize = 12f
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, padding / 2, 0, padding / 2)
        }
        layout.addView(summary)
        val content = TextView(activity).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setLineSpacing(padding / 4f, 1f)
            contentDescription = "筛选后的执行记录"
        }
        val scroll = ScrollView(activity).apply { addView(content) }
        layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun refresh() {
            val entries = HistoryLog.entries(Store(activity).history())
            val filtered = HistoryLog.filter(entries, date)
            chooseDate.text = date?.toString() ?: "选择日期"
            summary.text = "${date?.let { "$it（北京时间）" } ?: "全部日期"} · ${filtered.size} 条\n" +
                "仅保留最近 80 行，较早记录可能已被覆盖"
            content.text = filtered.joinToString("\n\n") { it.text }.ifEmpty {
                if (date == null) "暂无执行记录" else "所选日期暂无保留的执行记录"
            }
            scroll.scrollTo(0, 0)
        }
        chooseDate.setOnClickListener {
            val initial = date ?: TestPolicy.day(System.currentTimeMillis())
            DatePickerDialog(activity, { _, year, month, day ->
                date = LocalDate.of(year, month + 1, day)
                refresh()
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        }
        today.setOnClickListener { date = TestPolicy.day(System.currentTimeMillis()); refresh() }
        all.setOnClickListener { date = null; refresh() }
        refresh()
        val dialog = AlertDialog.Builder(activity).setTitle("执行记录 · 按日期筛选")
            .setView(layout).setPositiveButton("关闭", null).setNeutralButton("刷新", null).create()
        dialog.show()
        dialog.window?.setLayout(-1, (activity.resources.displayMetrics.heightPixels * 0.8).toInt())
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { refresh() }
        return dialog
    }
}
