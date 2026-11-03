package com.luskyle.clockintest

import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun simulatedSuccessNeverClaimsVerifiedRealAttendance() {
        val body = NotificationPolicy.body("2026-11-03 07:41:30", true, false, "模拟测试", testOnly = true)
        assertEquals("任务时间：2026-11-03 07:41:30（北京时间）\n模拟通知，未执行真实打卡。", body)
        assertFalse(body.contains("已核验"))
        assertTrue(body.length <= 100)
    }

    @Test fun clickWithoutEvidenceNeverBecomesSuccessfulNotification() {
        assertEquals("打卡未完成", NotificationPolicy.outcome(false, false))
        assertEquals("打卡结果待核验", NotificationPolicy.outcome(false, true))
        assertEquals("打卡成功（已核验）", NotificationPolicy.outcome(true, true))
    }

    @Test fun reportExplainsLoginFailureAndDoesNotInventARecord() {
        val body = NotificationPolicy.body("2026-11-02 08:00:00", false, false,
            "账号需要重新登录；请在运行设置配置 BOE 帐号密码，或手动登录")
        assertTrue(body.contains("账号需要重新登录"))
        assertTrue(body.contains("未点击打卡，详情见执行记录"))
        assertFalse(body.contains("配置 BOE 帐号密码"))
        assertFalse(body.contains("打卡成功"))
        assertTrue(body.length <= 100)
    }

    @Test fun verifiedSuccessContainsOnlyTaskTimeWithoutFailureOrLoginBoilerplate() {
        val body = NotificationPolicy.body("2026-11-03 07:41:30", true, true,
            "已核验 BOE 本次新增定位打卡记录或成功反馈；请以 BOE 最终考勤结果为准")
        assertEquals("任务时间：2026-11-03 07:41:30（北京时间）", body)
        assertTrue(body.length <= 40)
    }

    @Test fun pendingRetainsCauseAndRequiresCheckingRecordBeforeRepeating() {
        val body = NotificationPolicy.body("2026-11-03 07:41:30", false, true,
            "本轮导航或结果等待超时，未核验成功；已尝试点击打卡，结果待核验\n冗长详细过程")
        assertEquals("任务时间：2026-11-03 07:41:30（北京时间）\n" +
            "本轮导航或结果等待超时，未核验成功\n请先核验 BOE 记录，勿直接重打。", body)
        assertFalse(body.contains("未点击打卡"))
        assertTrue(body.length <= 100)
        assertEquals(3, body.lines().size)
    }
}
