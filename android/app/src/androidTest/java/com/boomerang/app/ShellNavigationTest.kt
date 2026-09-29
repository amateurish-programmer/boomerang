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
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import android.graphics.Rect
import android.content.res.Configuration
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

    /** Best-effort evidence for legacy device failures; never replaces the test assertion. */
    private fun captureProbe(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val evidence = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "acceptance/ink-ui")
        val metrics = StringBuilder()
        fun record(label: String, block: () -> Any?) {
            metrics.append(label).append("=")
                .append(runCatching(block).fold({ it.toString() }, { "error: $it" }))
                .append('\n')
        }

        record("screenshot") { captureScreenshot(name); "$name.png" }
        record("hierarchy") { device.dumpWindowHierarchy(java.io.File(evidence, "$name.xml")); "$name.xml" }
        record("display") { "${device.displayWidth}x${device.displayHeight}" }
        record("window") {
            var value = "unavailable"
            compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                val location = IntArray(2)
                decor.getLocationOnScreen(location)
                val visible = Rect()
                decor.getWindowVisibleDisplayFrame(visible)
                val insets = ViewCompat.getRootWindowInsets(decor)
                val types = listOf(
                    "status" to WindowInsetsCompat.Type.statusBars(),
                    "navigation" to WindowInsetsCompat.Type.navigationBars(),
                    "ime" to WindowInsetsCompat.Type.ime()
                )
                value = "decorLocation=${location.contentToString()} decorSize=${decor.width}x${decor.height} " +
                    "visibleFrame=$visible " + types.joinToString(" ") { (label, type) ->
                        "$label=${insets?.getInsets(type)} visible=${insets?.isVisible(type)}"
                    }
            }
            value
        }
        listOf("back", "save_record", "quote_input").forEach { tag ->
            record("compose.$tag.boundsInRoot") {
                compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            }
        }
        listOf("返回", "保存记录", "年底读完十二本书").forEach { label ->
            record("uiautomator.$label.visibleBounds") { device.findObject(By.text(label))?.visibleBounds }
        }
        runCatching { evidence.mkdirs(); java.io.File(evidence, "$name.txt").writeText(metrics.toString()) }
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

    private fun waitForNightAppearance(dark: Boolean) {
        val expected = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        compose.waitUntil(10_000) {
            var actual = 0
            compose.runOnUiThread {
                actual = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            }
            actual == expected
        }
        compose.waitForIdle()
    }

    /** Wait for both window geometry and editor semantics to stop moving after recreation. */
    private fun waitForStableEditorLayout() {
        var previous: String? = null
        var stableSince = SystemClock.elapsedRealtime()
        compose.waitUntil(10_000) {
            var window: String? = null
            compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                val visible = Rect()
                decor.getWindowVisibleDisplayFrame(visible)
                val insets = ViewCompat.getRootWindowInsets(decor)
                if (decor.width > 0 && decor.height > 0 && insets != null) {
                    val ime = WindowInsetsCompat.Type.ime()
                    window = "${decor.width}x${decor.height}/$visible/" +
                        "${insets.isVisible(ime)}/${insets.getInsets(ime).bottom}"
                }
            }
            val quote = runCatching { compose.onNodeWithTag("quote_input").fetchSemanticsNode().boundsInRoot }.getOrNull()
            val save = runCatching { compose.onNodeWithTag("save_record").fetchSemanticsNode().boundsInRoot }.getOrNull()
            val snapshot = if (window != null && quote != null && save != null) "$window/$quote/$save" else null
            val now = SystemClock.elapsedRealtime()
            if (snapshot == null || snapshot != previous) stableSince = now
            previous = snapshot
            snapshot != null && now - stableSince >= 500
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
            val node = device.wait(Until.findObject(By.text(text)), 5_000)
            assertNotNull("$text must remain on screen with the keyboard open", node)
            val bounds = requireNotNull(node).visibleBounds
            assertTrue("$text has visible screen bounds: $bounds", bounds.width() > 0 && bounds.height() > 0)
            assertTrue("$text must be below status bar: $bounds", bounds.top >= statusBottom)
            assertTrue("$text must be above keyboard: $bounds", bounds.bottom <= keyboardTop)
        }
    }

    @Test fun profileProvidesExplicitLoginAndImportBoundary() {
        compose.onNodeWithTag("nav_PROFILE").performClick()
        compose.onNodeWithTag("account_email").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("account_password").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("account_login").performScrollTo().assertIsDisplayed()
    }

    @Test fun updatesAreReachableWithoutLoginAndSurviveRecreation() {
        compose.onNodeWithTag("nav_PROFILE").performClick()
        compose.onNodeWithTag("open_updates").performScrollTo().performClick()
        compose.onNodeWithText("版本与更新").assertIsDisplayed()
        compose.onNodeWithTag("update_check").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("版本与更新").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("open_updates").performScrollTo().assertIsDisplayed()
    }

    @Test fun localReviewAndBackupEntryReturnsToProfile() {
        compose.onNodeWithTag("nav_PROFILE").performClick()
        compose.onNodeWithText("回顾、分享与备份").performScrollTo().performClick()
        compose.onNodeWithText("回顾与备份").assertIsDisplayed()
        compose.onNodeWithText("年度报告").assertIsDisplayed()
        compose.onNodeWithText("导入预览").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("account_email").performScrollTo().assertIsDisplayed()
    }

    @Test fun editorDraftSurvivesBackAndRecreation() {
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithTag("create_record").performClick()
        compose.onNodeWithTag("quote_input").performTextInput("年底读完十二本书")
        compose.activityRule.scenario.recreate()
        waitForStableEditorLayout()
        captureProbe("probe-draft-recreated")
        compose.onNodeWithTag("quote_input").performScrollTo()
        compose.onNodeWithText("年底读完十二本书").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("你的镖库").assertIsDisplayed()
        compose.onNodeWithTag("create_record").performClick()
        waitForStableEditorLayout()
        compose.onNodeWithTag("quote_input").performScrollTo()
        compose.onNodeWithText("年底读完十二本书").assertIsDisplayed()
    }

    @Test fun selectedDestinationSurvivesRecreation() {
        compose.onNodeWithTag("nav_AI").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("AI 助手").assertIsDisplayed()
    }

    @Test fun offlineCreateEditAndDelete() = exerciseOfflineCreateEditAndDelete("shell", true)

    @Test fun realSystemNightModeOfflineFlow() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val initial = device.executeShellCommand("cmd uimode night").trim()
        val originalMode = Regex("^Night mode: (yes|no|auto)$").matchEntire(initial)?.groupValues?.get(1)
            ?: error("Cannot safely restore unsupported system night mode: $initial")
        var changed = false
        try {
            if (originalMode != "yes") {
                changed = true // Restore even if the shell command changes mode before reporting an error.
                val result = device.executeShellCommand("cmd uimode night yes").trim()
                assertTrue("Could not enable system night mode: $result", result == "Night mode: yes")
            }
            waitForNightAppearance(true)
            exerciseOfflineCreateEditAndDelete("dark-shell", false)
            compose.onNodeWithTag("nav_HOME").performClick()
            compose.onNodeWithText("回旋镖").assertIsDisplayed()
            captureScreenshot("dark-shell-home")
        } finally {
            if (changed) {
                val result = device.executeShellCommand("cmd uimode night $originalMode").trim()
                assertTrue("Could not restore system night mode: $result", result == "Night mode: $originalMode")
                if (originalMode != "auto") waitForNightAppearance(originalMode == "yes")
            }
        }
    }

    @Test fun realSecondaryPagesLightAndSystemDarkScreenshots() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val initial = device.executeShellCommand("cmd uimode night").trim()
        val originalMode = Regex("^Night mode: (yes|no|auto)$").matchEntire(initial)?.groupValues?.get(1)
            ?: error("Cannot safely restore unsupported system night mode: $initial")
        var changed = false
        try {
            for (dark in listOf(false, true)) {
                val target = if (dark) "yes" else "no"
                if (originalMode != target) changed = true
                val result = device.executeShellCommand("cmd uimode night $target").trim()
                assertTrue("Could not set system night mode: $result", result == "Night mode: $target")
                waitForNightAppearance(dark)
                val label = if (dark) "dark" else "light"
                compose.onNodeWithTag("nav_PROFILE").performClick()
                compose.onNodeWithText("我的空间").assertIsDisplayed()
                captureScreenshot("secondary-real-profile-$label")
                compose.onNodeWithTag("open_updates").performScrollTo().performClick()
                compose.onNodeWithText("版本与更新").assertIsDisplayed()
                captureScreenshot("secondary-real-updates-$label")
                compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithText("回顾、分享与备份").performScrollTo().performClick()
                compose.onNodeWithText("回顾与备份").assertIsDisplayed()
                captureScreenshot("secondary-real-extras-$label")
                compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithTag("nav_AI").performClick()
                compose.onNodeWithText("AI 助手").assertIsDisplayed()
                captureScreenshot("secondary-real-ai-signed-out-$label")
            }
        } finally {
            if (changed) {
                val result = device.executeShellCommand("cmd uimode night $originalMode").trim()
                assertTrue("Could not restore system night mode: $result", result == "Night mode: $originalMode")
                if (originalMode != "auto") waitForNightAppearance(originalMode == "yes")
            }
        }
    }

    private fun exerciseOfflineCreateEditAndDelete(evidencePrefix: String, legacyProbe: Boolean) {
        val original = "离线闭环 ${System.nanoTime()}"
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithTag("create_record").performClick()
        compose.onNodeWithTag("quote_input").performClick().performTextInput(original)
        waitForIme(true)
        compose.onNodeWithTag("back").assertIsDisplayed()
        compose.onNodeWithTag("save_record").assertIsDisplayed()
        if (legacyProbe) captureProbe("probe-editor-keyboard")
        if (!legacyProbe) captureScreenshot("$evidencePrefix-editor-keyboard")
        assertEditorActionsInsideVisibleWindow()
        if (legacyProbe) captureScreenshot("$evidencePrefix-editor-keyboard")
        compose.onNodeWithTag("save_record").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("detail_original")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_original").assertTextEquals(original)
        waitForIme(false)
        captureScreenshot("$evidencePrefix-detail")
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("search").assertIsDisplayed()
        compose.onNodeWithText(original).performScrollTo().assertIsDisplayed()
        captureScreenshot("$evidencePrefix-library")
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
