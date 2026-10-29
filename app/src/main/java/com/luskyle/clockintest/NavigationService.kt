package com.luskyle.clockintest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class NavigationService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var deadline = 0L
    private var elapsedDeadline = 0L
    private var clickedEntry = false
    private var clickedWorkbench = false
    private var returnedHome = false
    private var activity = ""
    private var entryClickedAt = 0L
    private var attempt = AttendanceAttempt(AttendanceMode.NAVIGATION, false)
    private var attendanceSlot: ScheduleSlot? = null
    private var submittedAt = 0L
    private var submittedWallTime = 0L
    private var reentered = false
    private var waitingForReentry = false
    private var finishingSuccess = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            inspect()
            if (running) handler.postDelayed(this, 500)
        }
    }

    override fun onServiceConnected() {
        instance = this
        Store(this).log("考勤辅助服务已连接；等待用户发起或已启用的计划")
    }

    fun wakeScreen() {
        try {
            startActivity(WakeSession.wakeIntent(this))
        } catch (error: ActivityNotFoundException) {
            stop("无法启动亮屏页面：${error.message}")
        } catch (error: SecurityException) {
            stop("系统禁止后台亮屏启动：${error.message}；请检查后台权限")
        }
    }

    fun begin(
        until: Long = System.currentTimeMillis() + 60_000,
        slot: ScheduleSlot? = null,
        previewMode: AttendanceMode? = null
    ) {
        if (running || finishingSuccess) {
            Notice.result(this, "当前请求仍在执行或收尾，请等待本轮结束后再次打卡")
            return
        }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            WakeSession.finish()
            Notice.result(this, "手机已锁定，请解锁后再测试")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(TestPolicy.targetPackage)
        if (launch == null) {
            WakeSession.finish()
            Notice.result(this, "未安装 BOE 移动门户")
            return
        }
        val dryRun = previewMode != null
        val mode = previewMode ?: if (slot == null) AttendanceMode.NAVIGATION else AttendanceMode.LOCATION
        running = true
        attempt = AttendanceAttempt(mode, dryRun)
        attendanceSlot = slot
        submittedAt = 0
        submittedWallTime = 0
        reentered = false
        waitingForReentry = false
        clickedEntry = false
        clickedWorkbench = false
        returnedHome = false
        activity = ""
        val duration = if (mode == AttendanceMode.LOCATION && !dryRun) 60_000L else 45_000L
        deadline = minOf(until, System.currentTimeMillis() + duration)
        elapsedDeadline = SystemClock.elapsedRealtime() + duration
        val purpose = when {
            dryRun -> "${mode.label}预检（不点击打卡）"
            mode == AttendanceMode.NAVIGATION -> "页面测试（不提交）"
            else -> "${slot?.title}${mode.label}（可能写入真实考勤）"
        }
        Notice.show(this, "正在运行：$purpose", true)
        Store(this).log("开始$purpose")
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            handler.postDelayed(tick, 1000)
        } catch (error: ActivityNotFoundException) {
            stop("无法启动 BOE：${error.message}")
        } catch (error: SecurityException) {
            stop("系统禁止唤起应用：${error.message}；请手动打开后再测试")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running || event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() == TestPolicy.targetPackage) {
            val name = event.className?.toString() ?: ""
            if (name.startsWith("com.meicloud.") || name.startsWith("com.mobile.boe.")) activity = name
        }
    }

    private fun inspect() {
        if (System.currentTimeMillis() >= deadline || SystemClock.elapsedRealtime() >= elapsedDeadline) {
            stop("运行超时或已超过时间窗口；已停止")
            return
        }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            stop("执行期间锁屏，已停止；请解锁后手动测试")
            return
        }
        val root = rootInActiveWindow ?: return
        if (!root.refresh()) return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != TestPolicy.targetPackage) {
            if (pkg == "com.android.permissioncontroller" || pkg == "com.google.android.permissioncontroller") {
                stop("遇到系统权限弹窗；请手动处理后重新测试")
            }
            return
        }
        val nodes = flatten(root)
        val texts = nodes.filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.toSet()
        if (texts.any { it in setOf("请输入用户名", "请输入密码", "欢迎登录") } ||
            activity.contains("Login") || activity.contains("LockActivity")) {
            stop("账号需要登录或解锁；请手动处理，测试已停止")
            return
        }
        if (attempt.mode != AttendanceMode.NAVIGATION &&
            texts.any { it.contains("人脸") || it.contains("验证码") || it.contains("确认打卡") }) {
            stop("需要人工验证或确认，已停止自动操作")
            return
        }
        if (attempt.clicked) {
            when {
                attempt.hasNewRecord(texts, submittedWallTime, System.currentTimeMillis()) ->
                    stop("已核验 BOE 本次新增${attempt.mode.label}记录；请以 BOE 最终考勤结果为准", success = true)
                attempt.hasNewSuccess(texts) ->
                    stop("BOE 页面显示“打卡成功”；请以 BOE 考勤记录为准", success = true)
                attempt.waitingForResult &&
                    SystemClock.elapsedRealtime() - submittedAt >= AttendanceAttempt.RESULT_WAIT_MS -> {
                    if (!attempt.prepareRetry()) {
                        stop("本轮三次定位点击已用完，结果仍待核验；请核验 BOE 记录，可再次手动发起新请求")
                    } else if (attempt.clicks >= 2 && !reentered) {
                        reentered = true
                        waitingForReentry = true
                        clickedEntry = false
                        clickedWorkbench = false
                        returnedHome = true
                        activity = ""
                        if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                            stop("返回考勤入口失败，已停止重试")
                        } else Store(this).log("暂无本次记录，返回后重新进入考勤页；本轮最多自动点击三次")
                    } else {
                        Store(this).log("暂无本次记录，准备有界重试；已点击 ${attempt.clicks}/3")
                    }
                    return
                }
            }
            if (!running || attempt.waitingForResult) return
        }
        if (texts.any { it in setOf("蓝牙打卡", "定位打卡", "打卡记录") }) {
            if (waitingForReentry) return
            if (attempt.mode == AttendanceMode.NAVIGATION) {
                stop("已到达打卡页面；测试完成，未点击任何打卡按钮")
            } else {
                inspectAttendance(nodes, texts)
            }
            return
        }
        if (clickedEntry) {
            if (activity.startsWith("com.meicloud.appbrand.AppBrandUI") &&
                System.currentTimeMillis() - entryClickedAt >= 5000) {
                stop("已打开考勤入口；小程序内容不可读，需人工确认页面（未点击打卡）")
            }
            return
        }
        val entry = nodes.filter {
            TestPolicy.allowedNavigation(it.viewIdResourceName ?: "", it.text?.toString() ?: "") &&
                it.viewIdResourceName == "${TestPolicy.targetPackage}:id/name_tv" && it.isVisibleToUser
        }
        if (entry.size > 1) {
            stop("考勤入口不唯一，已停止，避免误点")
            return
        }
        if (entry.size == 1) {
            clickedEntry = true
            entryClickedAt = System.currentTimeMillis()
            if (!clickNavigation(entry.single())) stop("考勤入口无法点击；已停止")
            else waitingForReentry = false
            return
        }
        val tabs = nodes.filter {
            it.viewIdResourceName == "${TestPolicy.targetPackage}:id/tab_apps" && it.isVisibleToUser
        }
        if (tabs.size == 1 && !clickedWorkbench) {
            clickedWorkbench = true
            if (!clickNavigation(tabs.single())) stop("工作台无法点击；已停止")
            return
        }
        if (activity.startsWith("com.meicloud.appbrand.AppBrandUI") && !returnedHome) {
            returnedHome = true
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) stop("无法返回工作台；请手动返回再测试")
        }
    }

    private fun inspectAttendance(nodes: List<AccessibilityNodeInfo>, texts: Set<String>) {
        val candidates = nodes.filter {
            it.packageName?.toString() == TestPolicy.targetPackage &&
                it.text?.toString() == attempt.mode.button && it.isVisibleToUser
        }
        if (!attempt.ready(TestPolicy.targetPackage, texts, candidates.size)) {
            stop("考勤页面或打卡按钮不唯一、不可识别；未点击打卡，请人工处理")
            return
        }
        val target = candidates.single()
        if (!target.isEnabled || !target.isClickable) {
            stop("打卡按钮未启用或不可点击；本轮未点击，请检查真实定位及 BOE 权限")
            return
        }
        if (attempt.dryRun) {
            stop("${attempt.mode.label}预检通过：页面和唯一可点击按钮已识别；未点击打卡，未验证提交后的流程")
            return
        }
        val slot = attendanceSlot ?: run {
            stop("缺少上下班确认，未点击打卡")
            return
        }
        val store = Store(this)
        if (!attempt.clicked) store.startAttendance(slot, attempt.mode)
        if (!store.reserveAttendanceClick(slot)) {
            stop("定位打卡点击预算已耗尽，已停止")
            return
        }
        attempt.markClick(texts)
        submittedAt = SystemClock.elapsedRealtime()
        if (submittedWallTime == 0L) submittedWallTime = store.attendanceStartedAt(slot)
        val accepted = if (attempt.clicks == 1) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            val bounds = Rect().apply { target.getBoundsInScreen(this) }
            val currentRoot = rootInActiveWindow
            val windowBounds = Rect().apply { currentRoot?.getBoundsInScreen(this) }
            if (currentRoot?.packageName?.toString() != TestPolicy.targetPackage ||
                currentRoot.windowId != target.windowId ||
                bounds.isEmpty || !windowBounds.contains(bounds)) false
            else dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(
                    Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }, 0, 80))
                .build(), null, null)
        }
        if (!accepted) {
            Store(this).log("系统未确认第 ${attempt.clicks}/3 次定位点击成功；先等待记录核验")
        } else {
            Store(this).log("${slot.title}${attempt.mode.label}已点击 ${attempt.clicks}/3 次，等待 BOE 明确反馈；不自动确认其他弹窗")
        }
    }

    private fun clickNavigation(node: AccessibilityNodeInfo): Boolean {
        if (!TestPolicy.allowedNavigation(node.viewIdResourceName ?: "", node.text?.toString() ?: "")) return false
        var target: AccessibilityNodeInfo? = node
        repeat(4) {
            val current = target ?: return false
            if (current.packageName?.toString() != TestPolicy.targetPackage) return false
            if (current.isClickable && current.isEnabled && current.isVisibleToUser) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target = current.parent
        }
        return false
    }

    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty() && result.size < 1500) {
            val node = pending.removeFirst()
            result.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return result
    }

    fun stop(message: String, success: Boolean = false) {
        val submitted = attempt.clicked && running
        val lockScreen = running && attempt.shouldLockScreen(success)
        val result = if (submitted && !success) "$message；已尝试点击打卡，结果待核验" else message
        if (submitted) attendanceSlot?.let { Store(this).attendanceResult(it, result, success) }
        running = false
        handler.removeCallbacks(tick)
        WakeSession.finish()
        attempt = AttendanceAttempt(AttendanceMode.NAVIGATION, false)
        attendanceSlot = null
        Notice.result(this, result)
        if (lockScreen) exitBoeAndLockScreen(result)
    }

    internal fun exitBoeAndLockScreen(result: String) {
        if (finishingSuccess) return
        finishingSuccess = true
        val homeAccepted = try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (error: SecurityException) {
            Store(this).log("系统禁止返回桌面：${error.message}")
            false
        }
        if (!homeAccepted) {
            Notice.result(this, "$result；系统拒绝退出 BOE 前台，请手动返回桌面")
        } else {
            Store(this).log("打卡成功收尾：已请求返回系统桌面，不强行停止 BOE")
        }
        val homeDeadline = SystemClock.elapsedRealtime() + 2000
        val finish = object : Runnable {
            override fun run() {
                val foreground = rootInActiveWindow?.packageName?.toString()
                if (homeAccepted && (foreground == null || foreground == TestPolicy.targetPackage) &&
                    SystemClock.elapsedRealtime() < homeDeadline) {
                    handler.postDelayed(this, 100)
                    return
                }
                if (homeAccepted && foreground != null && foreground != TestPolicy.targetPackage) {
                    Store(this@NavigationService).log("已确认 BOE 不在前台，接下来锁屏息屏")
                } else if (homeAccepted) {
                    Notice.result(this@NavigationService, "$result；未确认 BOE 已退出前台，请手动检查")
                }
                lockAfterSuccess(result)
            }
        }
        handler.postDelayed(finish, 500)
    }

    private fun lockAfterSuccess(result: String) {
        try {
            if (performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) {
                Store(this).log("打卡成功收尾：已请求系统锁屏息屏")
                handler.postDelayed({
                    if (getSystemService(PowerManager::class.java).isInteractive) {
                        Notice.result(this, "$result；锁屏请求后屏幕仍可交互，请手动确认息屏")
                    } else {
                        Store(this).log("已确认定位打卡完成后屏幕不可交互")
                    }
                }, 1000)
            } else {
                val issue = "$result；系统拒绝自动锁屏息屏，请手动息屏"
                Notice.result(this, issue)
            }
        } catch (error: SecurityException) {
            Notice.result(this, "$result；系统禁止自动锁屏息屏：${error.message}，请手动息屏")
        } finally {
            finishingSuccess = false
        }
    }

    override fun onInterrupt() {
        stop("无障碍服务被中断，测试已停止")
    }

    override fun onDestroy() {
        if (running) stop("导航服务已关闭，测试已停止")
        instance = null
        super.onDestroy()
    }

    companion object {
        var instance: NavigationService? = null
            private set
    }
}
