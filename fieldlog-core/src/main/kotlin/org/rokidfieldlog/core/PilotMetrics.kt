package org.rokidfieldlog.core

import java.util.Locale

/** Counts of current, manually entered material notes, never verified acceptance or efficiency. */
data class PilotSummary(
    val nonDemoMaterials: Int, val datedMaterials: Int, val undatedNotes: Int,
    val replyNotes: Int, val timedMaterials: Int, val totalRecordedMinutes: Long, val medianRecordedMinutes: Double?,
    val albumAccepted: Int, val albumRejected: Int, val albumNotNeeded: Int, val albumUnknown: Int,
    val extraRequirementNotes: Int, val rejectedPhotoNotes: Int,
    val excludedDemo: Int, val unclassifiedMaterials: Int, val unreadableEntries: Int,
) {
    fun text(): String = "标记为非演示的材料：$nonDemoMaterials 份" +
        "\n有试交日期：$datedMaterials 份（每份仅当前记录）\n有笔记但未填试交日期：$undatedNotes 份（不计入下列试交统计）" +
        "\n\n有人工回执/回复文字：$replyNotes 份（未经外部核验）" +
        "\n已填写耗时：$timedMaterials 份；未填写：${datedMaterials - timedMaterials} 份" +
        "\n已填耗时合计：" + (if (timedMaterials == 0) "未知" else "$totalRecordedMinutes 分钟") +
        "\n已填耗时中位数：" + (medianRecordedMinutes?.let { String.format(Locale.ROOT, "%.1f 分钟", it) } ?: "未知") +
        "\n\n打码相册照片结果：接受 $albumAccepted；拒绝 $albumRejected；无需打码 $albumNotNeeded；未知 $albumUnknown" +
        "\n有额外要求笔记：$extraRequirementNotes 份；有退回照片笔记：$rejectedPhotoNotes 份" +
        "\n\n排除演示：$excludedDemo 份；类型未确定：$unclassifiedMaterials 份；不可读/冲突条目：$unreadableEntries" +
        "\n\n只汇总本机当前人工记录，不是独立问题数或完整试交次数。缺少对照组和接收人逐条评价，不计算省时比例、首次可接手率或政府受理率。"
}

object PilotMetrics {
    fun summarize(listing: ReportListing): PilotSummary {
        require(listing.unreadableCount >= 0)
        var unreadable = listing.unreadableCount
        val valid = mutableListOf<ReportDraft>()
        // Duplicate identities cannot inflate counts or silently pick a conflicting version.
        for (group in listing.reports.groupBy { it.reportId }.values) {
            if (group.size != 1) { unreadable += group.size; continue }
            val report = group.single()
            val checked = runCatching { ReportCodec.decode(ReportCodec.encode(report), report.reportId) }.getOrNull()
            if (checked == null) unreadable++ else valid += checked
        }
        val real = valid.filter { it.isSynthetic == false }
        val dated = real.filter { it.handoff.attemptedOn.isNotBlank() }
        val minutes = dated.mapNotNull { it.handoff.elapsedMinutes.toLongOrNull() }.sorted()
        val median = when {
            minutes.isEmpty() -> null
            minutes.size % 2 == 1 -> minutes[minutes.size / 2].toDouble()
            else -> (minutes[minutes.size / 2 - 1] + minutes[minutes.size / 2]) / 2.0
        }
        val undated = real.count { report -> with(report.handoff) {
            attemptedOn.isBlank() && (receipt.isNotBlank() || elapsedMinutes.isNotBlank() ||
                albumResult != "UNKNOWN" || extraRequirements.isNotBlank() || rejectedPhotos.isNotBlank())
        } }
        return PilotSummary(real.size, dated.size, undated,
            dated.count { it.handoff.receipt.isNotBlank() }, minutes.size, minutes.sum(), median,
            dated.count { it.handoff.albumResult == "ACCEPTED" }, dated.count { it.handoff.albumResult == "REJECTED" },
            dated.count { it.handoff.albumResult == "NOT_NEEDED" }, dated.count { it.handoff.albumResult == "UNKNOWN" },
            dated.count { it.handoff.extraRequirements.isNotBlank() }, dated.count { it.handoff.rejectedPhotos.isNotBlank() },
            valid.count { it.isSynthetic == true }, valid.count { it.isSynthetic == null }, unreadable)
    }
}
