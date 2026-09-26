package com.boomerang.app.extras

import androidx.room.withTransaction
import com.boomerang.app.data.*
import com.boomerang.app.domain.CapsulePolicy
import org.json.JSONObject
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ImportPreview(val backup: Backup, val existing: Map<String, Long?>) {
    val conflicts get() = existing.count { it.value != null }
}
enum class ImportChoice { SKIP_EXISTING, REPLACE_EXISTING }

class ExtrasRepository(private val database: BoomerangDatabase, private val owner: String, private val clock: Clock = Clock.systemUTC()) {
    private val dao = database.records()
    suspend fun export(): Backup = database.withTransaction {
        Backup(owner, dao.allIncludingDeleted().filter { it.ownerNamespace == owner }.map { record ->
            val sources = dao.sources(record.id)
            val history = dao.history(record.id).toMutableList()
            if (history.none { it.localRevision == record.localRevision }) history += RevisionEntity(record.id, record.localRevision, RecordSnapshot.encode(record, sources), record.updatedAt)
            RecordDetail(record, sources, history)
        })
    }
    suspend fun preview(bytes: ByteArray): ImportPreview {
        val backup = BackupCodec.decode(bytes, owner)
        return database.withTransaction { ImportPreview(backup, backup.records.associate { it.record.id to dao.get(it.record.id)?.localRevision }) }
    }
    suspend fun importBackup(preview: ImportPreview, choice: ImportChoice): Int {
        currentCoroutineContext().ensureActive()
        // Once started, finish both the atomic import and its notification, even if the screen closes.
        // Otherwise cancellation after COMMIT could leave an already observing library stale.
        return withContext(NonCancellable) {
            val count = importTransaction(preview, choice)
            if (count > 0) RecordInvalidations.committed(owner)
            count
        }
    }

    private suspend fun importTransaction(preview: ImportPreview, choice: ImportChoice): Int = database.withTransaction {
        require(preview.backup.owner == owner)
        // Parse the entire object again before the first write; no partial import on late validation errors.
        val checked = BackupCodec.decode(BackupCodec.encode(preview.backup), owner)
        val sending = dao.outbox().filter { it.status == "SENDING" }.map { it.recordId }.toSet()
        val importSources = mutableMapOf<String, List<SourceEntity>>()
        checked.records.forEach { detail ->
            val current = dao.get(detail.record.id)
            check(current?.localRevision == preview.existing[detail.record.id]) { "预览后记录已变化，请重新选择备份" }
            require(current == null || current.ownerNamespace == owner)
            if (current == null || choice == ImportChoice.REPLACE_EXISTING) {
                val trusted = current?.let { dao.sources(it.id).filter { source -> !source.clientEditable() } }.orEmpty()
                val normalized = detail.sources.map { incoming ->
                    trusted.firstOrNull { it.id == incoming.id && it.title == incoming.title && it.url == incoming.url }
                        ?: incoming.copy(origin = "CLIENT", verifiedByTool = false)
                }.toMutableList()
                // A file cannot revoke already-known cloud evidence or promote its own sources to trusted.
                trusted.forEach { source ->
                    require(normalized.none { it.id == source.id && (it.title != source.title || it.url != source.url) }) { "备份不能改写已有云端来源" }
                    if (normalized.none { it.id == source.id }) normalized += source
                }
                require(normalized.count { it.clientEditable() } <= 10 && normalized.size <= BackupCodec.MAX_SOURCES) {
                    "备份中的云端来源尚未经本机核验，转换后超过 10 条手工来源；请先通过云同步恢复这些来源，或跳过已存在记录"
                }
                importSources[detail.record.id] = normalized
            }
            if (current != null && choice == ImportChoice.REPLACE_EXISTING) {
                check(current.id !in sending) { "此记录正在同步，请等待同步结束后重新预览" }
                CapsulePolicy.validateChange(current.content, detail.record.content, clock)
            }
        }
        var count = 0
        checked.records.forEach { detail ->
            val current = dao.get(detail.record.id)
            if (current != null && choice == ImportChoice.SKIP_EXISTING) return@forEach
            val now = clock.instant().toString()
            val sources = checkNotNull(importSources[detail.record.id])
            // An external JSON claim of confirmation is not an audited user confirmation.
            val normalizedContent = detail.record.content.copy(confirmedStatus = null)
            val record: RecordEntity
            if (current == null) {
                record = detail.record.copy(ownerNamespace = owner, serverRevision = 0, content = normalizedContent,
                    localRevision = detail.record.localRevision + 1, updatedAt = now)
                dao.insert(record)
                detail.history.forEach { history -> dao.insertRevision(history.copy(snapshotJson =
                    JSONObject(history.snapshotJson).put("untrusted_archive", true).toString())) }
            } else {
                var next = current.localRevision
                // Existing immutable history remains; imported history is appended with new local revision numbers.
                detail.history.sortedBy { it.localRevision }.forEach { history ->
                    next += 1
                    val snapshot = JSONObject(history.snapshotJson).put("local_revision", next).put("server_revision", current.serverRevision)
                        .put("untrusted_archive", true).toString()
                    dao.insertRevision(RevisionEntity(current.id, next, snapshot, history.createdAt))
                }
                record = detail.record.copy(ownerNamespace = owner, localRevision = next + 1, content = normalizedContent,
                    serverRevision = current.serverRevision, createdAt = current.createdAt, updatedAt = now)
                check(dao.update(record) == 1)
                dao.deleteSources(record.id)
            }
            dao.insertRevision(RevisionEntity(record.id, record.localRevision, RecordSnapshot.encode(record, sources), now))
            dao.insertSources(sources)
            dao.clearOutbox(record.id); dao.clearConflict(record.id)
            dao.insertOutbox(OutboxEntity(UUID.randomUUID().toString(), record.id, record.localRevision, record.serverRevision,
                RecordSnapshot.encode(record, sources), now))
            count += 1
        }
        count
    }
}
