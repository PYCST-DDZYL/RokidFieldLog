package org.rokidfieldlog.core

import org.rokidfieldlog.core.LocationAttempt.Source

fun interface CancelHandle { fun cancel() }
fun interface ProbeScheduler { fun after(delayMillis: Long, task: () -> Unit): CancelHandle }
interface LocationSource {
    fun start(onFix: (LocationObservation) -> Unit, onUnavailable: (String) -> Unit): CancelHandle
}

data class LocationUpdate(val observation: LocationObservation, val source: Source)
data class LocationCompletion(
    val status: String,
    val source: Source,
    val observation: LocationObservation? = null,
    val reason: String? = null,
    val quality: String? = null,
)

/** All entry points/callbacks run on one owner thread (the Android main looper).
 * Platform providers may still call back after cancellation; attempt identity rejects them.
 */
class LocationCoordinator(
    private val clock: FieldClock,
    private val scheduler: ProbeScheduler,
    private val fused: LocationSource?,
    private val system: LocationSource,
) {
    private class Request(val attempt: LocationAttempt, val onProgress: (LocationUpdate) -> Unit,
        val onComplete: (LocationCompletion) -> Unit, val targetAccuracy: Float) {
        val timers = mutableListOf<CancelHandle>()
        var provider: CancelHandle? = null
        val recentFixes = mutableListOf<LocationUpdate>()
        var lastRejection: String? = null
    }
    private var current: Request? = null

    fun start(forceSystem: Boolean, approximate: Boolean = false,
        onProgress: (LocationUpdate) -> Unit = {}, onComplete: (LocationCompletion) -> Unit) {
        cancel()
        val request = Request(LocationAttempt(clock.elapsedNanos()), onProgress, onComplete,
            if (approximate) Float.MAX_VALUE else 50f)
        current = request
        request.timers += scheduler.after(LocationAttempt.TOTAL_TIMEOUT_MILLIS) { deadline(request) }
        if (forceSystem || fused == null) startSystem(request) else {
            request.attempt.useFused()
            request.timers += scheduler.after(LocationAttempt.FUSED_TIMEOUT_MILLIS) { startSystem(request) }
            subscribe(request, Source.FUSED, fused)
        }
    }

    private fun subscribe(request: Request, source: Source, provider: LocationSource) {
        val handle = try {
            provider.start({ fix -> receive(request, source, fix) }, { reason ->
                if (allowed(request, source)) {
                    if (source == Source.FUSED) startSystem(request)
                    else systemUnavailable(request, reason)
                }
            })
        } catch (_: Exception) {
            if (allowed(request, source)) {
                if (source == Source.FUSED) startSystem(request)
                else systemUnavailable(request, "PROVIDER_ERROR")
            }
            return
        }
        // A provider can complete synchronously during start(). Never overwrite a fallback's handle.
        if (current === request && request.attempt.source == source) request.provider = handle else handle.cancel()
    }

    private fun startSystem(request: Request) {
        if (current !== request) return
        val now = clock.elapsedNanos()
        if (request.attempt.isExpired(now)) { deadline(request); return }
        if (!request.attempt.useSystem(now)) return
        request.provider?.cancel()
        request.provider = null
        subscribe(request, Source.ANDROID_SYSTEM, system)
    }

    private fun allowed(request: Request, source: Source): Boolean {
        if (current !== request) return false
        val now = clock.elapsedNanos()
        if (request.attempt.isExpired(now)) { deadline(request); return false }
        return request.attempt.canReceive(source, now)
    }

    private fun receive(request: Request, source: Source, fix: LocationObservation) {
        if (!allowed(request, source)) return
        val now = clock.elapsedNanos()
        val rejection = when {
            fix.isMock -> "MOCK_LOCATION_REJECTED"
            fix.coordinateSystem != CoordinateSystem.WGS84 -> "UNEXPECTED_COORDINATE_SYSTEM"
            fix.locationTimeUtcMillis == null || fix.locationTimeUtcMillis <= 0 -> "MISSING_LOCATION_TIME"
            else -> LocationQuality.rejectionReason(fix.latitude, fix.longitude, fix.accuracyMeters,
                fix.locationElapsedRealtimeNanos ?: 0L, now)
        }
        if (rejection != null) { request.lastRejection = rejection; return }
        val update = LocationUpdate(fix.copy(ageAtReceiptMillis = (now - fix.locationElapsedRealtimeNanos!!) / 1_000_000), source)
        request.recentFixes.removeAll {
            now - it.observation.locationElapsedRealtimeNanos!! > LocationQuality.MAX_AGE_MILLIS * 1_000_000
        }
        request.recentFixes.add(update)
        if (fix.accuracyMeters != null && fix.accuracyMeters <= request.targetAccuracy) {
            finish(request, LocationCompletion("SUCCESS", source, update.observation, quality =
                if (request.targetAccuracy == Float.MAX_VALUE) "APPROXIMATE_PERMISSION" else "TARGET_ACCURACY_REACHED"))
        } else {
            request.onProgress(update)
            if (current === request && source == Source.FUSED) startSystem(request)
        }
    }

    private fun systemUnavailable(request: Request, reason: String) {
        if (current !== request) return
        if (!finishBestAvailable(request, "BEST_AVAILABLE_PROVIDER_UNAVAILABLE")) {
            finish(request, LocationCompletion("FAILED", request.attempt.source, reason = reason))
        }
    }

    private fun finishBestAvailable(request: Request, quality: String): Boolean {
        val now = clock.elapsedNanos()
        val best = request.recentFixes.filter {
            now - it.observation.locationElapsedRealtimeNanos!! in 0..LocationQuality.MAX_AGE_MILLIS * 1_000_000
        }.minWithOrNull(compareBy<LocationUpdate> { it.observation.accuracyMeters ?: Float.MAX_VALUE }
            .thenByDescending { it.observation.locationElapsedRealtimeNanos }) ?: return false
        finish(request, LocationCompletion("SUCCESS", best.source, best.observation, quality = quality))
        return true
    }

    private fun deadline(request: Request) {
        if (current !== request) return
        if (!finishBestAvailable(request, "BEST_AVAILABLE_AT_DEADLINE")) finish(request, LocationCompletion("FAILED", request.attempt.source,
            reason = request.lastRejection ?: "TIMEOUT"))
    }

    private fun finish(request: Request, completion: LocationCompletion) {
        if (current !== request) return
        // Release ownership before cleanup or user callbacks can reenter start().
        current = null
        request.attempt.close()
        request.timers.forEach { it.cancel() }
        request.provider?.cancel()
        request.onComplete(completion)
    }

    fun cancel() {
        current?.let { finish(it, LocationCompletion("CANCELLED", it.attempt.source, reason = "USER_OR_SCREEN_CLOSED")) }
    }
}
