package com.luskyle.clockintest

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.security.GeneralSecurityException

object BarkSettingsDialog {
    fun show(activity: Activity): AlertDialog {
        val settings = BarkSettings(activity)
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding)
        }
        layout.addView(TextView(activity).apply {
            text = "1. 在 iPhone 的 App Store 安装 Bark 并允许通知。\n" +
                "2. 只填写 Bark 的 Key，无需填写网址；已内置官方服务器。\n" +
                "3. 保存并发送测试通知，在 iPhone 上确认收到。\n\n" +
                "不需要邮箱。配置后，真实打卡成功、未完成（含登录失效）和结果待核验都会推送。" +
                "结果摘要会经过 Bark 官方服务器及苹果 APNs。" +
                "不发送 BOE 页面、位置或账号。Key 是私密密钥，请勿公开。"
        })
        val input = EditText(activity).apply {
            contentDescription = "Bark Key"
            hint = if (settings.configured()) "已保存，留空保留现有配置" else "只填写 Bark Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        layout.addView(input)
        val status = TextView(activity).apply { text = BarkDelivery.status(activity) }
        layout.addView(status)
        val error = TextView(activity).apply { setTextColor(android.graphics.Color.RED) }
        layout.addView(error)
        fun save(): Boolean {
            val value = input.text.toString().trim()
            if (value.isEmpty() && settings.configured()) return true
            if (BarkEndpoint.fromKey(value) == null) {
                error.text = "请只填写 Bark Key，不要填写网址、斜杠、空格或推送内容"
                return false
            }
            return try {
                settings.saveKey(value)
                input.text.clear()
                input.hint = "已保存，留空保留现有配置"
                error.text = ""
                BarkDelivery.retry(activity)
                true
            } catch (_: GeneralSecurityException) {
                error.text = "系统无法加密保存 Bark 密钥，请检查设备密钥状态"
                false
            } catch (_: IOException) {
                error.text = "无法访问设备密钥，请重试"
                false
            } catch (_: IllegalStateException) {
                error.text = "无法保存 Bark 配置，请检查设备存储空间"
                false
            }
        }
        layout.addView(Button(activity).apply {
            text = "保存并发送 Bark 测试通知"
            setOnClickListener {
                if (save()) {
                    BarkDelivery.enqueue(activity, "永不迟到 · Bark 配置测试",
                        "这是一条测试推送，不代表已执行打卡。\n北京时间：${Store.format(System.currentTimeMillis())}")
                    status.text = BarkDelivery.status(activity)
                }
            }
        })
        layout.addView(Button(activity).apply {
            text = "刷新 Bark 发送状态 / 重试"
            setOnClickListener {
                BarkDelivery.retry(activity)
                status.text = BarkDelivery.status(activity)
            }
        })
        layout.addView(Button(activity).apply {
            text = "关闭 Bark 并删除配置及待发推送"
            setOnClickListener {
                AlertDialog.Builder(activity).setTitle("关闭 Bark？")
                    .setMessage("删除本机 Bark Key 及待发推送，不影响打卡计划。已交给服务器的推送不能撤回。")
                    .setPositiveButton("确认删除") { _, _ ->
                        settings.clear()
                        BarkDelivery.clear(activity)
                        input.text.clear()
                        input.hint = "只填写 Bark Key"
                        status.text = "Bark 已关闭，重新保存 Key 即可启用"
                    }.setNegativeButton("取消", null).show()
            }
        })
        val dialog = AlertDialog.Builder(activity).setTitle("Bark 推送到 iPhone")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("保存", null).setNegativeButton("关闭", null).create()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (save()) {
                    Store(activity).log("Bark 推送配置已保存")
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        return dialog
    }
}
