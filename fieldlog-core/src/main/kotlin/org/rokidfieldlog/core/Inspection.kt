package org.rokidfieldlog.core

import com.google.gson.*
import com.google.gson.annotations.SerializedName
import java.time.LocalDate

enum class InspectionEventKind { OBSERVATION, REPAIR_CLAIM, VERIFICATION }
enum class InspectionVisibility { CLEAR, OBSTRUCTED, WRONG_VIEW, UNUSABLE }
enum class InspectionFinding { PROBLEM_PRESENT, NO_VISIBLE_PROBLEM, UNABLE_TO_ASSESS }
enum class VerificationDecision { REPAIRED, STILL_PRESENT, INCONCLUSIVE }
enum class InspectionState { OPEN, NEEDS_RECHECK, AWAITING_VERIFICATION, VERIFIED_REPAIRED }

data class InspectionEvent(
    @field:SerializedName("eventId") val eventId: String = "",
    @field:SerializedName("sequence") val sequence: Int = -1,
    @field:SerializedName("kind") val kind: InspectionEventKind = InspectionEventKind.OBSERVATION,
    @field:SerializedName("recordedAtUtcMillis") val recordedAtUtcMillis: Long = 0,
    @field:SerializedName("note") val note: String = "",
    @field:SerializedName("photoId") val photoId: String? = null,
    @field:SerializedName("photoSha256") val photoSha256: String? = null,
    @field:SerializedName("observedOn") val observedOn: String? = null,
    @field:SerializedName("visibility") val visibility: InspectionVisibility? = null,
    @field:SerializedName("finding") val finding: InspectionFinding? = null,
    @field:SerializedName("sameSiteConfirmed") val sameSiteConfirmed: Boolean = false,
    @field:SerializedName("basedOnObservationId") val basedOnObservationId: String? = null,
    @field:SerializedName("decision") val decision: VerificationDecision? = null,
    @field:SerializedName("chronologyConfirmed") val chronologyConfirmed: Boolean = false,
)

data class TrackedIssue(
    @field:SerializedName("schemaVersion") val schemaVersion: Int = 1,
    @field:SerializedName("issueId") val issueId: String = "",
    @field:SerializedName("revision") val revision: Long = 0,
    @field:SerializedName("siteLabel") val siteLabel: String = "",
    @field:SerializedName("category") val category: IssueCategory = IssueCategory.OTHER,
    @field:SerializedName("isSynthetic") val isSynthetic: Boolean = false,
    @field:SerializedName("events") val events: List<InspectionEvent> = emptyList(),
) {
    val observations: List<InspectionEvent> get() = events.filter { it.kind == InspectionEventKind.OBSERVATION }
    val state: InspectionState get() = when (val last = events.lastOrNull()) {
        null -> InspectionState.OPEN
        else -> when (last.kind) {
            InspectionEventKind.REPAIR_CLAIM -> InspectionState.NEEDS_RECHECK
            InspectionEventKind.OBSERVATION -> when (last.finding) {
                InspectionFinding.PROBLEM_PRESENT -> InspectionState.OPEN
                InspectionFinding.NO_VISIBLE_PROBLEM -> InspectionState.AWAITING_VERIFICATION
                else -> InspectionState.NEEDS_RECHECK
            }
            InspectionEventKind.VERIFICATION -> when (last.decision) {
                VerificationDecision.REPAIRED -> InspectionState.VERIFIED_REPAIRED
                VerificationDecision.STILL_PRESENT -> InspectionState.OPEN
                else -> InspectionState.NEEDS_RECHECK
            }
        }
    }
    val needsRecheck: Boolean get() = state in setOf(InspectionState.NEEDS_RECHECK, InspectionState.AWAITING_VERIFICATION)
    val recheckReason: String get() = when (state) {
        InspectionState.OPEN -> "现场仍有问题，等待处理"
        InspectionState.VERIFIED_REPAIRED -> "已有人工复查记录；结论对应当次可见范围"
        InspectionState.AWAITING_VERIFICATION -> "最近照片未见问题，仍需前后对照核对"
        InspectionState.NEEDS_RECHECK -> if (events.last().kind == InspectionEventKind.REPAIR_CLAIM) "维修反馈后尚需现场复查" else "最近证据不足，需要重新观察"
    }
}

