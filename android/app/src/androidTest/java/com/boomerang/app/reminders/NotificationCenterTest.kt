package com.boomerang.app.reminders

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import com.boomerang.app.ui.BoomerangTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class NotificationCenterTest {
    @get:Rule val compose = createComposeRule()
    @Test fun persistedInboxDisplaysWithoutPostingSystemNotificationAndOpensRecord() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        NotificationRepository(context).use {
            it.insertOnce(LocalNotification("$owner/$id/1/0", owner, id, 1, "提醒", "今天到期", "2026-09-17T00:00:00Z"))
        }
        var opened: String? = null
        compose.setContent { BoomerangTheme { NotificationCenter(owner, { opened = it }) } }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("今天到期").assertIsDisplayed(); true }.getOrDefault(false) }
        compose.onNodeWithText("今天到期").performClick()
        compose.runOnIdle { assertEquals(id, opened) }
    }
}
