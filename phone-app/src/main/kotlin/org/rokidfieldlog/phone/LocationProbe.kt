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
import android.util.Log
import androidx.core.location.LocationCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.rokidfieldlog.core.LocationQuality
import org.rokidfieldlog.core.LocationAttempt
import org.rokidfieldlog.core.LocationAttempt.Source
import org.json.JSONObject
import java.util.UUID

data class ProbeResult(val data: JSONObject)

/** Runs only while the diagnostic screen is visible. No background tracking. */
class LocationProbe(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var cancellation: CancellationTokenSource? = null
    private var callback: ((ProbeResult) -> Unit)? = null
    private var generation = 0
    private var active = false
    private var attempt: LocationAttempt? = null
    private var lastRejection: String? = null
    private var probeId = ""
    private var requestedAt = 0L
    private var requestedElapsed = 0L
    private var permissionLevel = "NONE"

    fun googleServicesAvailable(): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (_: Exception) { false }

    @SuppressLint("MissingPermission") // Both location grants checked before any provider is used.
    fun start(forceSystem: Boolean, onResult: (ProbeResult) -> Unit) {
        cancel()
        val operation = ++generation
        callback = onResult
        active = true
        probeId = UUID.randomUUID().toString()
        requestedAt = System.currentTimeMillis()
        requestedElapsed = SystemClock.elapsedRealtimeNanos()
        lastRejection = null
        attempt = LocationAttempt(requestedElapsed)
        permissionLevel = when {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> "PRECISE"
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> "APPROXIMATE"
            else -> "NONE"
        }
        if (permissionLevel == "NONE") { fail("PERMISSION_DENIED"); return }
        if (manager == null) { fail("PROVIDER_ERROR"); return }
        try {
            if (!manager.isLocationEnabled) { fail("LOCATION_DISABLED"); return }
        } catch (_: SecurityException) { fail("PERMISSION_DENIED"); return }
        catch (_: Exception) { fail("PROVIDER_ERROR"); return }
        handler.postDelayed({ if (isCurrent(operation)) fail(lastRejection ?: "TIMEOUT") }, LocationAttempt.TOTAL_TIMEOUT_MILLIS)
        if (forceSystem || !googleServicesAvailable()) {
            startSystem(operation)
            return
        }
        attempt!!.useFused()
        cancellation = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .setDurationMillis(LocationAttempt.FUSED_TIMEOUT_MILLIS)
            .build()
        // Preserve twelve seconds of the overall deadline for non-GMS providers.
        // A Task can complete after cancellation; source guards reject that result.
        handler.postDelayed({
            if (isCurrent(operation) && attempt?.source == Source.FUSED) startSystem(operation)
        }, LocationAttempt.FUSED_TIMEOUT_MILLIS)
        try {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(request, cancellation!!.token)
                .addOnSuccessListener { location ->
                    if (!callbackAllowed(operation, Source.FUSED)) return@addOnSuccessListener
                    if (location != null && accept(location)) return@addOnSuccessListener
                    startSystem(operation)
                }
                .addOnFailureListener {
                    if (callbackAllowed(operation, Source.FUSED)) startSystem(operation)
                }
        } catch (exception: SecurityException) { fail("PERMISSION_DENIED") }
        catch (exception: Exception) { startSystem(operation) }
    }

    @SuppressLint("MissingPermission")
    private fun startSystem(operation: Int) {
        if (!isCurrent(operation)) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (attempt!!.isExpired(now)) { fail("TIMEOUT"); return }
        if (!attempt!!.useSystem(now)) return
        cancellation?.cancel()
        cancellation = null
        val providers = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
                (it != LocationManager.GPS_PROVIDER || permissionLevel == "PRECISE") &&
                    manager.allProviders.contains(it) && manager.isProviderEnabled(it)
            }
        } catch (_: SecurityException) { fail("PERMISSION_DENIED"); return }
        catch (_: Exception) { fail("PROVIDER_ERROR"); return }
        if (providers.isEmpty()) { fail("NO_ENABLED_PROVIDER"); return }
        val registeredProviders = mutableSetOf<String>()
        @Suppress("OVERRIDE_DEPRECATION") // Provider callbacks also support Android 9.
        val systemListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (callbackAllowed(operation, Source.ANDROID_SYSTEM)) accept(location)
            }
            override fun onProviderDisabled(provider: String) {
                if (!isCurrent(operation)) return
                try {
                    if (registeredProviders.none { manager.isProviderEnabled(it) }) fail("LOCATION_DISABLED")
                } catch (_: SecurityException) { fail("PERMISSION_DENIED") }
                catch (_: Exception) { fail("PROVIDER_ERROR") }
            }
            override fun onProviderEnabled(provider: String) = Unit
            @Deprecated("Required for devices running Android 9")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = systemListener
        for (provider in providers) {
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, systemListener, Looper.getMainLooper())
                registeredProviders.add(provider)
            } catch (_: SecurityException) { fail("PERMISSION_DENIED"); return }
            catch (_: Exception) { /* Another already registered provider can still supply a fix. */ }
        }
        if (registeredProviders.isEmpty()) fail("PROVIDER_ERROR")
    }

    private fun accept(location: Location): Boolean {
        val receivedElapsed = SystemClock.elapsedRealtimeNanos()
        val receivedAt = System.currentTimeMillis()
        if (attempt!!.isExpired(receivedElapsed)) { fail("TIMEOUT"); return true }
        if (LocationCompat.isMock(location)) {
            lastRejection = "MOCK_LOCATION_REJECTED"
            return false
        }
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        if (location.time <= 0) { lastRejection = "MISSING_LOCATION_TIME"; return false }
        val rejection = LocationQuality.rejectionReason(location.latitude, location.longitude,
            accuracy, location.elapsedRealtimeNanos, receivedElapsed)
        if (rejection != null) { lastRejection = rejection; return false }
        val observation = JSONObject()
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("coordinateSystem", "WGS84")
            .put("accuracyMeters", accuracy ?: JSONObject.NULL)
            .put("locationTimeUtcMillis", location.time)
            .put("locationElapsedRealtimeNanos", location.elapsedRealtimeNanos)
            .put("ageAtReceiptMillis", (receivedElapsed - location.elapsedRealtimeNanos) / 1_000_000)
            .put("provider", location.provider ?: "unknown")
            .put("isMock", false)
        finish(baseResult("SUCCESS", receivedAt, receivedElapsed).put("location", observation))
        return true
    }

    private fun baseResult(status: String, receivedAt: Long = System.currentTimeMillis(),
        receivedElapsed: Long = SystemClock.elapsedRealtimeNanos()) = JSONObject()
        .put("schemaVersion", 1)
        .put("probeId", probeId)
        .put("status", status)
        .put("requestedAtUtcMillis", requestedAt)
        .put("requestedElapsedRealtimeNanos", requestedElapsed)
        .put("receivedAtUtcMillis", receivedAt)
        .put("receivedElapsedRealtimeNanos", receivedElapsed)
        .put("permissionLevel", permissionLevel)
        .put("source", attempt?.source?.name ?: "NONE")
        .put("glassesConnectionStatus", "NOT_CONFIGURED_PHASE1")
        .put("location", JSONObject.NULL)

    private fun fail(reason: String) = finish(baseResult("FAILED").put("reason", reason))

    private fun finish(data: JSONObject) {
        if (!active) return
        active = false
        attempt?.close()
        Log.i("FieldLog", "LOCATION_RESULT status=${data.optString("status")} source=${data.optString("source")}")
        cleanup()
        val resultCallback = callback
        callback = null
        resultCallback?.invoke(ProbeResult(data))
    }

    fun cancel() {
        if (active) finish(baseResult("CANCELLED").put("reason", "USER_OR_SCREEN_CLOSED"))
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        cancellation?.cancel()
        cancellation = null
        listener?.let {
            try { manager.removeUpdates(it) }
            catch (_: Exception) { /* Providers or permissions may disappear during the request. */ }
        }
        listener = null
    }

    private fun isCurrent(operation: Int) = active && generation == operation

    private fun callbackAllowed(operation: Int, source: Source): Boolean {
        if (!isCurrent(operation)) return false
        val now = SystemClock.elapsedRealtimeNanos()
        if (attempt!!.isExpired(now)) { fail("TIMEOUT"); return false }
        return attempt!!.canReceive(source, now)
    }
}