object InspectionCodec {
    const val MAX_EVENTS = 1000
    const val MAX_BYTES = 1024 * 1024
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().disableJdkUnsafe().setStrictness(Strictness.STRICT)
        .registerTypeAdapter(InspectionEventKind::class.java, JsonDeserializer { v, _, _ -> InspectionEventKind.valueOf(v.asString) })
        .registerTypeAdapter(InspectionVisibility::class.java, JsonDeserializer { v, _, _ -> InspectionVisibility.valueOf(v.asString) })
        .registerTypeAdapter(InspectionFinding::class.java, JsonDeserializer { v, _, _ -> InspectionFinding.valueOf(v.asString) })
        .registerTypeAdapter(VerificationDecision::class.java, JsonDeserializer { v, _, _ -> VerificationDecision.valueOf(v.asString) })
        .registerTypeAdapter(IssueCategory::class.java, JsonDeserializer { v, _, _ -> IssueCategory.valueOf(v.asString) }).create()
    private val issueFields = gson.toJsonTree(TrackedIssue()).asJsonObject.keySet()
    private val eventFields = gson.toJsonTree(InspectionEvent()).asJsonObject.keySet()
    fun encode(issue: TrackedIssue): String = gson.toJson(issue)
    fun decode(text: String, expectedId: String): TrackedIssue {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val tree = gson.fromJson(text, JsonObject::class.java) ?: error("Missing issue")
        require(tree.has("schemaVersion") && tree.has("isSynthetic") && tree.has("category"))
        require(tree.keySet().all { it in issueFields }) { "问题历史含尚不支持的字段，原文件保留" }
        tree.getAsJsonArray("events")?.forEach { event -> require(event.asJsonObject.keySet().all { it in eventFields }) }
        val issue = gson.fromJson(tree, TrackedIssue::class.java)
        require(issue.schemaVersion == 1 && CaptureFiles.validId(expectedId) && issue.issueId == expectedId)
        requireNotNull(issue.category); requireNotNull(issue.siteLabel); requireNotNull(issue.events)
        require(issue.siteLabel.isNotBlank() && issue.siteLabel.length <= 200)
        require(issue.events.size in 1..MAX_EVENTS && issue.revision == issue.events.size.toLong())
        val ids = mutableSetOf<String>(); val photos = mutableSetOf<String>()
        val prior = mutableListOf<InspectionEvent>()
        issue.events.forEachIndexed { index, event ->
            require(CaptureFiles.validId(event.eventId) && ids.add(event.eventId) && event.sequence == index)
            requireNotNull(event.kind); requireNotNull(event.note); require(event.note.length <= 500)
            when (event.kind) {
                InspectionEventKind.OBSERVATION -> {
                    require(CaptureFiles.validId(event.photoId ?: "") && photos.add(event.photoId!!))
                    require(event.photoSha256?.matches(Regex("[0-9a-f]{64}")) == true)
                    val date = LocalDate.parse(requireNotNull(event.observedOn))
                    prior.lastOrNull { it.kind == InspectionEventKind.OBSERVATION }?.let { require(date >= LocalDate.parse(it.observedOn)) { "请按观察日期先后关联照片" } }
                    requireNotNull(event.visibility); requireNotNull(event.finding); require(event.sameSiteConfirmed)
                    if (event.visibility != InspectionVisibility.CLEAR) require(event.finding == InspectionFinding.UNABLE_TO_ASSESS)
                    require(event.basedOnObservationId == null && event.decision == null && !event.chronologyConfirmed)
                    if (index == 0) require(event.finding == InspectionFinding.PROBLEM_PRESENT && event.visibility == InspectionVisibility.CLEAR)
                }
                InspectionEventKind.REPAIR_CLAIM -> {
                    require(index > 0 && event.note.isNotBlank())
                    requireNoPhoto(event); require(event.basedOnObservationId == null && event.decision == null && !event.chronologyConfirmed)
                }
                InspectionEventKind.VERIFICATION -> {
                    requireNoPhoto(event); require(event.note.isNotBlank()); requireNotNull(event.decision)
                    val latest = prior.lastOrNull { it.kind == InspectionEventKind.OBSERVATION } ?: error("缺少现场观察")
                    require(event.basedOnObservationId == latest.eventId) { "只能核对最近一次观察" }
                    require(prior.lastOrNull()?.kind != InspectionEventKind.VERIFICATION) { "该次复查已记录，请添加新的观察" }
                    val recentClaim = prior.lastOrNull { it.kind == InspectionEventKind.REPAIR_CLAIM }
                    if (recentClaim != null && event.decision != VerificationDecision.INCONCLUSIVE)
                        require(latest.sequence > recentClaim.sequence && event.chronologyConfirmed) { "需要最近维修反馈之后的新观察，并核对拍摄先后顺序" }
                    when (event.decision) {
                        VerificationDecision.REPAIRED -> {
                            val claim = prior.lastOrNull { it.kind == InspectionEventKind.REPAIR_CLAIM } ?: error("请先记录维修反馈")
                            require(latest.sequence > claim.sequence && event.chronologyConfirmed) { "需要维修反馈之后的复查照片并核对先后顺序" }
                            require(latest.visibility == InspectionVisibility.CLEAR && latest.finding == InspectionFinding.NO_VISIBLE_PROBLEM) { "照片需要清晰显示问题位置且未见原问题" }
                        }
                        VerificationDecision.STILL_PRESENT -> require(latest.visibility == InspectionVisibility.CLEAR && latest.finding == InspectionFinding.PROBLEM_PRESENT)
                        VerificationDecision.INCONCLUSIVE -> Unit
                    }
                }
            }
            prior += event
        }
        return issue
    }
    private fun requireNoPhoto(event: InspectionEvent) {
        require(event.photoId == null && event.photoSha256 == null && event.observedOn == null &&
            event.visibility == null && event.finding == null && !event.sameSiteConfirmed)
    }
}
