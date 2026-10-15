package org.rokidfieldlog.core

import java.io.File
import java.util.UUID

data class InspectionEvidence(val original: File, val importedSha256: String, val isSynthetic: Boolean)
fun interface InspectionEvidenceSource { fun load(photoId: String): InspectionEvidence }
data class InspectionListing(val issues: List<TrackedIssue>, val unreadableCount: Int)
data class InspectionComparison(val issue: TrackedIssue, val baseline: InspectionEvent, val latest: InspectionEvent,
    val baselinePhoto: File, val latestPhoto: File)

/** App-owned background queue serializes all commands and record deletion. */
class InspectionRepository(private val root: File, private val texts: AtomicTextFiles,
    private val clock: FieldClock, private val evidence: InspectionEvidenceSource) {
    private fun file(id: String): File { require(CaptureFiles.validId(id)); return File(root, "$id.json") }
    fun scan(): InspectionListing {
        var bad = 0
        val names = root.listFiles()?.map { it.name.removeSuffix(".bak").removeSuffix(".new") }
            ?.filter { it.endsWith(".json") && CaptureFiles.validId(it.removeSuffix(".json")) }?.toSet() ?: emptySet()
        val issues = names.mapNotNull { name -> try { load(name.removeSuffix(".json")) } catch (_: Exception) { bad++; null } }
            .sortedWith(compareByDescending<TrackedIssue> { it.needsRecheck }.thenBy { it.siteLabel }.thenBy { it.issueId })
        return InspectionListing(issues, bad)
    }
    fun load(id: String): TrackedIssue = InspectionCodec.decode(texts.read(file(id)), id)
    fun linkedIssue(photoId: String): TrackedIssue? {
        val listing = scan()
        check(listing.unreadableCount == 0) { "有问题历史无法读取，暂时无法确认照片关联；原文件已保留" }
        val linked = listing.issues.filter { issue -> issue.observations.any { it.photoId == photoId } }
        check(linked.size <= 1) { "照片关联冲突，请核查问题历史" }
        return linked.singleOrNull()
    }
    fun requireUnlinked(photoId: String) {
        require(linkedIssue(photoId) == null) { "这张照片保留在问题历史中，请先在问题详情删除跟踪，再删除记录" }
    }
    private fun checkEvidence(photoId: String, synthetic: Boolean? = null, hash: String? = null): InspectionEvidence {
        val source = evidence.load(photoId)
        require(source.importedSha256.matches(Regex("[0-9a-f]{64}")))
        require(source.original.isFile && PhotoDigest.sha256(source.original) == source.importedSha256) { "观察照片与导入时不一致，无法用于复查" }
        if (synthetic != null) require(source.isSynthetic == synthetic) { "演示与真实记录不能关联到同一个问题" }
        if (hash != null) require(source.importedSha256 == hash) { "观察照片与历史指纹不一致" }
        return source
    }
    fun create(siteLabel: String, category: IssueCategory, photoId: String, observedOn: String,
        sameSiteConfirmed: Boolean, note: String): TrackedIssue {
        requireUnlinked(photoId)
        val source = checkEvidence(photoId)
        val id = UUID.randomUUID().toString()
        val initial = observation(0, photoId, source.importedSha256, observedOn, InspectionVisibility.CLEAR,
            InspectionFinding.PROBLEM_PRESENT, sameSiteConfirmed, note)
        return commit(TrackedIssue(issueId = id, revision = 1, siteLabel = siteLabel.trim(), category = category,
            isSynthetic = source.isSynthetic, events = listOf(initial)))
    }
    fun observe(id: String, expectedRevision: Long, photoId: String, observedOn: String,
        visibility: InspectionVisibility, finding: InspectionFinding, sameSiteConfirmed: Boolean, note: String): TrackedIssue {
        val issue = current(id, expectedRevision)
        requireUnlinked(photoId)
        val source = checkEvidence(photoId, issue.isSynthetic)
        require(issue.observations.none { it.photoSha256 == source.importedSha256 }) { "照片与已有观察完全相同，请使用新的现场照片" }
        return append(issue, observation(issue.events.size, photoId, source.importedSha256, observedOn,
            visibility, finding, sameSiteConfirmed, note))
    }
    fun repairClaim(id: String, expectedRevision: Long, note: String): TrackedIssue {
        val issue = current(id, expectedRevision)
        return append(issue, event(issue, InspectionEventKind.REPAIR_CLAIM, note))
    }
    fun comparison(id: String): InspectionComparison {
        val issue = load(id)
        val observations = issue.observations
        val baseline = observations.first()
        val latest = observations.last()
        val before = checkEvidence(baseline.photoId!!, issue.isSynthetic, baseline.photoSha256).original
        val after = checkEvidence(latest.photoId!!, issue.isSynthetic, latest.photoSha256).original
        return InspectionComparison(issue, baseline, latest, before, after)
    }
    fun verify(id: String, expectedRevision: Long, basedOnObservationId: String,
        decision: VerificationDecision, chronologyConfirmed: Boolean, note: String): TrackedIssue {
        val issue = current(id, expectedRevision)
        val comparison = comparison(id) // Recheck both originals immediately before recording the human verdict.
        require(comparison.latest.eventId == basedOnObservationId) { "观察已更新，请重新打开对照" }
        return append(issue, event(issue, InspectionEventKind.VERIFICATION, note).copy(
            basedOnObservationId = basedOnObservationId, decision = decision, chronologyConfirmed = chronologyConfirmed))
    }
    fun deleteTracking(id: String, expectedRevision: Long) {
        current(id, expectedRevision)
        val target = file(id).canonicalFile
        require(target.parentFile == root.canonicalFile)
        // Remove only this issue's known AtomicFile companions, retaining source photo records.
        for (suffix in listOf(".bak", ".new", "")) {
            val candidate = File(target.path + suffix)
            check(!candidate.exists() || candidate.delete()) { "问题历史删除未完成" }
        }
    }
    private fun current(id: String, revision: Long): TrackedIssue = load(id).also {
        require(it.revision == revision) { "问题历史已更新，请重新打开" }
    }
    private fun event(issue: TrackedIssue, kind: InspectionEventKind, note: String) = InspectionEvent(
        UUID.randomUUID().toString(), issue.events.size, kind, clock.utcMillis(), note.trim())
    private fun observation(sequence: Int, photoId: String, hash: String, date: String,
        visibility: InspectionVisibility, finding: InspectionFinding, sameSite: Boolean, note: String) = InspectionEvent(
        UUID.randomUUID().toString(), sequence, InspectionEventKind.OBSERVATION, clock.utcMillis(), note.trim(),
        photoId, hash, date, visibility, finding, sameSite)
    private fun append(issue: TrackedIssue, event: InspectionEvent): TrackedIssue = commit(
        issue.copy(revision = issue.revision + 1, events = issue.events + event))
    private fun commit(issue: TrackedIssue): TrackedIssue {
        val text = InspectionCodec.encode(issue)
        InspectionCodec.decode(text, issue.issueId)
        check(root.isDirectory || root.mkdirs())
        texts.write(file(issue.issueId), text)
        return issue
    }
}
