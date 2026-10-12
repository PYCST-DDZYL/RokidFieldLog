package org.rokidfieldlog.core

/** One request's callback ownership and deadline, measured on this device only. */
class LocationAttempt(private val startedElapsedNanos: Long) {
    enum class Source { NONE, FUSED, ANDROID_SYSTEM }

    companion object {
        const val TOTAL_TIMEOUT_MILLIS = 20_000L
        const val FUSED_TIMEOUT_MILLIS = 8_000L
    }

    var source = Source.NONE
        private set
    var isOpen = true
        private set

    fun useFused() {
        check(isOpen && source == Source.NONE)
        source = Source.FUSED
    }

    fun isExpired(nowElapsedNanos: Long): Boolean =
        nowElapsedNanos < startedElapsedNanos ||
            nowElapsedNanos - startedElapsedNanos >= TOTAL_TIMEOUT_MILLIS * 1_000_000L

    fun useSystem(nowElapsedNanos: Long): Boolean {
        if (!isOpen || isExpired(nowElapsedNanos) || source == Source.ANDROID_SYSTEM) return false
        source = Source.ANDROID_SYSTEM
        return true
    }

    fun canReceive(expectedSource: Source, nowElapsedNanos: Long): Boolean =
        isOpen && source == expectedSource && source != Source.NONE && !isExpired(nowElapsedNanos)

    fun close() { isOpen = false }
}
