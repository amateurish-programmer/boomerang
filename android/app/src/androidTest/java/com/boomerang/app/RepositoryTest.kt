package com.boomerang.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boomerang.app.data.BoomerangDatabase
import com.boomerang.app.data.BoomerangRepository
import com.boomerang.app.data.LocalConflictException
import com.boomerang.app.domain.RecordContent
import com.boomerang.app.domain.SourceInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val namespace = UUID.randomUUID().toString()
    private lateinit var db: BoomerangDatabase
    private lateinit var repo: BoomerangRepository
    @Before fun open() { db = BoomerangDatabase.open(context, namespace); repo = BoomerangRepository(db, namespace) }
    @After fun close() { db.close(); context.deleteDatabase("boomerang_$namespace.db") }

    @Test fun saveSurvivesDatabaseCloseAndKeepsSources() = runBlocking {
        val id = repo.save(RecordContent(originalText = "年底读完十二本书"), listOf(SourceInput("官网", "https://example.org/books")))
        db.close(); db = BoomerangDatabase.open(context, namespace); repo = BoomerangRepository(db, namespace)
        assertEquals("年底读完十二本书", repo.detail(id)!!.record.content.originalText)
        assertEquals(1, repo.detail(id)!!.sources.size)
        assertEquals(1, db.records().outbox().size)
    }

    @Test fun eachEditCreatesExactlyOneImmutableSnapshot() = runBlocking {
        val id = repo.save(RecordContent(originalText = "初始原话"), emptyList())
        repo.save(RecordContent(originalText = "修改原话"), emptyList(), id, 1)
        val history = repo.detail(id)!!.history
        assertEquals(listOf(2L, 1L), history.map { it.localRevision })
        assertTrue(history.last().snapshotJson.contains("初始原话"))
        assertEquals(2, db.records().outbox().size)
        try { repo.save(RecordContent(originalText = "过期编辑"), emptyList(), id, 1); fail("stale write accepted") } catch (_: LocalConflictException) { }
        assertEquals(2, repo.detail(id)!!.history.size)
    }

    @Test fun outboxFailureRollsBackRecordRevisionAndSourceChanges() = runBlocking {
        val operation = UUID.randomUUID().toString()
        val id = repo.save(RecordContent(originalText = "原话"), emptyList(), operationId = operation)
        val result = runCatching { repo.save(RecordContent(originalText = "不应提交"), listOf(SourceInput("来源", "https://example.org/story")), id, 1, operation) }
        assertTrue(result.isFailure)
        val detail = repo.detail(id)!!
        assertEquals("原话", detail.record.content.originalText)
        assertEquals(1, detail.history.size)
        assertTrue(detail.sources.isEmpty())
        assertEquals(1, db.records().outbox().size)
    }

    @Test fun softDeleteDisappearsFromLibraryButKeepsExportTombstone() = runBlocking {
        val id = repo.save(RecordContent(originalText = "稍后删除"), emptyList())
        repo.softDelete(id, 1)
        assertTrue(repo.observeRecords().first().isEmpty())
        assertNotNull(repo.exportRecords().single().deletedAt)
        assertEquals(2, repo.detail(id)!!.history.size)
    }
}
