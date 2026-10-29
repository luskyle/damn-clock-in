package com.luskyle.clockintest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private val ink = Color.rgb(27, 44, 59)
    private val muted = Color.rgb(105, 121, 134)
    private val teal = Color.rgb(16, 120, 110)
    private val page = Color.rgb(244, 247, 249)
    private val handler = Handler(Looper.getMainLooper())
    private var scroll: ScrollView? = null
    private var settingsPanel: AlertDialog? = null
    private val updateUi = Runnable { render() }
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.removeCallbacks(updateUi)
        handler.post(updateUi)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = page
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    override fun onResume() {
        super.onResume()
        Store(this).prefs.registerOnSharedPreferenceChangeListener(changes)
        render()
    }

    override fun onPause() {
        Store(this).prefs.unregisterOnSharedPreferenceChangeListener(changes)
        handler.removeCallbacks(updateUi)
        settingsPanel?.dismiss()
        settingsPanel = null
        super.onPause()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun round(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun LinearLayout.space(height: Int) {
        addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else teal)
            background = round(if (primary) teal else Color.rgb(232, 244, 241), 14)
            stateListAnimator = null
            elevation = 0f
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { action() }
        }

    private fun LinearLayout.card(content: LinearLayout.() -> Unit) {
        addView(column().apply {
            background = round(Color.WHITE)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            content()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }

    private fun feedback(message: String) {
        Notice.result(this, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun settings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            feedback("本机没有该设置入口：${error.message}，请在系统设置中手动配置")
        }
    }

    private fun render() {
        val oldY = scroll?.scrollY ?: 0
        val store = Store(this)
        val scheduler = Scheduler(this)
        val content = column().apply { setPadding(dp(20), dp(24), dp(20), dp(24)) }
        content.addView(text(getString(R.string.app_name), 30f, bold = true))
        content.space(4)
        content.addView(text(getString(R.string.app_tagline), 15f, muted))
        content.space(20)
        content.addView(text("定位打卡  /  成功后退出 BOE 并息屏", 12f, teal).apply {
            background = round(Color.rgb(226, 242, 237), 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, LinearLayout.LayoutParams(-1, -2))
        content.space(18)
        for (slot in ScheduleSlot.entries) {
            val enabled = store.enabled(slot)
            val window = store.window(slot)
            content.card {
                addView(row().apply {
                    addView(text("${slot.title}打卡", 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(Switch(this@MainActivity).apply {
                        contentDescription = "${slot.title}计划开关"
                        isChecked = enabled
                        thumbTintList = ColorStateList.valueOf(if (enabled) teal else Color.rgb(143, 154, 164))
                        setOnCheckedChangeListener { _, on ->
                            if (on) confirmEnable(slot) else {
                                scheduler.disableSlot(slot)
                                store.log("${slot.title}计划已关闭")
                                render()
                            }
                        }
                    })
                })
                space(14)
                addView(text(window.toString(), 27f, bold = true))
                space(5)
                addView(text("时间段内随机一次 · 中国工作日", 12f, muted))
                space(16)
                addView(text(
                    if (enabled && store.next(slot) > 0) "下次  ${Store.format(store.next(slot))}"
                    else "尚未开启 · 只保存时间设置",
                    12f, if (enabled) teal else muted
                ))
                space(14)
                addView(button("设置${slot.title}时间段") { editWindow(slot) }, LinearLayout.LayoutParams(-1, -2))
                store.lastAttendance(slot)?.let {
                    space(10)
                    addView(text("最近真实打卡尝试：$it", 12f, muted))
                }
            }
        }
        content.space(2)
        content.addView(button("立即测试 · 只到页面", true) {
            val service = NavigationService.instance
            val issue = NavigationAccess.read(this).issue
            if (issue != null) feedback(issue)
            else if (service == null) feedback("无障碍连接已变化，请重试")
            else service.begin()
        }, LinearLayout.LayoutParams(-1, dp(54)))
        content.space(10)
        content.addView(button("立即打卡 · 需再次确认") { confirmAttendance(dryRun = false) },
            LinearLayout.LayoutParams(-1, -2))
        if (ScheduleSlot.entries.any { store.canRetryAttendance(it) }) {
            content.space(10)
            content.addView(button("继续待核验定位请求 · 有限重试") {
                confirmAttendance(dryRun = false, retryPending = true)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        content.space(10)
        content.addView(button("打卡预检 · 不点击打卡") { confirmAttendance(dryRun = true) },
            LinearLayout.LayoutParams(-1, -2))
        content.space(10)
        content.addView(button("停止测试并关闭全部计划") {
            scheduler.disable()
            render()
        }, LinearLayout.LayoutParams(-1, -2))
        content.space(18)
        val ready = scheduler.requirements() == null
        content.card {
            addView(text(if (ready) "运行条件已就绪" else "需要完成运行设置", 15f, bold = true))
            space(5)
            addView(text("定时自动亮屏；安全锁屏需手动解锁。", 12f, muted))
            space(12)
            addView(row().apply {
                addView(button("运行设置") { showSettings() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(10), 1))
                addView(button("执行记录") { showHistory() }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        content.addView(text("中国大陆工作日 · 节假日休息，调休补班执行\n官方日历：${ChinaWorkdays.years.joinToString()} 年 · 缺失年份暂停\n当天未尝试且窗口未结束，当天生效；窗口内从剩余时间随机。\n更改时间段后需重新开启；已过期或已尝试不补执行。", 12f, muted).apply {
            gravity = Gravity.CENTER
        })
        val keep = store.prefs.getBoolean("stayAwake", false)
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scroll = ScrollView(this).apply {
            setBackgroundColor(page)
            isFillViewport = true
            addView(content)
        }
        setContentView(scroll)
        scroll?.post { scroll?.scrollTo(0, oldY) }
    }

    private fun confirmEnable(slot: ScheduleSlot) {
        val scheduler = Scheduler(this)
        val issue = scheduler.requirements()
        if (issue != null) {
            feedback(issue)
            render()
            return
        }
        val store = Store(this)
        val action = "定位打卡，总计最多点击三次，等待核验后才重试，可返回重进一次；不再逐次询问。可能产生多条真实记录，核验成功后返回桌面退出 BOE 前台，再自动锁屏息屏"
        AlertDialog.Builder(this).setTitle("开启${slot.title}计划？")
            .setMessage("中国大陆工作日北京时间 ${store.window(slot)} 随机一次，$action。跳过节假日和普通周末，调休补班执行。\n\n到时自动亮屏，无需助手首页在前台；安全锁屏需手动解锁。需允许后台运行、联网且 BOE 已登录。仅使用真实定位；遇到额外认证或不明页面停止。系统强行停止或关机无法保证执行。")
            .setPositiveButton("确认开启") { _, _ -> scheduler.enable(slot); render() }
            .setNegativeButton("取消") { _, _ -> render() }
            .setOnCancelListener { render() }.show()
    }

    private fun confirmAttendance(dryRun: Boolean, retryPending: Boolean = false) {
        val mode = AttendanceMode.LOCATION
        AlertDialog.Builder(this).setTitle(if (dryRun) "选择预检时段（不提交）" else "选择本次真实打卡时段")
            .setItems(ScheduleSlot.entries.map { it.title }.toTypedArray()) { _, index ->
                val slot = ScheduleSlot.entries[index]
                AlertDialog.Builder(this).setTitle("${slot.title}${mode.label}")
                    .setMessage(if (dryRun) "仅打开页面并检查唯一可点击按钮，不点击打卡，不占用当天打卡次数。"
                        else "将${if (retryPending) "继续待核验请求" else "发起真实定位打卡"}，今天本时段总计最多三次点击，每次先等十秒核验，允许返回重新进入一次。旧请求次数也计入预算。\n\n重复请求可能生成多条真实记录，请确认本人实际出勤。核验成功后返回桌面退出 BOE 前台，再自动锁屏息屏，不点击额外弹窗、不绕过验证；已成功或预算耗尽不会继续。")
                    .setPositiveButton(if (dryRun) "开始预检" else "确认真实打卡") { _, _ ->
                        val issue = NavigationAccess.read(this).issue
                        val service = NavigationService.instance
                        if (issue != null) feedback(issue)
                        else if (service == null) feedback("无障碍连接已变化，请重试")
                        else service.begin(slot = slot, previewMode = if (dryRun) mode else null,
                            retryPending = retryPending)
                    }.setNegativeButton("取消", null).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun editWindow(slot: ScheduleSlot) {
        val current = Store(this).window(slot)
        var start = current.startMinute
        var end = current.endMinute
        val layout = column().apply { setPadding(dp(24), dp(8), dp(24), dp(12)) }
        fun wheels(label: String, minute: Int, changed: (Int) -> Unit): LinearLayout {
            var hour = minute / 60
            var min = minute % 60
            return column().apply {
                addView(text(label, 13f, muted).apply { gravity = Gravity.CENTER })
                space(8)
                addView(row().apply {
                    background = round(page, 14)
                    fun picker(max: Int, value: Int, unit: String, onChange: (Int) -> Unit) =
                        NumberPicker(this@MainActivity).apply {
                            minValue = 0
                            maxValue = max
                            setValue(value)
                            wrapSelectorWheel = true
                            setFormatter { String.format(Locale.ROOT, "%02d", it) }
                            contentDescription = "${slot.title}${label}$unit"
                            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                            setTextColor(ink)
                            setTextSize(dp(23).toFloat())
                            setSelectionDividerHeight(dp(1))
                            setOnValueChangedListener { _, _, newValue -> onChange(newValue) }
                        }
                    addView(picker(23, hour, "小时") {
                        hour = it; changed(hour * 60 + min)
                    }, LinearLayout.LayoutParams(0, dp(152), 1f))
                    addView(text(":", 22f, muted).apply { gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(dp(10), -2))
                    addView(picker(59, min, "分钟") {
                        min = it; changed(hour * 60 + min)
                    }, LinearLayout.LayoutParams(0, dp(152), 1f))
                })
            }
        }
        layout.addView(text("上下滑动选择时间 · 24 小时制 · 中国工作日", 13f, muted))
        layout.space(16)
        layout.addView(row().apply {
            addView(wheels("开始", start) { start = it }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(16), 1))
            addView(wheels("结束", end) { end = it }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        layout.space(12)
        layout.addView(text("开始须早于结束，不支持跨午夜。\n上下班时间段不可重叠。保存修改后，该计划会关闭，等待你重新确认。", 12f, muted))
        val error = text("", 12f, Color.rgb(174, 45, 54)).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        layout.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle("${slot.title}时间段")
            .setView(layout).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when {
                    start >= end -> error.text = "开始时间必须早于结束时间，暂不支持跨午夜"
                    TimeWindow(start, end).overlaps(Store(this).window(ScheduleSlot.entries.single { it != slot })) ->
                        error.text = "上下班时间段不能重叠或共用端点"
                    else -> if (Scheduler(this).changeWindow(slot, TimeWindow(start, end))) {
                        dialog.dismiss()
                        render()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showSettings() {
        val store = Store(this)
        val notification = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val layout = column().apply { setPadding(dp(24), dp(8), dp(24), dp(12)) }
        fun entry(label: String, action: () -> Unit) {
            layout.addView(button(label, action = action), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        entry("无障碍服务 · ${NavigationAccess.read(this).label}") {
            AlertDialog.Builder(this).setTitle("${getString(R.string.app_name)} · 考勤辅助")
                .setMessage(getString(R.string.service_description))
                .setPositiveButton("前往设置") { _, _ -> settings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("取消", null).show()
        }
        entry("通知 · ${if (notification) "已允许" else "未允许"}") {
            if (Build.VERSION.SDK_INT >= 33 && !notification)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            else settings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        entry("精确闹钟 · ${if (Scheduler(this).exactAllowed()) "已允许" else "未允许"}") {
            if (Build.VERSION.SDK_INT >= 31)
                settings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        entry("电池优化 · ${if (battery) "已豁免" else "去设置"}") {
            settings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        entry("自启动与后台保护（重要）") {
            AlertDialog.Builder(this).setTitle("避免清理任务时关闭无障碍")
                .setMessage("一加 / OPPO：在应用详情 → 耗电管理，开启“允许完全后台行为”和“允许应用自启动”。这与普通电池优化豁免不是同一项；无需为此开启关联启动。\n\n其他品牌请检查本应用的自启动、后台运行和省电限制。允许后台运行可能增加耗电。\n\n正常返回桌面不应要求重新授权；若系统已关闭无障碍，仍需手动开启一次。不能阻止用户强行停止，也不能保证永久授权。")
                .setPositiveButton("打开应用详情") { _, _ ->
                    settings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.setNegativeButton("取消", null).show()
        }
        entry("先检查锁屏显示权限（不导航）") {
            startActivity(Intent(this, WakeActivity::class.java).putExtra("preview", true))
        }
        entry("2 分钟后息屏测试（仅一次）") {
            AlertDialog.Builder(this).setTitle("测试自动亮屏与后台启动")
                .setMessage("请先在亮屏时完成“锁屏显示权限检查”。确认后助手会关闭首页和最近任务，你可以熄屏，两分钟后尝试亮屏并导航到考勤页，不提交。安全锁屏不会绕过。\n\n不修改上下班计划，不影响每天尝试次数。失败会记录原因；这不是系统的“强行停止”。")
                .setPositiveButton("安排并退出助手") { _, _ ->
                    if (Scheduler(this).scheduleWakeTest()) {
                        settingsPanel?.dismiss()
                        finishAndRemoveTask()
                    }
                }.setNegativeButton("取消", null).show()
        }
        layout.addView(CheckBox(this).apply {
            text = "专用设备保持面板亮屏"
            textSize = 14f
            isChecked = store.prefs.getBoolean("stayAwake", false)
            setOnCheckedChangeListener { _, checked -> store.prefs.edit().putBoolean("stayAwake", checked).apply() }
        })
        layout.addView(text("保持亮屏选项仅在本面板前台有效；定时任务另有短时唤醒机制。厂商自启动需手动配置；无法绕过安全锁屏、关机或系统强行停止。", 12f, muted))
        settingsPanel = AlertDialog.Builder(this).setTitle("运行设置")
            .setView(ScrollView(this).apply { addView(layout) }).setPositiveButton("完成") { _, _ -> render() }.show()
    }

    private fun showHistory() {
        val content = text(Store(this).history().ifEmpty { "暂无记录，试试立即测试吧。" }, 13f).apply {
            setPadding(dp(24), dp(8), dp(24), dp(24))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle("执行记录 · 仅本机")
            .setView(ScrollView(this).apply { addView(content) }).setPositiveButton("关闭", null).show()
    }
}
