package com.boomerang.app.reminders

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

class ReminderSystemTest {
    @Test fun workerPostsRealNotificationAndDoesNotRepostAfterDismissal(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        allowSystemNotifications(context)
        NotificationSystemFixture(context).use { fixture ->
            val id = fixture.saveDueRecord("原话不能出现在锁屏通知")
            fixture.runWorker()
            waitForSystem { fixture.active(id).size == 1 }
            val posted = fixture.active(id).single().notification
            assertEquals("回旋镖到期提醒", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("今天到期", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
            assertNotNull(posted.contentIntent)
            fixture.runWorker()
            assertEquals(1, fixture.inbox().size)
            assertEquals(1, fixture.active(id).size)
            fixture.manager.cancel("${fixture.owner}/$id", 0)
            waitForSystem { fixture.active(id).isEmpty() }
            fixture.runWorker()
            assertEquals(1, fixture.inbox().size)
            assertTrue("A dismissed reminder must not be posted by a duplicate worker", fixture.active(id).isEmpty())
        }
    }

    @Test fun newerRevisionReplacesNotificationAndLatestIneligibleRecordCancelsIt(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        allowSystemNotifications(context)
        NotificationSystemFixture(context).use { fixture ->
            val id = fixture.saveDueRecord()
            fixture.runWorker()
            waitForSystem { fixture.active(id).size == 1 }
            val previousPending = fixture.active(id).single().notification.contentIntent
            val first = fixture.repository.detail(id)!!.record
            fixture.repository.save(first.content.copy(notes = "用户补充说明"), emptyList(), id, first.localRevision)
            fixture.runWorker()
            waitForSystem { fixture.active(id).singleOrNull()?.notification?.contentIntent?.let { it != previousPending } == true }
            assertEquals(1, fixture.active(id).size)
            assertEquals(setOf(1L, 2L), fixture.inbox().map { it.revision }.toSet())

            val current = fixture.repository.detail(id)!!.record
            val farDate = LocalDate.now(ZoneOffset.UTC).plusDays(30).toString()
            fixture.repository.save(current.content.copy(dueStart = farDate, dueEnd = farDate,
                dateText = farDate), emptyList(), id, current.localRevision)
            fixture.runWorker()
            waitForSystem { fixture.active(id).isEmpty() }
            assertEquals("An invalidated system alert must not remove inbox history", 2, fixture.inbox().size)
        }
    }

    @Test fun cancellingOrDeletingLatestRecordRemovesAlreadyPostedNotification(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        allowSystemNotifications(context)
        NotificationSystemFixture(context).use { fixture ->
            val cancelledId = fixture.saveDueRecord("稍后取消的记录")
            val deletedId = fixture.saveDueRecord("稍后删除的记录")
            fixture.runWorker()
            waitForSystem { fixture.active(cancelledId).size == 1 && fixture.active(deletedId).size == 1 }
            val cancelled = fixture.repository.detail(cancelledId)!!.record
            fixture.repository.save(cancelled.content.copy(lifecycle = "CANCELLED"), emptyList(), cancelledId, cancelled.localRevision)
            val deleted = fixture.repository.detail(deletedId)!!.record
            fixture.repository.softDelete(deletedId, deleted.localRevision)
            fixture.runWorker()
            waitForSystem { fixture.active(cancelledId).isEmpty() && fixture.active(deletedId).isEmpty() }
            assertEquals(2, fixture.inbox().size)
        }
    }

    @Test fun accountActivationCancelsPreviousScheduledWorkAndLateWorkerCannotPost(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        allowSystemNotifications(context)
        NotificationSystemFixture(context).use { fixture ->
            val work = WorkManager.getInstance(context)
            val nextOwner = UUID.randomUUID().toString()
            try {
                val id = fixture.saveDueRecord()
                fixture.runWorker()
                waitForSystem { fixture.active(id).size == 1 }
                val pending = OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(1, TimeUnit.DAYS)
                    .setInputData(workDataOf(ReminderScheduler.OWNER to fixture.owner))
                    .addTag("reminders:${fixture.owner}").build()
                work.enqueue(pending).result.get(10, TimeUnit.SECONDS)
                assertEquals(WorkInfo.State.ENQUEUED, work.getWorkInfoById(pending.id).get(10, TimeUnit.SECONDS)!!.state)
                ReminderScheduler.activate(context, nextOwner)
                waitForSystem { work.getWorkInfoById(pending.id).get(10, TimeUnit.SECONDS)?.state == WorkInfo.State.CANCELLED }
                waitForSystem { fixture.active(id).isEmpty() }
                val lateId = fixture.saveDueRecord("账号切换后的旧任务不得投递")
                fixture.runWorker()
                assertFalse(fixture.inbox().any { it.recordId == lateId })
                assertTrue(fixture.active(lateId).isEmpty())
                assertTrue(NotificationRepository(context).use { it.list(nextOwner).isEmpty() })
                assertTrue(work.getWorkInfosForUniqueWork("reminders:$nextOwner").get(10, TimeUnit.SECONDS).any { !it.state.isFinished })
            } finally { work.cancelAllWorkByTag("reminders:$nextOwner").result.get(10, TimeUnit.SECONDS) }
        }
    }
}
