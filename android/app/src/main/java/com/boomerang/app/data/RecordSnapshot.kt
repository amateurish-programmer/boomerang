package com.boomerang.app.data

import org.json.JSONArray
import org.json.JSONObject

object RecordSnapshot {
    /** Immutable local snapshot. Sync must project only writable server fields. */
    fun encode(record: RecordEntity, sources: List<SourceEntity>): String {
        val c = record.content
        val row = JSONObject().apply {
            put("id", record.id); put("record_type", c.recordType); put("original_text", c.originalText)
            put("subject", c.subject); put("topic", c.topic); put("lifecycle", c.lifecycle)
            put("confirmed_status", c.confirmedStatus ?: JSONObject.NULL)
            put("said_at", c.saidAt ?: JSONObject.NULL); put("due_start", c.dueStart ?: JSONObject.NULL); put("due_end", c.dueEnd ?: JSONObject.NULL)
            put("date_text", c.dateText); put("date_precision", c.datePrecision); put("timezone", c.timezone)
            put("verification_criteria", c.verificationCriteria); put("notes", c.notes)
            put("capsule_locked_at", c.capsuleLockedAt ?: JSONObject.NULL); put("capsule_unlock_at", c.capsuleUnlockAt ?: JSONObject.NULL)
            put("created_at", record.createdAt); put("updated_at", record.updatedAt); put("deleted_at", record.deletedAt ?: JSONObject.NULL)
        }
        return JSONObject().apply {
            put("record", row); put("local_revision", record.localRevision); put("server_revision", record.serverRevision)
            put("sources", JSONArray().apply { sources.forEach { source -> put(JSONObject().apply {
                put("id", source.id); put("record_id", source.recordId); put("title", source.title); put("url", source.url)
                put("verified_by_tool", source.verifiedByTool); put("origin", source.origin)
            }) } })
        }.toString()
    }
}
