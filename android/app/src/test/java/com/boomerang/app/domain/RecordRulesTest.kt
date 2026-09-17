package com.boomerang.app.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecordRulesTest {
    private fun clock(instant: String) = Clock.fixed(Instant.parse(instant), ZoneId.of("UTC"))
    private val dated = RecordContent(originalText = "今年读十二本书", dueStart = "2026-09-17", dueEnd = "2026-09-17", datePrecision = "DAY")

    @Test fun dueDayIsInclusiveInRecordTimezone() {
        assertEquals("今天到期", RecordRules.countdown(dated, clock("2026-09-17T15:59:59Z")))
        assertEquals("待复核", RecordRules.countdown(dated, clock("2026-09-17T16:00:00Z")))
    }
    @Test fun calendarMonthsClampAtLeapFebruary() {
        assertEquals(LocalDate.of(2024, 2, 29), RecordRules.plusMonths(LocalDate.of(2024, 1, 31), 1))
        assertEquals(LocalDate.of(2025, 2, 28), RecordRules.plusMonths(LocalDate.of(2025, 1, 31), 1))
    }
    @Test fun quarterRetainsFullInterval() {
        assertEquals(LocalDate.of(2026, 4, 1) to LocalDate.of(2026, 6, 30), RecordRules.quarter(2026, 2))
    }
    @Test fun milestoneCanHaveNoDeadline() {
        val item = RecordContent(recordType = "MILESTONE", originalText = "完成作品")
        assertTrue(RecordRules.validate(item).isEmpty())
        assertEquals("未设期限", RecordRules.countdown(item, clock("2026-09-17T00:00:00Z")))
    }
    @Test fun reversedOrIncompleteRangeIsRejected() {
        assertTrue(RecordRules.validate(dated.copy(dueEnd = "2026-09-16")).containsKey("dueEnd"))
        assertTrue(RecordRules.validate(dated.copy(dueEnd = null)).containsKey("dueEnd"))
    }
    @Test fun invalidDateAndPrecisionAreRejected() {
        assertTrue(RecordRules.validate(dated.copy(saidAt = "2025-02-29")).containsKey("saidAt"))
        assertTrue(RecordRules.validate(dated.copy(datePrecision = "UNKNOWN")).containsKey("datePrecision"))
    }
    @Test fun blankAndOversizedOriginalAreRejectedWithoutTruncation() {
        assertTrue(RecordRules.validate(dated.copy(originalText = "  ")).containsKey("originalText"))
        assertTrue(RecordRules.validate(dated.copy(originalText = "中".repeat(4001))).containsKey("originalText"))
    }
    @Test fun onlyPublicHttpSourceUrlsAreAccepted() {
        assertTrue(RecordRules.sourceErrors(listOf(SourceInput("来源", "https://example.org/story"))).isEmpty())
        listOf("http://localhost/x", "http://127.0.0.1", "https://192.168.1.2", "file:///etc/passwd", "https://user:secret@example.org", "https://[::1]/", "https://2130706433").forEach {
            assertTrue(it, RecordRules.sourceErrors(listOf(SourceInput("来源", it))).isNotEmpty())
        }
    }
    @Test fun cancelledAndConfirmedRecordsDoNotShowDueCountdown() {
        assertEquals("已取消", RecordRules.countdown(dated.copy(lifecycle = "CANCELLED"), clock("2026-09-18T00:00:00Z")))
        assertEquals("已达成", RecordRules.countdown(dated.copy(confirmedStatus = "FULFILLED"), clock("2026-09-18T00:00:00Z")))
    }

    @Test fun semanticEditInvalidatesConfirmationButNotesAndTopicDoNot() {
        val confirmed = dated.copy(confirmedStatus = "FULFILLED")
        assertNull(RecordRules.afterEdit(confirmed, confirmed.copy(originalText = "新的承诺")).confirmedStatus)
        assertNull(RecordRules.afterEdit(confirmed, confirmed.copy(verificationCriteria = "新的标准")).confirmedStatus)
        assertEquals("FULFILLED", RecordRules.afterEdit(confirmed, confirmed.copy(notes = "补充备注", topic = "阅读")).confirmedStatus)
    }
}
