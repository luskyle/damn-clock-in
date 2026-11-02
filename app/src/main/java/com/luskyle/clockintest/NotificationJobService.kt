package com.luskyle.clockintest

import android.app.job.JobParameters
import android.app.job.JobService
import org.json.JSONObject
import org.json.JSONException
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean

abstract class NotificationJobService : JobService() {
    protected abstract val channel: DeliveryChannel
    protected abstract fun send(item: JSONObject): String
    private var cancelled = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        synchronized(NotificationOutbox.lock) { NotificationOutbox.sending.add(channel) }
        val token = AtomicBoolean(false)
        cancelled = token
        val outbox = NotificationOutbox(this, channel)
        worker = Thread({
            var retry = false
            try {
                repeat(5) {
                    if (token.get()) return@Thread
                    val item = outbox.first() ?: return@repeat
                    val result = send(item)
                    outbox.acknowledge(item.getString("id"), result)
                }
                retry = outbox.first() != null
            } catch (_: GeneralSecurityException) {
                retry = true
                outbox.updateStatus("${channel.label}凭据无法解密，请重新填写配置；待发内容已保留", true)
            } catch (error: BarkSendException) {
                retry = true
                outbox.updateStatus(error.safeMessage, true)
            } catch (_: IOException) {
                retry = true
                outbox.updateStatus("${channel.label}发送失败：请检查网络、服务器及配置；已保留并等待重试", true)
            } catch (_: IllegalArgumentException) {
                retry = true
                outbox.updateStatus("${channel.label}配置或凭据无效，请重新保存配置；待发内容已保留", true)
            } catch (_: JSONException) {
                retry = true
                outbox.updateStatus("${channel.label}待发内容无法解析，请检查并清理待发队列", true)
            } finally {
                if (!token.get()) synchronized(NotificationOutbox.lock) {
                    NotificationOutbox.sending.remove(channel)
                    jobFinished(params, retry)
                    if (!retry) outbox.schedule()
                }
            }
        }, "attendance-${channel.name.lowercase()}").apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancelled.set(true)
        synchronized(NotificationOutbox.lock) { NotificationOutbox.sending.remove(channel) }
        worker?.interrupt()
        return true
    }
}
