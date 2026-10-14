package org.rokidfieldlog.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rokidfieldlog.core.LocationAttempt.Source

class LocationAttemptTest {
    private val started = 1_000_000_000L
    private fun time(milliseconds: Long) = started + milliseconds * 1_000_000L

    @Test fun systemFallbackHasTimeToReceiveARealProviderCallback() {
        val request = LocationAttempt(started)
        request.useFused()
        assertTrue(request.useSystem(time(8_000)))
        assertTrue(request.canReceive(Source.ANDROID_SYSTEM, time(59_999)))
        assertFalse(request.canReceive(Source.ANDROID_SYSTEM, time(60_000)))
    }

    @Test fun fusedCallbackQueuedBeforeFallbackCannotOverwriteSystemResult() {
        val request = LocationAttempt(started)
        request.useFused()
        request.useSystem(time(8_000))
        assertFalse(request.canReceive(Source.FUSED, time(8_001)))
        assertTrue(request.canReceive(Source.ANDROID_SYSTEM, time(8_001)))
        assertFalse(request.useSystem(time(8_001)))
    }

    @Test fun cancelledRequestCannotReceiveAfterNewRequestStarts() {
        val oldRequest = LocationAttempt(started)
        oldRequest.useFused()
        oldRequest.close()
        val newRequest = LocationAttempt(time(2_000))
        newRequest.useFused()
        assertFalse(oldRequest.canReceive(Source.FUSED, time(2_001)))
        assertTrue(newRequest.canReceive(Source.FUSED, time(2_001)))
    }

    @Test fun delayedMainThreadCannotAcceptFixAfterDeadlineEvenBeforeTimeoutRunnable() {
        val request = LocationAttempt(started)
        request.useFused()
        assertTrue(request.isExpired(time(60_001)))
        assertFalse(request.canReceive(Source.FUSED, time(60_001)))
        assertFalse(request.useSystem(time(60_001)))
    }

    @Test fun nonGmsRequestReceivesTheFullOverallWindow() {
        val request = LocationAttempt(started)
        assertTrue(request.useSystem(time(0)))
        assertTrue(request.canReceive(Source.ANDROID_SYSTEM, time(59_999)))
        assertFalse(request.canReceive(Source.FUSED, time(1)))
    }
}
