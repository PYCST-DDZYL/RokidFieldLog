package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

class InspectionRepositoryTest {
    @get:Rule val temp = TemporaryFolder(File("build/inspection-tests").apply { mkdirs() })
    private val sources = mutableMapOf<String, InspectionEvidence>()
    private val clock = object : FieldClock {
        override fun utcMillis() = 123456789L // Deliberately independent of manually confirmed observation dates.
        override fun elapsedNanos() = 77L
    }
    private val texts = object : AtomicTextFiles {
        override fun read(file: File) = file.readText(Charsets.UTF_8)
        override fun write(file: File, text: String) { file.parentFile.mkdirs(); file.writeText(text, Charsets.UTF_8) }
    }
    private val folder by lazy { temp.newFolder("issues") }
    private fun repository(storage: AtomicTextFiles = texts) = InspectionRepository(folder, storage, clock,
        InspectionEvidenceSource { sources[it] ?: error("Missing fixture") })
    private val repo by lazy { repository() }
    private fun photo(synthetic: Boolean = true, color: Color = Color.RED): String {
        val id = UUID.randomUUID().toString(); val file = temp.newFile("$id.jpg")
        val image = BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { this.color = color; fillRect(0, 0, 80, 60); this.color = Color.WHITE; drawString(id.take(8), 2, 12); dispose() }
        check(ImageIO.write(image, "jpg", file))
        sources[id] = InspectionEvidence(file, PhotoDigest.sha256(file), synthetic)
        return id
    }
    private fun create(photoId: String = photo()) = repo.create("Synthetic campus / path A", IssueCategory.ROAD_DAMAGE,
        photoId, "2020-01-01", true, "Synthetic broken paving")
    private fun observe(issue: TrackedIssue, finding: InspectionFinding = InspectionFinding.NO_VISIBLE_PROBLEM,
        visibility: InspectionVisibility = InspectionVisibility.CLEAR, date: String = "2020-01-02", photoId: String = photo(color = Color.GREEN)) =
        repo.observe(issue.issueId, issue.revision, photoId, date, visibility, finding, true, "Synthetic revisit")
    private fun verify(issue: TrackedIssue, decision: VerificationDecision = VerificationDecision.REPAIRED,
        chronology: Boolean = true) = repo.verify(issue.issueId, issue.revision, issue.observations.last().eventId,
            decision, chronology, "Synthetic human comparison")

