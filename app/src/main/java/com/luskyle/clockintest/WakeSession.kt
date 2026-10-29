package com.luskyle.clockintest

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

object WakeSession {
    private val handler = Handler(Looper.getMainLooper())
    private var lock: PowerManager.WakeLock? = null
    private var timeout: Runnable? = null
    private var generation = 0
    private var until = 0L
    private var elapsedUntil = 0L
    private var consumed = false
    var attendanceSlot: ScheduleSlot? = null
        private set

    fun request(context: Context, deadline: Long, slot: ScheduleSlot? = null) {
        if (active()) {
            Store(context).log("已有唤醒或导航请求，忽略重复触发")
            return
        }
        finish()
        attendanceSlot = slot
        val duration = minOf(65_000L, deadline - System.currentTimeMillis())
        if (duration <= 0) {
            Notice.result(context, "唤醒请求已过期，未启动")
            return
        }
        until = deadline
        elapsedUntil = SystemClock.elapsedRealtime() + duration
        consumed = false
        val token = generation
        lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClockInTest:Navigation").apply {
                acquire(duration)
            }
        timeout = Runnable {
            if (generation == token) {
                NavigationService.instance?.stop("后台唤醒或导航超时，已停止；请检查自启动、后台运行和无障碍权限")
                    ?: Notice.result(context, "后台唤醒超时：无障碍服务未连接，请检查系统设置")
                finish()
            }
        }.also { handler.postDelayed(it, duration) }
        Store(context).log("定时请求已接收；正在等待无障碍服务并唤醒屏幕")
        waitForService(context.applicationContext, token, SystemClock.elapsedRealtime() + 10_000)
    }

    private fun waitForService(context: Context, token: Int, waitUntil: Long) {
        if (generation != token || !active()) return
        val service = NavigationService.instance
        val access = NavigationAccess.read(context)
        if (access == NavigationAccess.DISABLED) {
            Notice.result(context, access.issue!!)
            finish()
        } else if (service != null) {
            service.wakeScreen()
        } else if (SystemClock.elapsedRealtime() >= waitUntil) {
            Notice.result(context, "系统未重新连接无障碍服务，自动启动失败；请检查服务及厂商后台设置")
            finish()
        } else {
            handler.postDelayed({ waitForService(context, token, waitUntil) }, 500)
        }
    }

    fun active(): Boolean = until > System.currentTimeMillis() && elapsedUntil > SystemClock.elapsedRealtime()

    fun takeDeadline(): Long? {
        if (!active() || consumed) return null
        consumed = true
        return until
    }

    fun wakeIntent(context: Context): Intent = Intent(context, WakeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

    fun finish() {
        generation++
        timeout?.let(handler::removeCallbacks)
        timeout = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        until = 0
        elapsedUntil = 0
        attendanceSlot = null
    }
}
