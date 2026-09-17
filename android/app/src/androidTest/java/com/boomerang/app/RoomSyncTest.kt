package com.boomerang.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boomerang.app.data.*
import com.boomerang.app.cloud.*
import com.boomerang.app.domain.RecordContent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomSyncTest {
    @Test fun anonymousDataStaysSeparateUntilExplicitAtomicImport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val owner = UUID.randomUUID().toString()
        val anonymousDb = androidx.room.Room.inMemoryDatabaseBuilder(context, BoomerangDatabase::class.java).build()
        val db = BoomerangDatabase.open(context, owner)
        try {
            val anonymous = BoomerangRepository(anonymousDb)
            val account = BoomerangRepository(db, owner)
            val id = anonymous.save(RecordContent(originalText = "private anonymous record"), emptyList())
            assertTrue(account.exportRecords().isEmpty())
            val preview = listOf(anonymous.detail(id)!!)
            assertTrue(account.exportRecords().isEmpty())
            account.importAnonymous(preview)
            val importedId = account.anonymousTargetId(id)
            assertEquals(owner, account.detail(importedId)!!.record.ownerNamespace)
            assertNotEquals(id, importedId)
            assertEquals(importedId, account.anonymousTargetId(id))
            assertNotEquals(importedId, BoomerangRepository(db, UUID.randomUUID().toString()).anonymousTargetId(id))
            assertEquals("local", anonymous.detail(id)!!.record.ownerNamespace)
            assertEquals(1, db.records().outbox().size)
            val second = anonymous.save(RecordContent(originalText = "second"), emptyList())
            try { account.importAnonymous(listOf(anonymous.detail(second)!!, anonymous.detail(id)!!)); fail("duplicate must reject full batch") }
            catch (_: LocalConflictException) { }
            assertNull(account.detail(account.anonymousTargetId(second)))
        } finally { db.close(); anonymousDb.close(); context.deleteDatabase("boomerang_$owner.db") }
    }
    @Test fun acknowledgementAppendsAuthoritativeRevisionForPortableBackup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val owner = UUID.randomUUID().toString()
        val db = BoomerangDatabase.open(context, owner)
        try {
            val repo = BoomerangRepository(db, owner)
            val local = RoomSyncStore(db, owner)
            val id = repo.save(RecordContent(originalText = "first"), emptyList())
            val sent = local.pendingUploads().single()
            val response = JSONObject(sent.recordJson).put("owner_id", owner).put("revision", 1)
                .put("created_at", "2026-09-17T00:00:00Z").put("updated_at", "2026-09-17T00:00:01Z")
            local.acknowledge(sent, RemoteRecord(response.toString(), "[]"))
            val detail = repo.detail(id)!!
            assertEquals(2L, detail.record.localRevision)
            assertEquals(2, detail.history.size)
            assertEquals("2026-09-17T00:00:01Z", JSONObject(detail.history.first().snapshotJson).getJSONObject("record").getString("updated_at"))
            assertTrue(db.records().outbox().isEmpty())
        } finally { db.close(); context.deleteDatabase("boomerang_$owner.db") }
    }
    @Test fun lostResponseRetriesFrozenSnapshotAndAcknowledgementKeepsLaterEdit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val owner = UUID.randomUUID().toString()
        val db = BoomerangDatabase.open(context, owner)
        try {
            val repo = BoomerangRepository(db, owner)
            val local = RoomSyncStore(db, owner)
            val id = repo.save(RecordContent(originalText = "first"), emptyList())
            val sent = local.pendingUploads().single()
            repo.save(RecordContent(originalText = "newer offline edit"), emptyList(), id, 1)
            assertEquals(sent, local.pendingUploads().single())
            val response = JSONObject(sent.recordJson).put("owner_id", owner).put("revision", 1)
                .put("created_at", "2026-09-17T00:00:00Z").put("updated_at", "2026-09-17T00:00:00Z")
            local.acknowledge(sent, RemoteRecord(response.toString(), "[]"))
            assertEquals("newer offline edit", repo.detail(id)!!.record.content.originalText)
            val next = local.pendingUploads().single()
            assertEquals(1L, next.serverRevision)
            assertEquals(2L, next.localRevision)
        } finally { db.close(); context.deleteDatabase("boomerang_$owner.db") }
    }
    @Test fun choosingLocalDoesNotUnsealNewCapsuleWhenCloudWasUnsealed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val owner = UUID.randomUUID().toString()
        val db = BoomerangDatabase.open(context, owner)
        try {
            val repo = BoomerangRepository(db, owner)
            val local = RoomSyncStore(db, owner)
            val content = RecordContent(originalText = "sealed", dueStart = "2099-12-31", dueEnd = "2099-12-31", datePrecision = "DAY",
                capsuleLockedAt = "2026-09-17T00:00:00Z", capsuleUnlockAt = "2099-12-31T16:00:00Z")
            val id = repo.save(content, emptyList())
            val remote = CloudRecordCodec.writable(repo.detail(id)!!.record).put("owner_id", owner).put("revision", 2)
                .put("capsule_locked_at", JSONObject.NULL).put("capsule_unlock_at", JSONObject.NULL)
                .put("created_at", "2026-09-17T00:00:00Z").put("updated_at", "2026-09-17T00:00:00Z")
            local.applyRemoteBatch(listOf(RemoteRecord(remote.toString(), "[]", true)))
            try { local.resolve(id, false); fail("cloud choice must not unseal local capsule") }
            catch (_: IllegalArgumentException) { }
            assertEquals(content.capsuleLockedAt, repo.detail(id)!!.record.content.capsuleLockedAt)
            assertEquals(1, local.conflicts().size)
            local.resolve(id, true)
            assertEquals(content.capsuleLockedAt, repo.detail(id)!!.record.content.capsuleLockedAt)
            assertEquals(content.capsuleUnlockAt, repo.detail(id)!!.record.content.capsuleUnlockAt)
        } finally { db.close(); context.deleteDatabase("boomerang_$owner.db") }
    }
    @Test fun editingRichCloudRecordPreservesProtectedSourcesAndUploadsOnlyManual() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val owner = UUID.randomUUID().toString()
        val db = BoomerangDatabase.open(context, owner)
        try {
            val repo = BoomerangRepository(db, owner)
            val local = RoomSyncStore(db, owner)
            val id = UUID.randomUUID().toString()
            val seed = RecordEntity(id, owner, RecordContent(originalText = "cloud"), 1, 1, "2026-09-17T00:00:00Z", "2026-09-17T00:00:00Z")
            val row = CloudRecordCodec.writable(seed).put("owner_id", owner).put("revision", 1).put("created_at", seed.createdAt).put("updated_at", seed.updatedAt)
            val sources = org.json.JSONArray().apply { repeat(11) { put(JSONObject().put("id", UUID.randomUUID().toString()).put("record_id", id).put("owner_id", owner)
                .put("title", "source $it").put("url", "https://example.org/$it").put("origin", "SERVER").put("verified_by_tool", true)) } }
            local.applyRemoteBatch(listOf(RemoteRecord(row.toString(), sources.toString())))
            repo.save(RecordContent(originalText = "edited"), listOf(com.boomerang.app.domain.SourceInput("manual", "https://example.org/manual")), id, 1)
            val detail = repo.detail(id)!!
            assertEquals(12, detail.sources.size)
            assertEquals(11, detail.sources.count { it.verifiedByTool })
            val pending = local.pendingUploads().single()
            val upload = org.json.JSONArray(pending.sourcesJson)
            assertEquals(1, upload.length()); assertEquals("manual", upload.getJSONObject(0).getString("title"))
        } finally { db.close(); context.deleteDatabase("boomerang_$owner.db") }
    }
    @Test fun remoteConflictPreservesBothVersionsUntilExplicitResolution() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val owner = UUID.randomUUID().toString()
        val db = BoomerangDatabase.open(context, owner)
        try {
            val repo = BoomerangRepository(db, owner)
            val local = RoomSyncStore(db, owner)
            val id = repo.save(RecordContent(originalText = "local"), emptyList())
            val remote = CloudRecordCodec.writable(repo.detail(id)!!.record).put("owner_id", owner).put("revision", 3)
                .put("original_text", "remote").put("created_at", "2026-09-17T00:00:00Z").put("updated_at", "2026-09-17T00:00:00Z")
            assertEquals(1, local.applyRemoteBatch(listOf(RemoteRecord(remote.toString(), "[]", true))))
            assertEquals("local", repo.detail(id)!!.record.content.originalText)
            assertEquals(1, local.conflicts().size)
            local.resolve(id, useLocal = true)
            assertEquals("local", repo.detail(id)!!.record.content.originalText)
            assertEquals(3L, local.pendingUploads().single().serverRevision)
            assertTrue(local.conflicts().isEmpty())
            assertTrue(repo.detail(id)!!.history.any { it.snapshotJson.contains("remote") })
        } finally { db.close(); context.deleteDatabase("boomerang_$owner.db") }
    }
}
