package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Test
import org.rokidfieldlog.core.LocationAttempt.Source

class LocationCoordinatorTest {
    private class FakeTime : FieldClock, ProbeScheduler {
        var now = 1_000_000_000L
        private data class Job(val at: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val jobs = mutableListOf<Job>()
        override fun utcMillis() = 123456L
        override fun elapsedNanos() = now
        override fun after(delayMillis: Long, task: () -> Unit): CancelHandle {
            val job = Job(now + delayMillis * 1_000_000, task)
            jobs.add(job)
            return CancelHandle { job.cancelled = true }
        }
        fun advance(milliseconds: Long) {
            val target = now + milliseconds * 1_000_000
            while (true) {
                val next = jobs.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                jobs.remove(next)
                now = next.at
                next.task()
            }
            now = target
        }
        fun jump(milliseconds: Long) { now += milliseconds * 1_000_000 }
    }
    private class FakeSource : LocationSource {
        data class Subscription(val fix: (LocationObservation) -> Unit, val fail: (String) -> Unit, var cancelled: Boolean = false)
        val subscriptions = mutableListOf<Subscription>()
        var immediate: LocationObservation? = null
        override fun start(onFix: (LocationObservation) -> Unit, onUnavailable: (String) -> Unit): CancelHandle {
            val subscription = Subscription(onFix, onUnavailable)
            subscriptions.add(subscription)
            immediate?.let(onFix)
            return CancelHandle { subscription.cancelled = true }
        }
        // Deliberately delivers even cancelled subscriptions to model queued platform callbacks.
        fun emit(fix: LocationObservation, index: Int = subscriptions.lastIndex) = subscriptions[index].fix(fix)
        fun fail(reason: String = "PROVIDER_ERROR") = subscriptions.last().fail(reason)
    }
    private val time = FakeTime()
    private val fused = FakeSource()
    private val system = FakeSource()
    private val results = mutableListOf<LocationCompletion>()
    private val progress = mutableListOf<LocationUpdate>()
    private val coordinator = LocationCoordinator(time, time, fused, system)
    private fun start(force: Boolean = false) = coordinator.start(force, onProgress = progress::add, onComplete = results::add)
    private fun fix(accuracy: Float? = 10f) = LocationObservation(12.3, -45.6, accuracyMeters = accuracy,
        locationTimeUtcMillis = 123456L, locationElapsedRealtimeNanos = time.now, provider = "synthetic")

    @Test fun fusedTimeoutSwitchesOnceAndIgnoresLateFusedSuccessAndFailure() {
        start()
        time.advance(8_000)
        assertTrue(fused.subscriptions.single().cancelled)
        assertEquals(1, system.subscriptions.size)
        fused.emit(fix())
        fused.fail()
        assertTrue(results.isEmpty())
        assertEquals(1, system.subscriptions.size)
        system.emit(fix())
        assertEquals(Source.ANDROID_SYSTEM, results.single().source)
    }

    @Test fun fusedFailureStartsSystemImmediatelyAndCancelsItsTimerAfterSuccess() {
        start()
        fused.fail()
        system.emit(fix())
        time.advance(60_000)
        assertEquals(1, results.size)
        assertEquals(1, system.subscriptions.size)
        assertTrue(system.subscriptions.single().cancelled)
    }

    @Test fun coldGpsCanSucceedAfterThirtySeconds() {
        start(force = true)
        time.advance(45_000)
        system.emit(fix())
        assertEquals("SUCCESS", results.single().status)
    }

    @Test fun totalDeadlineRejectsLateFixAndReportsOnlyOnce() {
        start(force = true)
        time.advance(60_000)
        system.emit(fix())
        assertEquals("TIMEOUT", results.single().reason)
        assertTrue(system.subscriptions.single().cancelled)
    }

    @Test fun delayedTimeoutRunnableCannotLetLateCallbackPass() {
        start(force = true)
        time.jump(60_001)
        system.emit(fix())
        assertEquals("FAILED", results.single().status)
        assertNull(results.single().observation)
    }

