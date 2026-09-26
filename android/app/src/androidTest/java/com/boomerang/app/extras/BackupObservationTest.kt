package com.boomerang.app.extras

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.boomerang.app.data.*
import com.boomerang.app.domain.RecordContent
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** Exercises the long-lived library reader and short-lived Extras writer used by the app. */
class BackupObservationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owner = UUID.randomUUID().toString()
    private val otherOwner = UUID.randomUUID().toString()
    private lateinit var reader: BoomerangDatabase
    private lateinit var otherReader: BoomerangDatabase
    private lateinit var records: BoomerangRepository

    @Before fun open() {
        reader = BoomerangDatabase.open(context, owner)
        otherReader = BoomerangDatabase.open(context, otherOwner)
        records = BoomerangRepository(reader, owner)
    }

    @After fun close() {
        reader.close()
        otherReader.close()
        context.deleteDatabase("boomerang_$owner.db")
        context.deleteDatabase("boomerang_$otherOwner.db")
    }

    @Test fun newImportReachesAlreadyObservingLibraryAfterWriterCloses() = runBlocking {
        val incoming = backupRecord(UUID.randomUUID().toString())
        val emissions = Channel<List<RecordEntity>>(Channel.UNLIMITED)
        val collection = launch { records.observeRecords().collect { emissions.send(it) } }
        try {
            // Receiving the initial query ensures observation is active before any import write.
            assertTrue(withTimeout(5_000) { emissions.receive() }.isEmpty())
            assertEquals(1, importThroughSeparateInstances(incoming, ImportChoice.SKIP_EXISTING))

            // Persistence and history must succeed even if the independently observed Flow is stale.
            val persisted = records.detail(incoming.record.id)!!
            assertEquals("restored version two", persisted.record.content.originalText)
            assertEquals(3, persisted.history.size)
            assertEquals(listOf(3L, 2L, 1L), persisted.history.map { it.localRevision })
            assertNull(BoomerangRepository(otherReader, otherOwner).detail(incoming.record.id))
            assertTrue(otherReader.records().allIncludingDeleted().isEmpty())

            val observed = awaitRecord(emissions, incoming.record.id, "restored version two")
            assertNotNull("Import was persisted, but the active library did not observe it after the writer closed", observed)
            assertEquals(owner, observed!!.ownerNamespace)
            assertEquals(3L, observed.localRevision)
        } finally {
            collection.cancelAndJoin()
            emissions.close()
        }
    }

    @Test fun replacementReachesAlreadyObservingLibraryAndKeepsLocalHistory() = runBlocking {
        val id = records.save(RecordContent(originalText = "local version one"), emptyList())
        records.save(RecordContent(originalText = "local version two"), emptyList(), id, 1)
        val originalHistory = records.detail(id)!!.history
        val emissions = Channel<List<RecordEntity>>(Channel.UNLIMITED)
        val collection = launch { records.observeRecords().collect { emissions.send(it) } }
        try {
            assertEquals("local version two", withTimeout(5_000) { emissions.receive() }.single().content.originalText)
            assertEquals(1, importThroughSeparateInstances(backupRecord(id), ImportChoice.REPLACE_EXISTING))

            val persisted = records.detail(id)!!
            assertEquals("restored version two", persisted.record.content.originalText)
            assertEquals(5, persisted.history.size)
            assertEquals(originalHistory, persisted.history.filter { it.localRevision <= 2 })
            assertEquals(5L, persisted.record.localRevision)

            val observed = awaitRecord(emissions, id, "restored version two")
            assertNotNull("Replacement was persisted, but the active library retained the old record after the writer closed", observed)
            assertEquals(5L, observed!!.localRevision)
        } finally {
            collection.cancelAndJoin()
            emissions.close()
        }
    }

    private suspend fun importThroughSeparateInstances(detail: RecordDetail, choice: ImportChoice): Int {
        val bytes = BackupCodec.encode(Backup(owner, listOf(detail)))
        val previewDatabase = BoomerangDatabase.open(context, owner)
        val preview = try {
            ExtrasRepository(previewDatabase, owner).preview(bytes)
        } finally {
            previewDatabase.close()
        }
        val writer = BoomerangDatabase.open(context, owner)
        assertNotSame(reader, writer)
        return try {
            ExtrasRepository(writer, owner).importBackup(preview, choice)
        } finally {
            // ExtrasViewModel closes the import database immediately; do not wait for notification.
            writer.close()
        }
    }

    private suspend fun awaitRecord(emissions: Channel<List<RecordEntity>>, id: String, text: String): RecordEntity? =
        withTimeoutOrNull(5_000) {
            var found: RecordEntity? = null
            while (found == null) {
                found = emissions.receive().firstOrNull { it.id == id && it.content.originalText == text }
            }
            found
        }

    private fun backupRecord(id: String): RecordDetail {
        val first = RecordEntity(id, owner, RecordContent(originalText = "restored version one"), 1, 0,
            "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        val second = first.copy(content = RecordContent(originalText = "restored version two"), localRevision = 2,
            updatedAt = "2026-01-02T00:00:00Z")
        return RecordDetail(second, emptyList(), listOf(first, second).map {
            RevisionEntity(id, it.localRevision, RecordSnapshot.encode(it, emptyList()), it.updatedAt)
        })
    }
}
