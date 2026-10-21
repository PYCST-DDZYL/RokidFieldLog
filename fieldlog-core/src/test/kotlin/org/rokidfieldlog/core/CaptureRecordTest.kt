package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Test

class CaptureRecordTest {
    private val id = "00000000-0000-4000-8000-000000000001"

    @Test fun roundTripPreservesTypedTagsNullLocationsAndOriginalTimes() {
        val record = CaptureRecord.pending(id, CaptureStamp(id, 1234, 5678)).copy(issueCategory = IssueCategory.LITTER)
        val decoded = CaptureRecordCodec.decode(CaptureRecordCodec.encode(RecordDocument(record))).record
        assertEquals(record, decoded)
        assertNull(decoded.phoneLocation)
        assertNull(decoded.issueLocation)
        assertTrue(decoded.issueCategoryNeedsReview)
        assertEquals("NOT_REVIEWED", decoded.privacyReviewStatus)
    }

    @Test fun legacyMissingOptionalFieldsAreDecodedWithoutInventingTimeOrCrs() {
        val text = """{"schemaVersion":1,"photoId":"$id","issueLocation":{"latitude":12.3,"longitude":-45.6,"source":"MANUAL_FIXTURE"}}"""
        val document = CaptureRecordCodec.decode(text)
        assertNull(document.record.captureCompletedAtUtcMillis)
        assertNull(document.record.issueLocation!!.coordinateSystem)
        assertTrue(CaptureRecordCodec.encode(document).contains("MANUAL_FIXTURE"))
    }

    @Test fun unknownTopLevelAndNestedFieldsSurviveTypedRepair() {
        val text = """{"schemaVersion":1,"photoId":"$id","futureNote":{"flag":true},"phoneLocation":{"latitude":12.3,"longitude":-45.6,"futureProviderNote":"fixture"}}"""
        val old = CaptureRecordCodec.decode(text)
        val encoded = CaptureRecordCodec.encode(old.copy(record = old.record.copy(captureState = CaptureState.RECOVERED)))
        assertTrue(encoded.contains("futureNote"))
        assertTrue(encoded.contains("futureProviderNote"))
        assertEquals(CaptureState.RECOVERED, CaptureRecordCodec.decode(encoded).record.captureState)
    }

    @Test fun futureSchemaAndTraversalIdAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureRecordCodec.decode("""{"schemaVersion":2,"photoId":"$id"}""")
        }
        assertThrows(IllegalArgumentException::class.java) { CaptureRecord(photoId = "../fixture") }
    }

    @Test fun malformedSchemaVersionIsNotAcceptedOrRepairedAsVersionOne() {
        for (version in listOf("1.5", "\"1\"")) {
            val text = """{"schemaVersion":$version,"photoId":"$id"}"""
            assertThrows(IllegalArgumentException::class.java) { CaptureRecordCodec.decode(text) }
            assertThrows(IllegalArgumentException::class.java) { CaptureRecordCodec.readForRecovery(text, id) }
        }
    }

    @Test fun missingCoordinateAndUnknownEnumCannotTurnIntoInventedDefaults() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureRecordCodec.decode("""{"schemaVersion":1,"photoId":"$id","issueLocation":{"latitude":12.3}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureRecordCodec.decode("""{"schemaVersion":1,"photoId":"$id","captureState":"FUTURE_STATE"}""")
        }
    }

    @Test fun nonFiniteAndOutOfRangeMapCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(Double.NaN, 0.0, CoordinateSystem.WGS84) }
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(0.0, 181.0, CoordinateSystem.GCJ02) }
        // The phone form surfaces this message directly when a typed coordinate is out of range.
        assertTrue(assertThrows(IllegalArgumentException::class.java) { GeoPoint(91.0, 0.0, CoordinateSystem.WGS84) }.message!!.contains("纬度"))
    }
}
