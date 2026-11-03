package com.luskyle.clockintest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONException
import java.io.IOException
import java.security.GeneralSecurityException

class NavigationService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    internal val hasActiveRequest: Boolean get() = running || finishingSuccess
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
    private var startedAt = 0L
    private var loginOnly = false
    private var loginStarted = false
    private var userInitiated = false
    private var loginAttempt: BoeLoginAttempt? = null
    private var loginCredentials: BoeCredentials? = null
    private var automatic = false
    private var retryDeadline = 0L
    private var retryElapsedDeadline = 0L
    private var retryWaiting = false
    private var flowNumber = 1
    private var executionLock: PowerManager.WakeLock? = null
    private val retryFlow = Runnable {
        if (running && automatic && retryWaiting) restartAutomaticFlow()
    }
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
        previewMode: AttendanceMode? = null,
        loginOnly: Boolean = false,
        userInitiated: Boolean = false
    ) {
        if (running || finishingSuccess) {
            Notice.result(this, "当前请求仍在执行或收尾，请等待本轮结束后再次打卡")
            return
        }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            if (slot != null && previewMode == null)
                NotificationDelivery.result(this, slot, System.currentTimeMillis(), false, false, "手机已锁定，未能启动打卡")
            WakeSession.finish()
            Notice.result(this, "手机已锁定，请解锁后再测试")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(TestPolicy.targetPackage)
        if (launch == null) {
            if (slot != null && previewMode == null)
                NotificationDelivery.result(this, slot, System.currentTimeMillis(), false, false, "未安装 BOE 移动门户")
            WakeSession.finish()
            Notice.result(this, "未安装 BOE 移动门户")
            return
        }
        val dryRun = previewMode != null
        val mode = previewMode ?: if (slot == null) AttendanceMode.NAVIGATION else AttendanceMode.LOCATION
        this.loginOnly = loginOnly
        this.userInitiated = userInitiated
        loginStarted = false
        loginAttempt = null
        loginCredentials = null
        running = true
        automatic = slot != null && !dryRun && !userInitiated && !loginOnly
        retryWaiting = false
        flowNumber = 1
        retryDeadline = if (automatic) until else 0L
        retryElapsedDeadline = if (automatic)
            SystemClock.elapsedRealtime() + maxOf(0, until - System.currentTimeMillis()) else 0L
        startedAt = System.currentTimeMillis()
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
        val duration = if (mode == AttendanceMode.LOCATION && !dryRun) AttendanceRetryPolicy.SESSION_MS else 45_000L
        deadline = minOf(until, System.currentTimeMillis() + duration)
        elapsedDeadline = SystemClock.elapsedRealtime() + duration
        val purpose = when {
            loginOnly -> "BOE 重新登录测试（不提交打卡）"
            dryRun -> "${mode.label}预检（不点击打卡）"
            mode == AttendanceMode.NAVIGATION -> "页面测试（不提交）"
            else -> "${slot?.title}${mode.label}（可能写入真实考勤）"
        }
        Notice.show(this, "正在运行：$purpose", true)
        Store(this).log("开始$purpose")
        if (automatic) {
            val lockDuration = retryElapsedDeadline - SystemClock.elapsedRealtime()
            WakeSession.finish()
            if (lockDuration <= 0) {
                stop("自动重试截止时间已过，不补执行", terminal = true)
                return
            }
            executionLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClockInTest:AttendanceRetries").apply {
                    acquire(lockDuration)
                }
        }
        try {
            if (!loginOnly || rootInActiveWindow?.packageName?.toString() != TestPolicy.targetPackage)
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
        if (event.packageName?.toString() != TestPolicy.targetPackage) return
        if (running && loginAttempt != null &&
            event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            BoeLoginPolicy.rejected(event.text.map { it.toString() }.toSet())) {
            blockLoginRetry()
            stop("BOE 拒绝登录；已停止自动重试，请检查帐号密码或手动处理", terminal = true)
        }
    }

    private fun inspect() {
        if (retryWaiting) return
        if (automatic && (System.currentTimeMillis() >= retryDeadline ||
                SystemClock.elapsedRealtime() >= retryElapsedDeadline)) {
            stop("自动打卡重试已到截止时间；请在 BOE 核验并手动操作", terminal = true)
            return
        }
        if (System.currentTimeMillis() >= deadline || SystemClock.elapsedRealtime() >= elapsedDeadline) {
            stop(if (loginAttempt != null) "本轮 BOE 登录超时，未核验成功"
                else "本轮导航或结果等待超时，未核验成功")
            return
        }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            stop("执行期间锁屏，已停止；请解锁后手动测试", terminal = true)
            return
        }
        val root = rootInActiveWindow ?: return
        if (!root.refresh()) return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != TestPolicy.targetPackage) {
            if (pkg == "com.android.permissioncontroller" || pkg == "com.google.android.permissioncontroller") {
                stop("遇到系统权限弹窗；请手动处理后重新测试", terminal = true)
            }
            return
        }
        val nodes = flatten(root)
        val texts = nodes.filter { it.isVisibleToUser && !it.isEditable && !it.isPassword }
            .mapNotNull { it.text?.toString() }.toSet()
        if (attempt.clicked &&
            (attempt.hasNewRecord(texts, submittedWallTime, System.currentTimeMillis()) ||
                attempt.hasNewSuccess(texts))) {
            stop("已核验 BOE 本次新增定位打卡记录或成功反馈；请以 BOE 最终考勤结果为准", success = true)
            return
        }
        if (inspectLogin(nodes, texts)) return
        if (loginOnly) {
            if (loginControl(nodes, BoeLoginPolicy.HOME) != null)
                stop("BOE 已处于登录状态；未验证所保存的帐号密码，未提交打卡")
            return
        }
        if (attempt.mode != AttendanceMode.NAVIGATION &&
            texts.any { it.contains("人脸") || it.contains("验证码") || it.contains("确认打卡") }) {
            stop("需要人工验证或确认，已停止自动操作", terminal = true)
            return
        }
        if (attempt.clicked) {
            when {
                attempt.waitingForResult &&
                    SystemClock.elapsedRealtime() - submittedAt >= AttendanceAttempt.RESULT_WAIT_MS -> {
                    if (!attempt.prepareRetry()) {
                        stop("本轮三次定位点击已用完，结果仍待核验")
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
            if (!attempt.clicked && activity.startsWith("com.meicloud.appbrand.AppBrandUI") &&
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
            stop("考勤入口不唯一，已停止，避免误点", terminal = true)
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

    private fun loginControl(nodes: List<AccessibilityNodeInfo>, id: String): AccessibilityNodeInfo? =
        nodes.filter {
            it.packageName?.toString() == TestPolicy.targetPackage &&
                it.viewIdResourceName == id && it.isVisibleToUser
        }.singleOrNull()

    private fun inspectLogin(nodes: List<AccessibilityNodeInfo>, texts: Set<String>): Boolean {
        val username = loginControl(nodes, BoeLoginPolicy.USERNAME)
        val password = loginControl(nodes, BoeLoginPolicy.PASSWORD)
        val submit = loginControl(nodes, BoeLoginPolicy.SUBMIT)
        val privacy = loginControl(nodes, BoeLoginPolicy.PRIVACY)
        val home = loginControl(nodes, BoeLoginPolicy.HOME) != null
        val form = username?.isEditable == true && password?.isEditable == true &&
            submit?.text?.toString() == "登录"
        val kicked = BoeLoginPolicy.kickedOut(texts)
        val needsLogin = kicked || form || (!home &&
            BoeLoginPolicy.needsLogin(texts, nodes.mapNotNull { it.viewIdResourceName }.toSet(), activity))
        if (loginAttempt == null && !needsLogin) return false
        if (!AttendanceRetryPolicy.mayLogin(attendanceSlot != null && !attempt.dryRun, loginOnly, userInitiated)) {
            stop("BOE 未登录；非主动导航不填写帐号密码，请自行登录或主动运行测试", terminal = true)
            return true
        }
        if (attempt.clicked && !automatic) {
            stop("点击打卡后 BOE 要求重新登录；已停止，需先核验原请求结果，不自动重新提交打卡")
            return true
        }
        val settings = BoeLoginSettings(this)
        if (!settings.configured()) {
            stop("BOE 账号需要重新登录；请在运行设置配置 BOE 帐号密码，或手动登录", terminal = true)
            return true
        }
        try {
            if (loginAttempt == null) {
                if (!BoeLoginPolicy.canStart(settings.paused(), userInitiated, loginStarted,
                        attendanceRequest = attendanceSlot != null && !attempt.dryRun,
                        blocked = settings.blocked())) {
                    stop(if (loginStarted) "本轮已经重新登录过，BOE 再次要求登录；已停止，避免反复抢占其他设备会话"
                        else "BOE 自动登录已暂停；请检查帐号密码，主动点击测试或立即打卡可重新尝试一次",
                        terminal = settings.blocked())
                    return true
                }
                if (settings.paused()) {
                    settings.allowRetry()
                    Store(this).log(if (userInitiated) "用户主动发起新请求，允许本轮单次 BOE 填充和重新登录"
                        else "新的定时打卡请求恢复上次临时中断的 BOE 登录；本轮只尝试一次")
                }
                settings.markStarted(requireNotNull(settings.revision()))
                val credentials = settings.load()
                loginCredentials = credentials
                loginAttempt = BoeLoginAttempt()
                loginStarted = true
                Store(this).log("检测到 BOE 需要登录，开始本轮单次自动重新登录；不记录帐号密码")
            }
            val credentials = requireNotNull(loginCredentials)
            if (settings.revision() != credentials.revision) {
                stop("BOE 登录配置已更改或删除，已停止本轮自动登录", terminal = true)
                return true
            }
            val flow = requireNotNull(loginAttempt)
            val step = flow.next(BoeLoginScreen(TestPolicy.targetPackage, kicked, form, home,
                BoeLoginPolicy.humanVerification(texts, activity), BoeLoginPolicy.rejected(texts),
                privacy?.isCheckable == true && !privacy.isChecked, submit?.isEnabled == true))
            val accepted = when (step) {
                BoeLoginStep.WAIT -> return true
                BoeLoginStep.HUMAN -> {
                    settings.block(credentials.revision)
                    stop("BOE 登录需要验证码、人脸或安全解锁；已停止自动重试，请手动处理", terminal = true)
                    return true
                }
                BoeLoginStep.REJECTED -> {
                    if (BoeLoginPolicy.rejected(texts)) settings.block(credentials.revision)
                    stop("BOE 未接受登录或协议确认；请检查帐号密码或手动处理",
                        terminal = BoeLoginPolicy.rejected(texts))
                    return true
                }
                BoeLoginStep.SUCCESS -> {
                    settings.markSuccessful(credentials.revision)
                    loginAttempt = null
                    loginCredentials = null
                    activity = ""
                    clickedEntry = false
                    clickedWorkbench = false
                    returnedHome = false
                    Store(this).log("已确认 BOE 登录后工作台，继续原流程；登录本身不代表打卡成功")
                    if (loginOnly) stop("BOE 重新登录已核验；未提交打卡")
                    return true
                }
                BoeLoginStep.CONFIRM_KICK -> {
                    val confirm = loginControl(nodes, BoeLoginPolicy.CONFIRM)
                    confirm?.text?.toString() == "确定" && loginAction(confirm, AccessibilityNodeInfo.ACTION_CLICK)
                }
                BoeLoginStep.FILL_FORM -> {
                    username?.isEnabled == true && password?.isEnabled == true &&
                        loginAction(username, AccessibilityNodeInfo.ACTION_SET_TEXT, credentials.username) &&
                        loginAction(password, AccessibilityNodeInfo.ACTION_SET_TEXT, credentials.password)
                }
                BoeLoginStep.ACCEPT_PRIVACY -> privacy != null &&
                    loginAction(privacy, AccessibilityNodeInfo.ACTION_CLICK)
                BoeLoginStep.SUBMIT -> {
                    username?.text?.toString() == credentials.username && submit != null &&
                        loginAction(submit, AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            if (!accepted) stop("BOE 登录控件不唯一、状态变化或操作被拒绝；请手动检查", terminal = true)
            else {
                flow.completed(step)
                if (step == BoeLoginStep.SUBMIT) Store(this).log("已提交本轮唯一一次 BOE 登录请求，等待工作台核验")
            }
        } catch (_: SecurityException) {
            stop("系统禁止访问 BOE 登录控件或凭据密钥；已停止，请检查无障碍及系统权限", terminal = true)
        } catch (_: GeneralSecurityException) {
            stop("无法解密 BOE 登录配置，请在运行设置重新填写帐号密码", terminal = true)
        } catch (_: IOException) {
            stop("无法访问 BOE 登录加密密钥，请检查系统密钥状态", terminal = true)
        } catch (_: JSONException) {
            stop("BOE 登录配置不可识别，请重新填写帐号密码", terminal = true)
        } catch (_: IllegalArgumentException) {
            stop("BOE 登录配置不完整，请重新填写帐号密码", terminal = true)
        } catch (_: IllegalStateException) {
            stop("无法保存 BOE 登录尝试状态，或配置已改变；已停止，请检查存储及配置", terminal = true)
        }
        return true
    }

    private fun blockLoginRetry() {
        val revision = loginCredentials?.revision ?: return
        try {
            BoeLoginSettings(this).block(revision)
        } catch (_: IllegalStateException) {
            Notice.result(this, "无法保存 BOE 登录拒绝状态；请检查配置和设备存储，暂停自动计划后手动处理")
        }
    }

    private fun loginAction(node: AccessibilityNodeInfo, action: Int, value: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != TestPolicy.targetPackage || root.windowId != node.windowId ||
            !node.refresh() || !node.isEnabled || !node.isVisibleToUser) return false
        return if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
            node.isEditable && node.performAction(action, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })
        } else node.isClickable && node.performAction(action)
    }

    private fun inspectAttendance(nodes: List<AccessibilityNodeInfo>, texts: Set<String>) {
        val candidates = nodes.filter {
            it.packageName?.toString() == TestPolicy.targetPackage &&
                it.text?.toString() == attempt.mode.button && it.isVisibleToUser
        }
        if (!attempt.ready(TestPolicy.targetPackage, texts, candidates.size)) {
            stop("考勤页面或打卡按钮不唯一、不可识别；请人工处理", terminal = true)
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
            stop("缺少上下班确认，未点击打卡", terminal = true)
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

    fun cancelCurrent(message: String = "用户停止本次请求，保留打卡计划") {
        stop(message, terminal = true)
    }

    fun cancelAutomaticSlot(slot: ScheduleSlot) {
        if (automatic && attendanceSlot == slot) cancelCurrent("对应计划关闭，停止本次自动重试")
    }

    fun prepareManualOperation() {
        if (running && automatic) cancelCurrent("用户接管，已停止本次自动重试；保留定时计划和待核验结果")
        else if (WakeSession.active()) WakeSession.fail(this, "用户接管，取消本次唤醒任务，保留计划")
    }

    private fun restartAutomaticFlow() {
        retryWaiting = false
        if (!automatic || !running) return
        if (System.currentTimeMillis() >= retryDeadline ||
            SystemClock.elapsedRealtime() >= retryElapsedDeadline ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            stop("自动重试截止或设备锁定；请手动核验", terminal = true)
            return
        }
        flowNumber++
        attempt.restartFlow()
        try {
            attendanceSlot?.let { if (submittedWallTime > 0) Store(this).resetAttendanceBudget(it) }
        } catch (_: IllegalStateException) {
            stop("无法保存新轮点击预算，已停止自动重试；请检查设备存储", terminal = true)
            return
        }
        loginStarted = false
        loginAttempt = null
        loginCredentials = null
        clickedEntry = false
        clickedWorkbench = false
        returnedHome = false
        waitingForReentry = false
        reentered = false
        activity = ""
        deadline = minOf(retryDeadline, System.currentTimeMillis() + AttendanceRetryPolicy.SESSION_MS)
        elapsedDeadline = minOf(retryElapsedDeadline, SystemClock.elapsedRealtime() + AttendanceRetryPolicy.SESSION_MS)
        val launch = packageManager.getLaunchIntentForPackage(TestPolicy.targetPackage)
        if (launch == null) {
            stop("BOE 未安装，停止自动重试", terminal = true)
            return
        }
        Notice.show(this, "自动打卡第 $flowNumber 轮；在助手中主动操作可手动接管", true)
        Store(this).log("重新进入 BOE 打卡流程，第 $flowNumber 轮；保留原请求核验起点")
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            stop("无法重新打开 BOE", terminal = true)
        } catch (_: SecurityException) {
            stop("系统禁止重新打开 BOE", terminal = true)
        }
    }

    fun stop(message: String, success: Boolean = false, terminal: Boolean = false) {
        if (running && AttendanceRetryPolicy.mayRetry(automatic, terminal, success, false,
                System.currentTimeMillis(), retryDeadline) &&
            SystemClock.elapsedRealtime() + AttendanceRetryPolicy.RETRY_DELAY_MS < retryElapsedDeadline) {
            retryWaiting = true
            loginCredentials = null
            loginAttempt = null
            handler.removeCallbacks(retryFlow)
            handler.postDelayed(retryFlow, AttendanceRetryPolicy.RETRY_DELAY_MS)
            Store(this).log("自动打卡第 $flowNumber 轮未核验成功：$message；30 秒后重新走流程，可停止并手动接管")
            Notice.show(this, "打卡尚未核验成功，30 秒后重试；在助手中主动操作可手动接管", true)
            return
        }
        val submitted = attempt.clicked && running
        val lockScreen = running && attempt.shouldLockScreen(success)
        val result = if (submitted && !success) "$message；已尝试点击打卡，结果待核验" else message
        if (submitted) attendanceSlot?.let { Store(this).attendanceResult(it, result, success) }
        val reportSlot = if (running) attendanceSlot.takeUnless { attempt.dryRun } else WakeSession.attendanceSlot
        reportSlot?.let {
            NotificationDelivery.result(this, it, if (running) startedAt else System.currentTimeMillis(),
                success, submitted, result)
        }
        running = false
        automatic = false
        retryWaiting = false
        handler.removeCallbacks(retryFlow)
        executionLock?.let { if (it.isHeld) it.release() }
        executionLock = null
        loginOnly = false
        loginAttempt = null
        loginCredentials = null
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
        val settingsIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${TestPolicy.targetPackage}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (settingsIntent.resolveActivity(packageManager)?.packageName != BoeStopPolicy.SETTINGS) {
            Notice.result(this, "$result；本机应用详情不受支持，无法强行停止 BOE，请手动处理")
            returnHomeAndLockScreen(result)
            return
        }
        try {
            startActivity(settingsIntent)
        } catch (_: ActivityNotFoundException) {
            Notice.result(this, "$result；无法打开 BOE 应用详情，未强行停止")
            returnHomeAndLockScreen(result)
            return
        } catch (_: SecurityException) {
            Notice.result(this, "$result；系统禁止打开 BOE 应用详情，未强行停止")
            returnHomeAndLockScreen(result)
            return
        }
        val stopDeadline = SystemClock.elapsedRealtime() + 10_000
        var requested = false
        var confirmed = false
        val stopBoe = object : Runnable {
            override fun run() {
                if (!finishingSuccess) return
                @Suppress("DEPRECATION")
                val stopped = packageManager.getApplicationInfo(TestPolicy.targetPackage, 0).flags and
                    ApplicationInfo.FLAG_STOPPED != 0
                if (stopped) {
                    Store(this@NavigationService).log("已确认系统强行停止 BOE，应用处于 stopped 状态")
                    returnHomeAndLockScreen(result)
                    return
                }
                if (SystemClock.elapsedRealtime() >= stopDeadline) {
                    Notice.result(this@NavigationService, "$result；未确认 BOE 已强行停止，请手动检查；仍尝试息屏")
                    returnHomeAndLockScreen(result)
                    return
                }
                val root = rootInActiveWindow
                if (root?.packageName?.toString() == BoeStopPolicy.SETTINGS) {
                    val nodes = flatten(root).filter { it.isVisibleToUser }
                    fun unique(id: String) = nodes.filter { it.viewIdResourceName == id }.singleOrNull()
                    val button = unique(BoeStopPolicy.STOP_BUTTON)
                    val confirm = unique(BoeStopPolicy.CONFIRM)
                    val message = unique("android:id/message")?.text?.toString().orEmpty()
                    val target = when {
                        !requested && BoeStopPolicy.isBoeDetails(BoeStopPolicy.SETTINGS,
                            unique(BoeStopPolicy.APP_NAME)?.text?.toString().orEmpty()) &&
                            button?.text?.toString() == "强行停止" -> button
                        !confirmed && BoeStopPolicy.isConfirmation(BoeStopPolicy.SETTINGS, message,
                            confirm?.text?.toString().orEmpty(), requested) -> confirm
                        else -> null
                    }
                    if (target != null && target.isEnabled && target.isClickable &&
                        target.packageName?.toString() == BoeStopPolicy.SETTINGS &&
                        target.windowId == root.windowId) {
                        val accepted = try {
                            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } catch (_: SecurityException) {
                            false
                        }
                        if (!accepted) {
                            Notice.result(this@NavigationService, "$result；系统拒绝强行停止 BOE 操作，请手动检查")
                            returnHomeAndLockScreen(result)
                            return
                        }
                        if (!requested) requested = true else confirmed = true
                    }
                }
                handler.postDelayed(this, 300)
            }
        }
        handler.postDelayed(stopBoe, 500)
    }

    private fun returnHomeAndLockScreen(result: String) {
        val homeAccepted = try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (error: SecurityException) {
            Store(this).log("系统禁止返回桌面：${error.message}")
            false
        }
        if (!homeAccepted) {
            Notice.result(this, "$result；系统拒绝退出 BOE 前台，请手动返回桌面")
        } else {
            Store(this).log("打卡成功收尾：已请求返回系统桌面")
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
        stop("无障碍服务被中断，测试已停止", terminal = true)
    }

    override fun onDestroy() {
        if (running) stop("导航服务已关闭，测试已停止", terminal = true)
        instance = null
        super.onDestroy()
    }

    companion object {
        var instance: NavigationService? = null
            private set
    }
}
