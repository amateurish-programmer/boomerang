package com.boomerang.app.domain

import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object RecordRules {
    /** A previous confirmation applies only to the same claim and criteria. */
    fun afterEdit(previous: RecordContent, proposed: RecordContent): RecordContent {
        val changed = previous.recordType != proposed.recordType || previous.originalText != proposed.originalText ||
            previous.subject != proposed.subject || previous.saidAt != proposed.saidAt || previous.dueStart != proposed.dueStart ||
            previous.dueEnd != proposed.dueEnd || previous.dateText != proposed.dateText || previous.datePrecision != proposed.datePrecision ||
            previous.timezone != proposed.timezone || previous.verificationCriteria != proposed.verificationCriteria
        return if (changed) proposed.copy(confirmedStatus = null) else proposed
    }
    fun plusMonths(date: LocalDate, months: Long): LocalDate = date.plusMonths(months)
    fun quarter(year: Int, quarter: Int): Pair<LocalDate, LocalDate> {
        require(quarter in 1..4)
        val start = LocalDate.of(year, (quarter - 1) * 3 + 1, 1)
        return start to start.plusMonths(3).minusDays(1)
    }

    fun validate(value: RecordContent): Map<String, String> = buildMap {
        fun length(key: String, text: String, max: Int, required: Boolean = false) {
            if (required && text.isBlank()) put(key, "请填写此项")
            else if (text.codePointCount(0, text.length) > max) put(key, "最多 $max 字")
        }
        length("originalText", value.originalText, 4000, true)
        length("subject", value.subject, 200, true)
        length("topic", value.topic, 200)
        length("dateText", value.dateText, 500)
        length("verificationCriteria", value.verificationCriteria, 4000)
        length("notes", value.notes, 4000)
        if (value.recordType !in recordTypes) put("recordType", "请选择记录类型")
        if (value.lifecycle !in listOf("ACTIVE", "DRAFT", "CANCELLED")) put("lifecycle", "无效状态")
        if (value.confirmedStatus != null && value.confirmedStatus !in resultStatuses) put("confirmedStatus", "无效结果")
        if (value.timezone !in ZoneId.getAvailableZoneIds()) put("timezone", "请填写有效的 IANA 时区，如 Asia/Shanghai")
        fun date(key: String, text: String?): LocalDate? {
            if (text == null) return null
            val result = runCatching { LocalDate.parse(text) }.getOrNull()
            if (result == null || !text.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) put(key, "请填写有效日期：YYYY-MM-DD")
            return result
        }
        date("saidAt", value.saidAt)
        val start = date("dueStart", value.dueStart)
        val end = date("dueEnd", value.dueEnd)
        if ((value.dueStart == null) != (value.dueEnd == null)) put("dueEnd", "起止日期需要同时填写，或同时留空")
        if (start != null && end != null && end < start) put("dueEnd", "结束日期不能早于开始日期")
        if (value.datePrecision !in datePrecisions || (value.datePrecision == "UNKNOWN") != (value.dueEnd == null)) {
            put("datePrecision", "无期限请选择未知；有期限请选择对应精度")
        }
        if (start != null && end != null) {
            val valid = when (value.datePrecision) {
                "DAY" -> start == end
                "MONTH" -> start.dayOfMonth == 1 && end == start.plusMonths(1).minusDays(1)
                "QUARTER" -> start.dayOfMonth == 1 && start.monthValue in listOf(1, 4, 7, 10) && end == start.plusMonths(3).minusDays(1)
                "YEAR" -> start.dayOfYear == 1 && end == start.plusYears(1).minusDays(1)
                else -> true
            }
            if (!valid) put("datePrecision", "起止日期应覆盖所选精度的完整区间")
        }
    }

    /** Matches the server's public HTTPS URL policy; never resolves or fetches input. */
    fun validSourceUrl(url: String): Boolean {
        if (url.length > 2048 || !Regex("^https://[A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}(:443)?(/[^\\s]*)?$").matches(url)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.userInfo != null || uri.fragment != null || host.split('.').any { it in setOf("localhost", "local", "internal", "test", "invalid") }) return false
        return true
    }

    fun sourceErrors(sources: List<SourceInput>): Map<String, String> = buildMap {
        if (sources.size > 10) put("sources", "最多保留 10 个来源")
        if (sources.distinct().size != sources.size) put("sources", "请移除重复来源")
        sources.forEachIndexed { index, source ->
            if (source.title.isBlank() || source.title.codePointCount(0, source.title.length) > 500) put("sourceTitle$index", "来源标题为 1～500 字")
            if (!validSourceUrl(source.url)) put("sourceUrl$index", "请填写公开网站的 HTTPS 链接")
        }
    }

    fun countdown(value: RecordContent, clock: Clock): String {
        if (value.lifecycle == "CANCELLED") return "已取消"
        value.confirmedStatus?.let { return resultStatuses[it] ?: "已确认" }
        if (value.lifecycle == "DRAFT") return "草稿"
        val end = value.dueEnd?.let(LocalDate::parse) ?: return "未设期限"
        val days = ChronoUnit.DAYS.between(LocalDate.now(clock.withZone(ZoneId.of(value.timezone))), end)
        return when { days < 0 -> "待复核"; days == 0L -> "今天到期"; else -> "还有 $days 天" }
    }
}
