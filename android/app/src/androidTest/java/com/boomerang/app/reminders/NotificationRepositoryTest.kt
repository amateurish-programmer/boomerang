package com.boomerang.app.reminders

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NotificationRepositoryTest {
    @Test fun duplicateDeliveryPersistsAcrossReopenAndAccountsStaySeparate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val entry = LocalNotification("$owner/r/1/0", owner, "r", 1, "提醒", "今天到期", "2026-09-17T00:00:00Z")
        NotificationRepository(context).use { repository ->
            assertTrue(repository.insertOnce(entry))
            assertFalse(repository.insertOnce(entry))
            assertTrue(repository.list(other).isEmpty())
        }
        NotificationRepository(context).use { repository ->
            assertFalse(repository.insertOnce(entry))
            assertEquals(listOf(entry), repository.list(owner))
            assertTrue(repository.insertOnce(entry.copy(key = "$owner/r/2/0", revision = 2)))
            assertEquals(2, repository.list(owner).size)
        }
    }
}
