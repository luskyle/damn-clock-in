package com.luskyle.clockintest

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import kotlin.random.Random

class Scheduler(private val context: Context) {
    private val store = Store(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun requirements(): String? = when {
        !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() ->
            "请先允许通知，确保可以收到失败和解锁提醒"
        !exactAllowed() -> "请先允许闹钟和提醒（精确定时）"
        NavigationService.instance == null -> "请先手动开启测试导航无障碍服务"
        context.packageManager.getLaunchIntentForPackage(TestPolicy.targetPackage) == null ->
            "未安装 BOE 移动门户"
        else -> null
    }

    private fun pending(slot: ScheduleSlot): PendingIntent = PendingIntent.getBroadcast(
        context, slot.alarmId, Intent(context, AlarmReceiver::class.java)
            .setAction("daily").putExtra("slot", slot.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun enable(slot: ScheduleSlot) {
        val issue = requirements()
        if (issue != null) {
            Notice.result(context, issue)
            return
        }
        store.setEnabled(slot, true)
        restoreSlot(slot)
    }

    fun disable() {
        ScheduleSlot.entries.forEach { disableSlot(it) }
        NavigationService.instance?.stop("已停止测试和每日计划")
        Notice.result(context, "上下班计划已关闭；不会再自动导航")
    }

    fun disableSlot(slot: ScheduleSlot) {
        store.setEnabled(slot, false)
        alarms.cancel(pending(slot))
    }

    fun changeWindow(slot: ScheduleSlot, window: TimeWindow): Boolean {
        val other = ScheduleSlot.entries.single { it != slot }
        if (window.overlaps(store.window(other))) {
            Notice.result(context, "上下班时间段不能重叠或共用端点，请重新设置")
            return false
        }
        if (window == store.window(slot)) return true
        disableSlot(slot)
        store.saveWindow(slot, window)
        Notice.result(context, "${slot.title}时间段已保存为 $window；请确认后重新开启该计划")
        return true
    }

    fun restore() {
        // Remove the previous single-plan PendingIntent after upgrading.
        alarms.cancel(PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java).setAction("daily"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
        ScheduleSlot.entries.forEach {
            if (store.enabled(it)) restoreSlot(it) else alarms.cancel(pending(it))
        }
    }

    private fun restoreSlot(slot: ScheduleSlot) {
        if (!store.enabled(slot)) return
        if (!exactAllowed()) {
            disableSlot(slot)
            store.setNext(slot, 0)
            Notice.result(context, "${slot.title}：精确闹钟权限缺失，无法恢复计划；请授权后重新启用")
            return
        }
        val now = System.currentTimeMillis()
        val window = store.window(slot)
        schedule(slot) {
            ChinaWorkdays.plan(now, store.next(slot), store.attempted(slot), Random.nextInt(window.seconds + 1), window)
        }
    }

    private fun schedule(slot: ScheduleSlot, time: () -> Long) {
        try {
            arm(slot, time())
        } catch (error: CalendarUnavailable) {
            disableSlot(slot)
            store.setNext(slot, 0)
            Notice.result(context, "${slot.title}：${error.message}")
        }
    }

    private fun arm(slot: ScheduleSlot, time: Long) {
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(slot))
            store.setNext(slot, time)
            store.log("${slot.title}下次页面测试：${Store.format(time)}（北京时间）")
        } catch (error: SecurityException) {
            disableSlot(slot)
            store.setNext(slot, 0)
            Notice.result(context, "${slot.title}设置闹钟失败：${error.message}；请检查精确闹钟权限")
        }
    }

    fun fire(slot: ScheduleSlot) {
        if (!store.enabled(slot)) return
        val now = System.currentTimeMillis()
        val planned = store.next(slot)
        if (planned == 0L || now < planned) return
        val workday = try {
            ChinaWorkdays.isWorkday(TestPolicy.day(now))
        } catch (error: CalendarUnavailable) {
            disableSlot(slot)
            store.setNext(slot, 0)
            Notice.result(context, "${slot.title}：${error.message}")
            return
        }
        val window = store.window(slot)
        val day = TestPolicy.day(now).toString()
        val eligible = workday && TestPolicy.canAttempt(now, planned, store.attempted(slot), window)
        if (eligible) store.setAttempted(slot, day)
        store.setNext(slot, 0)
        schedule(slot) {
            TestPolicy.choose(ChinaWorkdays.nextWorkday(TestPolicy.day(now).plusDays(1)),
                Random.nextInt(window.seconds + 1), window)
        }
        if (!eligible) {
            Notice.result(context, "${slot.title}今天非工作日、计划已过期或已尝试，已跳过；不补执行")
            return
        }
        val issue = requirements()
        if (issue != null) {
            Notice.result(context, "${slot.title}定时测试未运行：$issue；请打开助手手动测试")
            return
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        if (keyguard.isKeyguardLocked || !power.isInteractive) {
            Notice.result(context, "${slot.title}定时已到：请点亮并解锁手机，再打开助手点立即测试；本时段今天不自动重试")
            return
        }
        store.log("${slot.title}定时导航测试开始（不提交）")
        NavigationService.instance?.begin(TestPolicy.end(TestPolicy.day(now), window))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "stop" -> Scheduler(context).disable()
            "daily" -> {
                val slotName = intent.getStringExtra("slot")
                val slot = ScheduleSlot.entries.find { it.name == slotName }
                if (slot != null) Scheduler(context).fire(slot)
                else {
                    Store(context).log("旧版或无效计划触发，重新恢复上下班计划")
                    Scheduler(context).restore()
                }
            }
        }
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store(context).log("系统事件后恢复计划：${intent.action}")
        Scheduler(context).restore()
    }
}
