package com.luskyle.clockintest

import java.util.Locale

enum class ScheduleSlot(val title: String, val defaultStart: Int, val defaultEnd: Int, val alarmId: Int) {
    MORNING("上班", 7 * 60 + 50, 8 * 60 + 10, 10),
    EVENING("下班", 17 * 60 + 30, 17 * 60 + 32, 11)
}

data class TimeWindow(val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0..1439 && endMinute in 0..1439 && startMinute < endMinute) {
            "开始时间必须早于结束时间，暂不支持跨午夜"
        }
    }

    val seconds: Int get() = (endMinute - startMinute) * 60
    fun overlaps(other: TimeWindow): Boolean =
        startMinute <= other.endMinute && other.startMinute <= endMinute

    override fun toString(): String = "${time(startMinute)} – ${time(endMinute)}"

    companion object {
        val morning = TimeWindow(ScheduleSlot.MORNING.defaultStart, ScheduleSlot.MORNING.defaultEnd)
        fun time(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
    }
}
