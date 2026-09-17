package com.boomerang.app.extras

import com.boomerang.app.domain.RecordContent
import org.junit.Assert.*
import org.junit.Test

class ReviewPolicyTest {
    private fun row(id: String, status: String? = null, date: String? = null, deleted: String? = null) = ReviewRecord(id,
        RecordContent(originalText = "原话", subject = "张三", topic = "太空", saidAt = date, confirmedStatus = status), "2025-12-31T18:00:00Z", deleted)
    @Test fun annualUsesSelectedTimezoneAndExcludesDeleted() {
        val rows = listOf(row("a", "FULFILLED"), row("b"), row("c", deleted = "2026-01-02T00:00:00Z"))
        val report = ReviewPolicy.annual(rows, 2026, "Asia/Shanghai")
        assertEquals(2, report.total); assertEquals(1, report.confirmed["FULFILLED"]); assertEquals(1, report.unconfirmed)
        assertEquals(0, ReviewPolicy.annual(rows, 2026, "UTC").total)
    }
    @Test fun timelinePreservesUnknownDatesAndUsesExplicitFilters() {
        val rows = listOf(row("unknown"), row("late", date = "2026-02-01"), row("early", date = "2026-01-01"), row("deleted", deleted = "2026-01-01T00:00:00Z"))
        assertEquals(listOf("early", "late", "unknown"), ReviewPolicy.timeline(rows, "张三", "太空").map { it.id })
        assertTrue(ReviewPolicy.timeline(rows, "张", "太空").isEmpty())
        assertEquals(3, ReviewPolicy.timeline(rows, "", "").size)
    }
}
