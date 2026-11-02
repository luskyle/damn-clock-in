package com.luskyle.clockintest

object BoeLoginPolicy {
    const val USERNAME = "com.mobile.boe:id/login_username_et"
    const val PASSWORD = "com.mobile.boe:id/login_password_et"
    const val SUBMIT = "com.mobile.boe:id/login"
    const val PRIVACY = "com.mobile.boe:id/login_privacy_compliance_tip"
    const val CONFIRM = "android:id/button1"
    const val HOME = "com.mobile.boe:id/tab_apps"

    fun canStart(paused: Boolean, userInitiated: Boolean, alreadyStarted: Boolean,
                 attendanceRequest: Boolean = false, blocked: Boolean = false): Boolean =
        !alreadyStarted && (userInitiated || (!blocked && (!paused || attendanceRequest)))

    fun kickedOut(texts: Set<String>): Boolean = texts.any {
        val normalized = it.replace("帐号", "账号").replace("登陆", "登录")
        normalized.contains("此账号在其他地方登录") || normalized.contains("其他设备登录") ||
            normalized.contains("其它设备登录") || normalized.contains("账号已在")
    }

    fun needsLogin(texts: Set<String>, ids: Set<String>, activity: String): Boolean =
        kickedOut(texts) || USERNAME in ids || PASSWORD in ids ||
            activity.contains("Login") || activity.contains("LockActivity") ||
            texts.any {
                it in setOf("请输入用户名", "请输入密码", "欢迎登录") ||
                    it.contains("重新登录") || it.contains("重新登陆") ||
                    it.contains("登录已失效") || it.contains("登录过期")
            }

    fun humanVerification(texts: Set<String>, activity: String): Boolean =
        activity.contains("LockActivity") || texts.any {
            it.contains("验证码") || it.contains("人脸") || it.contains("滑块") ||
                it.contains("手势密码") || it.contains("安全验证")
        }

    fun rejected(texts: Set<String>): Boolean = texts.any {
        it.contains("密码错误") || it.contains("密码不正确") || it.contains("用户名或密码") ||
            it.contains("账号或密码") || it.contains("帐号或密码") ||
            it.contains("账户或密码") || it.contains("账户已锁定") ||
            it.contains("账号已锁定") || it.contains("帐号已锁定") ||
            it.contains("登录失败") || it.contains("登陆失败") || it.contains("尝试次数")
    }
}

data class BoeLoginScreen(
    val packageName: String,
    val kickedOut: Boolean = false,
    val form: Boolean = false,
    val home: Boolean = false,
    val humanVerification: Boolean = false,
    val rejected: Boolean = false,
    val privacyUnchecked: Boolean = false,
    val submitEnabled: Boolean = false
)

enum class BoeLoginStep { WAIT, CONFIRM_KICK, FILL_FORM, ACCEPT_PRIVACY, SUBMIT, SUCCESS, HUMAN, REJECTED }

class BoeLoginAttempt {
    private var confirmedKick = false
    private var filledForm = false
    private var acceptedPrivacy = false
    var submitted = false
        private set

    fun next(screen: BoeLoginScreen): BoeLoginStep = when {
        screen.packageName != TestPolicy.targetPackage -> BoeLoginStep.WAIT
        screen.humanVerification -> BoeLoginStep.HUMAN
        screen.rejected -> BoeLoginStep.REJECTED
        screen.kickedOut -> when {
            submitted -> BoeLoginStep.REJECTED
            confirmedKick -> BoeLoginStep.WAIT
            else -> BoeLoginStep.CONFIRM_KICK
        }
        screen.home && !screen.form -> BoeLoginStep.SUCCESS
        !screen.form || submitted -> BoeLoginStep.WAIT
        !filledForm -> BoeLoginStep.FILL_FORM
        screen.privacyUnchecked -> if (acceptedPrivacy) BoeLoginStep.REJECTED else BoeLoginStep.ACCEPT_PRIVACY
        !screen.submitEnabled -> BoeLoginStep.WAIT
        else -> BoeLoginStep.SUBMIT
    }

    fun completed(step: BoeLoginStep) {
        when (step) {
            BoeLoginStep.CONFIRM_KICK -> confirmedKick = true
            BoeLoginStep.FILL_FORM -> filledForm = true
            BoeLoginStep.ACCEPT_PRIVACY -> acceptedPrivacy = true
            BoeLoginStep.SUBMIT -> submitted = true
            else -> Unit
        }
    }
}
