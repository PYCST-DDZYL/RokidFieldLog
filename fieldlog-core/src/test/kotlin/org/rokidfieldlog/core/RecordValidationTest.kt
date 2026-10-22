package org.rokidfieldlog.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.util.UUID

class RecordValidationTest {
    private val id = UUID.randomUUID().toString()
    private fun issue() = TrackedIssue(issueId = id, revision = 1, siteLabel = "SYNTHETIC fixture", isSynthetic = true,
        events = listOf(InspectionEvent(eventId = UUID.randomUUID().toString(), sequence = 0,
            recordedAtUtcMillis = 123456789, photoId = UUID.randomUUID().toString(), photoSha256 = "a".repeat(64),
            observedOn = "2020-01-01", visibility = InspectionVisibility.CLEAR,
            finding = InspectionFinding.PROBLEM_PRESENT, sameSiteConfirmed = true)))

    @Test fun inspectionConfirmationsRejectStringsAndNullInsteadOfCoercingThem() {
        for (key in listOf("isSynthetic", "sameSiteConfirmed", "chronologyConfirmed")) {
            for (value in listOf("\"true\"", "\"false\"", "null", "1")) {
                val tree = JsonParser.parseString(InspectionCodec.encode(issue())).asJsonObject
                val target = if (key == "isSynthetic") tree else tree.getAsJsonArray("events")[0].asJsonObject
                target.add(key, JsonParser.parseString(value))
                assertThrows(key + "=" + value, IllegalArgumentException::class.java) { InspectionCodec.decode(tree.toString(), id) }
            }
        }
    }

    @Test fun wrappedSchemaAndRevisionNumbersCannotMasqueradeAsSupportedRecords() {
        val report = ReportDraft(reportId = id)
        for ((field, value) in listOf("schemaVersion" to "4294967297", "revision" to "18446744073709551616")) {
            val tree = JsonParser.parseString(ReportCodec.encode(report)).asJsonObject
            tree.addProperty(field, BigInteger(value))
            assertThrows(IllegalArgumentException::class.java) { ReportCodec.decode(tree.toString(), id) }
        }
        val tree = JsonParser.parseString(InspectionCodec.encode(issue())).asJsonObject
        tree.addProperty("schemaVersion", BigInteger("4294967297"))
        assertThrows(IllegalArgumentException::class.java) { InspectionCodec.decode(tree.toString(), id) }
    }

    @Test fun duplicateConfirmationFieldsAreRejectedAcrossMaterialAndHistoryContracts() {
        val report = ReportCodec.encode(ReportDraft(reportId = id, locationConfirmed = true))
            .replace("\"locationConfirmed\": true", "\"locationConfirmed\": false, \"locationConfirmed\": true")
        assertThrows(Exception::class.java) { ReportCodec.decode(report, id) }
        val draft = DraftCodec.encode(IssueDraft(photoId = id, addressConfirmed = true))
            .replace("\"addressConfirmed\": true", "\"addressConfirmed\": false, \"addressConfirmed\": true")
        assertThrows(Exception::class.java) { DraftCodec.decode(draft, id) }
        val history = InspectionCodec.encode(issue()).replace("\"sameSiteConfirmed\": true",
            "\"sameSiteConfirmed\": false, \"sameSiteConfirmed\": true")
        assertThrows(Exception::class.java) { InspectionCodec.decode(history, id) }
    }

    @Test fun numericStringsFractionsAndOverflowCannotChangeHistoryOrdering() {
        for (key in listOf("sequence", "recordedAtUtcMillis")) {
            for (value in listOf("\"0\"", "0.5", "1e0", "-1", "18446744073709551616")) {
                val tree = JsonParser.parseString(InspectionCodec.encode(issue())).asJsonObject
                tree.getAsJsonArray("events")[0].asJsonObject.add(key, JsonParser.parseString(value))
                assertThrows(key + "=" + value, IllegalArgumentException::class.java) { InspectionCodec.decode(tree.toString(), id) }
            }
        }
    }

    @Test fun captureExtensionsRoundTripWhileDuplicateNestedNamesAreRejected() {
        val text = CaptureRecordCodec.encode(RecordDocument(CaptureRecord.pending(id, null)))
        val extended = text.dropLast(1) + ",\"extension\":{\"label\":\"SYNTHETIC\"}}"
        val document = CaptureRecordCodec.decode(extended)
        assertEquals("SYNTHETIC", JsonParser.parseString(CaptureRecordCodec.encode(document)).asJsonObject
            .getAsJsonObject("extension")["label"].asString)
        val duplicate = text.dropLast(1) + ",\"extension\":{\"label\":false,\"label\":true}}"
        assertThrows(Exception::class.java) { CaptureRecordCodec.decode(duplicate) }
        assertNull(CaptureRecordCodec.readForRecovery("{broken", id))
        assertThrows(Exception::class.java) { CaptureRecordCodec.decode(text.dropLast(1) +
            ",\"extension\":" + "[".repeat(70) + "0" + "]".repeat(70) + "}") }
    }
}
