package com.luskyle.clockintest

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Properties

class CalendarUnavailable(message: String) : IllegalStateException(message)

object ChinaWorkdays {
    val years: Set<Int> = setOf(2026)
    private data class Calendar(val off: Set<LocalDate>, val work: Set<LocalDate>)
    private val calendars: Map<Int, Calendar> by lazy {
        years.associateWith { year ->
            val resource = ChinaWorkdays::class.java.getResourceAsStream("/calendar/$year.properties")
                ?: throw CalendarUnavailable("$year 年工作日数据未安装，请更新 App")
            val properties = Properties().apply { resource.use { load(it) } }
            check(properties.getProperty("year") == year.toString()) { "Calendar year mismatch" }
            fun dates(key: String): Set<LocalDate> = properties.getProperty(key)
                .split(',').map { LocalDate.parse("$year-$it") }.toSet()
            val off = dates("off")
            val work = dates("work")
            check(off.intersect(work).isEmpty()) { "Conflicting calendar dates" }
            Calendar(off, work)
        }
    }

    fun isWorkday(date: LocalDate): Boolean {
        val calendar = calendars[date.year]
            ?: throw CalendarUnavailable("${date.year} 年官方工作日数据尚未接入，请更新 App；自动计划已暂停")
        return when (date) {
            in calendar.work -> true
            in calendar.off -> false
            else -> date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
        }
    }

    fun nextWorkday(from: LocalDate): LocalDate {
        var date = from
        while (!isWorkday(date)) date = date.plusDays(1)
        return date
    }

    fun plan(now: Long, previous: Long, attempted: String, second: Int, window: TimeWindow): Long {
        if (previous > 0 && TestPolicy.inWindow(previous, window) &&
            attempted != TestPolicy.day(previous).toString() && isWorkday(TestPolicy.day(previous))) {
            if (previous > now) return previous
            if (TestPolicy.day(previous) == TestPolicy.day(now) && TestPolicy.inWindow(now, window)) {
                return minOf(now + 1000, TestPolicy.end(TestPolicy.day(now), window))
            }
        }
        var first = TestPolicy.firstDay(now, window)
        if (attempted == first.toString()) first = first.plusDays(1)
        return TestPolicy.choose(nextWorkday(first), second, window)
    }
}
