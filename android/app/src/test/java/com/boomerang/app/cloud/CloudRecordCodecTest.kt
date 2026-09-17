package com.boomerang.app.cloud

import com.boomerang.app.data.CloudRecordCodec
import com.boomerang.app.data.RecordEntity
import com.boomerang.app.domain.RecordContent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudRecordCodecTest {
    private val owner = "00000000-0000-0000-0000-000000000001"
    private val id = "10000000-0000-0000-0000-000000000001"
    private fun row() = JSONObject().put("id", id).put("owner_id", owner).put("revision", 4)
        .put("record_type", "FLAG").put("original_text", "原话").put("subject", "我")
        .put("lifecycle", "ACTIVE").put("date_precision", "UNKNOWN").put("timezone", "Asia/Shanghai")
        .put("created_at", "2026-09-17T00:00:00Z").put("updated_at", "2026-09-17T00:00:00Z")
    @Test fun protectedServerEvidenceNeverBecomesManualUpload() {
        val manual = com.boomerang.app.data.SourceEntity(id, id, owner, "manual", "https://example.org/manual")
        val verified = manual.copy(id = "20000000-0000-0000-0000-000000000001", origin = "SERVER", verifiedByTool = true)
        val payload = org.json.JSONArray(CloudRecordCodec.sourcePayload(listOf(manual, verified)))
        assertEquals(1, payload.length())
        assertEquals("manual", payload.getJSONObject(0).getString("title"))
        assertFalse(payload.getJSONObject(0).has("verified_by_tool"))
    }
    @Test fun serverFieldsCannotBeUploaded() {
        val record = RecordEntity(id, owner, RecordContent(originalText = "原话", confirmedStatus = "FULFILLED"), 2, 1, "now", "now")
        val wire = CloudRecordCodec.writable(record)
        assertFalse(wire.has("confirmed_status")); assertFalse(wire.has("owner_id")); assertFalse(wire.has("created_at"))
        assertEquals("原话", wire.getString("original_text"))
    }
    @Test fun remoteOwnerMismatchIsRejected() {
        assertThrows(AccountIdentityMismatch::class.java) { CloudRecordCodec.decode(RemoteRecord(row().toString(), "[]"), "another", 1) }
    }
    @Test fun invalidRemoteDateIsRejectedBeforePersistence() {
        val row = row().put("due_start", "2026-02-30").put("due_end", "2026-02-30").put("date_precision", "DAY")
        assertThrows(InvalidCloudResponse::class.java) { CloudRecordCodec.decode(RemoteRecord(row.toString(), "[]"), owner, 1) }
    }
    @Test fun tombstonesAndServerVersionArePreserved() {
        val remote = row().put("deleted_at", "2026-09-17T01:00:00Z")
        val result = CloudRecordCodec.decode(RemoteRecord(remote.toString(), "[]"), owner, 7)
        assertEquals(7, result.first.localRevision); assertEquals(4, result.first.serverRevision)
        assertEquals("2026-09-17T01:00:00Z", result.first.deletedAt)
    }
}
