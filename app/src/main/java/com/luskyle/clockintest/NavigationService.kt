package com.luskyle.clockintest

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            inspect()
            if (running) handler.postDelayed(this, 500)
        }
    }

    override fun onServiceConnected() {
        instance = this
        Store(this).log("测试导航服务已连接；等待用户发起，不自动点击")
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

    fun begin(until: Long = System.currentTimeMillis() + 45_000) {
        if (running) {
            Store(this).log("已有测试在进行，忽略重复启动")
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
        running = true
        clickedEntry = false
        clickedWorkbench = false
        returnedHome = false
        activity = ""
        deadline = minOf(until, System.currentTimeMillis() + 45_000)
        elapsedDeadline = SystemClock.elapsedRealtime() + 45_000
        Notice.show(this, "正在导航；只允许工作台和考勤入口，绝不提交", true)
        Store(this).log("开始页面测试（不提交）")
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
            stop("测试超时或已超过时间窗口；已停止，未提交")
            return
        }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            stop("执行期间锁屏，已停止；请解锁后手动测试")
            return
        }
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != TestPolicy.targetPackage) {
            if (pkg == "com.android.permissioncontroller" || pkg == "com.google.android.permissioncontroller") {
                stop("遇到系统权限弹窗；请手动处理后重新测试")
            }
            return
        }
        val nodes = flatten(root)
        val texts = nodes.mapNotNull { it.text?.toString() }.toSet()
        if (texts.any { it in setOf("请输入用户名", "请输入密码", "欢迎登录") } ||
            activity.contains("Login") || activity.contains("LockActivity")) {
            stop("账号需要登录或解锁；请手动处理，测试已停止")
            return
        }
        // These are stop markers, never click targets.
        if (texts.any { it in setOf("蓝牙打卡", "定位打卡", "打卡记录") }) {
            stop("已到达打卡页面；测试完成，未点击任何打卡按钮")
            return
        }
        if (clickedEntry) {
            if (activity.startsWith("com.meicloud.appbrand.AppBrandUI") &&
                System.currentTimeMillis() - entryClickedAt >= 5000) {
                stop("已打开考勤入口；小程序内容不可读，需人工确认页面（未提交）")
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

    fun stop(message: String) {
        running = false
        handler.removeCallbacks(tick)
        WakeSession.finish()
        Notice.result(this, message)
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
