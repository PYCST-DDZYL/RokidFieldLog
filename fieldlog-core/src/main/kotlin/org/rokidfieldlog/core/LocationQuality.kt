package org.rokidfieldlog.core

/** Policy for observations on the same device; never compare different devices' uptime. */
object LocationQuality {
    const val MAX_AGE_MILLIS = 5_000L

    fun rejectionReason(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        fixElapsedNanos: Long,
        receivedElapsedNanos: Long,
    ): String? = when {
        !latitude.isFinite() || latitude !in -90.0..90.0 -> "INVALID_LATITUDE"
        !longitude.isFinite() || longitude !in -180.0..180.0 -> "INVALID_LONGITUDE"
        accuracyMeters != null && (!accuracyMeters.isFinite() || accuracyMeters < 0f) -> "INVALID_ACCURACY"
        fixElapsedNanos <= 0L -> "MISSING_MONOTONIC_TIME"
        fixElapsedNanos > receivedElapsedNanos -> "FUTURE_MONOTONIC_TIME"
        receivedElapsedNanos - fixElapsedNanos > MAX_AGE_MILLIS * 1_000_000L -> "STALE_LOCATION"
        else -> null
    }
}
