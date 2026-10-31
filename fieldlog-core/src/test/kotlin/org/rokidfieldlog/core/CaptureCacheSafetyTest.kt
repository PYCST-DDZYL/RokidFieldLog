package org.rokidfieldlog.core

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CaptureCacheSafetyTest {
    private val id = "00000000-0000-4000-8000-000000000030"
    private fun tree(): JsonObject = JsonParser.parseString(CaptureRecordCodec.encode(RecordDocument(
        CaptureRecord.pending(id, null).copy(width = 16, height = 16,
            photoVerification = PhotoVerification(4, 123456, "a".repeat(64), 16, 16))
    ))).asJsonObject

    @Test fun verificationCacheRejectsCoercedTruncatedAndWrappedNumbers() {
        for ((key, values) in mapOf("byteCount" to listOf("\"4\"", "4.5", "-1", "18446744073709551620"),
            "width" to listOf("\"16\"", "16.5", "4294967312"), "lastModifiedUtcMillis" to listOf("\"123456\"", "1e0"))) {
            for (value in values) {
                val record = tree()
                record.getAsJsonObject("photoVerification").add(key, JsonParser.parseString(value))
                assertThrows(key + "=" + value, Exception::class.java) { CaptureRecordCodec.decode(record.toString()) }
            }
        }
    }
    @Test fun verificationCacheRequiresAllFieldsAndAnActualFingerprintString() {
        for (key in listOf("byteCount", "lastModifiedUtcMillis", "sha256", "width", "height")) {
            for (missing in listOf(false, true)) {
                val record = tree(); val cache = record.getAsJsonObject("photoVerification")
                if (missing) cache.remove(key) else cache.add(key, JsonNull.INSTANCE)
                assertThrows(key, Exception::class.java) { CaptureRecordCodec.decode(record.toString()) }
            }
        }
        for (value in listOf("\"not-a-hash\"", "1".repeat(64))) {
            val record = tree()
            record.getAsJsonObject("photoVerification").add("sha256", JsonParser.parseString(value))
            assertThrows(Exception::class.java) { CaptureRecordCodec.decode(record.toString()) }
        }
    }
    @Test fun validCacheAndUnknownAnnotationsRoundTripWhileCacheRemainsOptional() {
        val record = tree()
        record.getAsJsonObject("photoVerification").addProperty("futureAnnotation", "SYNTHETIC_ONLY")
        assertEquals(record, JsonParser.parseString(CaptureRecordCodec.encode(CaptureRecordCodec.decode(record.toString()))))
        record.add("photoVerification", JsonNull.INSTANCE)
        assertNull(CaptureRecordCodec.decode(record.toString()).record.photoVerification)
    }
}
