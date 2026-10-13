package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Test

class CaptureTimeQualityTest {
    @Test fun intervalCanBeConsistentWithoutClaimingThatTheDeviceDateIsCorrect() {
        val result = CaptureTimeQuality.assess(1234L, 4234L, 1L, 3_000_000_001L)
        assertEquals(CaptureTimeQuality.Status.CONSISTENT_INTERVAL, result.status)
        assertEquals(3000L, result.durationMillis)
    }
    @Test fun forwardWallClockJumpRetainsTheMonotonicDuration() {
        val result = CaptureTimeQuality.assess(1000L, 100_000L, 1L, 3_000_000_001L)
        assertEquals(CaptureTimeQuality.Status.WALL_CLOCK_DISCONTINUITY, result.status)
        assertEquals(3000L, result.durationMillis)
    }
    @Test fun backwardWallClockJumpIsNotAValidNegativeCaptureDuration() {
        assertEquals(CaptureTimeQuality.Status.WALL_CLOCK_DISCONTINUITY,
            CaptureTimeQuality.assess(100_000L, 1000L, 1L, 3_000_000_001L).status)
    }
    @Test fun recoveredCaptureWithUnknownEndTimeRemainsUnknown() {
        assertEquals(CaptureTimeQuality.Assessment(CaptureTimeQuality.Status.UNKNOWN, null),
            CaptureTimeQuality.assess(1000L, null, 1L, null))
    }
    @Test fun invertedMonotonicClocksCannotProduceADuration() {
        assertEquals(CaptureTimeQuality.Assessment(CaptureTimeQuality.Status.INVALID_MONOTONIC_INTERVAL, null),
            CaptureTimeQuality.assess(1000L, 2000L, 10L, 5L))
    }
    @Test fun extremeWallTimesCannotOverflowIntoAValidInterval() {
        assertEquals(CaptureTimeQuality.Status.WALL_CLOCK_DISCONTINUITY,
            CaptureTimeQuality.assess(Long.MIN_VALUE, Long.MAX_VALUE, 1L, 1000L).status)
    }
}
