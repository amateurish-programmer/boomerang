package com.boomerang.app.reminders

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import android.app.NotificationManager
import com.boomerang.app.MainActivity
import com.boomerang.app.data.BoomerangDatabase
import com.boomerang.app.data.BoomerangRepository
import com.boomerang.app.domain.RecordContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ReminderPendingIntentTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun oldRealPendingIntentCannotOpenSameRecordIdInCurrentAccount(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        allowSystemNotifications(context)
        val localDatabase = BoomerangDatabase.open(context)
        try {
            val localRepository = BoomerangRepository(localDatabase)
            val sameId = localRepository.save(RecordContent(originalText = "当前账号同编号记录"), emptyList())
            try {
                NotificationSystemFixture(context).use { fixture ->
                    val oldId = fixture.saveDueRecord("旧账号私密记录")
                    // A cross-account UUID collision makes an unchecked route visibly unsafe.
                    val oldRecord = fixture.repository.detail(oldId)!!.record
                    fixture.database.records().insert(oldRecord.copy(id = sameId))
                    fixture.runWorker()
                    waitForSystem { fixture.active(sameId).size == 1 }
                    val pending = fixture.active(sameId).single().notification.contentIntent
                    val deliveryKey = fixture.inbox().single { it.recordId == sameId }.key
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        compose.waitUntil(10_000) {
                            ReminderScheduler.currentOwner(context) == "local" &&
                                compose.onAllNodesWithTag("create_record").fetchSemanticsNodes().isNotEmpty()
                        }
                        waitForSystem { fixture.active(sameId).isEmpty() }
                        pending.send()
                        waitForSystem {
                            var delivered = false
                            scenario.onActivity { delivered = it.intent.action == deliveryKey }
                            delivered
                        }
                        compose.waitForIdle()
                        compose.onNodeWithTag("detail_original").assertDoesNotExist()
                        assertTrue(compose.onAllNodesWithTag("create_record").fetchSemanticsNodes().isNotEmpty())
                    }
                }
            } finally {
                localRepository.detail(sameId)?.let { localRepository.softDelete(sameId, it.record.localRevision) }
            }
        } finally { localDatabase.close() }
    }

    @Test fun tappingPostedSystemNotificationOpensTheCurrentAccountsExactRecord(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        allowSystemNotifications(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val database = BoomerangDatabase.open(context)
        try {
            val repository = BoomerangRepository(database)
            val date = LocalDate.now(ZoneOffset.UTC).toString()
            val id = repository.save(RecordContent(originalText = "从真实系统通知打开的记录",
                dueStart = date, dueEnd = date, dateText = date, datePrecision = "DAY", timezone = "UTC"), emptyList())
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    compose.waitUntil(10_000) {
                        ReminderScheduler.currentOwner(context) == "local" &&
                            compose.onAllNodesWithTag("create_record").fetchSemanticsNodes().isNotEmpty()
                    }
                    val worker = TestListenableWorkerBuilder<ReminderWorker>(context)
                        .setInputData(workDataOf(ReminderScheduler.OWNER to "local")).build()
                    assertEquals(ListenableWorker.Result.success(), worker.doWork())
                    waitForSystem { manager.activeNotifications.any { it.tag == "local/$id" } }
                    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    device.openNotification()
                    val notification = device.wait(Until.findObject(By.text("回旋镖到期提醒")), 10_000)
                        ?: error("The posted notification was not present in the system notification shade")
                    notificationScreenshot(context, "notification-system-shade")
                    notification.click()
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("detail_original").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("detail_original").assertTextEquals("从真实系统通知打开的记录")
                    notificationScreenshot(context, "notification-opened-record")
                }
            } finally {
                repository.detail(id)?.let { repository.softDelete(id, it.record.localRevision) }
                manager.cancel("local/$id", 0)
            }
        } finally { database.close() }
    }
}
