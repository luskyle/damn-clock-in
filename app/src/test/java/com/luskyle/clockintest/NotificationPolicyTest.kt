package com.luskyle.clockintest

import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun simulatedSuccessNeverClaimsVerifiedRealAttendance() {
        val body = NotificationPolicy.body("模拟时段", "start", "finish", true, false, "模拟测试", testOnly = true)
        assertTrue(body.contains("模拟打卡成功（未执行真实考勤）"))
        assertTrue(body.contains("未打开 BOE、未点击打卡"))
        assertFalse(body.contains("结果：打卡成功（已核验）"))
        assertFalse(body.contains("成功表示助手观察到本次成功反馈"))
    }

    @Test fun clickWithoutEvidenceNeverBecomesSuccessfulNotification() {
        assertEquals("打卡未完成", NotificationPolicy.outcome(false, false))
        assertEquals("打卡结果待核验", NotificationPolicy.outcome(false, true))
        assertEquals("打卡成功（已核验）", NotificationPolicy.outcome(true, true))
    }

    @Test fun reportExplainsLoginFailureAndDoesNotInventARecord() {
        val body = NotificationPolicy.body("上班", "2026-11-02 08:00:00", "2026-11-02 08:00:05",
            false, false, "账号需要重新登录")
        assertTrue(body.contains("上班"))
        assertTrue(body.contains("打卡未完成"))
        assertTrue(body.contains("账号需要重新登录"))
        assertTrue(body.contains("手动登录"))
        assertTrue(body.contains("配置 BOE 帐号密码自动重新登录"))
        assertTrue(body.contains("不会绕过验证"))
        assertTrue(body.contains("2026-11-02 08:00:05"))
        assertTrue(body.contains("请勿仅凭通知重复提交"))
        assertFalse(body.contains("结果：打卡成功"))
    }
}