    @Test fun cancellationAndRestartRejectOldProviderCallbacks() {
        start(force = true)
        coordinator.cancel()
        start(force = true)
        system.emit(fix(), index = 0)
        assertEquals(1, results.size)
        system.emit(fix(), index = 1)
        assertEquals(listOf("CANCELLED", "SUCCESS"), results.map { it.status })
    }

    @Test fun coarseFusedFixIsProvisionalAndSystemCanImproveIt() {
        start()
        fused.emit(fix(500f))
        assertEquals(1, progress.size)
        assertTrue(results.isEmpty())
        assertEquals(1, system.subscriptions.size)
        system.emit(fix(20f))
        assertEquals(20f, results.single().observation!!.accuracyMeters)
        assertEquals("TARGET_ACCURACY_REACHED", results.single().quality)
    }

    @Test fun deadlineCanUseRecentCoarseFixWithExplicitQuality() {
        start(force = true)
        time.advance(58_000)
        system.emit(fix(500f))
        time.advance(2_000)
        assertEquals("SUCCESS", results.single().status)
        assertEquals("BEST_AVAILABLE_AT_DEADLINE", results.single().quality)
        assertEquals(500f, results.single().observation!!.accuracyMeters)
    }

    @Test fun freshCoarseFixRemainsAvailableWhenMoreAccurateProvisionalFixExpires() {
        start(force = true)
        time.advance(54_999)
        system.emit(fix(100f))
        time.advance(5_000)
        system.emit(fix(500f))
        time.advance(1)
        assertEquals("SUCCESS", results.single().status)
        assertEquals(500f, results.single().observation!!.accuracyMeters)
    }

    @Test fun staleProvisionalFixIsNotPromotedAtDeadline() {
        start(force = true)
        system.emit(fix(500f))
        time.advance(60_000)
        assertEquals("FAILED", results.single().status)
        assertNull(results.single().observation)
    }

    @Test fun mockStaleAndInvalidFixesAreRejectedUntilFreshFixArrives() {
        start(force = true)
        system.emit(fix().copy(isMock = true))
        system.emit(fix().copy(locationElapsedRealtimeNanos = time.now - 6_000_000_000L))
        system.emit(fix().copy(latitude = Double.NaN))
        assertTrue(progress.isEmpty())
        assertTrue(results.isEmpty())
        system.emit(fix())
        assertEquals("SUCCESS", results.single().status)
    }

    @Test fun approximatePermissionCanReturnAValidCoarseFixWithoutGpsWait() {
        coordinator.start(true, approximate = true, onComplete = results::add)
        system.emit(fix(1_000f))
        assertEquals("APPROXIMATE_PERMISSION", results.single().quality)
    }

    @Test fun synchronousProviderSuccessReleasesTheReturnedHandle() {
        system.immediate = fix()
        start(force = true)
        assertEquals("SUCCESS", results.single().status)
        assertTrue(system.subscriptions.single().cancelled)
        time.advance(60_000)
        assertEquals(1, results.size)
    }

    @Test fun completionCallbackCanStartAnotherRequest() {
        coordinator.start(true, onComplete = {
            results.add(it)
            coordinator.start(true, onComplete = results::add)
        })
        system.emit(fix(), index = 0)
        system.emit(fix(), index = 0)
        assertEquals(1, results.size)
        system.emit(fix(), index = 1)
        assertEquals(2, results.size)
        assertTrue(system.subscriptions.all { it.cancelled })
    }

    @Test fun systemProviderFailureKeepsNullCoordinatesAndCancelsDeadline() {
        start(force = true)
        system.fail("NO_ENABLED_PROVIDER")
        time.advance(60_000)
        assertEquals("NO_ENABLED_PROVIDER", results.single().reason)
        assertNull(results.single().observation)
    }
}
