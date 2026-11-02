package com.luskyle.clockintest

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class BoeLoginSettingsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    private fun fixture(action: (Context) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("barkTestDevice") == "true")
        val name = "boe-login-test-fixture"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(n: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(name, mode)
        }
        check(!BoeLoginSettings(target).configured())
        target.deleteSharedPreferences(name)
        try { action(context) } finally { BoeLoginSettings(context).clear(); target.deleteSharedPreferences(name) }
    }

    @Test fun encryptsBothFieldsAndDoesNotTrimPasswordOrExposeCredentials() = fixture { context ->
        val settings = BoeLoginSettings(context)
        settings.save(" synthetic-account ", " synthetic-password ", true)
        val saved = context.getSharedPreferences("ignored", Context.MODE_PRIVATE).getString("credentials", "")!!
        assertFalse(saved.contains("synthetic-account"))
        assertFalse(saved.contains("synthetic-password"))
        val reopened = BoeLoginSettings(context).load()
        assertEquals("synthetic-account", reopened.username)
        assertEquals(" synthetic-password ", reopened.password)
        assertEquals("BoeCredentials([redacted])", reopened.toString())
        settings.clear()
        assertFalse(settings.configured())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .containsAlias("clockin-boe-credentials-v1"))
    }

    @Test fun inFlightAttemptStaysPausedAcrossReopenUntilExplicitRetry() = fixture { context ->
        val settings = BoeLoginSettings(context)
        settings.save("synthetic-account", "synthetic-password", true)
        val revision = settings.load().revision
        settings.markStarted(revision)
        val reopened = BoeLoginSettings(context)
        assertTrue(reopened.paused())
        reopened.allowRetry()
        assertFalse(reopened.paused())
        assertNotEquals(revision, reopened.revision())
        assertThrows(IllegalStateException::class.java) { settings.markSuccessful(revision) }
        val current = reopened.load().revision
        reopened.markStarted(current)
        reopened.markSuccessful(current)
        assertFalse(BoeLoginSettings(context).paused())
    }

    @Test fun rejectsIncompleteOrUnconsentedChangesWithoutLosingExistingCredentials() = fixture { context ->
        val settings = BoeLoginSettings(context)
        settings.save("synthetic-account", "synthetic-password", true)
        val revision = settings.revision()
        assertThrows(IllegalArgumentException::class.java) { settings.save("", "new-password", true) }
        assertThrows(IllegalArgumentException::class.java) { settings.save("new-account", "", true) }
        assertThrows(IllegalArgumentException::class.java) { settings.save("new-account", "new-password", false) }
        assertEquals(revision, settings.revision())
        assertEquals("synthetic-account", settings.load().username)
        assertEquals("synthetic-password", settings.load().password)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun dialogRequiresConsentProtectsInputsAndNeverPrefillsSavedSecrets() {
        check(InstrumentationRegistry.getArguments().getString("barkTestDevice") == "true")
        val settings = BoeLoginSettings(target)
        check(!settings.configured())
        NotificationDelivery.schedule(target)
        val originalPlans = Store(target).prefs.all - "history"
        val activity = instrumentation.startActivitySync(Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                var dialog = BoeLoginSettingsDialog.show(activity)
                var nodes = descendants(dialog.window!!.decorView)
                var inputs = nodes.filterIsInstance<EditText>()
                assertEquals(2, inputs.size)
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                for (input in inputs) {
                    assertFalse(input.isSaveEnabled)
                    assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
                    assertTrue(input.text.isEmpty())
                }
                inputs[0].setText("synthetic-account")
                inputs[1].setText("synthetic-password")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                assertFalse(settings.configured())
                nodes.filterIsInstance<CheckBox>().single().isChecked = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(dialog.isShowing)
                assertEquals("synthetic-account", settings.load().username)
                dialog = BoeLoginSettingsDialog.show(activity)
                nodes = descendants(dialog.window!!.decorView)
                inputs = nodes.filterIsInstance<EditText>()
                assertTrue(inputs.all { it.text.isEmpty() })
                val revision = settings.revision()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(dialog.isShowing)
                assertEquals("synthetic-password", settings.load().password)
                assertNotEquals(revision, settings.revision())
                assertTrue(inputs.all { it.text.isEmpty() })
            }
            assertEquals(originalPlans, Store(target).prefs.all - "history")
            val log = Store(target).history()
            assertFalse(log.contains("synthetic-account"))
            assertFalse(log.contains("synthetic-password"))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            settings.clear()
        }
    }
}
