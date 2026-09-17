package com.boomerang.app.reminders

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ReminderPolicyTest {
    private val now = Instant.parse("2026-09-17T02:00:00Z")
    @Test fun duplicateScanStableButRevisionChangesKey() {
        val first = ReminderPolicy.plan("r", "local", "2026-09-17", "Asia/Shanghai", true, now, 1)!!
        assertEquals(first.key, ReminderPolicy.plan("r", "local", "2026-09-17", "Asia/Shanghai", true, now.plusSeconds(60), 1)!!.key)
        assertNotEquals(first.key, ReminderPolicy.plan("r", "local", "2026-09-17", "Asia/Shanghai", true, now, 2)!!.key)
    }
    @Test fun malformedDateAndZoneDoNotCrashScan() {
        assertNull(ReminderPolicy.plan("r", "local", "not-a-date", "Asia/Shanghai", true, now))
        assertNull(ReminderPolicy.plan("r", "local", "2026-09-17", "Invalid/Zone", true, now))
    }
    @Test fun choosesOnlyMostRelevantMissedReminder() {
        assertEquals(1, ReminderPolicy.plan("r", "local", "2026-09-18", "Asia/Shanghai", true, now)?.offset)
    }
    @Test fun dateUsesRecordTimezone() {
        assertEquals(0, ReminderPolicy.plan("r", "local", "2026-09-17", "Asia/Shanghai", true, now)?.offset)
        assertEquals(1, ReminderPolicy.plan("r", "local", "2026-09-17", "America/Los_Angeles", true, now)?.offset)
    }
    @Test fun unknownCancelledAndDistantHaveNoReminder() {
        assertNull(ReminderPolicy.plan("r", "local", null, "Asia/Shanghai", true, now))
        assertNull(ReminderPolicy.plan("r", "local", "2026-09-17", "Asia/Shanghai", false, now))
        assertNull(ReminderPolicy.plan("r", "local", "2026-10-01", "Asia/Shanghai", true, now))
    }
    @Test fun overdueGetsOnlyDueReminderAndStableKey() {
        val today=ReminderPolicy.plan("r", "local", "2026-09-17", "Asia/Shanghai", true, now)!!
        val late=ReminderPolicy.plan("r", "local", "2026-09-17", "Asia/Shanghai", true, now.plusSeconds(86400*5))!!
        assertEquals(today.key, late.key)
        assertEquals(0,late.offset)
    }
    @Test fun accountAndDeadlineChangesProduceDifferentKeys() {
        val first=ReminderPolicy.plan("r", "local", "2026-09-17", "Asia/Shanghai", true, now)!!
        assertNotEquals(first.key,ReminderPolicy.plan("r", "user-b", "2026-09-17", "Asia/Shanghai", true, now)!!.key)
        assertNotEquals(first.key,ReminderPolicy.plan("r", "local", "2026-09-16", "Asia/Shanghai", true, now)!!.key)
    }
}
