package com.boomerang.app.extras

import com.boomerang.app.data.*
import com.boomerang.app.domain.RecordContent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BackupCodecTest {
    private fun backup(): Backup {
        val record = RecordEntity(UUID.randomUUID().toString(), "local", RecordContent(originalText = "测试", notes = "私人备注"), 3, 7,
            "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "2026-01-03T00:00:00Z")
        val source = SourceEntity(UUID.randomUUID().toString(), record.id, "local", "来源", "https://example.org/a")
        return Backup("local", listOf(RecordDetail(record, listOf(source), listOf(RevisionEntity(record.id, 3, RecordSnapshot.encode(record, listOf(source)), record.updatedAt)))))
    }
    @Test fun versionedRoundTripPreservesHistoryAndTombstone() {
        val before = backup()
        val restored = BackupCodec.decode(BackupCodec.encode(before), "local")
        assertEquals(before.records.first().record, restored.records.first().record)
        assertEquals(before.records.first().sources, restored.records.first().sources)
        assertEquals(3L, restored.records.first().history.single().localRevision)
        assertNotNull(restored.records.first().record.deletedAt)
    }
    @Test fun richCloudEvidenceIsPreservedBeyondManualSourceLimit() {
        val original = backup().records.single()
        val sources = (1..12).map { index -> SourceEntity(UUID.randomUUID().toString(), original.record.id, "local", "工具来源 $index", "https://example.org/$index", "SERVER", true) }
        val detail = original.copy(sources = sources, history = listOf(original.history.single().copy(snapshotJson = RecordSnapshot.encode(original.record, sources))))
        val restored = BackupCodec.decode(BackupCodec.encode(Backup("local", listOf(detail))), "local").records.single()
        assertEquals(sources, restored.sources)
        assertEquals(12, restored.sources.size)
    }
    @Test fun externalArchiveWarningSurvivesReexportWithoutPromotingConfirmation() {
        val original = backup().records.single()
        val history = original.history.single().copy(snapshotJson = JSONObject(original.history.single().snapshotJson).put("untrusted_archive", true).toString())
        val restored = BackupCodec.decode(BackupCodec.encode(Backup("local", listOf(original.copy(history = listOf(history))))), "local")
        assertTrue(JSONObject(restored.records.single().history.single().snapshotJson).getBoolean("untrusted_archive"))
    }
    @Test fun wrongAccountVersionOversizeAndDuplicateIdsRejected() {
        val encoded = BackupCodec.encode(backup())
        assertTrue(runCatching { BackupCodec.decode(encoded, UUID.randomUUID().toString()) }.isFailure)
        val future = JSONObject(encoded.toString(Charsets.UTF_8)).put("version", 2).toString().toByteArray()
        assertTrue(runCatching { BackupCodec.decode(future, "local") }.isFailure)
        assertTrue(runCatching { BackupCodec.decode(ByteArray(BackupCodec.MAX_BYTES + 1), "local") }.isFailure)
        val duplicate = JSONObject(encoded.toString(Charsets.UTF_8))
        val rows = duplicate.getJSONArray("records"); rows.put(rows.getJSONObject(0))
        assertTrue(runCatching { BackupCodec.decode(duplicate.toString().toByteArray(), "local") }.isFailure)
    }
    @Test fun arbitrarySnapshotFieldsAreNotReexported() {
        val input = backup()
        val detail = input.records.single()
        val dirty = JSONObject(detail.history.single().snapshotJson).put("access_token", "should-never-export")
        val clean = BackupCodec.encode(input.copy(records = listOf(detail.copy(history = listOf(detail.history.single().copy(snapshotJson = dirty.toString()))))))
        assertFalse(clean.toString(Charsets.UTF_8).contains("should-never-export"))
    }
    @Test fun invalidLastRecordAndPrivateSourceRejected() {
        val root = JSONObject(BackupCodec.encode(backup()).toString(Charsets.UTF_8))
        root.getJSONArray("records").getJSONObject(0).getJSONObject("snapshot").getJSONArray("sources").getJSONObject(0).put("url", "https://127.0.0.1/private")
        assertTrue(runCatching { BackupCodec.decode(root.toString().toByteArray(), "local") }.isFailure)
    }
    @Test fun acknowledgedServerRevisionMayDifferFromImmutableSnapshot() {
        val input = backup(); val detail = input.records.single()
        val updated = input.copy(records = listOf(detail.copy(record = detail.record.copy(serverRevision = 10))))
        assertEquals(10L, BackupCodec.decode(BackupCodec.encode(updated), "local").records.single().record.serverRevision)
    }
    @Test fun shareCardExcludesPrivateNotesAndLabelsConfirmation() {
        val detail = backup().records.single()
        val text = ShareCard.text(detail)
        assertFalse(text.contains("私人备注")); assertTrue(text.contains("尚未由用户确认")); assertTrue(text.contains("来源"))
        assertTrue(ShareCard.text(detail.copy(record = detail.record.copy(content = detail.record.content.copy(confirmedStatus = "FULFILLED")))).contains("用户确认：已达成"))
    }
}
