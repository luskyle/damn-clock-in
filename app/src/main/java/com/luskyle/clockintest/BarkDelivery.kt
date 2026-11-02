package com.luskyle.clockintest

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

data class BarkEndpoint(val server: String, val deviceKey: String) {
    companion object {
        private const val DEFAULT_SERVER = "https://api.day.app"

        fun fromKey(value: String): BarkEndpoint? {
            val key = value.trim()
            if (!Regex("""[A-Za-z0-9._~-]+""").matches(key) ||
                key in setOf(".", "..", "push", "ping", "health")) return null
            return BarkEndpoint(DEFAULT_SERVER, key)
        }

        fun parse(value: String): BarkEndpoint? {
            val uri = try { URI(value.trim()) } catch (_: URISyntaxException) { return null }
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() || uri.userInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.rawAuthority.endsWith(':') ||
                (uri.port != -1 && uri.port !in 1..65535)) return null
            val path = uri.rawPath.orEmpty().removeSuffix("/")
            if (!path.startsWith("/")) return null
            val endpoint = fromKey(path.removePrefix("/")) ?: return null
            return endpoint.copy(server = "https://${uri.rawAuthority}")
        }
    }
}

class BarkSettings(context: Context) {
    private val prefs = context.getSharedPreferences("bark-settings", Context.MODE_PRIVATE)
    private val secrets = LocalSecret("clockin-bark-endpoint-v1")
    fun configured(): Boolean = prefs.contains("endpoint")
    fun saveKey(value: String) {
        val endpoint = requireNotNull(BarkEndpoint.fromKey(value)) { "Invalid Bark key" }
        save("${endpoint.server}/${endpoint.deviceKey}")
    }
    fun save(value: String) {
        require(BarkEndpoint.parse(value) != null) { "Invalid Bark endpoint" }
        check(prefs.edit().putString("endpoint", secrets.encrypt(value.trim())).commit()) {
            "Cannot save Bark settings"
        }
    }
    fun encryptedEndpoint(): String? = prefs.getString("endpoint", null)
    fun endpoint(encrypted: String): BarkEndpoint =
        requireNotNull(BarkEndpoint.parse(secrets.decrypt(encrypted))) { "Invalid saved Bark endpoint" }
    fun clear() { check(prefs.edit().clear().commit()) { "Cannot clear Bark settings" } }
}

class BarkSendException(val safeMessage: String) : IOException(safeMessage)

object BarkTransport {
    fun send(endpoint: BarkEndpoint, title: String, body: String,
             open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
        val connection = open(URL("${endpoint.server}/push"))
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = JSONObject().put("device_key", endpoint.deviceKey).put("title", title)
                .put("body", body).put("group", "永不迟到").toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            if (status !in 200..299) throw BarkSendException("Bark 服务拒绝推送（HTTP $status），请检查地址和密钥；待发内容已保留")
            val response = connection.inputStream.use { input ->
                val data = ByteArray(4097)
                var count = 0
                while (count < data.size) {
                    val read = input.read(data, count, data.size - count)
                    if (read < 0) break
                    count += read
                }
                data.copyOf(count)
            }
            if (response.size > 4096) throw BarkSendException("Bark 响应过大，未确认成功；待发内容已保留")
            val code = try {
                JSONObject(String(response, Charsets.UTF_8)).opt("code")
            } catch (_: JSONException) {
                throw BarkSendException("Bark 响应不可识别，未确认成功；待发内容已保留")
            }
            if (code != 200) throw BarkSendException("Bark 未确认推送成功，请检查服务器及密钥；待发内容已保留")
        } finally {
            connection.disconnect()
        }
    }
}

object BarkDelivery {
    private fun outbox(context: Context) = NotificationOutbox(context, DeliveryChannel.BARK)
    fun status(context: Context): String = outbox(context).status()
    fun schedule(context: Context): Boolean = outbox(context).schedule()
    fun retry(context: Context): Boolean = outbox(context).retry()
    fun clear(context: Context) = outbox(context).clear()
    fun enqueue(context: Context, title: String, body: String): Boolean {
        val settings = BarkSettings(context)
        val encrypted = settings.encryptedEndpoint()
        if (encrypted == null) {
            Notice.result(context, "Bark 未发送：请先填写 iPhone 的 Bark Key")
            return false
        }
        return outbox(context).enqueue(JSONObject().put("endpoint", encrypted)
            .put("subject", title).put("body", body))
    }
}

class BarkJobService : NotificationJobService() {
    override val channel = DeliveryChannel.BARK
    override fun send(item: JSONObject): String {
        BarkTransport.send(BarkSettings(this).endpoint(item.getString("endpoint")),
            item.getString("subject"), item.getString("body"))
        return "Bark 服务已接受推送（请在 iPhone 确认收到）"
    }
}
