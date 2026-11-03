package com.luskyle.clockintest

import android.app.AlertDialog
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class BarkSettingsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    @Test fun successAndSimulatedSuccessUseSameQueueWithoutChangingAttendance() {
        check(InstrumentationRegistry.getArguments().getString("barkTestDevice") == "true")
        val settings = BarkSettings(target)
        val outbox = NotificationOutbox(target, DeliveryChannel.BARK)
        check(!settings.configured() && outbox.first() == null)
        NotificationDelivery.schedule(target)
        val originalPlans = Store(target).prefs.all - "history"
        val scheduler = target.getSystemService(JobScheduler::class.java)
        try {
            settings.saveKey("synthetic-success-test-key")
            assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(
                JobInfo.Builder(31, ComponentName(target, BarkJobService::class.java))
                    .setMinimumLatency(600_000).build()))
            NotificationDelivery.result(target, ScheduleSlot.MORNING, System.currentTimeMillis(), true, true,
                "测试夹具中的本次新增记录")
            val real = requireNotNull(outbox.first())
            assertEquals("永不迟到 · 上班 · 打卡成功（已核验）", real.getString("subject"))
            assertTrue(real.getString("body").startsWith("任务时间："))
            assertEquals(1, real.getString("body").lines().size)
            assertTrue(real.getString("body").length <= 40)
            outbox.acknowledge(real.getString("id"), "测试夹具已核验，不发送到网络")
            NotificationDelivery.testSuccess(target)
            val simulated = requireNotNull(outbox.first())
            assertEquals("永不迟到 · 模拟打卡成功（非真实考勤）", simulated.getString("subject"))
            assertTrue(simulated.getString("body").contains("模拟通知，未执行真实打卡"))
            assertTrue(simulated.getString("body").length <= 100)
            assertFalse(simulated.getString("body").contains("结果：打卡成功（已核验）"))
            assertEquals(real.getString("endpoint"), simulated.getString("endpoint"))
            assertFalse(simulated.toString().contains("synthetic-success-test-key"))
            assertEquals(originalPlans, Store(target).prefs.all - "history")
        } finally {
            settings.clear()
            outbox.clear()
        }
    }

    @Test fun upgradeRemovesClawCredentialsAndJobButPreservesBarkQueueAndPlans() {
        check(InstrumentationRegistry.getArguments().getString("barkTestDevice") == "true")
        val settings = BarkSettings(target)
        val outbox = NotificationOutbox(target, DeliveryChannel.BARK)
        check(!settings.configured() && outbox.first() == null)
        NotificationDelivery.schedule(target)
        val store = Store(target)
        val originalPlans = store.prefs.all
        val scheduler = target.getSystemService(JobScheduler::class.java)
        try {
            settings.saveKey("synthetic-preserved-bark")
            val encryptedBark = settings.encryptedEndpoint()
            val legacySecret = LocalSecret("clockin-claw-account-v1").encrypt("synthetic-account")
            check(target.getSharedPreferences("claw-settings", Context.MODE_PRIVATE).edit()
                .putString("account", legacySecret).putString("bindingId", "synthetic-binding").commit())
            check(target.getSharedPreferences("claw-outbox", Context.MODE_PRIVATE).edit()
                .putString("pending", """[{"id":"old-claw-message"}]""").commit())
            check(target.getSharedPreferences("bark-outbox", Context.MODE_PRIVATE).edit()
                .putString("pending", """[{"id":"preserved-bark-message"}]""").commit())
            check(store.prefs.edit().remove("legacyClawRemovedV1").commit())
            for (id in listOf(31, 32)) {
                assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(
                    JobInfo.Builder(id, ComponentName(target, BarkJobService::class.java))
                        .setMinimumLatency(600_000).build()))
            }
            NotificationDelivery.schedule(target)
            assertNull(scheduler.getPendingJob(32))
            assertNotNull(scheduler.getPendingJob(31))
            assertTrue(target.getSharedPreferences("claw-settings", Context.MODE_PRIVATE).all.isEmpty())
            assertTrue(target.getSharedPreferences("claw-outbox", Context.MODE_PRIVATE).all.isEmpty())
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .containsAlias("clockin-claw-account-v1"))
            assertEquals(encryptedBark, settings.encryptedEndpoint())
            assertEquals("preserved-bark-message", outbox.first()!!.getString("id"))
            NotificationDelivery.schedule(target)
            assertEquals("preserved-bark-message", outbox.first()!!.getString("id"))
            assertEquals(originalPlans - "history", store.prefs.all - "history")
        } finally {
            settings.clear()
            outbox.clear()
            scheduler.cancel(32)
            target.deleteSharedPreferences("claw-settings")
            target.deleteSharedPreferences("claw-outbox")
        }
    }

    @Test fun endpointIsEncryptedAndReopeningCanDecryptOldQueueDestination() {
        val fixture = "bark-test-fixture"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(fixture, mode)
        }
        target.deleteSharedPreferences(fixture)
        try {
            val settings = BarkSettings(context)
            assertFalse(settings.configured())
            settings.save("https://bark.example.com/synthetic-key")
            val encrypted = requireNotNull(settings.encryptedEndpoint())
            assertFalse(encrypted.contains("synthetic-key"))
            assertFalse(encrypted.contains("bark.example.com"))
            val reopened = BarkSettings(context)
            assertEquals("synthetic-key", reopened.endpoint(encrypted).deviceKey)
            reopened.saveKey("another-key")
            assertEquals(BarkEndpoint("https://api.day.app", "another-key"),
                reopened.endpoint(requireNotNull(reopened.encryptedEndpoint())))
            assertEquals("synthetic-key", reopened.endpoint(encrypted).deviceKey)
            reopened.clear()
            assertFalse(BarkSettings(context).configured())
        } finally {
            target.deleteSharedPreferences(fixture)
        }
    }

    @Test fun dialogAcceptsOnlyKeyAndSavesOfficialServer() {
        check(InstrumentationRegistry.getArguments().getString("barkTestDevice") == "true")
        val settings = BarkSettings(target)
        check(!settings.configured())
        val activity = instrumentation.startActivitySync(Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var dialog: AlertDialog? = null
        instrumentation.runOnMainSync { dialog = BarkSettingsDialog.show(activity) }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val panel = requireNotNull(dialog)
            try {
                fun fields(view: View): List<EditText> = when (view) {
                    is EditText -> listOf(view)
                    is ViewGroup -> (0 until view.childCount).flatMap { fields(view.getChildAt(it)) }
                    else -> emptyList()
                }
                val inputs = fields(requireNotNull(panel.window).decorView)
                assertEquals(1, inputs.size)
                assertEquals("Bark Key", inputs.single().contentDescription)
                assertTrue(panel.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                inputs.single().setText("http://insecure.example.com/key")
                panel.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(panel.isShowing)
                assertFalse(settings.configured())
                inputs.single().setText("synthetic-ui-key")
                panel.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(panel.isShowing)
                assertEquals(BarkEndpoint("https://api.day.app", "synthetic-ui-key"),
                    settings.endpoint(requireNotNull(settings.encryptedEndpoint())))
            } finally {
                settings.clear()
                panel.dismiss()
                activity.finish()
            }
        }
    }

    @Test fun failedBarkKeepsQueueAndNeverChangesAttendancePlans() {
        check(InstrumentationRegistry.getArguments().getString("barkTestDevice") == "true")
        val settings = BarkSettings(target)
        val bark = NotificationOutbox(target, DeliveryChannel.BARK)
        check(!settings.configured() && bark.first() == null)
        val originalPlans = Store(target).prefs.all
        try {
            settings.save("https://127.0.0.1:1/synthetic-key")
            NotificationDelivery.result(target, ScheduleSlot.MORNING, System.currentTimeMillis(), false, false,
                "账号需要重新登录")
            val pending = requireNotNull(bark.first())
            assertTrue(pending.getString("subject").contains("打卡未完成"))
            assertFalse(pending.toString().contains("synthetic-key"))
            instrumentation.uiAutomation.executeShellCommand(
                "cmd jobscheduler run -f ${target.packageName} 31"
            ).use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!bark.status().contains("发送失败")) {
                check(SystemClock.elapsedRealtime() < deadline) { "Bark failure was not reported" }
                SystemClock.sleep(200)
            }
            assertEquals(pending.getString("id"), bark.first()!!.getString("id"))
            assertFalse(Store(target).history().contains("synthetic-key"))
            BarkDelivery.clear(target)
            assertNull(bark.first())
            assertEquals(originalPlans - "history" - "legacyEmailRemovedV1" - "legacyClawRemovedV1",
                Store(target).prefs.all - "history" - "legacyEmailRemovedV1" - "legacyClawRemovedV1")
        } finally {
            settings.clear()
            bark.clear()
        }
    }

    @Test fun upgradeRemovesLegacyMailButPreservesBarkAndPlans() {
        check(InstrumentationRegistry.getArguments().getString("barkTestDevice") == "true")
        val settings = BarkSettings(target)
        check(!settings.configured())
        val store = Store(target)
        val originalPlans = store.prefs.all
        val scheduler = target.getSystemService(JobScheduler::class.java)
        try {
            settings.saveKey("synthetic-preserved-key")
            val encryptedBark = settings.encryptedEndpoint()
            val legacySecret = LocalSecret("clockin-smtp-password-v1").encrypt("synthetic-password")
            check(target.getSharedPreferences("email-settings", Context.MODE_PRIVATE).edit()
                .putString("password", legacySecret).commit())
            check(target.getSharedPreferences("email-outbox", Context.MODE_PRIVATE).edit()
                .putString("pending", """[{"id":"synthetic"}]""").commit())
            check(store.prefs.edit().remove("legacyEmailRemovedV1").commit())
            assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(
                JobInfo.Builder(30, ComponentName(target, BarkJobService::class.java))
                    .setMinimumLatency(600_000).build()))
            assertNotNull(scheduler.getPendingJob(30))
            NotificationDelivery.schedule(target)
            assertNull(scheduler.getPendingJob(30))
            assertTrue(target.getSharedPreferences("email-settings", Context.MODE_PRIVATE).all.isEmpty())
            assertTrue(target.getSharedPreferences("email-outbox", Context.MODE_PRIVATE).all.isEmpty())
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .containsAlias("clockin-smtp-password-v1"))
            assertEquals(encryptedBark, settings.encryptedEndpoint())
            assertEquals("synthetic-preserved-key",
                settings.endpoint(requireNotNull(encryptedBark)).deviceKey)
            NotificationDelivery.schedule(target)
            assertEquals(encryptedBark, settings.encryptedEndpoint())
            assertEquals(originalPlans - "history" - "legacyEmailRemovedV1" - "legacyClawRemovedV1",
                store.prefs.all - "history" - "legacyEmailRemovedV1" - "legacyClawRemovedV1")
        } finally {
            settings.clear()
            BarkDelivery.clear(target)
            scheduler.cancel(30)
            target.deleteSharedPreferences("email-settings")
            target.deleteSharedPreferences("email-outbox")
        }
    }
}