    @Test fun repeatedObservationKeepsOneStableIssueAndAppendOnlyHistory() {
        val first = create(); val second = observe(first, InspectionFinding.PROBLEM_PRESENT)
        assertEquals(first.issueId, second.issueId)
        assertEquals(1, repo.scan().issues.size)
        assertEquals(first.events, second.events.take(first.events.size))
        assertEquals(2, second.observations.size)
        assertEquals(InspectionState.OPEN, second.state)
    }
    @Test fun repairClaimCreatesRecheckTaskWithoutClosingIssue() {
        val issue = create(); val claim = repo.repairClaim(issue.issueId, issue.revision, "Crew says repaired")
        assertEquals(InspectionState.NEEDS_RECHECK, claim.state)
        assertTrue(claim.needsRecheck)
        assertEquals(1, claim.observations.size)
    }
    @Test fun clearPhotoWithoutProblemStillRequiresExplicitVerification() {
        val issue = observe(create())
        assertEquals(InspectionState.AWAITING_VERIFICATION, issue.state)
        assertTrue(issue.needsRecheck)
        assertThrows(IllegalStateException::class.java) { verify(issue) }
    }
    @Test fun verifiedRepairRequiresClaimNewClearObservationAndHumanChronologyConfirmation() {
        val first = create(); val claim = repo.repairClaim(first.issueId, first.revision, "Synthetic repair feedback")
        val latest = observe(claim); val verified = verify(latest)
        assertEquals(InspectionState.VERIFIED_REPAIRED, verified.state)
        assertFalse(verified.needsRecheck)
        assertEquals(4, verified.events.size)
        assertEquals(verified, repository().load(verified.issueId))
    }
    @Test fun obstructionNeverMeansRepaired() {
        val first = create(); val claim = repo.repairClaim(first.issueId, first.revision, "Reported repaired")
        val blocked = observe(claim, InspectionFinding.UNABLE_TO_ASSESS, InspectionVisibility.OBSTRUCTED)
        assertEquals(InspectionState.NEEDS_RECHECK, blocked.state)
        assertThrows(IllegalArgumentException::class.java) { verify(blocked) }
        assertEquals(InspectionState.NEEDS_RECHECK, verify(blocked, VerificationDecision.INCONCLUSIVE, false).state)
    }
    @Test fun wrongViewAndUnreadablePhotosCannotClaimProblemAbsent() {
        for (visibility in listOf(InspectionVisibility.WRONG_VIEW, InspectionVisibility.UNUSABLE)) {
            val issue = create()
            assertThrows(IllegalArgumentException::class.java) { observe(issue, visibility = visibility) }
            assertEquals(issue, repo.load(issue.issueId))
        }
    }
    @Test fun userReachableInspectionRejectionsExplainTheProblem() {
        val issue = create()
        val obstructed = assertThrows(IllegalArgumentException::class.java) {
            observe(issue, InspectionFinding.PROBLEM_PRESENT, InspectionVisibility.OBSTRUCTED)
        }
        assertTrue(obstructed.message!!.contains("无法判断"))
        val blankClaim = assertThrows(IllegalArgumentException::class.java) { repo.repairClaim(issue.issueId, issue.revision, "  ") }
        assertTrue(blankClaim.message!!.contains("维修"))
        val clear = observe(issue)
        val stillPresent = assertThrows(IllegalArgumentException::class.java) { verify(clear, VerificationDecision.STILL_PRESENT) }
        assertTrue(stillPresent.message!!.contains("仍存在"))
        assertEquals(clear, repo.load(issue.issueId))
    }
    @Test fun oldObservationCannotVerifyNewRepairClaim() {
        val issue = create(); val claim = repo.repairClaim(issue.issueId, issue.revision, "Repair reported after original photo")
        assertThrows(IllegalArgumentException::class.java) { verify(claim, VerificationDecision.STILL_PRESENT) }
        assertEquals(claim, repo.load(claim.issueId))
    }
    @Test fun clearViewOfProblemCannotBeConfirmedRepaired() {
        val first = create(); val claim = repo.repairClaim(first.issueId, first.revision, "Reported repaired")
        val still = observe(claim, InspectionFinding.PROBLEM_PRESENT)
        assertThrows(IllegalArgumentException::class.java) { verify(still) }
        assertEquals(InspectionState.OPEN, verify(still, VerificationDecision.STILL_PRESENT).state)
    }
    @Test fun chronologyMustBeExplicitEvenWhenDatesLookPlausible() {
        val first = create(); val claim = repo.repairClaim(first.issueId, first.revision, "Reported repaired")
        val latest = observe(claim)
        assertThrows(IllegalArgumentException::class.java) { verify(latest, chronology = false) }
        assertEquals(latest, repo.load(latest.issueId))
    }
    @Test fun laterProblemReopensPreviouslyVerifiedIssue() {
        val first = create(); val claim = repo.repairClaim(first.issueId, first.revision, "Reported repaired")
        val verified = verify(observe(claim))
        val recurring = observe(verified, InspectionFinding.PROBLEM_PRESENT, date = "2020-01-03")
        assertEquals(InspectionState.OPEN, recurring.state)
        assertEquals(verified.issueId, recurring.issueId)
        assertEquals(1, recurring.events.count { it.decision == VerificationDecision.REPAIRED })
    }
    @Test fun laterObstructedViewDoesNotInheritHistoricalRepairConclusion() {
        val first = create(); val claim = repo.repairClaim(first.issueId, first.revision, "Reported repaired")
        val verified = verify(observe(claim))
        assertEquals(InspectionState.NEEDS_RECHECK, observe(verified, InspectionFinding.UNABLE_TO_ASSESS,
            InspectionVisibility.OBSTRUCTED, "2020-01-03").state)
    }
    @Test fun staleEditorCannotAppendOverNewerHistory() {
        val first = create(); observe(first)
        assertThrows(IllegalArgumentException::class.java) { repo.repairClaim(first.issueId, first.revision, "Stale claim") }
        assertEquals(2L, repo.load(first.issueId).revision)
    }
    @Test fun duplicatePhotoCannotCountAsANewVisitOrAnotherIssue() {
        val id = photo(); val first = create(id)
        assertThrows(IllegalArgumentException::class.java) { create(id) }
        assertThrows(IllegalArgumentException::class.java) { observe(first, photoId = id) }
        assertEquals(1, first.observations.size)
    }
    @Test fun identicalBytesUnderANewPhotoIdDoNotCountAsAnotherVisit() {
        val id = photo(); val first = create(id); val copyId = UUID.randomUUID().toString()
        val copyFile = temp.newFile("copy.jpg"); sources.getValue(id).original.copyTo(copyFile, overwrite = true)
        sources[copyId] = InspectionEvidence(copyFile, sources.getValue(id).importedSha256, true)
        assertThrows(IllegalArgumentException::class.java) { observe(first, photoId = copyId) }
        assertEquals(first, repo.load(first.issueId))
    }

