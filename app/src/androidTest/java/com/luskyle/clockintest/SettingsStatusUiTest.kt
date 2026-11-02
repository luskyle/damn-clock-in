package com.luskyle.clockintest

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStatusUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun open(activity: Activity): List<Button> {
        descendants(activity.window.decorView).filterIsInstance<Button>()
            .single { it.text.toString() == "运行设置" }.performClick()
        return WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }.filterIsInstance<Button>()
    }

    private fun assertStatus(buttons: List<Button>, prefix: String, complete: Boolean?) {
        val button = buttons.single { it.text.toString().startsWith(prefix) }
        val expectedBackground = when (complete) {
            true -> Color.rgb(232, 244, 241)
            false -> Color.rgb(253, 235, 236)
            null -> Color.rgb(244, 247, 249)
        }
        val expectedText = when (complete) {
            true -> Color.rgb(16, 120, 110)
            false -> Color.rgb(157, 47, 57)
            null -> Color.rgb(27, 44, 59)
        }
        assertEquals(prefix, expectedBackground, (button.background as GradientDrawable).color!!.defaultColor)
        assertEquals(prefix, expectedText, button.currentTextColor)
    }

    @Test fun settingsColorsMatchCurrentPermissionsWithoutChangingPlans() {
        val activity = instrumentation.startActivitySync(Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val plans = Store(target).prefs.all
        try {
            instrumentation.runOnMainSync {
                val buttons = open(activity)
                assertStatus(buttons, "BOE 帐号与自动登录", BoeLoginSettings(target).configured())
                assertStatus(buttons, "Bark 推送到 iPhone", BarkSettings(target).configured())
                assertStatus(buttons, "无障碍服务", NavigationAccess.read(target) == NavigationAccess.CONNECTED)
                assertStatus(buttons, "通知 ·", target.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
                assertStatus(buttons, "精确闹钟", Scheduler(target).exactAllowed())
                assertStatus(buttons, "电池优化", target.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(target.packageName))
                assertStatus(buttons, "自启动与后台保护", false)
                assertStatus(buttons, "先检查锁屏显示权限", false)
                assertStatus(buttons, "2 分钟后息屏测试", null)
                buttons.single { it.text.toString() == "完成" }.performClick()
            }
            assertEquals(plans, Store(target).prefs.all)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun savingAndDeletingCredentialsUpdatesColorsWhenReopeningSettings() {
        check(InstrumentationRegistry.getArguments().getString("barkTestDevice") == "true")
        val boe = BoeLoginSettings(target)
        val bark = BarkSettings(target)
        check(!boe.configured() && !bark.configured())
        val activity = instrumentation.startActivitySync(Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val plans = Store(target).prefs.all
        try {
            for (configured in listOf(false, true, false)) {
                if (configured) {
                    boe.save("synthetic-settings-account", "synthetic-settings-password", true)
                    bark.saveKey("synthetic-settings-key")
                } else {
                    boe.clear()
                    bark.clear()
                }
                instrumentation.runOnMainSync {
                    val buttons = open(activity)
                    assertStatus(buttons, "BOE 帐号与自动登录", configured)
                    assertStatus(buttons, "Bark 推送到 iPhone", configured)
                    assertTrue(buttons.single { it.text.toString().startsWith("BOE 帐号与自动登录") }
                        .text.toString().endsWith(if (configured) "已配置" else "未配置"))
                    buttons.single { it.text.toString() == "完成" }.performClick()
                }
            }
            assertEquals(plans, Store(target).prefs.all)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            boe.clear()
            bark.clear()
        }
    }
}
