package org.rokidfieldlog.core

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DraftCodecTest {
    private val id = "00000000-0000-4000-8000-000000000081"
    private fun json(point: String) = """{"schemaVersion":1,"photoId":"$id","issuePosition":$point}"""

    @Test fun explicitNullAxesMustNotBecomeZeroCoordinates() {
        for (point in listOf(
            """{"latitude":null,"longitude":12.3,"coordinateSystem":"WGS84"}""",
            """{"latitude":12.3,"longitude":null,"coordinateSystem":"WGS84"}""",
        )) assertThrows(IllegalArgumentException::class.java) { DraftCodec.decode(json(point), id) }
    }

    @Test fun coordinatesAndTheirSystemRequireDeclaredTypes() {
        for (point in listOf(
            """{"latitude":"12.3","longitude":45.6,"coordinateSystem":"WGS84"}""",
            """{"latitude":12.3,"longitude":"45.6","coordinateSystem":"WGS84"}""",
            """{"latitude":12.3,"longitude":45.6,"coordinateSystem":["WGS84"]}""",
        )) assertThrows(IllegalArgumentException::class.java) { DraftCodec.decode(json(point), id) }
    }

    @Test fun unknownPositionAndGenuineZeroPositionRemainDistinct() {
        assertNull(DraftCodec.decode(json("null"), id).issuePosition)
        val draft = IssueDraft(photoId = id, issuePosition = GeoPoint(0.0, 0.0, CoordinateSystem.WGS84))
        assertEquals(draft, DraftCodec.decode(DraftCodec.encode(draft), id))
    }

    @Test fun fractionalOrStringSchemaVersionsCannotBeTruncatedIntoSupportedVersion() {
        for (version in listOf("1.5", "\"1\"")) {
            val text = json("null").replace("\"schemaVersion\":1", "\"schemaVersion\":" + version)
            assertThrows(IllegalArgumentException::class.java) { DraftCodec.decode(text, id) }
        }
    }

    @Test fun confirmationFlagsMustBeActualBooleans() {
        for (key in listOf("addressConfirmed", "isSynthetic")) {
            val tree = JsonParser.parseString(DraftCodec.encode(IssueDraft(photoId = id))).asJsonObject
            tree.addProperty(key, "true")
            assertThrows(IllegalArgumentException::class.java) { DraftCodec.decode(tree.toString(), id) }
        }
    }

    @Test fun privacyDeclarationCannotBeAString() {
        val tree = JsonParser.parseString(DraftCodec.encode(IssueDraft(photoId = id, photoReview = PhotoReview()))).asJsonObject
        tree.getAsJsonObject("photoReview").addProperty("noThirdPartyInformation", "true")
        assertThrows(IllegalArgumentException::class.java) { DraftCodec.decode(tree.toString(), id) }
    }

    @Test fun revisionsCannotBeFractionalStringOrOverflowingNumbers() {
        for (value in listOf("1.5", "\"1\"", "18446744073709551617")) {
            val tree = JsonParser.parseString(DraftCodec.encode(IssueDraft(photoId = id))).asJsonObject
            tree.add("revision", JsonParser.parseString(value))
            assertThrows(IllegalArgumentException::class.java) { DraftCodec.decode(tree.toString(), id) }
        }
    }

    @Test fun unknownDraftAndReviewFieldsMustNotBeLostWhenSaved() {
        for (nested in listOf(false, true)) {
            val tree = JsonParser.parseString(DraftCodec.encode(IssueDraft(photoId = id, photoReview = PhotoReview()))).asJsonObject
            (if (nested) tree.getAsJsonObject("photoReview") else tree).addProperty("futureMeaning", "PRESERVE_ME")
            assertThrows(IllegalArgumentException::class.java) { DraftCodec.decode(tree.toString(), id) }
        }
    }

    @Test fun maskCoordinatesMustBeExplicitNumbers() {
        for (axis in listOf("left", "top", "right", "bottom")) for (missing in listOf(false, true)) {
            val review = PhotoReview(masks = listOf(MaskRect(0f, 0f, 1f, 1f)))
            val tree = JsonParser.parseString(DraftCodec.encode(IssueDraft(photoId = id, photoReview = review))).asJsonObject
            val mask = tree.getAsJsonObject("photoReview").getAsJsonArray("masks")[0].asJsonObject
            if (missing) mask.remove(axis) else mask.add(axis, JsonPrimitive(if (axis in listOf("left", "top")) "0" else "1"))
            assertThrows(IllegalArgumentException::class.java) { DraftCodec.decode(tree.toString(), id) }
        }
    }

    @Test fun reviewSizesAndTimesMustBeExactIntegersWithinTheirType() {
        for ((key, bad) in listOf("width" to "4294967297", "height" to "60.5", "byteCount" to "\"100\"", "reviewedAtUtcMillis" to "123.5")) {
            val tree = JsonParser.parseString(DraftCodec.encode(IssueDraft(photoId = id, photoReview = PhotoReview()))).asJsonObject
            tree.getAsJsonObject("photoReview").add(key, JsonParser.parseString(bad))
            assertThrows(IllegalArgumentException::class.java) { DraftCodec.decode(tree.toString(), id) }
        }
    }
}
