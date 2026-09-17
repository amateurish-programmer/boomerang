package com.boomerang.app.cloud

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class PendingUpload(val recordId: String, val localRevision: Long, val serverRevision: Long, val recordJson: String, val sourcesJson: String) {
    fun operationId(ownerId: String): String = UUID.nameUUIDFromBytes(
        "boomerang/sync/v1/$ownerId/$recordId/$localRevision/$serverRevision".toByteArray(Charsets.UTF_8),
    ).toString()
}
data class RemoteRecord(val recordJson: String, val sourcesJson: String, val forceConflict: Boolean = false)
data class SyncResult(val uploaded: Int, val downloaded: Int, val conflicts: Int)

/** Implementations must guard account identity and commit each acknowledgement/batch atomically. */
interface SyncLocalStore {
    val ownerId: String
    suspend fun pendingUploads(): List<PendingUpload>
    suspend fun acknowledge(upload: PendingUpload, remote: RemoteRecord)
    suspend fun applyRemoteBatch(records: List<RemoteRecord>): Int
}
interface SyncRemote {
    suspend fun upload(session: AuthSession, pending: PendingUpload, operationId: String): JSONObject
    suspend fun records(session: AuthSession): List<JSONObject>
    suspend fun sources(session: AuthSession, recordId: String): List<JSONObject>
    suspend fun acknowledgedSources(session: AuthSession, recordId: String, revision: Long): List<JSONObject> = sources(session, recordId)
    suspend fun snapshot(session: AuthSession, row: JSONObject): RemoteRecord =
        RemoteRecord(row.toString(), org.json.JSONArray(sources(session, row.getString("id"))).toString())
}

/** Offline edits remain authoritative until acknowledged; no network failure discards a local write. */
class SyncEngine(private val local: SyncLocalStore, private val remote: SyncRemote, private val isCurrentAccount: () -> Boolean) {
    private val mutex = Mutex()
    suspend fun sync(session: AuthSession): SyncResult = mutex.withLock {
        fun checkAccount() {
            if (local.ownerId != session.userId || !isCurrentAccount()) throw AccountIdentityMismatch()
        }
        checkAccount()
        var uploaded = 0
        val rejected = mutableSetOf<String>()
        for (pending in local.pendingUploads()) {
            checkAccount()
            try {
                val row = remote.upload(session, pending, pending.operationId(local.ownerId))
                checkAccount()
                val authoritativeSources = remote.acknowledgedSources(session, pending.recordId, row.getLong("revision"))
                checkAccount()
                local.acknowledge(pending, RemoteRecord(row.toString(), org.json.JSONArray(authoritativeSources).toString()))
                uploaded++
            } catch (_: RevisionConflict) {
                rejected += pending.recordId
                // Pull below persists the remote version beside the unchanged local record.
            }
        }
        checkAccount()
        val rows = remote.records(session)
        val downloads = rows.map { row ->
            checkAccount()
            remote.snapshot(session, row).copy(forceConflict = row.getString("id") in rejected)
        }
        checkAccount()
        val conflicts = local.applyRemoteBatch(downloads)
        SyncResult(uploaded, downloads.size, conflicts)
    }
}
