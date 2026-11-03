package com.luskyle.clockintest

object AttendanceRetryPolicy {
    const val SESSION_MS = 60_000L
    const val RETRY_DELAY_MS = 30_000L
    const val AUTO_LIMIT_MS = 30 * 60_000L

    fun mayLogin(realAttendance: Boolean, explicitLoginTest: Boolean, userInitiated: Boolean = false): Boolean =
        realAttendance || explicitLoginTest || userInitiated

    fun mayRetry(automatic: Boolean, terminal: Boolean, success: Boolean, cancelled: Boolean,
                 now: Long, deadline: Long): Boolean =
        automatic && !terminal && !success && !cancelled && now + RETRY_DELAY_MS < deadline

    fun verificationEnd(planned: Long): Long = planned + AUTO_LIMIT_MS
}
