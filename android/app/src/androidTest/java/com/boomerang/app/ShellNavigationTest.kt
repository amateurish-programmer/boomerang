package com.boomerang.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fourDestinationsAreReachable() {
        listOf("LIBRARY" to "你的镖库", "AI" to "AI 助手", "PROFILE" to "我的空间", "HOME" to "回旋镖").forEach { (tag, title) ->
            compose.onNodeWithTag("nav_$tag").performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        compose.onNodeWithTag("nav_HOME").assertIsDisplayed()
        captureScreenshot("shell-home")
    }

    private fun captureScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val evidence = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "acceptance/ink-ui").apply { mkdirs() }
        org.junit.Assert.assertTrue(androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            .takeScreenshot(java.io.File(evidence, "$name.png")))
    }

    /** Wait for the real IME and a stable inset, not merely Compose's idle clock. */
    private fun waitForIme(visible: Boolean) {
        var previousHeight = -1
        var stableSince = 0L
        compose.waitUntil(10_000) {
            var matches = false
            var height = -1
            compose.runOnUiThread {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.let {
                    matches = it.isVisible(WindowInsetsCompat.Type.ime()) == visible
                    height = it.getInsets(WindowInsetsCompat.Type.ime()).bottom
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (!matches || height != previousHeight) stableSince = now
            previousHeight = height
            matches && now - stableSince >= 300
        }
        compose.waitForIdle()
    }

    private fun assertEditorActionsInsideVisibleWindow() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var statusBottom = 0
        var keyboardTop = 0
        compose.runOnUiThread {
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
            assertTrue("Real keyboard must be visible", insets.isVisible(WindowInsetsCompat.Type.ime()))
            statusBottom = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            keyboardTop = device.displayHeight - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        }
        listOf("返回", "新建记录", "保存记录").forEach { text ->
            val node = device.findObject(By.text(text))
            assertNotNull("$text must remain on screen with the keyboard open", node)
            val bounds = requireNotNull(node).visibleBounds
            assertTrue("$text has visible screen bounds: $bounds", bounds.width() > 0 && bounds.height() > 0)
            assertTrue("$text must be below status bar: $bounds", bounds.top >= statusBottom)
            assertTrue("$text must be above keyboard: $bounds", bounds.bottom <= keyboardTop)
        }
    }

    @Test fun profileProvidesExplicitLoginAndImportBoundary() {
        compose.onNodeWithTag("nav_PROFILE").performClick()
        compose.onNodeWithTag("account_email").assertIsDisplayed()
        compose.onNodeWithTag("account_password").assertIsDisplayed()
        compose.onNodeWithTag("account_login").performScrollTo().assertIsDisplayed()
    }

    @Test fun updatesAreReachableWithoutLoginAndSurviveRecreation() {
        compose.onNodeWithTag("nav_PROFILE").performClick()
        compose.onNodeWithTag("open_updates").performClick()
        compose.onNodeWithText("版本与更新").assertIsDisplayed()
        compose.onNodeWithTag("update_check").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("版本与更新").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("open_updates").assertIsDisplayed()
    }

    @Test fun localReviewAndBackupEntryReturnsToProfile() {
        compose.onNodeWithTag("nav_PROFILE").performClick()
        compose.onNodeWithText("回顾、分享与备份").performClick()
        compose.onNodeWithText("回顾与备份").assertIsDisplayed()
        compose.onNodeWithText("年度报告").assertIsDisplayed()
        compose.onNodeWithText("导入预览").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("account_email").assertIsDisplayed()
    }

    @Test fun editorDraftSurvivesBackAndRecreation() {
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithTag("create_record").performClick()
        compose.onNodeWithTag("quote_input").performTextInput("年底读完十二本书")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("年底读完十二本书").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("你的镖库").assertIsDisplayed()
        compose.onNodeWithTag("create_record").performClick()
        compose.onNodeWithText("年底读完十二本书").assertIsDisplayed()
    }

    @Test fun selectedDestinationSurvivesRecreation() {
        compose.onNodeWithTag("nav_AI").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("AI 助手").assertIsDisplayed()
    }

    @Test fun offlineCreateEditAndDelete() {
        val original = "离线闭环 ${System.nanoTime()}"
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithTag("create_record").performClick()
        compose.onNodeWithTag("quote_input").performClick().performTextInput(original)
        waitForIme(true)
        compose.onNodeWithTag("back").assertIsDisplayed()
        compose.onNodeWithTag("save_record").assertIsDisplayed()
        assertEditorActionsInsideVisibleWindow()
        captureScreenshot("shell-editor-keyboard")
        compose.onNodeWithTag("save_record").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("detail_original")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_original").assertTextEquals(original)
        waitForIme(false)
        captureScreenshot("shell-detail")
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("search").assertIsDisplayed()
        compose.onNodeWithText(original).performScrollTo().assertIsDisplayed()
        captureScreenshot("shell-library")
        compose.onNodeWithText(original).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("detail_original")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("edit_record").performClick()
        compose.onNodeWithTag("quote_input").performTextClearance()
        compose.onNodeWithTag("quote_input").performTextInput("修改后的离线原话")
        compose.onNodeWithTag("save_record").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("detail_original")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_original").assertTextEquals("修改后的离线原话")
        compose.onNodeWithTag("detail_more").performClick()
        compose.onNodeWithTag("delete_record").performClick()
        compose.onNodeWithTag("confirm_delete").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("search")).fetchSemanticsNodes().isNotEmpty() }
    }
}
