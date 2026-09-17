package com.boomerang.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CapsulePolicyTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-17T16:00:00Z"), ZoneOffset.UTC)
    private val original = RecordContent(originalText="原话", dueStart="2026-09-18", dueEnd="2026-09-18", datePrecision="DAY", verificationCriteria="验收")
    @Test fun deadlineIncludesWholeLocalDay() {
        assertEquals("2026-09-18T16:00:00Z", CapsulePolicy.unlockAt(original))
        assertTrue(CapsulePolicy.isLocked("2026-09-17T15:00:00Z", "2026-09-18T16:00:00Z", clock))
    }
    @Test fun exactUnlockInstantAllowsEdit() {
        assertFalse(CapsulePolicy.isLocked("2026-09-16T15:00:00Z", "2026-09-17T16:00:00Z", clock))
    }
    @Test fun lockedCoreCannotChangeButSupplementCan() {
        assertFalse(CapsulePolicy.coreUnchanged(original, original.copy(originalText="偷改")))
        assertFalse(CapsulePolicy.coreUnchanged(original, original.copy(timezone="UTC")))
        assertTrue(CapsulePolicy.coreUnchanged(original, original.copy(notes="补充")))
    }
    @Test fun undatedCannotBecomeCapsule() {
        assertThrows(IllegalArgumentException::class.java) { CapsulePolicy.unlockAt(original.copy(dueEnd=null)) }
    }
    @Test fun invalidLockFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) { CapsulePolicy.isLocked(null,"2027-01-01T00:00:00Z",clock) }
    }
}
