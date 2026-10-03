package com.boomerang.app.domain

enum class InkMotionMode {
    FULL, REDUCED, OFF;

    companion object {
        fun fromPreference(value: String?): InkMotionMode = entries.firstOrNull { it.name == value } ?: FULL
    }
}

/** System limits never rewrite the device's selected preference. */
fun effectiveMotionMode(selected: InkMotionMode, systemScale: Float): InkMotionMode = when {
    selected == InkMotionMode.OFF || systemScale <= 0f -> InkMotionMode.OFF
    selected == InkMotionMode.REDUCED || systemScale < 1f -> InkMotionMode.REDUCED
    else -> InkMotionMode.FULL
}

fun inkMotionEligible(resumed: Boolean, focused: Boolean, home: Boolean, heroVisible: Boolean): Boolean =
    resumed && focused && home && heroVisible

/** Active time only; a new/resumed first frame is zero and stalls never catch up. */
class InkActiveClock {
    private var previous: Long? = null
    fun pause() { previous = null }
    fun frame(nanos: Long): Long {
        val last = previous
        previous = nanos
        return if (last == null) 0L else ((nanos - last) / 1_000_000L).coerceIn(0L, 50L)
    }
}
