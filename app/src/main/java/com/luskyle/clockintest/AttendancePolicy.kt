package com.luskyle.clockintest

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

enum class AttendanceMode(val label: String, val button: String?) {
    NAVIGATION("仅导航，不提交", null),
    LOCATION("定位打卡", "定位打卡")
}

class AttendanceAttempt(val mode: AttendanceMode, val dryRun: Boolean, initialClicks: Int = 0) {
    init { require(initialClicks in 0..MAX_CLICKS) }
    var clicks = initialClicks
        private set
    val clicked: Boolean get() = clicks > 0
    var waitingForResult = false
        private set
    private var successVisibleBeforeClick = false
    private var baselineCaptured = false
    private var beforeClick: Set<String> = emptySet()

    fun ready(packageName: String, texts: Set<String>, candidateCount: Int): Boolean =
        mode != AttendanceMode.NAVIGATION && !waitingForResult && clicks < MAX_CLICKS &&
            packageName == TestPolicy.targetPackage && "考勤打卡" in texts &&
            "打卡记录" in texts && mode.button in texts && candidateCount == 1

    fun markClick(texts: Set<String>) {
        check(!dryRun && !waitingForResult && clicks < MAX_CLICKS && mode != AttendanceMode.NAVIGATION) {
            "禁止预检提交、重复点击或仅导航模式提交"
        }
        if (!baselineCaptured) {
            successVisibleBeforeClick = "打卡成功" in texts
            beforeClick = texts.toSet()
            baselineCaptured = true
        }
        clicks++
        waitingForResult = true
    }

    fun prepareRetry(): Boolean {
        if (dryRun || !waitingForResult || clicks >= MAX_CLICKS) return false
        waitingForResult = false
        return true
    }

    fun hasNewSuccess(texts: Set<String>): Boolean =
        clicked && baselineCaptured && !successVisibleBeforeClick && "打卡成功" in texts

    fun shouldLockScreen(success: Boolean): Boolean =
        success && clicked && !dryRun && mode == AttendanceMode.LOCATION

    fun hasNewRecord(texts: Set<String>, requestedAt: Long, now: Long): Boolean {
        if (!clicked || now < requestedAt) return false
        val pattern = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})${mode.button}$""")
        return (texts - beforeClick).any { text ->
            val match = pattern.matchEntire(text) ?: return@any false
            val time = try {
                LocalDateTime.parse(match.groupValues[1], DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT))
                    .atZone(TestPolicy.zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                return@any false
            }
            time >= requestedAt - 5_000 && time <= now + 5_000
        }

    }

    companion object {
        const val MAX_CLICKS = 3
        const val RESULT_WAIT_MS = 10_000L
    }
}
