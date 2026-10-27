package org.rokidfieldlog.core

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** Wire-format tests only: these coordinates and clocks are synthetic. */
class CaptureLocationValidationTest {
    private val id = "00000000-0000-4000-8000-000000000027"
    private fun tree(): JsonObject = JsonParser.parseString(CaptureRecordCodec.encode(RecordDocument(
        CaptureRecord.pending(id, null).copy(phoneLocation = LocationObservation(0.0, 0.0,
            locationTimeUtcMillis = 1000, locationElapsedRealtimeNanos = 0, ageAtReceiptMillis = 0))
    ))).asJsonObject

    @Test fun phoneCoordinatesAndAccuracyMustBeFiniteAndInRange() {
        for ((key, value) in listOf("latitude" to "91", "latitude" to "-91", "longitude" to "181",
            "longitude" to "-181", "accuracyMeters" to "-1", "accuracyMeters" to "1e100")) {
            val valueTree = tree()
            valueTree.getAsJsonObject("phoneLocation").add(key, JsonParser.parseString(value))
            assertThrows(key + "=" + value, Exception::class.java) { CaptureRecordCodec.decode(valueTree.toString()) }
        }
    }

    @Test fun phoneCrsAndMockStatusCannotBeInventedByDefaultsOrStringCoercion() {
        for (key in listOf("coordinateSystem", "isMock")) {
            val missing = tree()
            missing.getAsJsonObject("phoneLocation").remove(key)
            assertThrows(key, Exception::class.java) { CaptureRecordCodec.decode(missing.toString()) }
            val nullValue = tree()
            nullValue.getAsJsonObject("phoneLocation").add(key, JsonNull.INSTANCE)
            assertThrows(key, Exception::class.java) { CaptureRecordCodec.decode(nullValue.toString()) }
        }
        val coerced = tree()
        coerced.getAsJsonObject("phoneLocation").addProperty("isMock", "false")
        assertThrows(Exception::class.java) { CaptureRecordCodec.decode(coerced.toString()) }
    }

    @Test fun recordingClocksRejectStringsFractionsNegativeValuesAndOverflow() {
        for (key in listOf("captureRequestedAtUtcMillis", "captureDurationMillis")) {
            for (value in listOf("\"1\"", "1.5", "-1", "18446744073709551617")) {
                val valueTree = tree().apply { add(key, JsonParser.parseString(value)) }
                assertThrows(key + "=" + value, Exception::class.java) { CaptureRecordCodec.decode(valueTree.toString()) }
            }
        }
        for (key in listOf("locationTimeUtcMillis", "locationElapsedRealtimeNanos", "ageAtReceiptMillis")) {
            val valueTree = tree()
            valueTree.getAsJsonObject("phoneLocation").addProperty(key, "1")
            assertThrows(key, Exception::class.java) { CaptureRecordCodec.decode(valueTree.toString()) }
        }
    }

    @Test fun categoryReviewFlagMustBeAnActualBoolean() {
        for (value in listOf("\"false\"", "0", "null")) {
            val valueTree = tree().apply { add("issueCategoryNeedsReview", JsonParser.parseString(value)) }
            assertThrows(Exception::class.java) { CaptureRecordCodec.decode(valueTree.toString()) }
        }
    }

    @Test fun validZeroCoordinatesNullAccuracyAndUnknownExtensionsStayUnchanged() {
        val valueTree = tree()
        valueTree.addProperty("futureAnnotation", "SYNTHETIC_ONLY")
        val document = CaptureRecordCodec.decode(valueTree.toString())
        val fix = document.record.phoneLocation!!
        assertEquals(0.0, fix.latitude, 0.0); assertEquals(0.0, fix.longitude, 0.0)
        assertNull(fix.accuracyMeters); assertEquals(0L, fix.ageAtReceiptMillis)
        assertEquals("PENDING_CONFIRMATION", document.record.locationStatus)
        assertEquals(valueTree, JsonParser.parseString(CaptureRecordCodec.encode(document)))
        val withoutLocation = tree().apply { add("phoneLocation", JsonNull.INSTANCE) }
        assertNull(CaptureRecordCodec.decode(withoutLocation.toString()).record.phoneLocation)
    }
}
