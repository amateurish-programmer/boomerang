package com.boomerang.app

import com.boomerang.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class InkMotionPolicyTest {
    @Test fun systemScaleCapsFullWithoutChangingUserChoice() {
        assertEquals(InkMotionMode.REDUCED, effectiveMotionMode(InkMotionMode.FULL, .5f))
        assertEquals(InkMotionMode.FULL, effectiveMotionMode(InkMotionMode.FULL, 1f))
        assertEquals(InkMotionMode.FULL, effectiveMotionMode(InkMotionMode.FULL, 2f))
        assertEquals(InkMotionMode.REDUCED, effectiveMotionMode(InkMotionMode.REDUCED, 2f))
    }
    @Test fun zeroScaleStopsEveryUserMode() {
        InkMotionMode.entries.forEach { assertEquals(InkMotionMode.OFF, effectiveMotionMode(it, 0f)) }
    }
    @Test fun userOffIsNeverEnabledBySystem() {
        assertEquals(InkMotionMode.OFF, effectiveMotionMode(InkMotionMode.OFF, .5f))
        assertEquals(InkMotionMode.OFF, effectiveMotionMode(InkMotionMode.OFF, 1f))
    }
    @Test fun invalidPreferenceUsesSafeDefault() {
        assertEquals(InkMotionMode.FULL, InkMotionMode.fromPreference(null))
        assertEquals(InkMotionMode.FULL, InkMotionMode.fromPreference("invalid"))
        assertEquals(InkMotionMode.REDUCED, InkMotionMode.fromPreference("REDUCED"))
    }
    @Test fun everyVisibilityGuardStopsDecoration() {
        assertTrue(inkMotionEligible(true, true, true, true))
        assertFalse(inkMotionEligible(false, true, true, true))
        assertFalse(inkMotionEligible(true, false, true, true))
        assertFalse(inkMotionEligible(true, true, false, true))
        assertFalse(inkMotionEligible(true, true, true, false))
    }
    @Test fun activeClockNeverCatchesUpAfterPause() {
        val clock = InkActiveClock()
        assertEquals(0L, clock.frame(1_000_000_000L))
        assertEquals(16L, clock.frame(1_016_000_000L))
        clock.pause()
        assertEquals(0L, clock.frame(99_000_000_000L))
        assertEquals(20L, clock.frame(99_020_000_000L))
    }
    @Test fun delayedAndBackwardFramesStayBounded() {
        val clock = InkActiveClock()
        clock.frame(1_000_000_000L)
        assertEquals(50L, clock.frame(2_000_000_000L))
        assertEquals(0L, clock.frame(1_500_000_000L))
    }
}
