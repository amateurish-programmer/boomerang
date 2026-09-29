package com.boomerang.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boomerang.app.data.*
import com.boomerang.app.domain.RecordContent
import com.boomerang.app.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated synthetic fixtures; never opens the account database or network. */
@RunWith(AndroidJUnit4::class)
class InkUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val record = RecordEntity("ink-fixture", "synthetic", RecordContent(
        originalText = "九月结束前，读完一本书，留下自己的读书笔记。", subject = "林川",
        recordType = "PROMISE", dueStart = "2026-09-01", dueEnd = "2026-09-30",
        datePrecision = "MONTH", dateText = "九月", verificationCriteria = "以完整的阅读笔记为准。"
    ), 1, createdAt = "2026-09-01T00:00:00Z", updatedAt = "2026-09-01T00:00:00Z")

    @Test fun filterCancellationDoesNotCommitAndApplyCommitsBothChoices() {
        var selectedType by mutableStateOf("")
        var selectedResult by mutableStateOf("")
        compose.setContent { BoomerangTheme(false) {
            LibraryScreen(LibraryState(false, listOf(record)), listOf(record), "", selectedType, selectedResult, "due",
                {}, { selectedType = it }, { selectedResult = it }, {}, { "明天到期" }, {}, {}, {})
        } }
        compose.onNodeWithTag("open_filters").performClick()
        compose.onNodeWithTag("filter_type_PROMISE").performScrollTo().performClick()
        compose.onNodeWithTag("cancel_filters").performClick()
        compose.runOnIdle { assertEquals("", selectedType); assertEquals("", selectedResult) }
        compose.onNodeWithTag("open_filters").performClick()
        compose.onNodeWithTag("filter_type_PROMISE").performScrollTo().performClick()
        compose.onNodeWithTag("filter_result_FULFILLED").performScrollTo().performClick()
        compose.onNodeWithTag("apply_filters").performClick()
        compose.runOnIdle { assertEquals("PROMISE", selectedType); assertEquals("FULFILLED", selectedResult) }
    }

    @Test fun detailTabsKeepEditReachableAndDeletionRequiresMenuAndConfirmation() {
        var edits = 0
        var deletes = 0
        compose.setContent { BoomerangTheme(false) {
            RecordDetailScreen(RecordDetail(record, emptyList(), emptyList()), "明天到期",
                listOf(HistoryDisplay(1, "2026-09-01", listOf("原话" to "历史原话"))), false, null,
                { edits++ }, { deletes++ }, {})
        } }
        compose.onNodeWithTag("delete_record").assertDoesNotExist()
        compose.onNodeWithTag("detail_tab_sources").performScrollTo().performClick()
        compose.onNodeWithTag("edit_record").assertIsDisplayed().performClick()
        compose.onNodeWithTag("detail_tab_history").performScrollTo().performClick()
        compose.onNodeWithText("第 1 版 · 2026-09-01").performScrollTo().performClick()
        compose.onNodeWithText("历史原话").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("edit_record").assertIsDisplayed().performClick()
        compose.onNodeWithTag("detail_more").performClick()
        compose.onNodeWithTag("delete_record").performClick()
        compose.runOnIdle { assertEquals(2, edits); assertEquals(0, deletes) }
        compose.onNodeWithTag("confirm_delete").performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun editorSaveRemainsVisibleAtBottomAndHiddenErrorsExpand() {
        var saves = 0
        compose.setContent { BoomerangTheme(false) {
            RecordEditor(EditorState(record.content), mapOf("notes" to "备注过长"), false, null,
                {}, {}, { saves++ }, {})
        } }
        compose.onNodeWithTag("notes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("备注过长").assertIsDisplayed()
        compose.onNodeWithTag("save_record").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun busyEditorDisablesTypeBackInputsAndSave() {
        compose.setContent { BoomerangTheme(false) {
            RecordEditor(EditorState(record.content), emptyMap(), true, null, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("back").assertIsNotEnabled()
        compose.onNodeWithText("类型：承诺").assertIsNotEnabled()
        compose.onNodeWithTag("quote_input").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("save_record").assertIsNotEnabled()
    }

    @Test fun syntheticScreenshotsLightDarkAndLargeText() {
        var page by mutableStateOf("home")
        var dark by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                BoomerangTheme(dark) { Surface(Modifier.fillMaxSize()) {
                    when (page) {
                        "home" -> HomeScreen(LibraryState(false, listOf(record)), { "明天到期" }, {}, {}, {})
                        "library" -> LibraryScreen(LibraryState(false, listOf(record)), listOf(record), "", "", "", "due",
                            {}, {}, {}, {}, { "明天到期" }, {}, {}, {})
                        "detail" -> RecordDetailScreen(RecordDetail(record, emptyList(), emptyList()), "明天到期", emptyList(), false, null, {}, {}, {})
                        else -> RecordEditor(EditorState(record.content), emptyMap(), false, null, {}, {}, {}, {})
                    }
                } }
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val evidence = File(instrumentation.targetContext.getExternalFilesDir(null), "acceptance/ink-ui").apply { mkdirs() }
        val device = UiDevice.getInstance(instrumentation)
        fun capture(name: String) {
            compose.waitForIdle()
            org.junit.Assert.assertTrue(device.takeScreenshot(File(evidence, "$name.png")))
        }
        capture("home-light")
        compose.runOnIdle { page = "library" }; capture("library-light")
        compose.runOnIdle { page = "detail" }; capture("detail-light")
        compose.runOnIdle { dark = true }; capture("detail-dark")
        compose.runOnIdle { dark = false; fontScale = 1.5f; page = "editor" }
        compose.onNodeWithTag("save_record").assertIsDisplayed()
        capture("editor-large-text")
    }
}
