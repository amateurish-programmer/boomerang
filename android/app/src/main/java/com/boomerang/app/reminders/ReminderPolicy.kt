package com.boomerang.app.reminders

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ReminderPlan(val key: String, val offset: Int, val daysRemaining: Long)

object ReminderPolicy {
    fun plan(id: String, owner: String, dueEnd: String?, timezone: String, active: Boolean, now: Instant, revision: Long = 1): ReminderPlan? {
        if (!active || dueEnd == null) return null
        val days = runCatching { ChronoUnit.DAYS.between(now.atZone(ZoneId.of(timezone)).toLocalDate(), LocalDate.parse(dueEnd)) }.getOrNull() ?: return null
        val offset = when { days <= 0 -> 0; days <= 1 -> 1; days <= 7 -> 7; else -> return null }
        // Revision is part of the delivery contract; only the latest applicable offset is emitted.
        return ReminderPlan("$owner/$id/$revision/$dueEnd/$offset", offset, days)
    }
}
