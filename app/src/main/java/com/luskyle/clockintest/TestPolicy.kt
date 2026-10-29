package com.luskyle.clockintest

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

object TestPolicy {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    const val targetPackage = "com.mobile.boe"

    fun day(now: Long): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun start(day: LocalDate, window: TimeWindow = TimeWindow.morning): Long =
        day.atTime(window.startMinute / 60, window.startMinute % 60).atZone(zone).toInstant().toEpochMilli()
    fun end(day: LocalDate, window: TimeWindow = TimeWindow.morning): Long =
        day.atTime(window.endMinute / 60, window.endMinute % 60).atZone(zone).toInstant().toEpochMilli()
    fun inWindow(now: Long, window: TimeWindow = TimeWindow.morning): Boolean =
        now in start(day(now), window)..end(day(now), window)

    fun choose(
        day: LocalDate,
        second: Int? = null,
        window: TimeWindow = TimeWindow.morning
    ): Long {
        val offset = second ?: Random.nextInt(window.seconds + 1)
        require(offset in 0..window.seconds)
        return start(day, window) + offset * 1000L
    }

    fun firstDay(now: Long, window: TimeWindow = TimeWindow.morning): LocalDate =
        if (now < start(day(now), window)) day(now) else day(now).plusDays(1)

    fun canAttempt(now: Long, planned: Long, attempted: String, window: TimeWindow = TimeWindow.morning): Boolean =
        planned > 0 && now >= planned && inWindow(planned, window) && inWindow(now, window) &&
            day(planned) == day(now) && attempted != day(now).toString()

    fun restoredPlan(
        now: Long, planned: Long, attempted: String, second: Int,
        window: TimeWindow = TimeWindow.morning
    ): Long {
        if (planned > 0 && inWindow(planned, window) && attempted != day(planned).toString()) {
            if (planned > now) return planned
            if (day(planned) == day(now) && inWindow(now, window)) return minOf(now + 1000, end(day(now), window))
        }
        val first = firstDay(now, window)
        return choose(if (attempted == first.toString()) first.plusDays(1) else first, second, window)
    }

    fun allowedNavigation(id: String, text: String): Boolean =
        (id == "$targetPackage:id/tab_apps" && text in setOf("", "工作台")) ||
            (id == "$targetPackage:id/name_tv" && text == "考勤打卡")
}
