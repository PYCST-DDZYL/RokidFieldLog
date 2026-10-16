package org.rokidfieldlog.phone

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.rokidfieldlog.core.PhotographerReference
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded system address lookup. No map SDK, coordinate conversion, or fallback to a coordinate string. */
class AddressSuggestionLookup(context: Context) {
    companion object {
        private val legacyBusy = AtomicBoolean()
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "FieldLogAddress").apply { isDaemon = true } }
    }
    private val geocoder = Geocoder(context.applicationContext, Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    fun lookup(reference: PhotographerReference, done: (String?) -> Unit): () -> Unit {
        var finished = false
        lateinit var timeout: Runnable
        fun complete(addresses: List<Address>) {
            handler.post {
                if (!finished) {
                    finished = true; handler.removeCallbacks(timeout)
                    val candidate = addresses.firstOrNull()?.let { address ->
                        if (address.maxAddressLineIndex >= 0) address.getAddressLine(0)?.trim() else null
                    }?.takeIf { it.isNotBlank() && it.length <= 200 }
                    done(candidate)
                }
            }
        }
        timeout = Runnable { if (!finished) { finished = true; done(null) } }
        handler.postDelayed(timeout, 10_000)
        if (!Geocoder.isPresent()) complete(emptyList())
        else try {
            if (Build.VERSION.SDK_INT >= 33) geocoder.getFromLocation(reference.latitude, reference.longitude, 1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = complete(addresses)
                    override fun onError(errorMessage: String?) = complete(emptyList())
                })
            else if (legacyBusy.compareAndSet(false, true)) worker.execute {
                try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(reference.latitude, reference.longitude, 1)
                    complete(addresses ?: emptyList())
                } catch (_: Exception) { complete(emptyList()) }
                finally { legacyBusy.set(false) }
            } else complete(emptyList())
        } catch (_: Exception) { complete(emptyList()) }
        return { finished = true; handler.removeCallbacks(timeout) }
    }
}
