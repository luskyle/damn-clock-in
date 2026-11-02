package com.luskyle.clockintest

import org.junit.Assert.*
import org.junit.Test

class BoeLoginPolicyTest {
    private val form = BoeLoginScreen(TestPolicy.targetPackage, form = true, submitEnabled = true)

    @Test fun explicitNewRequestMayRetryPausedLoginButBackgroundMayNot() {
        assertTrue(BoeLoginPolicy.canStart(paused = true, userInitiated = true, alreadyStarted = false))
        assertFalse(BoeLoginPolicy.canStart(paused = true, userInitiated = false, alreadyStarted = false))
        assertTrue(BoeLoginPolicy.canStart(paused = false, userInitiated = true, alreadyStarted = false))
        assertTrue(BoeLoginPolicy.canStart(paused = false, userInitiated = false, alreadyStarted = false))
    }

    @Test fun neitherManualNorBackgroundMayStartAnotherLoginWithinSameRequest() {
        for (paused in listOf(false, true)) {
            for (user in listOf(false, true)) {
                assertFalse(BoeLoginPolicy.canStart(paused, user, alreadyStarted = true))
            }
        }
    }

    @Test fun recognizesActualKickedOutMessageAndSpellingVariants() {
        for (text in listOf("此账号在其他地方登陆", "此帐号在其他地方登录", "账号已在其他设备登录")) {
            assertTrue(BoeLoginPolicy.kickedOut(setOf(text)))
            assertTrue(BoeLoginPolicy.needsLogin(setOf(text), emptySet(), ""))
        }
        assertFalse(BoeLoginPolicy.kickedOut(setOf("登录", "确定", "打卡成功")))
    }

    @Test fun knownFieldsAndLoginActivityRequireLoginButHomeDoesNot() {
        assertTrue(BoeLoginPolicy.needsLogin(emptySet(), setOf(BoeLoginPolicy.USERNAME), ""))
        assertTrue(BoeLoginPolicy.needsLogin(emptySet(), emptySet(), "com.meicloud.LoginActivity"))
        assertFalse(BoeLoginPolicy.needsLogin(setOf("考勤打卡"), setOf(BoeLoginPolicy.HOME), ""))
    }

    @Test fun kickThenFillConsentSubmitWaitAndVerifyHome() {
        val attempt = BoeLoginAttempt()
        val kicked = BoeLoginScreen(TestPolicy.targetPackage, kickedOut = true, home = true)
        assertEquals(BoeLoginStep.CONFIRM_KICK, attempt.next(kicked))
        attempt.completed(BoeLoginStep.CONFIRM_KICK)
        assertEquals(BoeLoginStep.WAIT, attempt.next(kicked))
        assertEquals(BoeLoginStep.FILL_FORM, attempt.next(form))
        attempt.completed(BoeLoginStep.FILL_FORM)
        assertEquals(BoeLoginStep.ACCEPT_PRIVACY, attempt.next(form.copy(privacyUnchecked = true)))
        attempt.completed(BoeLoginStep.ACCEPT_PRIVACY)
        assertEquals(BoeLoginStep.SUBMIT, attempt.next(form))
        attempt.completed(BoeLoginStep.SUBMIT)
        assertTrue(attempt.submitted)
        repeat(100) { assertEquals(BoeLoginStep.WAIT, attempt.next(form)) }
        assertEquals(BoeLoginStep.SUCCESS, attempt.next(BoeLoginScreen(TestPolicy.targetPackage, home = true)))
    }

    @Test fun neverTouchesAnotherPackageEvenIfIdsAndTextsMatch() {
        val attempt = BoeLoginAttempt()
        assertEquals(BoeLoginStep.WAIT, attempt.next(form.copy(packageName = "another.app")))
        assertEquals(BoeLoginStep.WAIT, attempt.next(BoeLoginScreen("another.app", kickedOut = true)))
        assertEquals(BoeLoginStep.WAIT, attempt.next(BoeLoginScreen("another.app", home = true)))
        assertFalse(attempt.submitted)
    }

    @Test fun aSecondKickAfterSubmitNeverResubmits() {
        val attempt = BoeLoginAttempt()
        attempt.completed(BoeLoginStep.FILL_FORM)
        attempt.completed(BoeLoginStep.SUBMIT)
        assertEquals(BoeLoginStep.REJECTED,
            attempt.next(BoeLoginScreen(TestPolicy.targetPackage, kickedOut = true, home = true)))
    }

    @Test fun humanVerificationAndRejectionStopBeforeFillingOrSubmitting() {
        val attempt = BoeLoginAttempt()
        assertEquals(BoeLoginStep.HUMAN, attempt.next(form.copy(humanVerification = true)))
        assertEquals(BoeLoginStep.REJECTED, attempt.next(form.copy(rejected = true)))
        for (text in listOf("请输入验证码", "人脸验证", "请完成滑块", "安全验证")) {
            assertTrue(BoeLoginPolicy.humanVerification(setOf(text), ""))
        }
        assertTrue(BoeLoginPolicy.humanVerification(emptySet(), "com.meicloud.LockActivity"))
        for (text in listOf("密码错误", "账号或密码不正确", "登录失败", "账号已锁定")) {
            assertTrue(BoeLoginPolicy.rejected(setOf(text)))
        }
        assertFalse(attempt.submitted)
    }

    @Test fun refusesRepeatedConsentClicksAndWaitsForDisabledSubmit() {
        val attempt = BoeLoginAttempt()
        attempt.completed(BoeLoginStep.FILL_FORM)
        assertEquals(BoeLoginStep.WAIT, attempt.next(form.copy(submitEnabled = false)))
        attempt.completed(BoeLoginStep.ACCEPT_PRIVACY)
        assertEquals(BoeLoginStep.REJECTED, attempt.next(form.copy(privacyUnchecked = true)))
    }

    @Test fun unknownPageOrLoginPageNeverCountsAsSuccess() {
        val attempt = BoeLoginAttempt()
        attempt.completed(BoeLoginStep.SUBMIT)
        assertEquals(BoeLoginStep.WAIT, attempt.next(BoeLoginScreen(TestPolicy.targetPackage)))
        assertEquals(BoeLoginStep.WAIT, attempt.next(form.copy(home = true)))
        assertEquals(BoeLoginStep.REJECTED,
            attempt.next(BoeLoginScreen(TestPolicy.targetPackage, home = true, rejected = true)))
    }
}
