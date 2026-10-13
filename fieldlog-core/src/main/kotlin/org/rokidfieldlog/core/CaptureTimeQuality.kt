package org.rokidfieldlog.core

/** An interval check on one device; it does not validate the absolute date or align devices. */
object CaptureTimeQuality {
    private const val WALL_CLOCK_TOLERANCE_MILLIS = 2_000L
    enum class Status { CONSISTENT_INTERVAL, WALL_CLOCK_DISCONTINUITY, INVALID_MONOTONIC_INTERVAL, UNKNOWN }
    data class Assessment(val status: Status, val durationMillis: Long?)

    fun assess(startUtc: Long?, endUtc: Long?, startElapsed: Long?, endElapsed: Long?): Assessment {
        if (startElapsed == null || endElapsed == null) return Assessment(Status.UNKNOWN, null)
        if (startElapsed <= 0 || endElapsed < startElapsed) return Assessment(Status.INVALID_MONOTONIC_INTERVAL, null)
        val duration = (endElapsed - startElapsed) / 1_000_000
        if (startUtc == null || endUtc == null) return Assessment(Status.UNKNOWN, duration)
        val wallDuration = try { Math.subtractExact(endUtc, startUtc) }
        catch (_: ArithmeticException) { return Assessment(Status.WALL_CLOCK_DISCONTINUITY, duration) }
        val consistent = wallDuration >= duration - WALL_CLOCK_TOLERANCE_MILLIS &&
            wallDuration <= duration + WALL_CLOCK_TOLERANCE_MILLIS
        return Assessment(if (consistent) Status.CONSISTENT_INTERVAL else Status.WALL_CLOCK_DISCONTINUITY, duration)
    }
}
