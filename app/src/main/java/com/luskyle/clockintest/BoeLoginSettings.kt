package com.luskyle.clockintest

import android.content.Context
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID

class BoeCredentials(val username: String, val password: String, val revision: String) {
    override fun toString() = "BoeCredentials([redacted])"
}

class BoeLoginSettings(context: Context) {
    private val prefs = context.getSharedPreferences("boe-login-settings", Context.MODE_PRIVATE)
    private val secret = LocalSecret(KEY_ALIAS)

    fun configured(): Boolean = prefs.contains("credentials") && prefs.getBoolean("privacyConsent", false)
    fun paused(): Boolean = prefs.getBoolean("paused", false)
    fun blocked(): Boolean = prefs.getBoolean("blocked", false)
    fun revision(): String? = prefs.getString("revision", null)

    fun save(username: String, password: String, privacyConsent: Boolean) {
        require(username.isNotBlank() && password.isNotEmpty() && privacyConsent) { "Invalid BOE login configuration" }
        val value = JSONObject().put("username", username.trim()).put("password", password).toString()
        val encrypted = secret.encrypt(value)
        check(prefs.edit().putString("credentials", encrypted)
            .putString("revision", UUID.randomUUID().toString()).putBoolean("privacyConsent", true)
            .putBoolean("paused", false).putBoolean("blocked", false).commit()) { "Cannot save BOE login configuration" }
    }

    fun load(): BoeCredentials {
        check(configured()) { "BOE login not configured" }
        val value = JSONObject(secret.decrypt(requireNotNull(prefs.getString("credentials", null))))
        val username = value.getString("username")
        val password = value.getString("password")
        require(username.isNotBlank() && password.isNotEmpty()) { "Invalid saved BOE credentials" }
        return BoeCredentials(username, password, requireNotNull(revision()))
    }

    fun allowRetry() {
        check(configured()) { "BOE login not configured" }
        check(prefs.edit().putBoolean("paused", false).putBoolean("blocked", false)
            .putString("revision", UUID.randomUUID().toString()).commit()) { "Cannot reset BOE login attempt" }
    }

    fun markStarted(revision: String) {
        check(configured() && revision() == revision && !paused()) { "BOE login configuration changed or paused" }
        check(prefs.edit().putBoolean("paused", true).commit()) { "Cannot persist BOE login attempt" }
    }

    fun markSuccessful(revision: String) {
        check(configured() && revision() == revision) { "BOE login configuration changed" }
        check(prefs.edit().putBoolean("paused", false).putBoolean("blocked", false).commit()) { "Cannot persist BOE login success" }
    }

    fun block(revision: String) {
        check(configured() && revision() == revision) { "BOE login configuration changed" }
        check(prefs.edit().putBoolean("paused", true).putBoolean("blocked", true).commit()) {
            "Cannot persist BOE login rejection"
        }
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "Cannot delete BOE login configuration" }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(KEY_ALIAS) }
    }

    companion object {
        private const val KEY_ALIAS = "clockin-boe-credentials-v1"
    }
}
