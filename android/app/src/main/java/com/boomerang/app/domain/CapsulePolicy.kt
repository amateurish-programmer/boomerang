package com.boomerang.app.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Clock is injected; locked source words and date semantics cannot be rewritten. */
object CapsulePolicy {
    fun unlockAt(content: RecordContent): String {
        require(content.dueEnd != null) { "请先填写时间胶囊的截止日期" }
        return LocalDate.parse(content.dueEnd).plusDays(1).atStartOfDay(ZoneId.of(content.timezone)).toInstant().toString()
    }
    fun isLocked(lockedAt: String?, unlockAt: String?, clock: Clock): Boolean {
        require((lockedAt == null) == (unlockAt == null)) { "时间胶囊信息不完整" }
        if (lockedAt == null) return false
        val start = runCatching { Instant.parse(lockedAt) }.getOrElse { throw IllegalArgumentException("时间胶囊时间无效") }
        val end = runCatching { Instant.parse(unlockAt) }.getOrElse { throw IllegalArgumentException("时间胶囊时间无效") }
        require(end > start) { "时间胶囊解锁时间无效" }
        return clock.instant() < end
    }
    fun coreUnchanged(before: RecordContent, after: RecordContent): Boolean =
        before.originalText == after.originalText && before.saidAt == after.saidAt &&
        before.dueStart == after.dueStart && before.dueEnd == after.dueEnd &&
        before.dateText == after.dateText && before.datePrecision == after.datePrecision &&
        before.timezone == after.timezone && before.verificationCriteria == after.verificationCriteria

    fun validateChange(before: RecordContent?, after: RecordContent, clock: Clock) {
        isLocked(after.capsuleLockedAt, after.capsuleUnlockAt, clock)
        if (before?.capsuleLockedAt != null) {
            require(before.capsuleLockedAt == after.capsuleLockedAt && before.capsuleUnlockAt == after.capsuleUnlockAt) { "已封存的时间胶囊不能修改解锁约定" }
            require(!isLocked(before.capsuleLockedAt, before.capsuleUnlockAt, clock) || coreUnchanged(before, after)) { "时间胶囊尚未到期，只能补充备注" }
        }
    }
}