    @Test fun syntheticAndRealEvidenceCannotMix() {
        val first = create()
        assertThrows(IllegalArgumentException::class.java) { observe(first, photoId = photo(synthetic = false)) }
        assertTrue(repo.load(first.issueId).isSynthetic)
    }
    @Test fun manualSiteConfirmationAndObservationDateAreRequired() {
        assertThrows(IllegalArgumentException::class.java) { repo.create("Site", IssueCategory.ROAD_DAMAGE, photo(), "2020-01-01", false, "") }
        assertThrows(java.time.format.DateTimeParseException::class.java) { repo.create("Site", IssueCategory.ROAD_DAMAGE, photo(), "", true, "") }
        assertTrue(repo.scan().issues.isEmpty())
    }
    @Test fun outOfOrderObservationDateIsRejectedWithoutRewritingHistory() {
        val first = create()
        assertThrows(IllegalArgumentException::class.java) { observe(first, date = "2019-12-31") }
        assertEquals(first, repo.load(first.issueId))
    }
    @Test fun alteredOriginalCannotEnterHistory() {
        val id = photo(); sources.getValue(id).original.appendText("changed")
        assertThrows(IllegalArgumentException::class.java) { create(id) }
        assertTrue(repo.scan().issues.isEmpty())
    }
    @Test fun changedEvidenceAfterObservationPreventsComparisonAndVerdict() {
        val first = create(); val claim = repo.repairClaim(first.issueId, first.revision, "Reported repaired")
        val latest = observe(claim)
        sources.getValue(first.observations.first().photoId!!).original.appendText("changed")
        assertThrows(IllegalArgumentException::class.java) { repo.comparison(first.issueId) }
        assertThrows(IllegalArgumentException::class.java) { verify(latest) }
        assertEquals(latest, repo.load(first.issueId))
    }
    @Test fun olderComparisonCannotVerifyAnotherObservation() {
        val first = create(); val claim = repo.repairClaim(first.issueId, first.revision, "Reported repaired")
        val old = observe(claim); val latest = observe(old, date = "2020-01-03")
        assertThrows(IllegalArgumentException::class.java) { repo.verify(latest.issueId, latest.revision,
            old.observations.last().eventId, VerificationDecision.REPAIRED, true, "Stale photo selection") }
    }
    @Test fun linkedPhotoIsProtectedUntilTrackingIsExplicitlyRemoved() {
        val id = photo(); val first = create(id)
        assertThrows(IllegalArgumentException::class.java) { repo.requireUnlinked(id) }
        repo.deleteTracking(first.issueId, first.revision)
        repo.requireUnlinked(id)
        assertTrue(sources.getValue(id).original.isFile)
        assertTrue(repo.scan().issues.isEmpty())
    }
    @Test fun damagedIssuePreservesHealthyIssuesAndBlocksUnsafePhotoDeletion() {
        val broken = create(); val healthy = create()
        File(folder, broken.issueId + ".json").writeText("{broken")
        assertEquals(listOf(healthy.issueId), repo.scan().issues.map { it.issueId })
        assertEquals(1, repo.scan().unreadableCount)
        assertThrows(IllegalStateException::class.java) { repo.requireUnlinked(photo()) }
    }
    @Test fun schemaAndEventReorderingCannotInventVerifiedHistory() {
        val first = create(); val claim = repo.repairClaim(first.issueId, first.revision, "Reported repaired")
        val latest = observe(claim); val verified = verify(latest)
        assertEquals(verified, InspectionCodec.decode(InspectionCodec.encode(verified), verified.issueId))
        assertThrows(IllegalArgumentException::class.java) { InspectionCodec.decode(InspectionCodec.encode(verified.copy(schemaVersion = 2)), verified.issueId) }
        assertThrows(IllegalArgumentException::class.java) { InspectionCodec.decode(InspectionCodec.encode(verified.copy(events = verified.events.reversed())), verified.issueId) }
        assertThrows(IllegalArgumentException::class.java) { InspectionCodec.decode(InspectionCodec.encode(verified.copy(events = verified.events.dropLast(1))), verified.issueId) }
    }
    @Test fun unknownHistoryFieldsAreRejectedRatherThanDiscardedOnAppend() {
        val issue = create()
        val tree = com.google.gson.JsonParser.parseString(InspectionCodec.encode(issue)).asJsonObject
        tree.addProperty("futureExtension", "must remain on disk")
        val target = File(folder, issue.issueId + ".json"); target.writeText(tree.toString())
        assertThrows(IllegalArgumentException::class.java) { repo.repairClaim(issue.issueId, issue.revision, "Must not overwrite") }
        assertTrue(target.readText().contains("must remain on disk"))
    }

