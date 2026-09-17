package com.boomerang.app.data

import androidx.room.withTransaction
import com.boomerang.app.cloud.*
import org.json.JSONObject
import java.util.UUID

/** Immutable in-flight snapshots ensure a response lost across restart can be retried exactly. */
class RoomSyncStore(private val database: BoomerangDatabase, override val ownerId: String) : SyncLocalStore {
    private val dao = database.records()
    init { requireUuid(ownerId) }
    override suspend fun pendingUploads(): List<PendingUpload> = database.withTransaction {
        val conflicts = dao.conflicts().map { it.recordId }.toSet()
        dao.outbox().groupBy { it.recordId }.mapNotNull { (id, operations) ->
            if (id in conflicts) return@mapNotNull null
            val record = dao.get(id) ?: throw InvalidCloudResponse()
            checkOwner(record)
            val frozen = operations.firstOrNull { it.status == "SENDING" }
            val selected = frozen ?: operations.maxBy { it.localRevision }.copy(expectedServerRevision = record.serverRevision, status = "SENDING")
            if (frozen == null) dao.updateOutbox(selected)
            val snapshot = JSONObject(selected.payloadJson)
            val sources = snapshot.getJSONArray("sources")
            val projectedSources = org.json.JSONArray().apply {
                for (i in 0 until sources.length()) {
                    val s = sources.getJSONObject(i)
                    if (s.optString("origin", "CLIENT") == "CLIENT" && !s.optBoolean("verified_by_tool", false))
                        put(JSONObject().put("id", s.getString("id")).put("title", s.getString("title")).put("url", s.getString("url")))
                }
            }
            PendingUpload(id, selected.localRevision, selected.expectedServerRevision, CloudRecordCodec.project(snapshot.getJSONObject("record")).toString(), projectedSources.toString())
        }
    }
    override suspend fun acknowledge(upload: PendingUpload, remote: RemoteRecord) = database.withTransaction {
        val current = dao.get(upload.recordId) ?: throw InvalidCloudResponse()
        checkOwner(current)
        val (cloud, sources) = CloudRecordCodec.decode(remote, ownerId, current.localRevision)
        if (cloud.id != current.id || cloud.serverRevision != upload.serverRevision + 1) throw InvalidCloudResponse()
        if (!SyncMergePolicy.canAcknowledge(current.localRevision, current.serverRevision, upload)) throw LocalConflictException()
        if (current.localRevision == upload.localRevision) {
            persist(cloud.copy(localRevision = current.localRevision + 1), sources, false)
        } else dao.update(current.copy(serverRevision = cloud.serverRevision))
        dao.clearAcknowledged(current.id, upload.localRevision)
    }
    override suspend fun applyRemoteBatch(records: List<RemoteRecord>): Int = database.withTransaction {
        var conflicts = 0
        for (remote in records) {
            val decoded = CloudRecordCodec.decode(remote, ownerId, 1)
            val current = dao.get(decoded.first.id)
            current?.let(::checkOwner)
            val dirty = dao.outbox().any { it.recordId == decoded.first.id }
            when (SyncMergePolicy.pull(current?.serverRevision, dirty, decoded.first.serverRevision, remote.forceConflict)) {
                SyncMergePolicy.Pull.KEEP_LOCAL -> Unit
                SyncMergePolicy.Pull.CONFLICT -> { dao.putConflict(SyncConflictEntity(decoded.first.id, remote.recordJson, remote.sourcesJson)); conflicts++ }
                SyncMergePolicy.Pull.APPLY -> {
                    if (current == null || decoded.first.serverRevision > current.serverRevision) {
                        val (cloud, sources) = CloudRecordCodec.decode(remote, ownerId, (current?.localRevision ?: 0) + 1)
                        persist(cloud, sources, current == null)
                    }
                }
            }
        }
        conflicts
    }
    suspend fun conflicts(): List<SyncConflictEntity> = dao.conflicts()
    /** Both alternatives remain in immutable local history after the explicit choice. */
    suspend fun resolve(id: String, useLocal: Boolean) = database.withTransaction {
        val conflict = dao.conflict(id) ?: throw LocalConflictException()
        val local = dao.get(id) ?: throw LocalConflictException()
        checkOwner(local)
        val localSources = dao.sources(id)
        val (cloud, sources) = CloudRecordCodec.decode(RemoteRecord(conflict.remoteRecordJson, conflict.remoteSourcesJson), ownerId, local.localRevision + 1)
        if (!useLocal) com.boomerang.app.domain.CapsulePolicy.validateChange(local.content, cloud.content, java.time.Clock.systemUTC())
        persist(cloud, sources, false)
        dao.clearOutbox(id)
        if (useLocal) {
            val chosen = local.copy(localRevision = cloud.localRevision + 1, serverRevision = cloud.serverRevision,
                content = com.boomerang.app.domain.RecordRules.afterEdit(cloud.content, local.content.copy(confirmedStatus = cloud.content.confirmedStatus, capsuleLockedAt = cloud.content.capsuleLockedAt ?: local.content.capsuleLockedAt, capsuleUnlockAt = cloud.content.capsuleUnlockAt ?: local.content.capsuleUnlockAt)), updatedAt = java.time.Instant.now().toString())
            if (cloud.content.capsuleLockedAt != null && local.content.capsuleLockedAt != null &&
                (cloud.content.capsuleLockedAt != local.content.capsuleLockedAt || cloud.content.capsuleUnlockAt != local.content.capsuleUnlockAt)) throw LocalConflictException()
            com.boomerang.app.domain.CapsulePolicy.validateChange(cloud.content, chosen.content, java.time.Clock.systemUTC())
            val chosenSources = sources.filter { !it.clientEditable() } + localSources.filter { it.clientEditable() }
            persist(chosen, chosenSources, false)
            dao.insertOutbox(OutboxEntity(UUID.randomUUID().toString(), id, chosen.localRevision, chosen.serverRevision,
                RecordSnapshot.encode(chosen, chosenSources), chosen.updatedAt))
        }
        dao.clearConflict(id)
    }
    private fun checkOwner(record: RecordEntity) { if (record.ownerNamespace != ownerId) throw AccountIdentityMismatch() }
    private suspend fun replaceSources(id: String, sources: List<SourceEntity>) { dao.deleteSources(id); dao.insertSources(sources) }
    private suspend fun persist(record: RecordEntity, sources: List<SourceEntity>, insert: Boolean) {
        if (insert) dao.insert(record) else dao.update(record)
        replaceSources(record.id, sources)
        dao.insertRevision(RevisionEntity(record.id, record.localRevision, RecordSnapshot.encode(record, sources), record.updatedAt))
    }
}
