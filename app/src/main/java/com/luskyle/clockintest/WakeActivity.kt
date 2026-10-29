package com.luskyle.clockintest

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout

class WakeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var handled = false
    private var preview = false
    private val timeout = Runnable { fail("亮屏或解除普通锁屏超时；请手动解锁后测试") }
    private val continueNavigation = Runnable { navigate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = intent.getBooleanExtra("preview", false)
        if (!preview && !WakeSession.active()) {
            finish()
            return
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (preview) {
            setContentView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                addView(TextView(this@WakeActivity).apply {
                    text = "锁屏显示权限检查\n\n如果系统提示“在锁屏上显示”，请允许。\n\n此页仅用于提前完成厂商授权，不会打开 BOE，也不会提交打卡；不会解除安全锁屏。\n\n完成后仍需通过两分钟息屏测试验证设备。"
                    textSize = 18f
                    gravity = Gravity.CENTER
                })
                addView(Button(this@WakeActivity).apply {
                    text = "完成，返回助手"
                    setOnClickListener { finish() }
                })
            })
            return
        }
        setContentView(TextView(this).apply {
            text = if (WakeSession.attendanceSlot == null) "正在唤醒页面测试\n只到考勤页面，不提交打卡"
                else "正在唤醒考勤计划\n按已确认的方式运行"
            textSize = 20f
            gravity = Gravity.CENTER
        })
    }

    override fun onResume() {
        super.onResume()
        if (preview) return
        if (!WakeSession.active()) {
            finish()
            return
        }
        if (handled) return
        handled = true
        handler.postDelayed(timeout, 8000)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked && keyguard.isDeviceSecure) {
            fail("屏幕已唤醒，但存在安全锁屏；请手动解锁后点击立即测试，不会绕过密码或人脸")
        } else if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { navigate() }
                override fun onDismissCancelled() { fail("普通锁屏解除被取消，测试已停止") }
                override fun onDismissError() { fail("系统不允许解除普通锁屏，请手动解锁后测试") }
            })
        } else {
            navigate()
        }
    }

    private fun navigate() {
        if (isFinishing || isDestroyed) return
        if (WakeSession.active() &&
            (getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
                !getSystemService(PowerManager::class.java).isInteractive)) {
            handler.postDelayed(continueNavigation, 100)
            return
        }
        handler.removeCallbacksAndMessages(null)
        val until = WakeSession.takeDeadline()
        val service = NavigationService.instance
        if (until == null || service == null) {
            fail("唤醒请求已过期或无障碍服务已断开，测试已停止")
            return
        }
        Store(this).log("屏幕唤醒成功；开始导航，无需助手首页在前台")
        val slot = WakeSession.attendanceSlot
        service.begin(until, slot = slot)
        finish()
    }

    private fun fail(message: String) {
        handler.removeCallbacksAndMessages(null)
        WakeSession.finish()
        Notice.result(this, message)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
