package com.luskyle.clockintest

object NotificationPolicy {
    fun outcome(success: Boolean, submitted: Boolean): String = when {
        success -> "打卡成功（已核验）"
        submitted -> "打卡结果待核验"
        else -> "打卡未完成"
    }

    fun body(slot: String, started: String, finished: String, success: Boolean, submitted: Boolean,
             reason: String, testOnly: Boolean = false) =
        "时段：$slot\n开始时间（北京时间）：$started\n结束时间（北京时间）：$finished\n" +
            "结果：${if (testOnly) "模拟打卡成功（未执行真实考勤）" else outcome(success, submitted)}\n原因：$reason\n\n" +
            (if (testOnly) "本条仅测试成功结果通知链路，未打开 BOE、未点击打卡、不代表真实打卡成功。"
            else "成功表示助手观察到本次成功反馈，最终考勤以 BOE 记录为准。") +
            "待核验不表示失败，也不表示成功，请勿仅凭通知重复提交。\n" +
            "若 BOE 提示重新登录或账号被挤下线，可在安卓助手配置 BOE 帐号密码自动重新登录；" +
            "未配置、登录未完成或遇到额外验证时仍需手动登录或处理，助手不会绕过验证。"
}
