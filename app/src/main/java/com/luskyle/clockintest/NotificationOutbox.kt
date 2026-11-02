package com.luskyle.clockintest

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class DeliveryChannel(val label: String, val preferences: String, val jobId: Int) {
    BARK("Bark 推送", "bark-outbox", 31)
}

class NotificationOutbox(private val context: Context, private val channel: DeliveryChannel) {
    private val prefs = context.getSharedPreferences(channel.preferences, Context.MODE_PRIVATE)
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private fun pending() = JSONArray(prefs.getString("pending", "[]"))
    private fun save(items: JSONArray) {
        check(prefs.edit().putString("pending", items.toString()).commit()) { "Cannot persist notification outbox" }
    }

    fun status(): String = synchronized(lock) {
        "待发 ${pending().length()} 条；${prefs.getString("status", "尚未发送")}"
    }

    fun enqueue(item: JSONObject): Boolean = synchronized(lock) {
        val items = pending()
        if (items.length() >= 100) {
            updateStatus("${channel.label}队列已满，本次未入队，请检查配置", true)
            return false
        }
        items.put(item.put("id", UUID.randomUUID().toString()).put("createdAt", System.currentTimeMillis()))
        save(items)
        updateStatus("${channel.label}已加入待发队列，尚未确认送达", false)
        schedule()
    }

    fun schedule(): Boolean = synchronized(lock) {
        if (pending().length() == 0 || scheduler.getPendingJob(channel.jobId) != null) return true
        val service = when (channel) {
            DeliveryChannel.BARK -> BarkJobService::class.java
        }
        val result = scheduler.schedule(JobInfo.Builder(channel.jobId, ComponentName(context, service))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        if (result == JobScheduler.RESULT_SUCCESS) return true
        updateStatus("系统拒绝安排${channel.label}发送，待发内容仍保留，请重新尝试", true)
        false
    }

    fun retry(): Boolean = synchronized(lock) {
        if (channel in sending) return true
        scheduler.cancel(channel.jobId)
        schedule()
    }

    fun first(): JSONObject? = synchronized(lock) { pending().optJSONObject(0) }

    fun acknowledge(id: String, message: String) = synchronized(lock) {
        val items = pending()
        val remaining = JSONArray()
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            if (item.getString("id") != id) remaining.put(item)
        }
        save(remaining)
        updateStatus(message, false)
    }

    fun updateStatus(message: String, notify: Boolean) = synchronized(lock) {
        check(prefs.edit().putString("status", "${Store.format(System.currentTimeMillis())} $message").commit())
        if (notify) Notice.result(context, message) else Store(context).log(message)
    }

    fun clear() = synchronized(lock) {
        scheduler.cancel(channel.jobId)
        save(JSONArray())
        updateStatus("已按用户操作删除待发${channel.label}", false)
    }

    companion object {
        internal val lock = Any()
        internal val sending = mutableSetOf<DeliveryChannel>()
    }
}
