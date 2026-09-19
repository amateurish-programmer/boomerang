package com.boomerang.app.reminders

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.boomerang.app.ui.BoomerangTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

/** Run this class in its own instrumentation invocation, after revoking POST_NOTIFICATIONS externally. */
@SdkSuppress(minSdkVersion = 33)
class NotificationPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun denialKeepsWorkerInboxAndSettingsReturnRefreshesPermission(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("The runner must reset POST_NOTIFICATIONS before this class",
            PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
        NotificationSystemFixture(context).use { fixture ->
            lateinit var model: NotificationViewModel
            compose.activityRule.scenario.onActivity { model = ViewModelProvider(it)[NotificationViewModel::class.java] }
            var stage = "prepare notification page"
            try {
              withNotificationFailureEvidence(context, "notification-permission-failure", details = {
                buildString {
                    compose.activityRule.scenario.onActivity { activity ->
                        appendLine("Lifecycle: ${activity.lifecycle.currentState}; focused: ${activity.hasWindowFocus()}")
                    }
                    appendLine("Stage: $stage; owner: ${fixture.owner}")
                    appendLine("Model record IDs: ${model.items.value.map { it.recordId }}")
                    appendLine("Model error: ${model.error.value}")
                    appendLine(compose.onRoot(useUnmergedTree = true).printToString())
                }
              }) {
                val recordId = fixture.saveDueRecord("拒绝权限后仍可查看的提醒")
                compose.setContent { BoomerangTheme { NotificationCenter(fixture.owner, {}, model = model) } }
                stage = "deny the real runtime permission"
                compose.onNodeWithText("开启系统通知").performClick()
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val deny = device.wait(Until.findObject(By.res(Pattern.compile("(?:com\\.android|com\\.google\\.android)\\.permissioncontroller:id/permission_deny_button"))), 10_000)
                    ?: error("POST_NOTIFICATIONS permission dialog did not appear")
                deny.click()
                assertTrue("The system permission dialog did not close", device.wait(Until.gone(
                    By.res(Pattern.compile("(?:com\\.android|com\\.google\\.android)\\.permissioncontroller:id/permission_deny_button"))), 10_000))
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                waitForSystem {
                    var foreground = false
                    compose.activityRule.scenario.onActivity {
                        foreground = it.lifecycle.currentState == Lifecycle.State.RESUMED && it.hasWindowFocus()
                    }
                    foreground
                }
                compose.waitForIdle()
                assertEquals(PackageManager.PERMISSION_DENIED,
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
                assertFalse(fixture.manager.areNotificationsEnabled())
                stage = "persist denied reminder"
                fixture.runWorker()
                assertEquals(1, fixture.inbox().count { it.recordId == recordId })
                assertTrue(fixture.active(recordId).isEmpty())
                stage = "wait for persisted reminder in ViewModel"
                compose.waitUntil(10_000) { model.items.value.any { it.recordId == recordId } }
                stage = "wait for inbox text in Compose semantics"
                compose.waitUntil(10_000) { compose.onAllNodesWithText("拒绝权限后仍可查看的提醒").fetchSemanticsNodes().isNotEmpty() }
                stage = "verify inbox item is displayed"
                compose.onNodeWithText("拒绝权限后仍可查看的提醒").assertIsDisplayed()
                compose.onNodeWithText("系统通知未开启，提醒仍会保存在这里。").assertIsDisplayed()
                notificationScreenshot(context, "notification-permission-denied")
                stage = "open notification settings and enable permission"
                compose.onNodeWithText("通知设置").performClick()
                enableNotificationsInSettings(device, context)
                waitForSystem {
                    var foreground = false
                    compose.activityRule.scenario.onActivity {
                        foreground = it.lifecycle.currentState == Lifecycle.State.RESUMED && it.hasWindowFocus()
                    }
                    foreground
                }
                compose.waitForIdle()
                stage = "verify permission refresh after returning from settings"
                compose.onNodeWithText("系统通知未开启，提醒仍会保存在这里。").assertDoesNotExist()
                assertEquals(PackageManager.PERMISSION_GRANTED,
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
                val nextId = fixture.saveDueRecord("开启后显示系统通知")
                stage = "post reminder after permission was enabled"
                fixture.runWorker()
                waitForSystem { fixture.active(nextId).size == 1 }
                // The denied reminder remains in the inbox and is not backfilled as a duplicate alert.
                assertTrue(fixture.active(recordId).isEmpty())
                assertEquals(2, fixture.inbox().size)
                notificationScreenshot(context, "notification-permission-enabled")
              }
            } finally { allowSystemNotifications(context) }
        }
    }
}
