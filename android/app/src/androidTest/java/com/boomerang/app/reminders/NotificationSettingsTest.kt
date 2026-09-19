package com.boomerang.app.reminders

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import com.boomerang.app.ui.BoomerangTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** On Android 13+, NotificationPermissionTest exercises the actual runtime permission instead. */
@SdkSuppress(maxSdkVersion = 32)
class NotificationSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun systemDisabledNotificationsHaveSettingsRouteAndRefreshOnReturn(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationSystemFixture(context).use { fixture ->
            val originallyAllowed = fixture.manager.areNotificationsEnabled()
            try {
              withNotificationFailureEvidence(context, "notification-settings-route-failure") {
                setLegacySystemNotifications(context, enabled = false)
                assertFalse(fixture.manager.areNotificationsEnabled())
                compose.setContent { BoomerangTheme { NotificationCenter(fixture.owner, {}) } }
                compose.onNodeWithText("系统通知未开启，提醒仍会保存在这里。").assertIsDisplayed()
                compose.onNodeWithText("通知设置").performClick()
                enableNotificationsInSettings(fixture.device, context)
                compose.waitForIdle()
                compose.onNodeWithText("系统通知未开启，提醒仍会保存在这里。").assertDoesNotExist()
                notificationScreenshot(context, "notification-settings-return-enabled")
              }
            } finally { setLegacySystemNotifications(context, originallyAllowed) }
        }
    }

    @Test fun returningFromExternalSettingsRefreshesPreviouslyDeniedStatus(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationSystemFixture(context).use { fixture ->
            val originallyAllowed = fixture.manager.areNotificationsEnabled()
            try {
              withNotificationFailureEvidence(context, "notification-settings-return-failure") {
                setLegacySystemNotifications(context, enabled = true)
                compose.setContent { BoomerangTheme { NotificationCenter(fixture.owner, {}) } }
                compose.onNodeWithText("系统通知未开启，提醒仍会保存在这里。").assertDoesNotExist()
                setLegacySystemNotifications(context, enabled = false)
                compose.waitForIdle()
                compose.onNodeWithText("系统通知未开启，提醒仍会保存在这里。").assertIsDisplayed()
                notificationScreenshot(context, "notification-settings-return-disabled")
                setLegacySystemNotifications(context, enabled = true)
                compose.waitForIdle()
                compose.onNodeWithText("系统通知未开启，提醒仍会保存在这里。").assertDoesNotExist()
              }
            } finally { setLegacySystemNotifications(context, originallyAllowed) }
        }
    }
}
