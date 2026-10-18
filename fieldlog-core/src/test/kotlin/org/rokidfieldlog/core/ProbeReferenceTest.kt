package org.rokidfieldlog.core

import com.google.gson.JsonNull
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** Synthetic historical phone results; never a GPS/device accuracy test. */
class ProbeReferenceTest {
    private val selectedAt = 999999999L
    private fun result() = JsonParser.parseString(CaptureRecordCodec.encodeProbe(LocationProbeRecord(
        probeId = "00000000-0000-4000-8000-000000000092", status = "SUCCESS",
        requestedAtUtcMillis = 123000, requestedElapsedRealtimeNanos = 1_000_000_000,
        receivedAtUtcMillis = 124000, receivedElapsedRealtimeNanos = 2_100_000_000,
        permissionLevel = "PRECISE", source = "FUSED",
        location = LocationObservation(0.0, 0.0, accuracyMeters = null, locationTimeUtcMillis = 123900,
            locationElapsedRealtimeNanos = 2_000_000_000, ageAtReceiptMillis = 100, provider = "SYNTHETIC_ONLY"),
    ))).asJsonObject

    @Test fun validHistoricalZeroFixRemainsHistoricalAndKeepsUnknownAccuracy() {
        val fix = CaptureRecordCodec.referenceFromProbe(result().toString(), selectedAt)!!
        assertEquals(0.0, fix.latitude, 0.0); assertEquals(0.0, fix.longitude, 0.0)
        assertNull(fix.accuracyMeters)
        assertEquals(123900L, fix.locationTimeUtcMillis)
        assertEquals(selectedAt, fix.selectedAtUtcMillis)
        assertEquals("FUSED/SYNTHETIC_ONLY", fix.source)
    }

    @Test fun failureOrCancellationCannotBecomeSuccessJustBecauseCoordinatesRemain() {
        for (status in listOf("FAILED", "CANCELLED", "UNKNOWN")) {
            val tree = result().apply { addProperty("status", status) }
            assertNull(CaptureRecordCodec.referenceFromProbe(tree.toString(), selectedAt))
        }
    }

    @Test fun mockStatusMustBePresentBooleanAndFalse() {
        for (value in listOf(null, JsonNull.INSTANCE, JsonParser.parseString("true"), JsonParser.parseString("\"false\""))) {
            val tree = result()
            val fix = tree.getAsJsonObject("location")
            if (value == null) fix.remove("isMock") else fix.add("isMock", value)
            assertNull(CaptureRecordCodec.referenceFromProbe(tree.toString(), selectedAt))
        }
    }

    @Test fun coordinateAndTimeStringsCannotPassThroughThePhoneHistoryPath() {
        for ((key, value) in listOf("latitude" to "\"0\"", "longitude" to "null", "locationTimeUtcMillis" to "123900.5", "accuracyMeters" to "\"10\"")) {
            val tree = result()
            tree.getAsJsonObject("location").add(key, JsonParser.parseString(value))
            assertNull(CaptureRecordCodec.referenceFromProbe(tree.toString(), selectedAt))
        }
    }

    @Test fun unknownSchemasAndUnavailableSourcesCannotBeSelectedAsSuccessfulResults() {
        for ((key, value) in listOf("schemaVersion" to "2", "schemaVersion" to "\"1\"", "source" to "\"NONE\"",
            "permissionLevel" to "[\"PRECISE\"]", "receivedAtUtcMillis" to "124000.5")) {
            val tree = result().apply { add(key, JsonParser.parseString(value)) }
            assertNull(CaptureRecordCodec.referenceFromProbe(tree.toString(), selectedAt))
        }
    }

    @Test fun staleUnknownOrMalformedReceiptAgeIsNotAcceptedAsVerifiedHistory() {
        for (value in listOf("5001", "-1", "null", "\"100\"", "100.5", "18446744073709551617")) {
            val tree = result()
            tree.getAsJsonObject("location").add("ageAtReceiptMillis", JsonParser.parseString(value))
            assertNull(CaptureRecordCodec.referenceFromProbe(tree.toString(), selectedAt))
        }
    }

    @Test fun validSystemCoarseResultAndFiveSecondBoundaryRemainSupported() {
        val tree = result().apply { addProperty("source", "ANDROID_SYSTEM"); addProperty("permissionLevel", "APPROXIMATE") }
        tree.getAsJsonObject("location").apply { addProperty("ageAtReceiptMillis", 5000); addProperty("accuracyMeters", 1000f) }
        val fix = CaptureRecordCodec.referenceFromProbe(tree.toString(), selectedAt)!!
        assertEquals(1000f, fix.accuracyMeters)
        assertEquals("ANDROID_SYSTEM/SYNTHETIC_ONLY", fix.source)
        assertNull(CaptureRecordCodec.referenceFromProbe(tree.toString(), 0L))
    }
}
