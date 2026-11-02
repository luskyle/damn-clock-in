package com.luskyle.clockintest

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.security.GeneralSecurityException

object BoeLoginSettingsDialog {
    fun show(activity: Activity): AlertDialog {
        val settings = BoeLoginSettings(activity)
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding)
        }
        layout.addView(TextView(activity).apply {
            text = "保存后，BOE 前台出现被挤下线或登录页时，助手会填写帐号密码并重新登录；" +
                "打卡过程中登录成功后继续原请求，单独测试登录不会打卡。\n\n" +
                "安卓重新登录可能将 iPhone 上的 BOE 挤下线。不会在后台反复抢登录。" +
                "每轮只提交一次；未确认成功或遇到密码错误、验证码、人脸、安全锁时暂停。" +
                "新的定时打卡可恢复临时中断，但不会自动重试明确密码错误或额外验证。" +
                "请检查后主动点击测试或立即打卡重试，无需反复保存配置。\n\n" +
                "帐号密码仅在本机通过 Android Keystore 加密保存，只填写到已识别的 BOE 登录控件，" +
                "不进入日志、推送或备份。已保存的内容不会回显。更换时请同时填写帐号及密码；都留空则保留。"
        })
        fun input(label: String, password: Boolean) = EditText(activity).apply {
            contentDescription = label
            hint = label
            inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            layout.addView(this)
        }
        val username = input("BOE 帐号", false)
        val password = input("BOE 密码", true)
        val consent = CheckBox(activity).apply {
            text = "我已阅读并同意 BOE 登录页隐私政策，允许自动勾选并登录"
            isChecked = settings.configured()
            layout.addView(this)
        }
        val status = TextView(activity).apply {
            text = when {
                !settings.configured() -> "未配置，不自动登录"
                settings.blocked() -> "已保存；登录被拒绝或需人工验证，请检查后重新保存"
                settings.paused() -> "已保存；上次登录中断，新打卡任务可尝试一次"
                else -> "已保存，可自动重新登录"
            }
            layout.addView(this)
        }
        val error = TextView(activity).apply {
            setTextColor(android.graphics.Color.RED)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            layout.addView(this)
        }
        fun protect(action: () -> Unit): Boolean = try {
            action()
            true
        } catch (_: SecurityException) {
            error.text = "系统禁止访问设备密钥，请检查系统权限"
            false
        } catch (_: GeneralSecurityException) {
            error.text = "无法访问设备加密密钥，请检查系统密钥状态"
            false
        } catch (_: IOException) {
            error.text = "无法访问设备密钥，请重试"
            false
        } catch (_: IllegalStateException) {
            error.text = "无法保存或删除 BOE 配置，请检查设备存储空间"
            false
        }
        fun save(): Boolean {
            if (!consent.isChecked) {
                error.text = "请先在 BOE 阅读隐私政策，并确认允许自动登录"
                return false
            }
            val user = username.text.toString().trim()
            val pass = password.text.toString()
            if ((user.isEmpty() || pass.isEmpty()) &&
                !(user.isEmpty() && pass.isEmpty() && settings.configured())) {
                error.text = "请同时填写 BOE 帐号和密码"
                return false
            }
            return protect {
                if (user.isEmpty() && pass.isEmpty()) settings.allowRetry()
                else settings.save(user, pass, true)
                username.text.clear()
                password.text.clear()
                error.text = ""
                Store(activity).log("BOE 自动登录配置已保存；帐号密码不记入日志")
            }
        }
        val dialog = AlertDialog.Builder(activity).setTitle("BOE 帐号与自动登录")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("保存", null).setNegativeButton("关闭", null).create()
        layout.addView(Button(activity).apply {
            text = "保存并测试登录（不打卡）"
            setOnClickListener {
                val issue = NavigationAccess.read(activity).issue
                val service = NavigationService.instance
                if (issue != null || service == null) {
                    error.text = issue ?: "无障碍连接已变化，请重试"
                } else if (save()) {
                    dialog.dismiss()
                    service.begin(loginOnly = true, userInitiated = true)
                }
            }
        })
        layout.addView(Button(activity).apply {
            text = "删除帐号密码并停止自动登录"
            setOnClickListener {
                AlertDialog.Builder(activity).setTitle("删除 BOE 登录配置？")
                    .setMessage("删除本机保存的帐号、密码和加密密钥，正在进行的自动登录也会停止。" +
                        "不退出 BOE 当前登录，不改变 Bark 或打卡计划。")
                    .setPositiveButton("确认删除") { _, _ ->
                        if (protect { settings.clear() }) {
                            username.text.clear()
                            password.text.clear()
                            consent.isChecked = false
                            status.text = "配置已删除，不再自动登录"
                            Store(activity).log("已删除 BOE 自动登录配置")
                        }
                    }.setNegativeButton("取消", null).show()
            }
        })
        val secureBefore = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener {
            username.text.clear()
            password.text.clear()
            if (!secureBefore) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (save()) dialog.dismiss() }
        return dialog
    }
}
