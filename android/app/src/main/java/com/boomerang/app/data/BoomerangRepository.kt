package com.boomerang.app.data

import androidx.room.withTransaction
import com.boomerang.app.domain.RecordContent
import com.boomerang.app.domain.RecordRules
import com.boomerang.app.domain.SourceInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID

data class RecordDetail(val record: RecordEntity, val sources: List<SourceEntity>, val history: List<RevisionEntity>)
class ValidationException(val errors: Map<String, String>) : IllegalArgumentException("请检查填写内容")
class LocalConflictException : IllegalStateException("记录已更新，请重新打开后编辑")

class BoomerangRepository(
    private val database: BoomerangDatabase,
    val ownerNamespace: String = "local",
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao = database.records()
    fun observeRecords(): Flow<List<RecordEntity>> = dao.observeActive().map { rows -> rows.filter { it.ownerNamespace == ownerNamespace } }
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
            require(content.confirmedStatus == existing?.content?.confirmedStatus) { "最终结果只能经确认流程修改" }
            val now = clock.instant().toString()
            val recordId = id ?: UUID.randomUUID().toString()
            val effectiveContent = existing?.let { RecordRules.afterEdit(it.content, content) } ?: content
            val record = RecordEntity(recordId, ownerNamespace, effectiveContent, (existing?.localRevision ?: 0) + 1,
                existing?.serverRevision ?: 0, existing?.createdAt ?: now, now)
            if (existing == null) dao.insert(record) else check(dao.update(record) == 1)
            val oldSources = dao.sources(recordId)
            val newSources = sources.map { input ->
                SourceEntity(oldSources.firstOrNull { it.title == input.title && it.url == input.url }?.id ?: UUID.randomUUID().toString(), recordId, ownerNamespace, input.title, input.url)
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

    /** Tombstones are deliberately retained for subsequent export and sync. */
    suspend fun exportRecords(): List<RecordEntity> = dao.allIncludingDeleted().filter { it.ownerNamespace == ownerNamespace }
}
