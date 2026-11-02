package com.luskyle.clockintest

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class BarkDeliveryTest {
    @Test fun keyOnlyUsesOfficialHttpsServer() {
        assertEquals(BarkEndpoint("https://api.day.app", "synthetic_key-123"),
            BarkEndpoint.fromKey(" synthetic_key-123 "))
    }

    @Test fun keyOnlyRejectsAddressesAndMessageContent() {
        for (value in listOf("", " ", "https://api.day.app/key", "http://host/key",
            "key/body", "key?body=message", "key#fragment", "key with spaces",
            "key\nheader", "%0akey", ".", "..", "push")) {
            assertNull("Expected invalid key rejection", BarkEndpoint.fromKey(value))
        }
    }

    @Test fun acceptsPublicAndHttpsSelfHostedEndpoints() {
        assertEquals(BarkEndpoint("https://api.day.app", "synthetic_key"),
            BarkEndpoint.parse(" https://api.day.app/synthetic_key/ "))
        assertEquals(BarkEndpoint("https://bark.example.com:8443", "synthetic-key"),
            BarkEndpoint.parse("https://bark.example.com:8443/synthetic-key"))
    }

    @Test fun rejectsUnsafeOrAmbiguousEndpoints() {
        for (value in listOf("", "synthetic_key", "http://api.day.app/key", "https://api.day.app",
            "https://user:password@api.day.app/key", "https://api.day.app/key/body",
            "https://api.day.app/key?q=secret", "https://api.day.app/key#fragment",
            "https://api.day.app/push", "https://api.day.app/..", "https://api.day.app/%0akey",
            "https://api.day.app:0/key", "https://api.day.app:65536/key", "https://api.day.app:/key",
            "https://api.day.app/key\r\nX-Header: value")) {
            assertNull("Expected endpoint rejection", BarkEndpoint.parse(value))
        }
    }

    @Test fun sendsUtf8JsonToPushWithoutKeyInUrlOrRedirects() {
        val connection = FakeConnection(200, """{"code":200,"message":"success"}""")
        val endpoint = BarkEndpoint("https://bark.example.com", "synthetic-secret-key")
        var target = ""
        BarkTransport.send(endpoint, "上班 · 打卡未完成", "账号需要重新登录\n结果待核验") {
            target = it.toString()
            connection
        }
        assertEquals("https://bark.example.com/push", target)
        assertFalse(target.contains(endpoint.deviceKey))
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
        assertEquals("application/json; charset=utf-8", connection.getRequestProperty("Content-Type"))
        val json = JSONObject(connection.output.toString("UTF-8"))
        assertEquals(endpoint.deviceKey, json.getString("device_key"))
        assertEquals("上班 · 打卡未完成", json.getString("title"))
        assertEquals("账号需要重新登录\n结果待核验", json.getString("body"))
        assertEquals("永不迟到", json.getString("group"))
        assertTrue(connection.disconnected)
    }

    @Test fun rejectsHttpErrorsRedirectsAndUnconfirmedApiResponses() {
        for ((http, response) in listOf(
            302 to """{"code":200}""", 401 to """{"code":200}""", 500 to """{"code":200}""",
            200 to """{"code":400}""", 200 to """{"code":"200"}""", 200 to "{}",
            200 to "<html>error</html>", 200 to "x".repeat(4097))) {
            val connection = FakeConnection(http, response)
            assertThrows(BarkSendException::class.java) {
                BarkTransport.send(BarkEndpoint("https://bark.example.com", "synthetic"), "title", "body") { connection }
            }
            assertTrue(connection.disconnected)
        }
    }

    @Test fun networkFailuresAreNotReportedAsAcceptedAndAlwaysDisconnect() {
        val connection = object : FakeConnection(200, """{"code":200}""") {
            override fun getOutputStream(): ByteArrayOutputStream = throw IOException("synthetic network failure")
        }
        assertThrows(IOException::class.java) {
            BarkTransport.send(BarkEndpoint("https://bark.example.com", "synthetic"), "title", "body") { connection }
        }
        assertTrue(connection.disconnected)
    }

    private open class FakeConnection(private val status: Int, private val response: String) :
        HttpURLConnection(URL("https://bark.example.com/push")) {
        val output = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getOutputStream(): ByteArrayOutputStream = output
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
    }
}
