package com.boomerang.app.extras

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.boomerang.app.data.BoomerangDatabase
import com.boomerang.app.data.BoomerangRepository
import com.boomerang.app.domain.RecordContent
import com.boomerang.app.domain.SourceInput
import com.boomerang.app.ui.BoomerangTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

/** Real DocumentsUI, ActivityResult, ContentResolver and chooser; every record is synthetic. */
@RunWith(AndroidJUnit4::class)
class ExtrasSystemTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val owner = UUID.randomUUID().toString()
    private val otherOwner = UUID.randomUUID().toString()
    private val visibleOwner = mutableStateOf(owner)
    private lateinit var model: ExtrasViewModel
    private lateinit var database: BoomerangDatabase
    private lateinit var records: BoomerangRepository
    private lateinit var recordId: String
    private val documentNames = mutableListOf<String>()
    private val cacheFiles = mutableListOf<File>()
    private val evidence get() = File(context.getExternalFilesDir(null), "acceptance").apply { mkdirs() }
    private val original = "系统验收样例：年底读完十二本书，整理每本书的阅读记录。"

    @get:Rule val failureEvidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching {
                device.takeScreenshot(File(evidence, "p8-api${Build.VERSION.SDK_INT}-${description.methodName}-failure.png"))
                device.dumpWindowHierarchy(File(evidence, "p8-api${Build.VERSION.SDK_INT}-${description.methodName}-failure.xml"))
            }
        }
    }

    @Before fun setUp(): Unit {
        database = BoomerangDatabase.open(context, owner)
        records = BoomerangRepository(database, owner)
        recordId = runBlocking {
            records.save(
                RecordContent(originalText = original, subject = "验收样例", notes = "PRIVATE_NOTE_NEVER_ON_SHARE_CARD",
                    saidAt = "2026-01-01", dueStart = "2026-12-31", dueEnd = "2026-12-31", datePrecision = "DAY"),
                listOf(SourceInput("示例来源（合成验收资料）", "https://example.org/reading-plan")),
            )
        }
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[ExtrasViewModel::class.java]
        }
        compose.setContent { BoomerangTheme { ExtrasScreen(visibleOwner.value, {}, model = model) } }
        awaitIdle(owner)
    }

    @After fun tearDown(): Unit {
        // Clear the host's ViewModel first, so any Room work is cancelled before removing test DBs.
        compose.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        database.close()
        context.deleteDatabase("boomerang_$owner.db")
        context.deleteDatabase("boomerang_$otherOwner.db")
        cacheFiles.forEach { it.delete() }
        // These exact UUID names were created by this test in the system Downloads provider.
        documentNames.forEach { name -> device.executeShellCommand("rm -f /sdcard/Download/$name") }
    }

    @Test fun downloadsRoundTripRequiresPreviewAndExplicitConfirmation(): Unit {
        val name = exportToDownloads()
        runBlocking { records.save(RecordContent(originalText = "预览前本机编辑"), emptyList(), recordId, 1) }
        val before = snapshot()

        openFromDownloads(name)
        awaitPreview()
        assertEquals(original, model.state.value.preview!!.backup.records.single().record.content.originalText)
        assertEquals("PRIVATE_NOTE_NEVER_ON_SHARE_CARD", model.state.value.preview!!.backup.records.single().record.content.notes)
        assertEquals(1, model.state.value.preview!!.conflicts)
        assertEquals(before, snapshot())
        capture("import-preview")
        compose.onNodeWithText("取消").performClick()
        assertNull(model.state.value.preview)
        assertEquals(before, snapshot())

        openFromDownloads(name)
        awaitPreview()
        compose.onNodeWithText("跳过重复并导入").performClick()
        awaitMessage("已导入 0 条记录")
        assertEquals(before, snapshot())

        openFromDownloads(name)
        awaitPreview()
        compose.onNodeWithText("替换重复并导入").performClick()
        awaitMessage("已导入 1 条记录")
        val restored = snapshot()
        assertEquals(original, restored.first.record.content.originalText)
        assertNull(restored.first.record.content.confirmedStatus)
        assertEquals(4, restored.first.history.size)
        assertTrue(restored.first.history.any { it.snapshotJson.contains("预览前本机编辑") })
        assertEquals(1, restored.second.size)
        assertEquals(restored.first.record.localRevision, restored.second.single().localRevision)
    }

    @Test fun cancellingSystemPickersLeavesRecordsHistoryAndQueueUnchanged(): Unit {
        val before = snapshot()
        click("导出 JSON")
        awaitDocuments()
        cancelDocuments()
        assertEquals(before, snapshot())
        assertNull(model.state.value.message)

        click("导入预览")
        awaitDocuments()
        cancelDocuments()
        assertNull(model.state.value.preview)
        assertNull(model.state.value.message)
        assertEquals(before, snapshot())
    }

    @Test fun freshRestoreWritesRecordsHistoryAndQueueOnlyAfterConfirmation(): Unit {
        val name = exportToDownloads()
        database.clearAllTables()
        compose.runOnUiThread { model.refresh() }
        awaitIdle(owner)
        openFromDownloads(name)
        awaitPreview()
        assertEquals(0, model.state.value.preview!!.conflicts)
        runBlocking {
            assertTrue(database.records().allIncludingDeleted().isEmpty())
            assertTrue(database.records().outbox().isEmpty())
        }
        compose.onNodeWithText("跳过重复并导入").performClick()
        awaitMessage("已导入 1 条记录")
        val restored = snapshot()
        assertEquals(original, restored.first.record.content.originalText)
        assertEquals(2, restored.first.history.size)
        assertNull(restored.first.record.content.confirmedStatus)
        assertEquals(1, restored.first.sources.size)
        assertEquals(1, restored.second.size)
    }

    @Test fun fileChosenAfterOwnerSwitchCannotPreviewOrImportIntoNewOwner(): Unit {
        val name = exportToDownloads()
        val before = snapshot()
        click("导入预览")
        val documents = awaitDocuments()
        chooseDownloads(documents)
        switchOwner()
        selectDocument(name)
        awaitApplication()
        awaitIdle(otherOwner)
        assertNull(model.state.value.preview)
        assertNull(model.state.value.message)
        assertTrue(model.state.value.records.isEmpty())
        assertEquals(before, snapshot())
        assertOtherOwnerEmpty()
    }

    @Test fun exportDestinationReturnedAfterOwnerSwitchReceivesNoAccountData(): Unit {
        val name = nextDocumentName()
        click("导出 JSON")
        val documents = awaitDocuments()
        chooseDownloads(documents)
        switchOwner()
        saveDocument(documents, name)
        awaitApplication()
        awaitIdle(otherOwner)
        assertNull(model.state.value.message)
        assertOtherOwnerEmpty()
        assertEquals("Old account callback must not write any bytes", "0",
            device.executeShellCommand("stat -c %s /sdcard/Download/$name").trim())
        // Android created the requested empty document. Reopening through the real picker must
        // fail parsing, rather than contain a backup from either account.
        openFromDownloads(name)
        compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.message != null }
        assertNull(model.state.value.preview)
        assertOtherOwnerEmpty()
    }

    @Test fun contentResolverRejectsCorruptOversizedAndWrongOwnerFilesWithoutWriting(): Unit {
        val before = snapshot()
        val backup = runBlocking { ExtrasRepository(database, owner).export() }
        val samples = listOf(
            "corrupt" to "{broken".toByteArray(),
            "oversized" to ByteArray(BackupCodec.MAX_BYTES + 1) { ' '.code.toByte() },
            "other-owner" to BackupCodec.encode(backup.copy(owner = otherOwner)),
        )
        samples.forEach { (label, bytes) ->
            val file = File(context.cacheDir, "share_cards/test-${UUID.randomUUID()}-$label.json")
            file.parentFile!!.mkdirs()
            file.writeBytes(bytes)
            cacheFiles += file
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
            compose.runOnUiThread { model.previewImport(uri, owner) }
            compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.message != null }
            assertNull("$label must not produce an import preview", model.state.value.preview)
            assertEquals("$label must leave records/history/outbox intact", before, snapshot())
        }
    }

    @Test fun pngPreviewUsesRestrictedFileProviderAndOpensSystemChooserOnlyOnClick(): Unit {
        click("预览回旋卡")
        compose.waitUntil(15_000) { model.state.value.card != null && !model.state.value.busy }
        compose.onNodeWithText("回旋卡预览（不含私人备注）").assertIsDisplayed()
        assertEquals(context.packageName, device.currentPackageName)
        val file = model.state.value.card!!
        cacheFiles += file
        val png = file.readBytes()
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), png.take(8).toByteArray())
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)!!
        assertEquals(1080, bitmap.width)
        assertTrue(bitmap.height > 200)
        val background = bitmap.getPixel(0, 0)
        var inkPixels = 0
        for (y in 100 until bitmap.height - 100 step 4) {
            for (x in 100 until bitmap.width - 100 step 4) if (bitmap.getPixel(x, y) != background) inkPixels++
        }
        bitmap.recycle()
        assertTrue("PNG must contain rendered text, not just a background", inkPixels > 200)
        val detail = runBlocking { records.detail(recordId)!! }
        assertFalse(ShareCard.text(detail).contains("PRIVATE_NOTE_NEVER_ON_SHARE_CARD"))
        file.copyTo(File(evidence, "p8-api${Build.VERSION.SDK_INT}-share-card.png"), overwrite = true)
        capture("share-preview")

        val send = ShareCard.intent(context, file)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.share", uri.authority)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals("image/png", context.contentResolver.getType(uri))
        assertArrayEquals(png, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        val outside = File(context.filesDir, "private-${UUID.randomUUID()}.txt").apply { writeText("SYNTHETIC_PRIVATE") }
        cacheFiles += outside
        assertTrue(runCatching { FileProvider.getUriForFile(context, "${context.packageName}.share", outside) }.exceptionOrNull() is IllegalArgumentException)

        compose.onNodeWithText("打开系统分享").performClick()
        compose.waitUntil(10_000) {
            device.executeShellCommand("dumpsys activity activities").lineSequence().any {
                (it.contains("mResumedActivity") || it.contains("topResumedActivity")) && it.contains("ChooserActivity")
            }
        }
        // Activity resume precedes the chooser's window/content transition. API 26/33 use
        // the framework package; API 35 uses IntentResolver, retaining the framework IDs.
        compose.waitUntil(10_000) {
            val foreground = device.currentPackageName
            foreground != null && foreground in setOf("android", "com.android.intentresolver") && (
                device.hasObject(By.pkg(foreground).res("android", "resolver_list")) ||
                    device.hasObject(By.pkg(foreground).res("android", "chooser_header")) ||
                    device.hasObject(By.pkg(foreground).text("分享回旋卡"))
                )
        }
        device.waitForIdle()
        device.dumpWindowHierarchy(File(evidence, "p8-api${Build.VERSION.SDK_INT}-system-chooser.xml"))
        capture("system-chooser")
        device.pressBack()
        awaitApplication()
        compose.onNodeWithText("关闭").performClick()
        assertNull(model.state.value.card)
    }

    private fun snapshot() = runBlocking {
        records.detail(recordId)!! to database.records().outbox()
    }

    private fun assertOtherOwnerEmpty(): Unit = runBlocking {
        val other = BoomerangDatabase.open(context, otherOwner)
        try {
            assertTrue(other.records().allIncludingDeleted().isEmpty())
            assertTrue(other.records().outbox().isEmpty())
        } finally { other.close() }
    }

    private fun switchOwner(): Unit {
        // Simulates only the local owner transition; never signs in or sends network traffic.
        compose.runOnUiThread { visibleOwner.value = otherOwner; model.selectOwner(otherOwner) }
        awaitIdle(otherOwner)
    }

    private fun awaitIdle(expectedOwner: String): Unit {
        compose.waitUntil(15_000) { model.state.value.owner == expectedOwner && !model.state.value.busy }
    }

    private fun awaitMessage(message: String): Unit {
        compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.message == message }
    }

    private fun awaitPreview(): Unit {
        compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.preview != null }
        compose.onNodeWithText("确认导入预览").assertIsDisplayed()
    }

    private fun click(label: String): Unit {
        compose.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun nextDocumentName(): String = "p8-${UUID.randomUUID()}.json".also { documentNames += it }

    private fun exportToDownloads(): String {
        val name = nextDocumentName()
        click("导出 JSON")
        val documents = awaitDocuments()
        chooseDownloads(documents)
        saveDocument(documents, name)
        awaitApplication()
        awaitMessage("JSON 备份已导出；包含私人备注，请妥善保存")
        return name
    }

    private fun openFromDownloads(name: String): Unit {
        click("导入预览")
        val documents = awaitDocuments()
        chooseDownloads(documents)
        selectDocument(name)
        awaitApplication()
    }

    private fun awaitDocuments(): String {
        val packages = setOf("com.android.documentsui", "com.google.android.documentsui")
        var documents: String? = null
        // DocumentsUI replaces its accessibility tree while opening. Read the foreground
        // package afresh rather than retaining a UiObject2 across that transition.
        compose.waitUntil(10_000) {
            val current = device.currentPackageName
            if (current != null && current in packages && device.hasObject(By.pkg(current))) {
                documents = current
                true
            } else false
        }
        return checkNotNull(documents) { "Android DocumentsUI must open" }
    }

    private fun chooseDownloads(documents: String): Unit {
        val downloads = By.text(Pattern.compile("Downloads|下载"))
        val roots = device.findObject(By.res(documents, "roots_list"))
        if (roots == null) {
            val drawer = device.findObject(By.desc(Pattern.compile("Show roots|显示根目录|显示根文件夹|显示位置")))
                ?: device.findObject(By.res("android", "home"))
                ?: device.findObject(By.res(documents, "toolbar"))?.findObject(By.clazz("android.widget.ImageButton"))
            checkNotNull(drawer) { "DocumentsUI navigation drawer not found" }.click()
        }
        val list = device.wait(Until.findObject(By.res(documents, "roots_list")), 5_000)
        val target = list?.findObject(downloads) ?: device.wait(Until.findObject(downloads), 5_000)
        checkNotNull(target) { "System Downloads provider not found" }.click()
        device.waitForIdle()
    }

    private fun saveDocument(documents: String, name: String): Unit {
        val input = device.wait(Until.findObject(By.res(documents, "file_name")), 5_000)
            ?: device.findObject(By.clazz("android.widget.EditText"))
        checkNotNull(input) { "DocumentsUI filename field not found" }.text = name
        val save = device.findObject(By.res(documents, "button_save"))
            ?: device.findObject(By.res("android", "button1"))
            ?: device.findObject(By.text(Pattern.compile("SAVE|Save|保存")))
        checkNotNull(save) { "DocumentsUI Save button not found" }.click()
    }

    private fun selectDocument(name: String): Unit {
        val document = device.wait(Until.findObject(By.text(name)), 10_000)
        checkNotNull(document) { "Exported document is not listed in Downloads: $name" }.click()
    }

    private fun awaitApplication(): Unit {
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
        compose.waitForIdle()
    }

    private fun cancelDocuments(): Unit {
        device.pressBack()
        // CreateDocument may focus the filename and show the IME; the first Back then only
        // dismisses that keyboard. A second Back cancels the actual system document request.
        if (!device.wait(Until.hasObject(By.pkg(context.packageName)), 1_500)) device.pressBack()
        awaitApplication()
    }

    private fun capture(label: String): Unit {
        assertTrue(device.takeScreenshot(File(evidence, "p8-api${Build.VERSION.SDK_INT}-$label.png")))
    }
}
