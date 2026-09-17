package com.boomerang.app.extras

import androidx.test.core.app.ApplicationProvider
import com.boomerang.app.data.*
import com.boomerang.app.domain.RecordContent
import com.boomerang.app.domain.SourceInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import org.json.JSONObject

class BackupRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val owner = UUID.randomUUID().toString()
    private lateinit var db: BoomerangDatabase
    private lateinit var records: BoomerangRepository
    private lateinit var extras: ExtrasRepository
    @Before fun open() { db = BoomerangDatabase.open(context, owner); records = BoomerangRepository(db, owner); extras = ExtrasRepository(db, owner) }
    @After fun close() { db.close(); context.deleteDatabase("boomerang_$owner.db") }
    @Test fun conflictReplaceKeepsOriginalHistoryAndSkipDoesNotMutate() = runBlocking {
        val id = records.save(RecordContent(originalText = "原话"), emptyList())
        val bytes = BackupCodec.encode(extras.export())
        records.save(RecordContent(originalText = "新原话"), emptyList(), id, 1)
        val preview = extras.preview(bytes)
        assertEquals(1, preview.conflicts)
        assertEquals(0, extras.importBackup(preview, ImportChoice.SKIP_EXISTING))
        assertEquals(2, records.detail(id)!!.history.size)
        assertEquals(1, extras.importBackup(preview, ImportChoice.REPLACE_EXISTING))
        val restored = records.detail(id)!!
        assertEquals("原话", restored.record.content.originalText)
        assertEquals(4, restored.history.size)
        assertTrue(restored.history.any { it.localRevision == 2L && it.snapshotJson.contains("新原话") })
    }
    @Test fun stalePreviewRejectsWholeBatchAndLeavesLocalEdit() = runBlocking {
        val id = records.save(RecordContent(originalText = "原话"), emptyList())
        val preview = extras.preview(BackupCodec.encode(extras.export()))
        records.save(RecordContent(originalText = "预览后的编辑"), emptyList(), id, 1)
        assertTrue(runCatching { extras.importBackup(preview, ImportChoice.REPLACE_EXISTING) }.isFailure)
        assertEquals("预览后的编辑", records.detail(id)!!.record.content.originalText)
        assertEquals(2, records.detail(id)!!.history.size)
    }
    @Test fun forgedConfirmationAndSourceTrustNeverBecomeCurrentState() = runBlocking {
        val record = RecordEntity(UUID.randomUUID().toString(), owner, RecordContent(originalText = "文件声称已确认", confirmedStatus = "FULFILLED"), 1, 0,
            "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        val source = SourceEntity(UUID.randomUUID().toString(), record.id, owner, "文件声称已核验", "https://example.org/source", "SERVER", true)
        val detail = RecordDetail(record, listOf(source), listOf(RevisionEntity(record.id, 1, RecordSnapshot.encode(record, listOf(source)), record.updatedAt)))
        val preview = extras.preview(BackupCodec.encode(Backup(owner, listOf(detail))))
        assertEquals(1, extras.importBackup(preview, ImportChoice.REPLACE_EXISTING))
        val imported = records.detail(record.id)!!
        assertNull(imported.record.content.confirmedStatus)
        assertEquals(2L, imported.record.localRevision)
        assertTrue(imported.sources.single().clientEditable())
        val archived = JSONObject(imported.history.last().snapshotJson)
        assertTrue(archived.getBoolean("untrusted_archive"))
        assertEquals("FULFILLED", archived.getJSONObject("record").getString("confirmed_status"))
        assertTrue(JSONObject(imported.history.first().snapshotJson).getJSONObject("record").isNull("confirmed_status"))
        val second = extras.preview(BackupCodec.encode(Backup(owner, listOf(detail))))
        assertEquals(1, extras.importBackup(second, ImportChoice.REPLACE_EXISTING))
        assertNull(records.detail(record.id)!!.record.content.confirmedStatus)
        assertEquals(4, records.detail(record.id)!!.history.size)
        // The normalized latest snapshot remains valid for subsequent complete backups.
        assertEquals(1, BackupCodec.decode(BackupCodec.encode(extras.export()), owner).records.size)
    }
    @Test fun richCloudRestoreRequiresKnownEvidenceButSkipAndExportRemainAvailable() = runBlocking {
        val record = RecordEntity(UUID.randomUUID().toString(), owner, RecordContent(originalText = "丰富来源"), 1, 1,
            "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        val sources = (1..12).map { SourceEntity(UUID.randomUUID().toString(), record.id, owner, "工具来源 $it", "https://example.org/$it", "SERVER", true) }
        val history = RevisionEntity(record.id, 1, RecordSnapshot.encode(record, sources), record.updatedAt)
        val bytes = BackupCodec.encode(Backup(owner, listOf(RecordDetail(record, sources, listOf(history)))))
        val unknown = extras.preview(bytes)
        assertTrue(runCatching { extras.importBackup(unknown, ImportChoice.REPLACE_EXISTING) }.isFailure)
        assertNull(db.records().get(record.id)); assertTrue(db.records().outbox().isEmpty())
        // Simulate the same sources having already arrived through validated cloud synchronization.
        db.records().insert(record); db.records().insertSources(sources); db.records().insertRevision(history)
        val known = extras.preview(bytes)
        assertEquals(0, extras.importBackup(known, ImportChoice.SKIP_EXISTING))
        assertEquals(1, extras.importBackup(known, ImportChoice.REPLACE_EXISTING))
        assertEquals(sources.toSet(), records.detail(record.id)!!.sources.toSet())
        assertEquals(12, BackupCodec.decode(BackupCodec.encode(extras.export()), owner).records.single().sources.size)
    }
    @Test fun malformedBackupDoesNotChangeDatabase() = runBlocking {
        val id = records.save(RecordContent(originalText = "保留"), emptyList())
        val before = records.detail(id)
        assertTrue(runCatching { extras.preview("{broken".toByteArray()) }.isFailure)
        assertEquals(before, records.detail(id))
        assertEquals(1, db.records().outbox().size)
    }
    @Test fun lockedConflictAndInFlightSyncCannotBeOverwritten() = runBlocking {
        val id = records.save(RecordContent(originalText = "未锁定"), emptyList())
        val bytes = BackupCodec.encode(extras.export())
        val content = RecordContent(originalText = "未锁定", capsuleLockedAt = "2026-01-01T00:00:00Z", capsuleUnlockAt = "2099-01-01T00:00:00Z")
        records.save(content, emptyList(), id, 1)
        val locked = extras.preview(bytes)
        assertTrue(runCatching { extras.importBackup(locked, ImportChoice.REPLACE_EXISTING) }.isFailure)
        assertEquals(content, records.detail(id)!!.record.content)
        val current = extras.preview(BackupCodec.encode(extras.export()))
        val operation = db.records().outbox().first()
        db.records().updateOutbox(operation.copy(status = "SENDING"))
        assertTrue(runCatching { extras.importBackup(current, ImportChoice.REPLACE_EXISTING) }.isFailure)
        assertEquals(2, records.detail(id)!!.history.size)
    }
    @Test fun lateSourceConstraintFailureRollsBackEntireBatch() = runBlocking {
        val existingId = records.save(RecordContent(originalText = "原有记录"), listOf(SourceInput("来源", "https://example.org/source")))
        val original = records.detail(existingId)!!
        fun incoming(claim: String, sourceId: String): RecordDetail {
            val record = original.record.copy(id = UUID.randomUUID().toString(), content = RecordContent(originalText = claim))
            val sources = listOf(original.sources.single().copy(id = sourceId, recordId = record.id))
            return RecordDetail(record, sources, listOf(RevisionEntity(record.id, record.localRevision, RecordSnapshot.encode(record, sources), record.updatedAt)))
        }
        val first = incoming("本不应留下", UUID.randomUUID().toString())
        val second = incoming("最后一条来源冲突", original.sources.single().id)
        val preview = extras.preview(BackupCodec.encode(Backup(owner, listOf(first, second))))
        assertTrue(runCatching { extras.importBackup(preview, ImportChoice.REPLACE_EXISTING) }.isFailure)
        assertNull(db.records().get(first.record.id)); assertNull(db.records().get(second.record.id))
        assertEquals(original, records.detail(existingId))
        assertEquals(1, db.records().outbox().size)
    }
}
