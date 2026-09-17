package com.boomerang.app.data

import com.boomerang.app.cloud.*
import com.boomerang.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Strict boundary for untrusted cloud rows and an allowlist for client writes. */
object CloudRecordCodec {
    private val writableFields = setOf("id", "record_type", "original_text", "subject", "topic", "lifecycle", "said_at", "due_start", "due_end", "date_text", "date_precision", "timezone", "verification_criteria", "notes", "deleted_at", "capsule_locked_at", "capsule_unlock_at")
    fun writable(record: RecordEntity): JSONObject = project(JSONObject(RecordSnapshot.encode(record, emptyList())).getJSONObject("record"))
    fun project(row: JSONObject): JSONObject = JSONObject().also { out -> writableFields.forEach { if (row.has(it)) out.put(it, row.get(it)) } }
    fun sourcePayload(sources: List<SourceEntity>): String = JSONArray().apply {
        sources.filter { it.clientEditable() }.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url)) }
    }.toString()
    fun decode(remote: RemoteRecord, owner: String, localRevision: Long): Pair<RecordEntity, List<SourceEntity>> {
        try {
            val row = JSONObject(remote.recordJson)
            if (row.getString("owner_id") != owner) throw AccountIdentityMismatch()
            val id = requireUuid(row.getString("id"))
            fun text(key: String, default: String = ""): String = if (row.has(key) && !row.isNull(key)) row.getString(key) else default
            fun optional(key: String): String? = if (!row.has(key) || row.isNull(key)) null else row.getString(key)
            val content = RecordContent(row.getString("record_type"), row.getString("original_text"), row.getString("subject"), text("topic"),
                row.getString("lifecycle"), optional("confirmed_status"), optional("said_at"), optional("due_start"), optional("due_end"),
                text("date_text"), row.getString("date_precision"), row.getString("timezone"), text("verification_criteria"), text("notes"), optional("capsule_locked_at"), optional("capsule_unlock_at"))
            if (RecordRules.validate(content).isNotEmpty()) throw InvalidCloudResponse()
            CapsulePolicy.validateChange(null, content, java.time.Clock.systemUTC())
            val version = row.getLong("revision")
            if (version <= 0 || localRevision <= 0) throw InvalidCloudResponse()
            val created = row.getString("created_at"); val updated = row.getString("updated_at"); val deleted = optional("deleted_at")
            Instant.parse(created); Instant.parse(updated); deleted?.let(Instant::parse)
            val array = JSONArray(remote.sourcesJson)
            if (array.length() > 1000) throw InvalidCloudResponse()
            val sources = (0 until array.length()).map { index ->
                val s = array.getJSONObject(index)
                if (s.has("owner_id") && s.getString("owner_id") != owner) throw AccountIdentityMismatch()
                if (s.has("record_id") && s.getString("record_id") != id) throw InvalidCloudResponse()
                val origin = s.optString("origin", "SERVER")
                if (origin !in setOf("CLIENT", "SERVER")) throw InvalidCloudResponse()
                val verified = if (s.has("verified_by_tool")) s.getBoolean("verified_by_tool") else false
                SourceEntity(requireUuid(s.getString("id")), id, owner, s.getString("title"), s.getString("url"), origin, verified)
            }
            if (sources.map { it.id }.distinct().size != sources.size || sources.any {
                it.title.isBlank() || it.title.codePointCount(0, it.title.length) > 500 || !RecordRules.validSourceUrl(it.url)
            }) throw InvalidCloudResponse()
            return RecordEntity(id, owner, content, localRevision, version, created, updated, deleted) to sources
        } catch (e: AccountIdentityMismatch) { throw e }
        catch (_: Exception) { throw InvalidCloudResponse() }
    }
}
