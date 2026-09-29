package com.boomerang.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.boomerang.app.assistant.AssistantModeNavigation
import com.boomerang.app.extras.ImportPreviewDialog
import com.boomerang.app.ui.*
import com.boomerang.app.updates.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/** Synthetic display states; no account, update service, installer or file picker is invoked. */
@RunWith(AndroidJUnit4::class)
class InkSecondaryUiTest {
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val backupFontScale = TestRule { base, description -> object : Statement() {
        override fun evaluate() {
            if (description.methodName != "backupPreviewOffersCancelSkipAndConditionalReplace") {
                base.evaluate()
                return
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val device = UiDevice.getInstance(instrumentation)
            val original = device.executeShellCommand("settings get system font_scale").trim()
            val originalScale = instrumentation.targetContext.resources.configuration.fontScale
            try {
                device.executeShellCommand("settings put system font_scale 2.0")
                awaitFontScale(instrumentation.targetContext.resources, 2f)
                base.evaluate()
            } finally {
                if (original.toFloatOrNull() == null) device.executeShellCommand("settings delete system font_scale")
                else device.executeShellCommand("settings put system font_scale $original")
                awaitFontScale(instrumentation.targetContext.resources, originalScale)
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(backupFontScale).around(compose)
    private val manifest = UpdateManifest(1, "com.boomerang.app", 99, "9.9", 26,
        "https://example.org/synthetic.apk", "0".repeat(64), 2_097_152, "合成更新说明", "2026-09-29T00:00:00Z")

    @Test fun accountToolsRemainReachableInShortLargeTextWindow() {
        var updates = 0
        var extras = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
            BoomerangTheme(false) { Surface {
                AccountScreen(AccountUiState(ready = true), "local", false, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, { _, _ -> }, {},
                    modifier = Modifier.height(360.dp), onUpdates = { updates++ }, onExtras = { extras++ })
            } }
        } }
        compose.onNodeWithTag("open_updates").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("回顾、分享与备份").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, updates); assertEquals(1, extras) }
    }

    @Test fun assistantModeNavigationSwitchesAndBusyDisablesEveryMode() {
        var selected by mutableStateOf("录入")
        var busy by mutableStateOf(false)
        compose.setContent { BoomerangTheme { AssistantModeNavigation(selected, busy) { selected = it } } }
        compose.onNodeWithTag("assistant_mode_调查").performClick()
        compose.runOnIdle { assertEquals("调查", selected); busy = true }
        listOf("录入", "对话", "调查", "复核").forEach { compose.onNodeWithTag("assistant_mode_$it").assertIsNotEnabled() }
    }

    @Test fun updateStatesExposeOnlyAvailableActionsAndCallbacks() {
        var state by mutableStateOf(UpdateUiState(currentVersion = "0.4.2 (7)", stage = UpdateStage.AVAILABLE, manifest = manifest))
        val calls = mutableListOf<String>()
        compose.setContent { BoomerangTheme { UpdateContent(state, {}, {}, { calls += "download" },
            { calls += "cancel" }, { calls += "permission" }, { calls += "install" }) } }
        compose.onNodeWithTag("update_download").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("download"), calls); state = state.copy(stage = UpdateStage.DOWNLOADING, downloaded = 1_048_576, busy = true) }
        compose.onNodeWithTag("update_cancel").performScrollTo().performClick()
        compose.onNodeWithText("已下载 50%").assertExists()
        compose.runOnIdle { assertEquals(listOf("download", "cancel"), calls); state = state.copy(stage = UpdateStage.READY, busy = false, canInstall = false) }
        compose.onNodeWithTag("update_install").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("update_permission").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("download", "cancel", "permission"), calls); state = state.copy(canInstall = true) }
        compose.onNodeWithTag("update_install").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("download", "cancel", "permission", "install"), calls) }
    }

    @Test fun backupPreviewOffersCancelSkipAndConditionalReplace() {
        val calls = mutableListOf<String>()
        var conflicts by mutableIntStateOf(0)
        var busy by mutableStateOf(false)
        compose.setContent { BoomerangTheme { ImportPreviewDialog(5, conflicts,
                List(5) { "合成记录 $it：这是一段用来检查长预览滚动的原话。" }, busy,
                { calls += "skip" }, { calls += "replace" }, { calls += "cancel" },
                modifier = Modifier.height(360.dp).testTag("bounded_import_dialog")) }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        val textLayout = compose.onNodeWithText("确认导入预览").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
        assertTrue(textLayout.action?.invoke(layouts) == true)
        assertEquals(2f, layouts.single().layoutInput.density.fontScale, 0.05f)
        val actualHeight = compose.onNodeWithTag("bounded_import_dialog").fetchSemanticsNode().boundsInRoot.height
        assertTrue(actualHeight > 0 && actualHeight <= 360 * compose.activity.resources.displayMetrics.density + 1)
        compose.onNodeWithText("合成记录 4：这是一段用来检查长预览滚动的原话。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("替换重复并导入").assertDoesNotExist()
        compose.onNodeWithText("取消").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { conflicts = 1 }
        compose.onNodeWithText("跳过重复并导入").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("替换重复并导入").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("cancel", "skip", "replace"), calls) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val evidence = File(instrumentation.targetContext.getExternalFilesDir(null), "acceptance/ink-secondary").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(evidence, "import-preview-large-text-short-synthetic.png")))
        compose.onNodeWithText("取消").performScrollTo().assertIsDisplayed()
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(evidence, "import-preview-large-text-short-cancel-synthetic.png")))
        compose.runOnIdle { busy = true }
        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.onNodeWithText("跳过重复并导入").assertIsNotEnabled()
        compose.onNodeWithText("替换重复并导入").assertIsNotEnabled()
    }

    private fun awaitFontScale(resources: android.content.res.Resources, expected: Float) {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (kotlin.math.abs(resources.configuration.fontScale - expected) > 0.05f &&
            android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
        assertEquals("System font scale did not apply", expected, resources.configuration.fontScale, 0.05f)
    }

    @Test fun syntheticUpdateScreenshotsCoverLightDarkAndLargeText() {
        var dark by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        var state by mutableStateOf(UpdateUiState(currentVersion = "0.4.2 (7)", stage = UpdateStage.AVAILABLE, manifest = manifest))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
            BoomerangTheme(dark) { Surface(if (scale == 2f) Modifier.fillMaxWidth().height(360.dp) else Modifier.fillMaxSize()) {
                UpdateContent(state, {}, {}, {}, {}, {}, {}, Modifier.testTag("update_viewport"))
            } }
        } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val evidence = File(instrumentation.targetContext.getExternalFilesDir(null), "acceptance/ink-secondary").apply { mkdirs() }
        fun capture(name: String) { compose.waitForIdle(); assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(evidence, "$name.png"))) }
        capture("update-light-synthetic")
        compose.runOnIdle { dark = true }; capture("update-dark-synthetic")
        compose.runOnIdle { dark = false; scale = 2f; state = state.copy(stage = UpdateStage.READY, canInstall = false) }
        val actualHeight = compose.onNodeWithTag("update_viewport").fetchSemanticsNode().boundsInRoot.height
        assertTrue(actualHeight > 0 && actualHeight <= 360 * compose.activity.resources.displayMetrics.density + 1)
        compose.onNodeWithTag("update_permission").performScrollTo().assertIsDisplayed()
        capture("update-large-text-short-synthetic")
        compose.runOnIdle { state = state.copy(canInstall = true) }
        compose.onNodeWithTag("update_install").performScrollTo().assertIsDisplayed()
        capture("update-large-text-short-install-synthetic")
    }
}
