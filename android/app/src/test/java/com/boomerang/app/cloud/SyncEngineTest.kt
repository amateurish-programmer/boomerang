package com.boomerang.app.cloud

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncEngineTest {
    private val account = "00000000-0000-0000-0000-000000000001"
    private val id = "10000000-0000-0000-0000-000000000001"
    private val session get() = AuthSession(account, "access", "refresh", 9999999999)
    private fun pending(local: Long = 2, base: Long = 1) = PendingUpload(id, local, base,
        JSONObject().put("id", id).put("original_text", "local").toString(), "[]")
    private fun remote(version: Long = 2) = JSONObject().put("id", id).put("owner_id", account).put("revision", version)
    private class Local(override val ownerId: String, val queued: List<PendingUpload>) : SyncLocalStore {
        var acknowledgements = 0
        var applied = false
        var acknowledgedSources: String? = null
        override suspend fun pendingUploads() = queued
        override suspend fun acknowledge(upload: PendingUpload, remote: RemoteRecord) { acknowledgements++; acknowledgedSources = remote.sourcesJson }
        override suspend fun applyRemoteBatch(records: List<RemoteRecord>): Int { applied = true; return 0 }
    }
    private class Remote : SyncRemote {
        var uploads = mutableListOf<String>()
        var failure: Exception? = null
        var sourcesFailure: Exception? = null
        var rows = emptyList<JSONObject>()
        var afterUpload: () -> Unit = {}
        var sourceRows = emptyList<JSONObject>()
        override suspend fun upload(session: AuthSession, pending: PendingUpload, operationId: String): JSONObject {
            uploads += operationId
            failure?.let { throw it }
            afterUpload()
            return JSONObject(pending.recordJson).put("owner_id", session.userId).put("revision", pending.serverRevision + 1)
        }
        override suspend fun records(session: AuthSession) = rows
        override suspend fun sources(session: AuthSession, recordId: String): List<JSONObject> {
            sourcesFailure?.let { throw it }
            return sourceRows
        }
    }
    @Test fun acknowledgementKeepsServerEvidenceEvenWhenClientOmitsIt() = runBlocking<Unit> {
        val local = Local(account, listOf(pending()))
        val remote = Remote().apply { sourceRows = listOf(JSONObject().put("id", "server-evidence")) }
        SyncEngine(local, remote) { true }.sync(session)
        assertTrue(local.acknowledgedSources!!.contains("server-evidence"))
    }
    @Test fun failedAuthoritativeSourceReadRetainsInFlightUpload() = runBlocking<Unit> {
        val local = Local(account, listOf(pending()))
        val remote = Remote().apply { sourcesFailure = CloudTimeout() }
        try { SyncEngine(local, remote) { true }.sync(session); fail("source failure must retain upload") }
        catch (_: CloudTimeout) { }
        assertEquals(0, local.acknowledgements)
    }    @Test fun ambiguousFailurePreservesOutboxAndRetryUsesSameOperationId() = runBlocking<Unit> {
        val local = Local(account, listOf(pending()))
        val remote = Remote().apply { failure = CloudOffline() }
        repeat(2) { try { SyncEngine(local, remote) { true }.sync(session) } catch (_: CloudOffline) { } }
        assertEquals(0, local.acknowledgements)
        assertFalse(local.applied)
        assertEquals(2, remote.uploads.size)
        assertEquals(remote.uploads[0], remote.uploads[1])
    }
    @Test fun switchingAccountsWhileRequestIsInFlightRejectsAcknowledgement() = runBlocking<Unit> {
        var sameAccount = true
        val local = Local(account, listOf(pending()))
        val remote = Remote().apply { afterUpload = { sameAccount = false } }
        try { SyncEngine(local, remote) { sameAccount }.sync(session); fail("account switch must stop sync") }
        catch (_: AccountIdentityMismatch) { }
        assertEquals(0, local.acknowledgements)
        assertFalse(local.applied)
    }
    @Test fun differentSessionCannotUploadAnotherAccountStore() = runBlocking<Unit> {
        val remote = Remote()
        try { SyncEngine(Local("another", listOf(pending())), remote) { true }.sync(session); fail("wrong account") }
        catch (_: AccountIdentityMismatch) { }
        assertTrue(remote.uploads.isEmpty())
    }
    @Test fun conflictContinuesToDownloadBothVersionsWithoutAcknowledgingLocal() = runBlocking<Unit> {
        val local = Local(account, listOf(pending()))
        val remote = Remote().apply { failure = RevisionConflict(); rows = listOf(remote(4)) }
        SyncEngine(local, remote) { true }.sync(session)
        assertEquals(0, local.acknowledgements)
        assertTrue(local.applied)
    }
    @Test fun failedSourceDownloadNeverAppliesSourceLessRecords() = runBlocking<Unit> {
        val local = Local(account, emptyList())
        val remote = Remote().apply { rows = listOf(remote()); sourcesFailure = CloudTimeout() }
        try { SyncEngine(local, remote) { true }.sync(session); fail("incomplete download") }
        catch (_: CloudTimeout) { }
        assertFalse(local.applied)
    }
    @Test fun localRevisionChangesReceiveDifferentIdempotencyKeys() {
        assertNotEquals(pending(2).operationId(account), pending(3).operationId(account))
        assertNotEquals(pending(2, 1).operationId(account), pending(2, 2).operationId(account))
    }
}
