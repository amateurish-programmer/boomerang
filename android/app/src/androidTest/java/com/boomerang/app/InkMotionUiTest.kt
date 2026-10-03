package com.boomerang.app

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.boomerang.app.data.*
import com.boomerang.app.domain.InkMotionMode
import com.boomerang.app.domain.RecordContent
import com.boomerang.app.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic screens, namespaced preferences and an in-memory Room store. Never creates ShellVM. */
@RunWith(AndroidJUnit4::class)
class InkMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val record = RecordEntity("native-fixture", "synthetic", RecordContent(
        originalText = "本月读完一本书，写下阅读笔记。", subject = "合成人物", recordType = "PROMISE",
        dueStart = "2026-10-01", dueEnd = "2026-10-31", datePrecision = "MONTH", dateText = "本月",
        verificationCriteria = "以阅读笔记为准。"
    ), 1, createdAt = "2026-10-01T00:00:00Z", updatedAt = "2026-10-01T00:00:00Z")

    private fun advance(ms: Long = 64) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun hero() = compose.onNodeWithTag("ink_landscape")
    private fun assertMode(mode: InkMotionMode) = hero().assert(
        SemanticsMatcher.expectValue(InkEffectiveModeKey, mode.name))
    private fun assertRunning(value: Boolean) = hero().assert(
        SemanticsMatcher.expectValue(InkMotionRunningKey, value))
    private fun assertFeedback(value: Boolean) = compose.onNodeWithTag("ink_save_feedback").assert(
        SemanticsMatcher.expectValue(InkFeedbackActiveKey, value))
    private fun list() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))

    @Composable private fun Home(
        mode: InkMotionMode = InkMotionMode.FULL, scale: Float = 1f, signal: Long? = null,
        consume: (Long) -> Unit = {}, entered: () -> Unit = {}, create: () -> Unit = {},
        open: (String) -> Unit = {}, records: List<RecordEntity> = listOf(record),
    ) {
        HomeScreen(LibraryState(false, records), { "合成倒计时" }, create, open, {},
            motionMode = mode, systemAnimationScale = scale, pendingSaveFeedback = signal,
            onConsumeSaveFeedback = consume, onEntranceShown = entered, dateLabel = "10月3日 · 合成日期")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "acceptance/ink-native")
        assertTrue(directory.isDirectory || directory.mkdirs())
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "$name.png")))
    }

    @Test fun settingsRadioSelectionCallsBackOncePerClickAndKeeps48dpTargets() {
        var selected by mutableStateOf(InkMotionMode.FULL)
        val callbacks = mutableListOf<InkMotionMode>()
        compose.setContent { BoomerangTheme(false) {
            // Not-ready account avoids NotificationCenter and all business data.
            AccountScreen(AccountUiState(), "synthetic", false, { _, _ -> }, { _, _ -> }, {}, {}, {}, {},
                { _, _ -> }, {}, motionMode = selected, systemAnimationScale = 1f,
                onMotionMode = { callbacks += it; selected = it })
        } }
        compose.onNodeWithTag("motion_FULL").performScrollTo().assertIsSelected()
        listOf(InkMotionMode.REDUCED, InkMotionMode.OFF, InkMotionMode.FULL).forEach { mode ->
            val choice = compose.onNodeWithTag("motion_${mode.name}")
            choice.performScrollTo().assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
            InkMotionMode.entries.filter { it != mode }.forEach {
                compose.onNodeWithTag("motion_${it.name}").assertIsNotSelected()
            }
        }
        compose.runOnIdle { assertEquals(listOf(InkMotionMode.REDUCED, InkMotionMode.OFF, InkMotionMode.FULL), callbacks) }
        capture("settings-selected-full")
    }

    @Test fun systemUpperLimitChangesEffectiveModeWithoutOverwritingChoice() {
        compose.mainClock.autoAdvance = false
        var scale by mutableFloatStateOf(1f)
        var selected by mutableStateOf(InkMotionMode.FULL)
        compose.setContent { BoomerangTheme(false) { Home(selected, scale) } }
        advance(); assertMode(InkMotionMode.FULL)
        compose.runOnIdle { scale = .5f }; advance(); assertMode(InkMotionMode.REDUCED)
        compose.runOnIdle { scale = 0f }; advance(); assertMode(InkMotionMode.OFF)
        compose.runOnIdle { assertEquals(InkMotionMode.FULL, selected); scale = 1f }
        advance(); assertMode(InkMotionMode.FULL)
        compose.runOnIdle { selected = InkMotionMode.REDUCED }; advance(); assertMode(InkMotionMode.REDUCED)
        compose.runOnIdle { selected = InkMotionMode.OFF }; advance(); assertMode(InkMotionMode.OFF)
    }

    @Test fun forcedThemesChooseOriginalArtworkAndHaveNoBrandSeal() {
        var dark by mutableStateOf(false)
        compose.setContent { BoomerangTheme(dark) { Surface(Modifier.fillMaxSize()) { Home(InkMotionMode.OFF) } } }
        hero().assert(SemanticsMatcher.expectValue(InkArtworkKey, "light"))
        hero().assert(hasClickAction().not())
        compose.onNodeWithTag("brand_seal").assertDoesNotExist()
        compose.onNodeWithText("回旋镖").assertIsDisplayed()
        capture("home-light-off")
        compose.runOnIdle { dark = true }
        hero().assert(SemanticsMatcher.expectValue(InkArtworkKey, "dark"))
        compose.onNodeWithTag("brand_seal").assertDoesNotExist()
        capture("home-dark-off")
    }

    @Test fun short360dpWindowAt200PercentKeepsRecordCreateAndRealSaveSingleShot() {
        val database = Room.inMemoryDatabaseBuilder(compose.activity, BoomerangDatabase::class.java).build()
        val repository = BoomerangRepository(database, "synthetic")
        var editing by mutableStateOf(false)
        var signal by mutableStateOf<Long?>(null)
        var creates = 0
        var saves = 0
        var consumed = 0
        var opened = ""
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    BoomerangTheme(false) { Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.safeDrawingPadding().requiredSize(360.dp, 360.dp)) {
                            if (editing) RecordEditor(EditorState(record.content), emptyMap(), false, null, {}, {}, {
                                saves++
                                runBlocking { repository.save(record.content, emptyList()) }
                                signal = 1L // Receipt exists only after the real isolated transaction succeeds.
                                editing = false
                            }, { editing = false })
                            else Home(InkMotionMode.OFF, signal = signal,
                                consume = { consumed++; signal = null }, create = { creates++; editing = true },
                                open = { opened = it })
                        }
                    } }
                }
            }
            compose.onNodeWithTag("create_record").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            hero().assert(hasClickAction().not())
            list().performScrollToNode(hasText(record.content.originalText))
            compose.onNodeWithText(record.content.originalText).assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(record.id, opened) }
            capture("home-short-window-200-percent-record")
            list().performScrollToIndex(0)
            capture("home-short-window-200-percent-hero")
            compose.onNodeWithTag("create_record").assertIsDisplayed().performClick()
            compose.onNodeWithTag("save_record").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(1, creates); assertEquals(1, saves); assertEquals(1, consumed) }
            assertFeedback(false)
            runBlocking {
                val stored = repository.exportRecords().single()
                assertEquals(record.content.originalText, stored.content.originalText)
                assertEquals(1, repository.detail(stored.id)!!.history.size)
                assertEquals(1, database.records().outbox().size)
            }
        } finally { database.close() }
    }

    @Test fun actualValidationFailureAndEditorCancellationProduceNoSuccessReceipt() {
        val database = Room.inMemoryDatabaseBuilder(compose.activity, BoomerangDatabase::class.java).build()
        val repository = BoomerangRepository(database, "synthetic")
        var editing by mutableStateOf(false)
        var errors by mutableStateOf(emptyMap<String, String>())
        var consumed = 0
        var attempts = 0
        try {
            compose.setContent { BoomerangTheme(false) {
                if (editing) RecordEditor(EditorState(), errors, false, null, {}, {}, {
                    attempts++
                    try { runBlocking { repository.save(RecordContent(), emptyList()) }; fail("invalid save accepted") }
                    catch (error: ValidationException) { errors = error.errors }
                }, { editing = false })
                else Home(InkMotionMode.OFF, consume = { consumed++ }, create = { editing = true })
            } }
            compose.onNodeWithTag("create_record").performClick()
            compose.onNodeWithTag("save_record").performClick()
            compose.runOnIdle { assertTrue(errors.isNotEmpty()); assertEquals(1, attempts) }
            compose.onNodeWithTag("back").performClick()
            assertFeedback(false)
            compose.onNodeWithTag("create_record").performClick()
            compose.onNodeWithTag("back").performClick()
            compose.runOnIdle { assertEquals(0, consumed); assertEquals(1, attempts) }
            runBlocking { assertTrue(repository.exportRecords().isEmpty()); assertTrue(database.records().outbox().isEmpty()) }
        } finally { database.close() }
    }

    @Test fun fullDrawingChangesAcrossFramesAndOffDrawingStaysStatic() {
        compose.mainClock.autoAdvance = false
        var mode by mutableStateOf(InkMotionMode.FULL)
        val acceptedFrames = AtomicInteger()
        compose.setContent { CompositionLocalProvider(LocalInkFrameObserver provides { acceptedFrames.incrementAndGet(); Unit }) {
            BoomerangTheme(false) { Home(mode) }
        } }
        advance(1_200)
        assertRunning(true)
        val full = hero().captureToImage()
        capture("home-full-frame-a")
        advance(2_000)
        assertFalse("FULL must draw changing decorations", samePixels(full, hero().captureToImage()))
        capture("home-full-frame-b")
        val fullFrames = acceptedFrames.get()
        assertTrue("FULL fixture must observe accepted decorative frames", fullFrames > 0)
        compose.runOnIdle { mode = InkMotionMode.OFF }; advance()
        assertMode(InkMotionMode.OFF)
        assertRunning(false)
        awaitCommittedFrame()
        val off = hero().captureToImage()
        val offStartFrames = acceptedFrames.get()
        advance(2_000)
        assertMode(InkMotionMode.OFF)
        assertRunning(false)
        awaitCommittedFrame()
        val after = hero().captureToImage()
        val offEndFrames = acceptedFrames.get()
        val unchanged = samePixels(off, after)
        if (!unchanged) saveOffPixelFailure(off, after, fullFrames, offStartFrames, offEndFrames)
        assertTrue("OFF must remain static across clock advancement", unchanged)
        assertEquals("OFF must accept zero additional decorative frames", offStartFrames, offEndFrames)
    }

    private fun awaitCommittedFrame() {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // MainTestClock advances Compose state; Android submits hardware draws separately.
        // API 26 and software rendering retain the existing idle/capture/strict pixel checks.
        if (Build.VERSION.SDK_INT >= 29) {
            val committed = AtomicBoolean(false)
            val latch = CountDownLatch(1)
            val callback = Runnable { committed.set(true); latch.countDown() }
            var observer: ViewTreeObserver? = null
            try {
                instrumentation.runOnMainSync {
                    val decor = compose.activity.window.decorView
                    if (decor.isHardwareAccelerated) {
                        val tree = decor.viewTreeObserver
                        check(tree.isAlive) { "Host decor ViewTreeObserver must be alive" }
                        observer = tree
                        tree.registerFrameCommitCallback(callback)
                        decor.invalidate()
                    }
                }
                if (observer != null) {
                    assertTrue("Host hardware frame did not commit within 5 seconds", latch.await(5, TimeUnit.SECONDS))
                    assertTrue("Host hardware frame callback must acknowledge submission", committed.get())
                }
            } finally {
                instrumentation.runOnMainSync {
                    observer?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(callback)
                }
            }
        }
    }

    private fun saveOffPixelFailure(before: ImageBitmap, after: ImageBitmap, fullFrames: Int, startFrames: Int, endFrames: Int) {
        // Both original PixelCopy captures already exist before any failure-only file I/O.
        val first = before.toPixelMap()
        val second = after.toPixelMap()
        val width = maxOf(before.width, after.width)
        val height = maxOf(before.height, after.height)
        var count = 0L
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            val inBefore = x < before.width && y < before.height
            val inAfter = x < after.width && y < after.height
            if (inBefore != inAfter || (inBefore && inAfter && first[x, y] != second[x, y])) {
                count++
                minX = minOf(minX, x); minY = minOf(minY, y)
                maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
            }
        }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "acceptance/ink-native")
        check(directory.isDirectory || directory.mkdirs()) { "OFF failure evidence directory unavailable" }
        val prefix = "off-pixel-api${Build.VERSION.SDK_INT}"
        listOf("before" to before, "after" to after).forEach { (label, bitmap) ->
            File(directory, "$prefix-$label.png").outputStream().use {
                check(bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) { "Cannot encode OFF $label capture" }
            }
        }
        File(directory, "$prefix-difference.txt").writeText(
            "before=${before.width}x${before.height}\nafter=${after.width}x${after.height}\n" +
                "differentPixels=$count\nboundsInclusive=$minX,$minY,$maxX,$maxY\n" +
                "fullAcceptedFrames=$fullFrames\noffStartFrames=$startFrames\noffEndFrames=$endFrames\n"
        )
    }

    @Test fun realLifecyclePauseClearsFeedbackAndResumeStartsWithZeroDelta() {
        compose.mainClock.autoAdvance = false
        val deltas = mutableListOf<Long>()
        var signal by mutableStateOf<Long?>(null)
        var consumed = 0
        var entrances = 0
        var feedbackActive = false
        compose.setContent { CompositionLocalProvider(
            LocalInkFrameObserver provides { deltas += it },
            LocalInkFeedbackObserver provides { feedbackActive = it },
        ) {
            BoomerangTheme(false) { Home(signal = signal, consume = { consumed++; signal = null }, entered = { entrances++ }) }
        } }
        advance(1_000); assertRunning(true)
        compose.runOnIdle { signal = 11L }; advance(); assertFeedback(true)
        var count = 0
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            // Paused activities have no registered Compose semantics roots. Inspect retained callbacks.
            compose.mainClock.advanceTimeBy(64)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse("finite feedback clears during real lifecycle pause", feedbackActive)
                count = deltas.size
            }
            compose.mainClock.advanceTimeBy(60_000)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertEquals("no decorative frames while paused", count, deltas.size) }
        } finally { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
        advance(); assertRunning(true); assertFeedback(false)
        assertEquals(0L, deltas.drop(count).first())
        assertTrue(deltas.drop(count).all { it in 0L..50L })
        compose.runOnIdle { assertEquals(1, consumed); assertEquals(1, entrances) }
    }

    @Test fun realWindowFocusLossClearsReducedFeedbackAndDoesNotReplayEntrance() {
        compose.mainClock.autoAdvance = false
        var signal by mutableStateOf<Long?>(null)
        var consumed = 0
        var entrances = 0
        compose.setContent { BoomerangTheme(false) {
            Home(InkMotionMode.REDUCED, signal = signal, consume = { consumed++; signal = null }, entered = { entrances++ })
        } }
        advance(320)
        compose.runOnIdle { signal = 12L }; advance(); assertFeedback(true)
        var dialog: Dialog? = null
        try {
            compose.runOnUiThread { dialog = Dialog(compose.activity).apply { show() } }
            compose.waitUntil(5_000) { !compose.activity.hasWindowFocus() }
            advance(); assertFeedback(false)
            advance(30_000); assertFeedback(false)
        } finally { compose.runOnUiThread { dialog?.dismiss() } }
        compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
        advance(); assertFeedback(false)
        compose.runOnIdle { assertEquals(1, consumed); assertEquals(1, entrances) }
    }

    @Test fun actualScrollOffHeroClearsReducedFeedbackAndPreservesOneShotReceipt() {
        compose.mainClock.autoAdvance = false
        var signal by mutableStateOf<Long?>(null)
        var consumed = 0
        var entrances = 0
        val records = List(8) { index -> record.copy(id = "native-$index", content = record.content.copy(originalText = "合成记录 $index")) }
        compose.setContent { BoomerangTheme(false) {
            Home(InkMotionMode.REDUCED, signal = signal, consume = { consumed++; signal = null },
                entered = { entrances++ }, records = records)
        } }
        advance(320)
        compose.runOnIdle { signal = 13L }; advance(); assertFeedback(true)
        list().performScrollToIndex(5); advance()
        hero().assertDoesNotExist(); assertFeedback(false)
        advance(30_000)
        list().performScrollToIndex(0); advance()
        hero().assertIsDisplayed(); assertFeedback(false)
        compose.runOnIdle { assertEquals(1, consumed); assertEquals(1, entrances) }
    }

    @Test fun leavingHomeStopsFrameCallbacksAndConsumesPendingSuccessOnlyOnReturn() {
        compose.mainClock.autoAdvance = false
        var home by mutableStateOf(true)
        var signal by mutableStateOf<Long?>(null)
        var consumed = 0
        val deltas = mutableListOf<Long>()
        compose.setContent { CompositionLocalProvider(LocalInkFrameObserver provides { deltas += it }) {
            BoomerangTheme(false) {
                if (home) Home(signal = signal, consume = { consumed++; signal = null })
                else RecordEditor(EditorState(record.content), emptyMap(), false, null, {}, {}, {}, {})
            }
        } }
        advance(1_000)
        compose.runOnIdle { home = false }; advance()
        val count = deltas.size
        compose.runOnIdle { signal = 14L }; advance(30_000)
        assertEquals(count, deltas.size)
        compose.onNodeWithTag("ink_save_feedback").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, consumed); home = true }
        advance(); assertFeedback(true)
        advance(600); assertFeedback(false)
        compose.runOnIdle { assertEquals(1, consumed) }
    }

    @Test fun finiteFeedbackHasModeSpecificLifetimeAndOffConsumesWithoutDrawing() {
        compose.mainClock.autoAdvance = false
        var mode by mutableStateOf(InkMotionMode.FULL)
        var signal by mutableStateOf<Long?>(null)
        val consumed = mutableListOf<Long>()
        compose.setContent { BoomerangTheme(false) { Home(mode, signal = signal, consume = { consumed += it; signal = null }) } }
        advance(1_000)
        compose.runOnIdle { signal = 21L }; advance(); assertFeedback(true)
        advance(300); assertFeedback(true) // FULL's 450ms burst is still finite and active here.
        advance(240); assertFeedback(false)
        compose.runOnIdle { mode = InkMotionMode.REDUCED; signal = 22L }; advance(); assertFeedback(true)
        advance(240); assertFeedback(false) // REDUCED's 180ms color effect must have ended.
        compose.runOnIdle { mode = InkMotionMode.OFF; signal = 23L }; advance(); assertFeedback(false)
        advance(1_000); assertFeedback(false)
        compose.runOnIdle { assertEquals(listOf(21L, 22L, 23L), consumed) }
    }

    @Test fun switchingModeDuringFiniteFeedbackClearsOffAndDoesNotReplayReceipt() {
        compose.mainClock.autoAdvance = false
        var mode by mutableStateOf(InkMotionMode.FULL)
        var signal by mutableStateOf<Long?>(null)
        var consumed = 0
        compose.setContent { BoomerangTheme(false) { Home(mode, signal = signal, consume = { consumed++; signal = null }) } }
        advance(1_000)
        compose.runOnIdle { signal = 24L }; advance(); assertFeedback(true)
        compose.runOnIdle { mode = InkMotionMode.OFF }; advance(); assertFeedback(false)
        compose.runOnIdle { mode = InkMotionMode.REDUCED }; advance(); assertFeedback(false)
        compose.runOnIdle { assertEquals(1, consumed) }
    }

    @Test fun replacementReceiptAndReducedModeRestartKeepFiniteFeedbackVisible() {
        compose.mainClock.autoAdvance = false
        var mode by mutableStateOf(InkMotionMode.FULL)
        var signal by mutableStateOf<Long?>(null)
        val consumed = mutableListOf<Long>()
        compose.setContent { BoomerangTheme(false) { Home(mode, signal = signal, consume = { consumed += it; signal = null }) } }
        advance(1_000)
        compose.runOnIdle { signal = 31L }; advance(); assertFeedback(true)
        compose.runOnIdle { signal = 32L }; advance(); assertFeedback(true)
        advance(128); assertFeedback(true)
        compose.runOnIdle { mode = InkMotionMode.REDUCED }; advance(); assertFeedback(true)
        advance(240); assertFeedback(false)
        compose.runOnIdle { assertEquals(listOf(31L, 32L), consumed) }
    }

    @Test fun reducedEntranceIsFiniteAndCancellationFinishesAllLayers() {
        compose.mainClock.autoAdvance = false
        val entrance = InkEntranceState()
        var eligible by mutableStateOf(true)
        var entered = 0
        compose.setContent { BoomerangTheme(false) {
            InkLandscape(InkMotionMode.REDUCED, eligible, false, { entered++ }, entrance, { 0f }, "合成日期")
        } }
        advance(64)
        compose.runOnIdle { assertTrue(entrance.sceneAlpha < 1f); assertTrue(entrance.brandAlpha < 1f); assertTrue(entrance.recordsAlpha < 1f) }
        advance(240)
        compose.runOnIdle { assertEquals(1f, entrance.sceneAlpha); assertEquals(1f, entrance.brandAlpha); assertEquals(1f, entrance.recordsAlpha) }
        compose.runOnIdle { eligible = false }; advance(30_000)
        compose.runOnIdle { eligible = true }; advance()
        compose.runOnIdle { assertEquals(1, entered); assertEquals(1f, entrance.sceneAlpha) }
    }

    @Test fun fullEntranceUsesIndependentDurationsAndPauseFinishesAllLayers() {
        compose.mainClock.autoAdvance = false
        val entrance = InkEntranceState()
        var eligible by mutableStateOf(true)
        compose.setContent { BoomerangTheme(false) {
            InkLandscape(InkMotionMode.FULL, eligible, false, {}, entrance, { 0f }, "合成日期")
        } }
        advance(450)
        compose.runOnIdle {
            assertTrue(entrance.sceneAlpha < entrance.brandAlpha)
            assertTrue(entrance.brandAlpha < 1f)
            assertEquals(1f, entrance.recordsAlpha)
        }
        compose.runOnIdle { eligible = false }; advance()
        compose.runOnIdle { assertEquals(1f, entrance.sceneAlpha); assertEquals(1f, entrance.brandAlpha); assertEquals(1f, entrance.recordsAlpha) }
    }

    @Test fun actualPreferencesPersistDefaultInvalidValuesAndStopListeningAfterClose() {
        val context = IsolatedAppearanceContext(compose.activity)
        val prefs = context.getSharedPreferences("ink_appearance", Context.MODE_PRIVATE)
        try {
            AppearanceRepository(context).use { repository ->
                assertEquals(InkMotionMode.FULL, repository.motionMode.value)
                compose.runOnUiThread { prefs.edit().putString("motion_mode", "unknown").commit() }
                assertEquals(InkMotionMode.FULL, repository.motionMode.value)
                compose.runOnUiThread { prefs.edit().putInt("motion_mode", 99).commit() }
                assertEquals(InkMotionMode.FULL, repository.motionMode.value)
                repository.setMotionMode(InkMotionMode.OFF)
                assertEquals("OFF", prefs.getString("motion_mode", null))
            }
            AppearanceRepository(context).use { repository ->
                assertEquals(InkMotionMode.OFF, repository.motionMode.value)
                compose.runOnUiThread { prefs.edit().putString("motion_mode", "REDUCED").commit() }
                assertEquals(InkMotionMode.REDUCED, repository.motionMode.value)
                repository.close(); repository.close()
                compose.runOnUiThread { prefs.edit().putString("motion_mode", "FULL").commit() }
                repository.setMotionMode(InkMotionMode.OFF)
                assertEquals(InkMotionMode.REDUCED, repository.motionMode.value)
                assertEquals("FULL", prefs.getString("motion_mode", null))
            }
        } finally { context.deleteSharedPreferences("ink_appearance") }
    }

    @Test fun actualSystemObserverUpdatesScalePreservesChoiceAndUnregistersOnClose() {
        // This mutation is emulator-only instrumentation, never a physical-device acceptance action.
        assertTrue("system-scale test requires an emulator", Build.HARDWARE in listOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic"))
        val context = IsolatedAppearanceContext(compose.activity)
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val original = Settings.Global.getString(context.contentResolver, key)
        val repository = AppearanceRepository(context)
        try {
            repository.setMotionMode(InkMotionMode.FULL)
            setSystemScale("0.5")
            assertEquals("0.5", Settings.Global.getString(context.contentResolver, key))
            compose.waitUntil(5_000) { repository.systemScale.value == .5f }
            assertEquals(InkMotionMode.FULL, repository.motionMode.value)
            setSystemScale("0")
            assertEquals("0", Settings.Global.getString(context.contentResolver, key))
            compose.waitUntil(5_000) { repository.systemScale.value == 0f }
            repository.close()
            AppearanceRepository(context).use { witness ->
                setSystemScale("1")
                assertEquals("1", Settings.Global.getString(context.contentResolver, key))
                compose.waitUntil(5_000) { witness.systemScale.value == 1f }
                instrumentation.waitForIdleSync()
                assertEquals("closed observer must not receive changes", 0f, repository.systemScale.value)
                assertEquals(InkMotionMode.FULL, repository.motionMode.value)
            }
        } finally {
            repository.close()
            try {
                setSystemScale(original)
                assertEquals(original, Settings.Global.getString(context.contentResolver, key))
            } finally { context.deleteSharedPreferences("ink_appearance") }
        }
    }

    private fun setSystemScale(value: String?) {
        require(value == null || (value.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) && value.toFloat().isFinite())) {
            "emulator animation scale must be a finite unsigned decimal"
        }
        val command = if (value == null) "settings delete global animator_duration_scale"
            else "settings put global animator_duration_scale $value"
        // Drain and close the shell descriptor so writes complete before observer assertions/restoration.
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun samePixels(a: ImageBitmap, b: ImageBitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val first = a.toPixelMap()
        val second = b.toPixelMap()
        for (y in 0 until a.height) for (x in 0 until a.width) if (first[x, y] != second[x, y]) return false
        return true
    }

    private class IsolatedAppearanceContext(base: Context) : ContextWrapper(base) {
        private val prefix = "ink-native-${UUID.randomUUID()}-"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        override fun deleteSharedPreferences(name: String): Boolean = super.deleteSharedPreferences(prefix + name)
    }
}
