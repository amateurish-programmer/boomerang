package com.boomerang.app.reminders

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.boomerang.app.ui.BoomerangTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class NotificationSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun systemDisabledNotificationsHaveSettingsRouteAndRefreshOnReturn(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationSystemFixture(context).use { fixture ->
            allowSystemNotifications(context)
            try {
                fixture.device.executeShellCommand("appops set ${context.packageName} POST_NOTIFICATION ignore")
                waitForSystem { !fixture.manager.areNotificationsEnabled() }
                assertFalse(fixture.manager.areNotificationsEnabled())
                compose.setContent { BoomerangTheme { NotificationCenter(fixture.owner, {}) } }
                compose.onNodeWithText("系统通知未开启，提醒仍会保存在这里。").assertIsDisplayed()
                compose.onNodeWithText("通知设置").performClick()
                enableNotificationsInSettings(fixture.device, context)
                compose.waitForIdle()
                compose.onNodeWithText("系统通知未开启，提醒仍会保存在这里。").assertDoesNotExist()
            } finally { allowSystemNotifications(context) }
        }
    }

    @Test fun returningFromExternalSettingsRefreshesPreviouslyDeniedStatus(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationSystemFixture(context).use { fixture ->
            allowSystemNotifications(context)
            try {
                fixture.device.executeShellCommand("appops set ${context.packageName} POST_NOTIFICATION ignore")
                waitForSystem { !fixture.manager.areNotificationsEnabled() }
                compose.setContent { BoomerangTheme { NotificationCenter(fixture.owner, {}) } }
                compose.onNodeWithText("系统通知未开启，提醒仍会保存在这里。").assertIsDisplayed()
                // Opening Settings independently also catches stale remembered state when the route is absent.
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                enableNotificationsInSettings(fixture.device, context)
                compose.waitForIdle()
                compose.onNodeWithText("系统通知未开启，提醒仍会保存在这里。").assertDoesNotExist()
            } finally { allowSystemNotifications(context) }
        }
    }
}
