package com.boomerang.app.extras

import com.boomerang.app.domain.RecordContent
import java.time.Instant
import java.time.ZoneId

data class ReviewRecord(val id: String, val content: RecordContent, val createdAt: String, val deletedAt: String? = null)
data class AnnualReport(val year: Int, val timezone: String, val total: Int, val confirmed: Map<String, Int>, val unconfirmed: Int)

object ReviewPolicy {
    fun annual(records: List<ReviewRecord>, year: Int, timezone: String): AnnualReport {
        require(year in 1900..9999)
        val zone = ZoneId.of(timezone)
        val selected = records.filter { it.deletedAt == null && Instant.parse(it.createdAt).atZone(zone).year == year }
        return AnnualReport(year, timezone, selected.size,
            selected.mapNotNull { it.content.confirmedStatus }.groupingBy { it }.eachCount().toSortedMap(),
            selected.count { it.content.confirmedStatus == null })
    }
    fun timeline(records: List<ReviewRecord>, subject: String, topic: String): List<ReviewRecord> = records
        .filter { it.deletedAt == null && (subject.isBlank() || it.content.subject == subject.trim()) && (topic.isBlank() || it.content.topic == topic.trim()) }
        .sortedWith(compareBy<ReviewRecord> { it.content.saidAt == null }.thenBy { it.content.saidAt }.thenBy { it.id })
}
