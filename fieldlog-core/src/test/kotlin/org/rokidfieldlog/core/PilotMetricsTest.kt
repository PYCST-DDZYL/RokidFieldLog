package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PilotMetricsTest {
    private fun report(minutes: String = "", date: String = "2020-01-01", album: String = "UNKNOWN", reply: String = "",
        synthetic: Boolean? = false) = ReportDraft(reportId = UUID.randomUUID().toString(), isSynthetic = synthetic,
        handoff = ReportHandoff(attemptedOn = date, elapsedMinutes = minutes, albumResult = album, receipt = reply))
    private fun summary(vararg reports: ReportDraft, unreadable: Int = 0) = PilotMetrics.summarize(ReportListing(reports.toList(), unreadable))

    @Test fun emptyAndMissingTimeStayUnknownRatherThanZeroOrSuccess() {
        val empty = summary()
        assertEquals(0, empty.nonDemoMaterials); assertNull(empty.medianRecordedMinutes)
        val result = summary(report())
        assertEquals(1, result.datedMaterials); assertEquals(0, result.timedMaterials)
        assertNull(result.medianRecordedMinutes); assertEquals(1, result.albumUnknown)
        assertEquals(0, result.replyNotes); assertTrue(result.text().contains("合计：未知"))
    }
    @Test fun demoUnclassifiedAndUnreadableEntriesDoNotInflateResults() {
        val result = summary(report("10"), report("99999", synthetic = true), report("99999", synthetic = null), unreadable = 2)
        assertEquals(1, result.nonDemoMaterials); assertEquals(10L, result.totalRecordedMinutes)
        assertEquals(1, result.excludedDemo); assertEquals(1, result.unclassifiedMaterials); assertEquals(2, result.unreadableEntries)
    }
    @Test fun missingAttemptDateIsVisibleButNotCountedAsAnAttempt() {
        val result = summary(report("10", date = "", album = "ACCEPTED", reply = "SYNTHETIC_REPLY"), report(date = ""))
        assertEquals(2, result.nonDemoMaterials); assertEquals(1, result.undatedNotes)
        assertEquals(0, result.datedMaterials); assertEquals(0, result.replyNotes); assertEquals(0, result.albumAccepted)
        assertEquals(0, result.timedMaterials); assertNull(result.medianRecordedMinutes)
    }
    @Test fun mediansUseOnlyRecordedTimesAndEveryAlbumOutcomeHasItsOwnCount() {
        val result = summary(report("10", album = "ACCEPTED"), report("20", album = "REJECTED"),
            report(album = "NOT_NEEDED"), report(reply = "SYNTHETIC_REPLY"))
        assertEquals(4, result.datedMaterials); assertEquals(2, result.timedMaterials)
        assertEquals(30L, result.totalRecordedMinutes); assertEquals(15.0, result.medianRecordedMinutes!!, 0.0)
        assertEquals(listOf(1, 1, 1, 1), listOf(result.albumAccepted, result.albumRejected, result.albumNotNeeded, result.albumUnknown))
        assertEquals(1, result.replyNotes)
        assertEquals(2.0, summary(report("1"), report("99"), report("2")).medianRecordedMinutes!!, 0.0)
    }
    @Test fun invalidOrConflictingRecordsAreExcludedRatherThanSilentlyChosen() {
        val duplicate = report("10")
        val result = summary(duplicate, duplicate.copy(handoff = duplicate.handoff.copy(elapsedMinutes = "20")), report("0"), report("12.5"))
        assertEquals(0, result.nonDemoMaterials); assertEquals(4, result.unreadableEntries)
        assertNull(result.medianRecordedMinutes)
    }
    @Test fun summaryContainsNoAddressesContactsReceiptsOrGpsAndDoesNotMutateNotes() {
        val raw = report("10", reply = "PRIVATE_RECEIPT_SENTINEL").copy(address = "PRIVATE_ADDRESS_SENTINEL",
            contactName = "PRIVATE_CONTACT_SENTINEL", photographerReference = PhotographerReference(0.0, 0.0,
                locationTimeUtcMillis = 100, selectedAtUtcMillis = 200, source = "PRIVATE_GPS_SOURCE_SENTINEL"),
            handoff = report("10").handoff.copy(receipt = "PRIVATE_RECEIPT_SENTINEL",
                extraRequirements = "PRIVATE_REQUIREMENT_SENTINEL", rejectedPhotos = "PRIVATE_REJECTION_SENTINEL"))
        val before = ReportCodec.encode(raw)
        val result = summary(raw)
        assertEquals(1, result.extraRequirementNotes); assertEquals(1, result.rejectedPhotoNotes)
        assertFalse(result.text().contains("SENTINEL")); assertFalse(result.text().contains(raw.reportId))
        assertEquals(before, ReportCodec.encode(raw))
        assertTrue(result.text().contains("不计算省时比例"))
    }
}
