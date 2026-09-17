package com.boomerang.app.extras

import com.boomerang.app.data.*
import com.boomerang.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class Backup(val owner: String, val records: List<RecordDetail>)

/** Whitelisted content only: sessions, keys, outbox payloads and sync credentials are never exported. */
object BackupCodec {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_SOURCES = 1000
    fun encode(backup: Backup): ByteArray {
        val json = JSONObject().put("format", "boomerang-backup").put("version", 1).put("owner", backup.owner)
        json.put("records", JSONArray().apply { backup.records.forEach { detail ->
            put(JSONObject().put("snapshot", JSONObject(RecordSnapshot.encode(detail.record, detail.sources)))
                .put("history", JSONArray().apply { detail.history.sortedBy { it.localRevision }.forEach { revision ->
                    // Reparse snapshots to remove fields outside the public content schema.
                    val original = JSONObject(revision.snapshotJson)
                    val clean = decodeSnapshot(original, backup.owner)
                    put(JSONObject().put("revision", revision.localRevision).put("created_at", revision.createdAt)
                        .put("snapshot", cleanSnapshot(clean.first, clean.second, original)))
                } }))
        } })
        return json.toString(2).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { "备份超过 5 MiB 限制，请减少数据量" } }
    }
    fun decode(bytes: ByteArray, expectedOwner: String): Backup {
        require(bytes.size <= MAX_BYTES) { "备份文件不能超过 5 MiB" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getString("format") == "boomerang-backup" && root.getInt("version") == 1) { "不支持的备份版本" }
        val owner = root.getString("owner")
        require(owner == expectedOwner) { "备份所属账号不匹配，请切换到原账号；本机数据请使用专用迁入入口" }
        require(owner == "local" || validUuid(owner))
        val rows = root.getJSONArray("records")
        require(rows.length() <= 10_000)
        val records = (0 until rows.length()).map { index ->
            val item = rows.getJSONObject(index)
            val (record, sources) = decodeSnapshot(item.getJSONObject("snapshot"), owner)
            val historyJson = item.getJSONArray("history")
            require(historyJson.length() in 1..10_000)
            val history = (0 until historyJson.length()).map { offset ->
                val old = historyJson.getJSONObject(offset)
                val number = old.getLong("revision")
                require(number in 1..record.localRevision)
                val stamp = old.getString("created_at").also { Instant.parse(it) }
                val (previous, previousSources) = decodeSnapshot(old.getJSONObject("snapshot"), owner)
                require(previous.id == record.id && previous.localRevision == number)
                RevisionEntity(record.id, number, cleanSnapshot(previous, previousSources, old.getJSONObject("snapshot")).toString(), stamp)
            }.sortedBy { it.localRevision }
            require(history.map { it.localRevision }.distinct().size == history.size)
            require(history.last().localRevision == record.localRevision)
            val (latest, latestSources) = decodeSnapshot(JSONObject(history.last().snapshotJson), owner)
            require(latest.copy(serverRevision = record.serverRevision) == record && latestSources.toSet() == sources.toSet()) { "当前记录与最新历史不一致" }
            RecordDetail(record, sources, history)
        }
        require(records.map { it.record.id }.distinct().size == records.size) { "文件内有重复记录 UUID" }
        require(records.flatMap { it.sources }.map { it.id }.distinct().size == records.sumOf { it.sources.size }) { "来源 UUID 重复" }
        return Backup(owner, records)
    }
    private fun validUuid(text: String) = runCatching { UUID.fromString(text).toString() == text }.getOrDefault(false)
    private fun cleanSnapshot(record: RecordEntity, sources: List<SourceEntity>, original: JSONObject): JSONObject =
        JSONObject(RecordSnapshot.encode(record, sources)).apply {
            if (original.optBoolean("untrusted_archive", false)) put("untrusted_archive", true)
        }
    private fun decodeSnapshot(snapshot: JSONObject, owner: String): Pair<RecordEntity, List<SourceEntity>> {
        val row = snapshot.getJSONObject("record")
        fun optional(key: String): String? = if (!row.has(key) || row.isNull(key)) null else row.getString(key)
        val id = row.getString("id"); require(validUuid(id))
        val content = RecordContent(recordType = row.getString("record_type"), originalText = row.getString("original_text"),
            subject = row.getString("subject"), topic = row.getString("topic"), lifecycle = row.getString("lifecycle"),
            confirmedStatus = optional("confirmed_status"), saidAt = optional("said_at"), dueStart = optional("due_start"), dueEnd = optional("due_end"),
            dateText = row.getString("date_text"), datePrecision = row.getString("date_precision"), timezone = row.getString("timezone"),
            verificationCriteria = row.getString("verification_criteria"), notes = row.getString("notes"),
            capsuleLockedAt = optional("capsule_locked_at"), capsuleUnlockAt = optional("capsule_unlock_at"))
        require(RecordRules.validate(content).isEmpty()) { "备份含有无效记录字段" }
        require((content.capsuleLockedAt == null) == (content.capsuleUnlockAt == null))
        content.capsuleLockedAt?.let { require(Instant.parse(content.capsuleUnlockAt) > Instant.parse(it)) }
        val revision = snapshot.getLong("local_revision"); val serverRevision = snapshot.getLong("server_revision")
        require(revision > 0 && revision < Long.MAX_VALUE / 2 && serverRevision >= 0)
        val record = RecordEntity(id, owner, content, revision, serverRevision,
            row.getString("created_at").also { Instant.parse(it) }, row.getString("updated_at").also { Instant.parse(it) }, optional("deleted_at")?.also { Instant.parse(it) })
        val sourceRows = snapshot.getJSONArray("sources")
        require(sourceRows.length() <= MAX_SOURCES) { "单条记录来源超过备份安全限制" }
        val sources = (0 until sourceRows.length()).map { index ->
            val source = sourceRows.getJSONObject(index)
            require(source.getString("record_id") == id && validUuid(source.getString("id")))
            val origin = source.optString("origin", "CLIENT")
            require(origin in setOf("CLIENT", "SERVER"))
            require(!source.has("verified_by_tool") || source.get("verified_by_tool") is Boolean)
            SourceEntity(source.getString("id"), id, owner, source.getString("title"), source.getString("url"),
                origin, source.optBoolean("verified_by_tool", false))
        }
        require(sources.map { it.id }.distinct().size == sources.size)
        require(sources.count { it.clientEditable() } <= 10) { "手工来源不能超过 10 条" }
        // A cloud record can include tool evidence in addition to the ten editable manual sources.
        require(sources.all { RecordRules.sourceErrors(listOf(SourceInput(it.title, it.url))).isEmpty() }) { "备份含有无效来源" }
        return record to sources
    }
}
