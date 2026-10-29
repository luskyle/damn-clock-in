package com.luskyle.clockintest

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.format.DateTimeFormatter

// Persist the daily attempt before navigating, including when a receiver exits.
@SuppressLint("ApplySharedPref")
class Store(context: Context) {
    val prefs = context.getSharedPreferences("navigation-test", Context.MODE_PRIVATE)
    init {
        if (!prefs.getBoolean("dualSchedule", false)) {
            check(prefs.edit()
                .putBoolean("MORNING.enabled", prefs.getBoolean("enabled", false))
                .putLong("MORNING.next", prefs.getLong("next", 0))
                .putString("MORNING.attempted", prefs.getString("attempted", "") ?: "")
                .remove("enabled").remove("next").remove("attempted")
                .putBoolean("dualSchedule", true).commit()) { "Cannot migrate daily plan" }
        }
        if (!prefs.getBoolean("workdayV3", false)) {
            val editor = prefs.edit().putBoolean("workdayV3", true)
            if (prefs.getInt("EVENING.start", 1050) == 1050 &&
                prefs.getInt("EVENING.end", 1080) == 1080) {
                editor.putInt("EVENING.start", 1050).putInt("EVENING.end", 1052)
                    .putBoolean("EVENING.enabled", false).putLong("EVENING.next", 0)
            }
            check(editor.commit()) { "Cannot migrate evening default" }
        }
        if (!prefs.getBoolean("locationOnlyV1", false)) {
            val wasLocation = prefs.getString("attendanceMode", null) == "LOCATION"
            val editor = prefs.edit().remove("attendanceMode").putBoolean("locationOnlyV1", true)
            if (!wasLocation) {
                ScheduleSlot.entries.forEach {
                    editor.putBoolean("${it.name}.enabled", false).putLong("${it.name}.next", 0)
                }
            }
            check(editor.commit()) { "Cannot migrate fixed location attendance" }
            if (!wasLocation) log("计划已统一为定位打卡；旧非定位计划保持关闭，请重新确认开启")
        }
    }

    fun enabled(slot: ScheduleSlot): Boolean = prefs.getBoolean("${slot.name}.enabled", false)
    fun setEnabled(slot: ScheduleSlot, value: Boolean) {
        check(prefs.edit().putBoolean("${slot.name}.enabled", value).commit()) { "Cannot persist schedule state" }
    }
    fun next(slot: ScheduleSlot): Long = prefs.getLong("${slot.name}.next", 0)
    fun setNext(slot: ScheduleSlot, value: Long) {
        check(prefs.edit().putLong("${slot.name}.next", value).commit()) { "Cannot persist next test" }
    }
    fun attempted(slot: ScheduleSlot): String = prefs.getString("${slot.name}.attempted", "") ?: ""
    fun attendanceClaimed(slot: ScheduleSlot): Boolean =
        prefs.getString("${slot.name}.attendanceDay", "") == TestPolicy.day(System.currentTimeMillis()).toString()

    fun claimAttendance(slot: ScheduleSlot, mode: AttendanceMode): Boolean {
        check(mode != AttendanceMode.NAVIGATION)
        if (attendanceClaimed(slot)) return false
        check(prefs.edit()
            .putString("${slot.name}.attendanceDay", TestPolicy.day(System.currentTimeMillis()).toString())
            .putString("${slot.name}.attendanceResult", "${mode.label}请求准备发出，结果待核验")
            .putInt("${slot.name}.attendanceClicks", 0)
            .putLong("${slot.name}.attendanceStartedAt", System.currentTimeMillis())
            .putBoolean("${slot.name}.attendanceSucceeded", false)
            .commit()) { "Cannot persist attendance claim" }
        return true
    }

    fun attendanceClicks(slot: ScheduleSlot): Int =
        if (attendanceClaimed(slot)) prefs.getInt("${slot.name}.attendanceClicks", 1) else 0

