package org.rokidfieldlog.core

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

    @Test fun coordinateStringsAreNotTypedNumericCoordinates() {
        for (point in listOf(
            """{"latitude":"12.3","longitude":45.6,"coordinateSystem":"WGS84"}""",
            """{"latitude":12.3,"longitude":"45.6","coordinateSystem":"WGS84"}""",
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
}
