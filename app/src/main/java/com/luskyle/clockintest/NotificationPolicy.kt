package com.luskyle.clockintest

object NotificationPolicy {
    fun outcome(success: Boolean, submitted: Boolean): String = when {
        success -> "打卡成功（已核验）"
        submitted -> "打卡结果待核验"
        else -> "打卡未完成"
    }

    fun body(taskTime: String, success: Boolean, submitted: Boolean,
             reason: String, testOnly: Boolean = false): String {
        val time = "任务时间：$taskTime（北京时间）"
        if (testOnly) return "$time\n模拟通知，未执行真实打卡。"
        if (success) return time
        val briefReason = reason.lineSequence().first().substringBefore('；').trim()
        val action = if (submitted) "请先核验 BOE 记录，勿直接重打。"
            else "未点击打卡，详情见执行记录。"
        return "$time\n$briefReason\n$action"
    }
}
