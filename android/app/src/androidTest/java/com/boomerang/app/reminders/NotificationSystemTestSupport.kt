package com.boomerang.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.boomerang.app.data.BoomerangDatabase
import com.boomerang.app.data.BoomerangRepository
import com.boomerang.app.domain.RecordContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Device-only fixtures. The worker, database, notification service and scheduled work remain real. */
internal class NotificationSystemFixture(val context: Context) : AutoCloseable {
    val owner: String = UUID.randomUUID().toString()
    val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val previousOwner = ReminderScheduler.currentOwner(context)
    val database = BoomerangDatabase.open(context, owner)
    val repository = BoomerangRepository(database, owner)
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    init {
        // No scheduled scan may race these per-invocation assertions.
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        synchronized(ReminderScheduler.lock) {
            check(context.getSharedPreferences("reminder_account", Context.MODE_PRIVATE)
                .edit().putString("owner", owner).commit())
            manager.cancelAll()
            manager.createNotificationChannel(NotificationChannel(ReminderScheduler.CHANNEL,
                "到期提醒", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    suspend fun saveDueRecord(text: String = "系统提醒验收记录"): String {
        val date = LocalDate.now(ZoneOffset.UTC).toString()
        return repository.save(RecordContent(originalText = text, dueStart = date, dueEnd = date,
            dateText = date, datePrecision = "DAY", timezone = "UTC"), emptyList())
    }

    suspend fun runWorker(forOwner: String = owner) {
        val worker = TestListenableWorkerBuilder<ReminderWorker>(context)
            .setInputData(workDataOf(ReminderScheduler.OWNER to forOwner)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    fun inbox() = NotificationRepository(context).use { it.list(owner) }
    fun active(id: String) = manager.activeNotifications.filter { it.tag == "$owner/$id" }

    override fun close() {
        synchronized(ReminderScheduler.lock) {
            check(context.getSharedPreferences("reminder_account", Context.MODE_PRIVATE)
                .edit().putString("owner", previousOwner).commit())
            manager.cancelAll()
        }
        WorkManager.getInstance(context).cancelAllWorkByTag("reminders:$owner").result.get(10, TimeUnit.SECONDS)
        database.close()
        context.deleteDatabase("boomerang_$owner.db")
        NotificationRepository(context).use {
            it.writableDatabase.delete("notifications", "owner=?", arrayOf(owner))
        }
    }
}

internal fun allowSystemNotifications(context: Context) {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    if (Build.VERSION.SDK_INT >= 33) {
        device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
    }
    device.executeShellCommand("appops set ${context.packageName} POST_NOTIFICATION allow")
    waitForSystem { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
}

internal fun waitForSystem(timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
    assertTrue("System state did not reach the expected value within $timeoutMs ms", condition())
}

internal fun enableNotificationsInSettings(device: UiDevice, context: Context) {
    assertTrue("Application notification settings did not open",
        device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000))
    val toggle = device.wait(Until.findObject(By.checkable(true).checked(false).enabled(true)), 10_000)
        ?: error("No disabled notification switch found in application notification settings")
    toggle.click()
    waitForSystem { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    device.pressBack()
}

internal fun notificationScreenshot(context: Context, name: String) {
    val directory = File(context.getExternalFilesDir(null), "acceptance").apply { mkdirs() }
    assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        .takeScreenshot(File(directory, "$name-api${Build.VERSION.SDK_INT}.png")))
}

/** Capture before the fixture restores permissions, notifications or the foreground Activity. */
internal inline fun <T> withNotificationFailureEvidence(
    context: Context,
    name: String,
    details: () -> String = { "" },
    block: () -> T,
): T = try {
    block()
} catch (failure: Throwable) {
    val directory = File(context.getExternalFilesDir(null), "acceptance").apply { mkdirs() }
    val stem = "$name-api${Build.VERSION.SDK_INT}"
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    runCatching { notificationScreenshot(context, name) }.exceptionOrNull()?.let(failure::addSuppressed)
    runCatching { device.dumpWindowHierarchy(File(directory, "$stem.xml")) }.exceptionOrNull()?.let(failure::addSuppressed)
    runCatching {
        File(directory, "$stem.txt").writeText(buildString {
            appendLine(failure.stackTraceToString())
            appendLine(runCatching(details).getOrElse { "Unable to collect UI details: $it" })
            appendLine(device.executeShellCommand("dumpsys activity top"))
        })
    }.exceptionOrNull()?.let(failure::addSuppressed)
    throw failure
}
