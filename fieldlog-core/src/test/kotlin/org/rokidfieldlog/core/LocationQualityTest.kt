package org.rokidfieldlog.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationQualityTest {
    @Test fun oldPositionIsRejectedEvenWithSmallAccuracy() {
        assertEquals("STALE_LOCATION", reason(fix = 1_000_000_000L, received = 7_000_000_001L))
    }

    @Test fun futureUptimeIsRejected() {
        assertEquals("FUTURE_MONOTONIC_TIME", reason(fix = 8_000_000_000L, received = 7_000_000_000L))
    }

    @Test fun zeroCoordinatesAreValidObservationsRatherThanMissingData() {
        assertNull(LocationQuality.rejectionReason(0.0, 0.0, null, 1L, 1L))
    }

    @Test fun invalidNumbersAreRejected() {
        assertEquals("INVALID_LATITUDE", LocationQuality.rejectionReason(Double.NaN, 10.0, 1f, 1L, 1L))
        assertEquals("INVALID_ACCURACY", LocationQuality.rejectionReason(10.0, 10.0, Float.POSITIVE_INFINITY, 1L, 1L))
    }

    @Test fun recentCoarseLocationCanBeKeptWithItsActualAccuracy() {
        assertNull(LocationQuality.rejectionReason(10.0, 10.0, 2000f, 1_000_000_000L, 6_000_000_000L))
    }

    private fun reason(fix: Long, received: Long) =
        LocationQuality.rejectionReason(10.0, 10.0, 1f, fix, received)
}
