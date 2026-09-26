package com.boomerang.app.data

import androidx.room.withTransaction
import com.boomerang.app.domain.RecordContent
import com.boomerang.app.domain.RecordRules
import com.boomerang.app.domain.SourceInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest

data class RecordDetail(val record: RecordEntity, val sources: List<SourceEntity>, val history: List<RevisionEntity>)
class ValidationException(val errors: Map<String, String>) : IllegalArgumentException("请检查填写内容")
class LocalConflictException : IllegalStateException("记录已更新，请重新打开后编辑")

class BoomerangRepository(
    private val database: BoomerangDatabase,
    val ownerNamespace: String = "local",
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao = database.records()
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRecords(): Flow<List<RecordEntity>> = RecordInvalidations.observe(ownerNamespace)
        .flatMapLatest { dao.observeActive() }
        .map { rows -> rows.filter { it.ownerNamespace == ownerNamespace } }
    suspend fun detail(id: String): RecordDetail? = database.withTransaction {
        val record = dao.get(id)?.takeIf { it.ownerNamespace == ownerNamespace } ?: return@withTransaction null
        RecordDetail(record, dao.sources(id), dao.history(id))
    }

    suspend fun save(
        content: RecordContent,
        sources: List<SourceInput>,
        id: String? = null,
        expectedLocalRevision: Long? = null,
        operationId: String = UUID.randomUUID().toString(),
    ): String {
        val errors = RecordRules.validate(content) + RecordRules.sourceErrors(sources)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        return database.withTransaction {
            val existing = id?.let { dao.get(it) }
            if (id != null && (existing == null || existing.ownerNamespace != ownerNamespace || existing.deletedAt != null || existing.localRevision != expectedLocalRevision)) throw LocalConflictException()
            com.boomerang.app.domain.CapsulePolicy.validateChange(existing?.content, content, clock)
            require(content.confirmedStatus == existing?.content?.confirmedStatus) { "最终结果只能经确认流程修改" }
            val now = clock.instant().toString()
            val recordId = id ?: UUID.randomUUID().toString()
            val effectiveContent = existing?.let { RecordRules.afterEdit(it.content, content) } ?: content
            val record = RecordEntity(recordId, ownerNamespace, effectiveContent, (existing?.localRevision ?: 0) + 1,
                existing?.serverRevision ?: 0, existing?.createdAt ?: now, now)
            if (existing == null) dao.insert(record) else check(dao.update(record) == 1)
            val oldSources = dao.sources(recordId)
            val newSources = oldSources.filter { !it.clientEditable() } + sources.map { input ->
                SourceEntity(oldSources.firstOrNull { it.clientEditable() && it.title == input.title && it.url == input.url }?.id ?: UUID.randomUUID().toString(), recordId, ownerNamespace, input.title, input.url)
            }
            dao.deleteSources(recordId)
            dao.insertSources(newSources)
            appendOperation(record, newSources, operationId)
            recordId
        }
    }

    suspend fun softDelete(id: String, expectedLocalRevision: Long) = database.withTransaction {
        val existing = dao.get(id) ?: throw LocalConflictException()
        if (existing.ownerNamespace != ownerNamespace || existing.localRevision != expectedLocalRevision || existing.deletedAt != null) throw LocalConflictException()
        val now = clock.instant().toString()
        val record = existing.copy(localRevision = existing.localRevision + 1, updatedAt = now, deletedAt = now)
        check(dao.update(record) == 1)
        appendOperation(record, dao.sources(id), UUID.randomUUID().toString())
    }

    private suspend fun appendOperation(record: RecordEntity, sources: List<SourceEntity>, operationId: String) {
        val snapshot = RecordSnapshot.encode(record, sources)
        dao.insertRevision(RevisionEntity(record.id, record.localRevision, snapshot, record.updatedAt))
        dao.insertOutbox(OutboxEntity(operationId, record.id, record.localRevision, record.serverRevision, snapshot, record.updatedAt))
    }

    fun anonymousTargetId(id: String): String = UUID.nameUUIDFromBytes("boomerang/anonymous/$ownerNamespace/$id".toByteArray(Charsets.UTF_8)).toString()

    /** Selection is captured by the preview. Nothing is copied before the user's explicit confirmation. */
    suspend fun importAnonymous(selection: List<RecordDetail>) {
        require(ownerNamespace != "local" && selection.isNotEmpty())
        require(selection.map { it.record.id }.distinct().size == selection.size)
        selection.forEach { detail ->
            require(detail.record.ownerNamespace == "local" && detail.record.deletedAt == null)
            com.boomerang.app.domain.CapsulePolicy.validateChange(null, detail.record.content, clock)
            val errors = RecordRules.validate(detail.record.content) + RecordRules.sourceErrors(detail.sources.map { com.boomerang.app.domain.SourceInput(it.title, it.url) })
            if (errors.isNotEmpty()) throw ValidationException(errors)
        }
        database.withTransaction {
            if (selection.any { dao.get(anonymousTargetId(it.record.id)) != null }) throw LocalConflictException()
            selection.forEach { detail ->
                val record = detail.record.copy(id = anonymousTargetId(detail.record.id), ownerNamespace = ownerNamespace, localRevision = detail.record.localRevision + 1, serverRevision = 0, content = detail.record.content.copy(confirmedStatus = null))
                val sources = detail.sources.map { it.copy(id = anonymousTargetId(it.id), recordId = record.id, ownerNamespace = ownerNamespace) }
                dao.insert(record); dao.insertSources(sources)
                detail.history.forEach { revision ->
                    val snapshot = org.json.JSONObject(revision.snapshotJson)
                    snapshot.getJSONObject("record").put("id", record.id)
                    val oldSources = snapshot.getJSONArray("sources")
                    for (i in 0 until oldSources.length()) {
                        val source = oldSources.getJSONObject(i)
                        source.put("id", anonymousTargetId(source.getString("id"))).put("record_id", record.id)
                    }
                    snapshot.put("server_revision", 0)
                    dao.insertRevision(revision.copy(recordId = record.id, snapshotJson = snapshot.toString()))
                }
                if (detail.history.none { it.localRevision == record.localRevision })
                    dao.insertRevision(RevisionEntity(record.id, record.localRevision, RecordSnapshot.encode(record, sources), record.updatedAt))
                dao.insertOutbox(OutboxEntity(UUID.randomUUID().toString(), record.id, record.localRevision, 0,
                    RecordSnapshot.encode(record, sources), clock.instant().toString()))
            }
        }
    }
    /** Tombstones are deliberately retained for subsequent export and sync. */
    suspend fun exportRecords(): List<RecordEntity> = dao.allIncludingDeleted().filter { it.ownerNamespace == ownerNamespace }
}