    @Test fun blankFeedbackAndBlankVerificationNotesDoNotMutateHistory() {
        val first = create()
        assertThrows(IllegalArgumentException::class.java) { repo.repairClaim(first.issueId, first.revision, " ") }
        assertThrows(IllegalArgumentException::class.java) { repo.verify(first.issueId, first.revision,
            first.observations.first().eventId, VerificationDecision.INCONCLUSIVE, false, " ") }
        assertEquals(first, repo.load(first.issueId))
    }

    @Test fun coercedChronologyCannotLoadAsRepairedOrOverwriteTheStoredHistory() {
        val first = create()
        val claim = repo.repairClaim(first.issueId, first.revision, "SYNTHETIC repair feedback")
        val verified = verify(observe(claim))
        val healthy = create()
        val target = File(folder, verified.issueId + ".json")
        val broken = InspectionCodec.encode(verified).replace("\"chronologyConfirmed\": true",
            "\"chronologyConfirmed\": \"true\"").toByteArray(Charsets.UTF_8)
        target.writeBytes(broken)
        assertEquals(1, repo.scan().unreadableCount)
        assertEquals(listOf(healthy), repo.scan().issues)
        assertThrows(IllegalArgumentException::class.java) { repo.load(verified.issueId) }
        assertThrows(IllegalArgumentException::class.java) { repo.repairClaim(verified.issueId, verified.revision, "Must not overwrite") }
        assertThrows(IllegalStateException::class.java) { repo.requireUnlinked(first.observations.first().photoId!!) }
        assertArrayEquals(broken, target.readBytes())
        assertEquals(healthy, repo.load(healthy.issueId))
    }
}
