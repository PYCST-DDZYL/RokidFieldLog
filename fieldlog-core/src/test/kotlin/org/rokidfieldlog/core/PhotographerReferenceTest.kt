package org.rokidfieldlog.core

import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PhotographerReferenceTest {
    private val id = "00000000-0000-4000-8000-000000000091"
    private val reference = PhotographerReference(0.0, 0.0, accuracyMeters = null,
        locationTimeUtcMillis = 123000, selectedAtUtcMillis = 124000, source = "SYNTHETIC_TEST")

    private fun encodedDrafts() = listOf(
        DraftCodec.encode(IssueDraft(photoId = id, photographerReference = reference)),
        ReportCodec.encode(ReportDraft(reportId = id, photographerReference = reference)),
    )
    private fun decode(text: String, index: Int): PhotographerReference? = if (index == 0)
        DraftCodec.decode(text, id).photographerReference else ReportCodec.decode(text, id).photographerReference

    @Test fun missingReferenceAxesCannotBecomeZero() {
        encodedDrafts().forEachIndexed { index, text ->
            for (axis in listOf("latitude", "longitude")) {
                val tree = JsonParser.parseString(text).asJsonObject
                tree.getAsJsonObject("photographerReference").remove(axis)
                assertThrows("codec=$index missing=$axis", IllegalArgumentException::class.java) { decode(tree.toString(), index) }
            }
        }
    }

    @Test fun nullOrStringReferenceAxesCannotBecomeCoordinates() {
        encodedDrafts().forEachIndexed { index, text ->
            for (axis in listOf("latitude", "longitude")) for (bad in listOf(JsonNull.INSTANCE, JsonPrimitive("0.0"))) {
                val tree = JsonParser.parseString(text).asJsonObject
                tree.getAsJsonObject("photographerReference").add(axis, bad)
                assertThrows("codec=$index axis=$axis value=$bad", IllegalArgumentException::class.java) { decode(tree.toString(), index) }
            }
        }
    }

    @Test fun missingCoordinateSystemCannotSilentlyBecomeWgs84() {
        encodedDrafts().forEachIndexed { index, text ->
            val tree = JsonParser.parseString(text).asJsonObject
            tree.getAsJsonObject("photographerReference").remove("coordinateSystem")
            assertThrows(IllegalArgumentException::class.java) { decode(tree.toString(), index) }
        }
    }

    @Test fun referenceTimesMustBeExactIntegerNumbers() {
        encodedDrafts().forEachIndexed { index, text ->
            for (key in listOf("locationTimeUtcMillis", "selectedAtUtcMillis")) {
                for (bad in listOf("123000.5", "\"123000\"", "18446744073709674616")) {
                    val tree = JsonParser.parseString(text).asJsonObject
                    tree.getAsJsonObject("photographerReference").add(key, JsonParser.parseString(bad))
                    assertThrows("codec=$index time=$key value=$bad", IllegalArgumentException::class.java) { decode(tree.toString(), index) }
                }
            }
        }
    }

    @Test fun genuineZeroReferenceAndUnknownReferenceRemainDistinct() {
        encodedDrafts().forEachIndexed { index, text -> assertEquals(reference, decode(text, index)) }
        assertNull(DraftCodec.decode(DraftCodec.encode(IssueDraft(photoId = id)), id).photographerReference)
        assertNull(ReportCodec.decode(ReportCodec.encode(ReportDraft(reportId = id)), id).photographerReference)
    }

    @Test fun referenceAccuracyMustBeNumericOrUnknown() {
        encodedDrafts().forEachIndexed { index, text ->
            val tree = JsonParser.parseString(text).asJsonObject
            val point = tree.getAsJsonObject("photographerReference")
            point.addProperty("accuracyMeters", "12.5")
            assertThrows(IllegalArgumentException::class.java) { decode(tree.toString(), index) }
            point.addProperty("accuracyMeters", 12.5f)
            assertEquals(12.5f, decode(tree.toString(), index)!!.accuracyMeters)
            point.remove("accuracyMeters")
            assertNull(decode(tree.toString(), index)!!.accuracyMeters)
        }
    }

    @Test fun unknownReferenceFieldsCannotBeLostByResavingAnOlderFormat() {
        encodedDrafts().forEachIndexed { index, text ->
            val tree = JsonParser.parseString(text).asJsonObject
            tree.getAsJsonObject("photographerReference").addProperty("futureReferenceMeaning", "PRESERVE_ME")
            assertThrows(IllegalArgumentException::class.java) { decode(tree.toString(), index) }
        }
    }
}
