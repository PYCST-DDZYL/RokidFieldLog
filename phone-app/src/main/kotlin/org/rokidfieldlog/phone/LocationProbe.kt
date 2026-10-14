package org.rokidfieldlog.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.location.LocationCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import org.rokidfieldlog.core.*
import java.util.UUID

// The phone diagnostic screen still consumes JSON; the shared typed codec defines its wire format.
data class ProbeResult(val data: JSONObject)

/** Foreground Android providers. Request ownership/deadlines live in the JVM coordinator. */
class LocationProbe(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val clock = object : FieldClock {
        override fun utcMillis() = System.currentTimeMillis()
        override fun elapsedNanos() = SystemClock.elapsedRealtimeNanos()
    }
    private var coordinator: LocationCoordinator? = null

    fun googleServicesAvailable(): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (_: Exception) { false }

    fun start(forceSystem: Boolean, onProgress: (LocationUpdate) -> Unit = {}, onResult: (ProbeResult) -> Unit) {
        cancel()
        val id = UUID.randomUUID().toString()
        val requestedAt = clock.utcMillis()
        val requestedElapsed = clock.elapsedNanos()
        val permission = when {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> "PRECISE"
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> "APPROXIMATE"
            else -> "NONE"
        }
        fun report(completion: LocationCompletion) {
            val record = LocationProbeRecord(probeId = id, status = completion.status,
                requestedAtUtcMillis = requestedAt, requestedElapsedRealtimeNanos = requestedElapsed,
                receivedAtUtcMillis = clock.utcMillis(), receivedElapsedRealtimeNanos = clock.elapsedNanos(),
                permissionLevel = permission, source = completion.source.name, location = completion.observation,
                reason = completion.reason, quality = completion.quality)
            onResult(ProbeResult(JSONObject(CaptureRecordCodec.encodeProbe(record))))
        }
        fun fail(reason: String) = report(LocationCompletion("FAILED", LocationAttempt.Source.NONE, reason = reason))
        if (permission == "NONE") { fail("PERMISSION_DENIED"); return }
        if (manager == null) { fail("PROVIDER_ERROR"); return }
        try {
            if (!manager.isLocationEnabled) { fail("LOCATION_DISABLED"); return }
        } catch (_: SecurityException) { fail("PERMISSION_DENIED"); return }
        catch (_: Exception) { fail("PROVIDER_ERROR"); return }
        val scheduler = ProbeScheduler { delay, task ->
            val runnable = Runnable { task() }
            handler.postDelayed(runnable, delay)
            CancelHandle { handler.removeCallbacks(runnable) }
        }
        val request = LocationCoordinator(clock, scheduler,
            if (!forceSystem && googleServicesAvailable()) FusedSource() else null, SystemSource(permission))
        coordinator = request
        request.start(forceSystem, approximate = permission == "APPROXIMATE", onProgress = onProgress, onComplete = ::report)
    }

    private fun observation(location: Location) = LocationObservation(
        latitude = location.latitude, longitude = location.longitude, accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
        locationTimeUtcMillis = location.time, locationElapsedRealtimeNanos = location.elapsedRealtimeNanos,
        provider = location.provider ?: "unknown", isMock = LocationCompat.isMock(location),
    )

    private inner class FusedSource : LocationSource {
        @SuppressLint("MissingPermission") // start() checks both grants before providers are subscribed.
        override fun start(onFix: (LocationObservation) -> Unit, onUnavailable: (String) -> Unit): CancelHandle {
            val cancellation = CancellationTokenSource()
            val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(0).setDurationMillis(LocationAttempt.FUSED_TIMEOUT_MILLIS).build()
            try {
                LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, cancellation.token)
                    .addOnSuccessListener { location ->
                        if (location == null) onUnavailable("NO_FIX") else onFix(observation(location))
                    }.addOnFailureListener { onUnavailable("PROVIDER_ERROR") }
            } catch (_: SecurityException) { onUnavailable("PERMISSION_DENIED") }
            catch (_: Exception) { onUnavailable("PROVIDER_ERROR") }
            return CancelHandle { cancellation.cancel() }
        }
    }

    private inner class SystemSource(private val permission: String) : LocationSource {
        @SuppressLint("MissingPermission")
        override fun start(onFix: (LocationObservation) -> Unit, onUnavailable: (String) -> Unit): CancelHandle {
            val providers = try {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
                    (it != LocationManager.GPS_PROVIDER || permission == "PRECISE") &&
                        manager.allProviders.contains(it) && manager.isProviderEnabled(it)
                }
            } catch (_: SecurityException) { onUnavailable("PERMISSION_DENIED"); return CancelHandle {} }
            catch (_: Exception) { onUnavailable("PROVIDER_ERROR"); return CancelHandle {} }
            if (providers.isEmpty()) { onUnavailable("NO_ENABLED_PROVIDER"); return CancelHandle {} }
            val registered = mutableSetOf<String>()
            @Suppress("OVERRIDE_DEPRECATION")
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = onFix(observation(location))
                override fun onProviderDisabled(provider: String) {
                    try {
                        if (registered.none { manager.isProviderEnabled(it) }) onUnavailable("LOCATION_DISABLED")
                    } catch (_: SecurityException) { onUnavailable("PERMISSION_DENIED") }
                    catch (_: Exception) { onUnavailable("PROVIDER_ERROR") }
                }
                override fun onProviderEnabled(provider: String) = Unit
                @Deprecated("Required for Android 9")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            val cleanup = CancelHandle { try { manager.removeUpdates(listener) } catch (_: Exception) { } }
            for (provider in providers) {
                try {
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    registered.add(provider)
                } catch (_: SecurityException) { cleanup.cancel(); onUnavailable("PERMISSION_DENIED"); return CancelHandle {} }
                catch (_: Exception) { /* A different registered provider may still supply a fix. */ }
            }
            if (registered.isEmpty()) onUnavailable("PROVIDER_ERROR")
            return cleanup
        }
    }

    fun cancel() { coordinator?.cancel(); coordinator = null }
}
