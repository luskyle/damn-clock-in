package com.luskyle.clockintest

import android.app.job.JobScheduler
import android.content.Context
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

object NotificationDelivery {
    fun result(context: Context, slot: ScheduleSlot, startedAt: Long, success: Boolean, submitted: Boolean, reason: String) {
        removeLegacyNotifications(context)
        val subject = "永不迟到 · ${slot.title} · ${NotificationPolicy.outcome(success, submitted)}"
        val body = NotificationPolicy.body(slot.title, Store.format(startedAt), Store.format(System.currentTimeMillis()),
            success, submitted, reason)
        if (BarkSettings(context).configured()) BarkDelivery.enqueue(context, subject, body)
    }

    fun schedule(context: Context): Boolean {
        removeLegacyNotifications(context)
        return BarkDelivery.schedule(context)
    }

    @Synchronized
    private fun removeLegacyNotifications(context: Context) {
        removeLegacyChannel(context, "legacyEmailRemovedV1", 30,
            listOf("email-settings", "email-outbox"), "clockin-smtp-password-v1", "邮件")
        removeLegacyChannel(context, "legacyClawRemovedV1", 32,
            listOf("claw-settings", "claw-outbox"), "clockin-claw-account-v1", "微信 ClawBot")
    }

    private fun removeLegacyChannel(context: Context, flag: String, jobId: Int,
                                    preferences: List<String>, alias: String, label: String) {
        val store = Store(context)
        if (store.prefs.getBoolean(flag, false)) return
        context.getSystemService(JobScheduler::class.java).cancel(jobId)
        preferences.forEach { check(context.deleteSharedPreferences(it)) { "Cannot remove legacy notification settings" } }
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        } catch (_: GeneralSecurityException) {
            Notice.result(context, "旧${label}凭据清理失败，发送服务已移除，请重启应用后重试")
            return
        } catch (_: IOException) {
            Notice.result(context, "无法访问设备密钥清理旧${label}凭据，发送服务已移除，请重启应用后重试")
            return
        }
        check(store.prefs.edit().putBoolean(flag, true).commit()) {
            "Cannot persist notification upgrade"
        }
    }
}