    fun attendanceStartedAt(slot: ScheduleSlot): Long =
        prefs.getLong("${slot.name}.attendanceStartedAt", 0)

    fun ensureAttendanceStartedAt(slot: ScheduleSlot): Long {
        val saved = attendanceStartedAt(slot)
        if (saved > 0) return saved
        val now = System.currentTimeMillis()
        check(prefs.edit().putLong("${slot.name}.attendanceStartedAt", now).commit()) {
            "Cannot persist retry verification start"
        }
        log("旧请求缺少时间依据，本次明确确认的重试将仅核验此刻之后的新增记录，保留原点击预算")
        return now
    }

    fun canRetryAttendance(slot: ScheduleSlot): Boolean =
        attendanceClaimed(slot) && attendanceClicks(slot) in 1 until AttendanceAttempt.MAX_CLICKS &&
            !prefs.getBoolean("${slot.name}.attendanceSucceeded", false) &&
            prefs.getString("${slot.name}.attendanceResult", "").orEmpty().let {
                !it.contains("打卡成功") && !it.contains("已核验")
            }

    fun reserveAttendanceClick(slot: ScheduleSlot): Boolean {
        if (!attendanceClaimed(slot) || attendanceClicks(slot) >= AttendanceAttempt.MAX_CLICKS) return false
        check(prefs.edit().putInt("${slot.name}.attendanceClicks", attendanceClicks(slot) + 1)
            .putString("${slot.name}.attendanceResult", "定位打卡请求处理中，结果待核验")
            .commit()) { "Cannot persist attendance click budget" }
        return true
    }

    fun attendanceResult(slot: ScheduleSlot, result: String, success: Boolean = false) {
        check(prefs.edit().putString("${slot.name}.attendanceResult", result)
            .putBoolean("${slot.name}.attendanceSucceeded", success).commit()) {
            "Cannot persist attendance result"
        }
    }

    fun lastAttendance(slot: ScheduleSlot): String? {
        val day = prefs.getString("${slot.name}.attendanceDay", null) ?: return null
        val result = prefs.getString("${slot.name}.attendanceResult", null)
            ?: "请求结果缺失，请在 BOE 核验，勿直接重试"
        return "$day  $result"
    }

    fun setAttempted(slot: ScheduleSlot, value: String) {
        check(prefs.edit().putString("${slot.name}.attempted", value).commit()) { "Cannot persist daily attempt" }
    }
    fun window(slot: ScheduleSlot): TimeWindow = TimeWindow(
        prefs.getInt("${slot.name}.start", slot.defaultStart),
        prefs.getInt("${slot.name}.end", slot.defaultEnd)
    )
    fun saveWindow(slot: ScheduleSlot, window: TimeWindow) {
        check(prefs.edit().putInt("${slot.name}.start", window.startMinute)
            .putInt("${slot.name}.end", window.endMinute).putLong("${slot.name}.next", 0)
            .putBoolean("${slot.name}.enabled", false).commit()) { "Cannot persist time window" }
    }

    fun log(message: String) {
        val line = "${format(System.currentTimeMillis())}  $message"
        val history = prefs.getString("history", "") ?: ""
        check(prefs.edit().putString("history", (line + "\n" + history).lineSequence().take(80).joinToString("\n")).commit()) {
            "Cannot persist execution log"
        }
        android.util.Log.i("ClockInTest", message)
    }

    fun history(): String = prefs.getString("history", "") ?: ""

    companion object {
        fun format(time: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(TestPolicy.zone).format(Instant.ofEpochMilli(time))
    }
}

object Notice {
    fun show(context: Context, text: String, active: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("test", "页面测试状态", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(!active)
            .setOngoing(active)
        if (active) {
            val stop = PendingIntent.getBroadcast(
                context, 1, Intent(context, AlarmReceiver::class.java).setAction("stop"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(null, "停止测试及定时", stop).build())
        }
        manager.notify(1, builder.build())
    }

    fun result(context: Context, text: String) {
        Store(context).log(text)
        show(context, text)
    }
}
