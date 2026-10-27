package org.rokidfieldlog.core

enum class PhotoHealthProblem(val label: String) {
    ORIGINAL_MISSING("原图缺失"), ORIGINAL_CHANGED("原图指纹变化"), ORIGINAL_UNREADABLE("原图无法读取或格式异常"),
    REVIEW_MISSING("审阅副本缺失"), REVIEW_CHANGED("审阅副本大小或指纹变化"),
    REVIEW_UNSAFE("审阅副本格式或附加信息异常"), REVIEW_STALE("审阅副本关联的原图不一致"),
}

data class PhotoHealthIssue(val photoId: String, val problem: PhotoHealthProblem)
data class LibraryHealthReport(val checkedRecords: Int, val unreadableRecords: Int, val unreviewedPhotos: Int,
    val issues: List<PhotoHealthIssue>) {
    val affectedPhotos: Int get() = issues.map { it.photoId }.distinct().size
    fun summary(): String = if (checkedRecords == 0 && unreadableRecords == 0) "暂无可检查的照片记录。" else
        "可读照片记录：$checkedRecords\n文件检查未发现异常：${checkedRecords - affectedPhotos}" +
        "\n文件异常照片：$affectedPhotos\n不可读记录条目：$unreadableRecords" +
        "\n尚未审阅：$unreviewedPhotos（与异常数可重叠；不代表可以导出）"
}

/** Explicit, background-only inspection. Never repairs, rewrites or deletes library files. */
class LibraryHealthCheck(private val library: OfflineLibrary, private val inspector: ImageInspector) {
    fun check(): LibraryHealthReport {
        val listing = library.scan()
        val issues = mutableListOf<PhotoHealthIssue>()
        for (entry in listing.entries) {
            val originalProblem = try {
                when {
                    !entry.original.isFile -> PhotoHealthProblem.ORIGINAL_MISSING
                    entry.original.length() !in 1..CapturePackage.MAX_PHOTO_BYTES -> PhotoHealthProblem.ORIGINAL_UNREADABLE
                    PhotoDigest.sha256(entry.original) != entry.importedSha256 -> PhotoHealthProblem.ORIGINAL_CHANGED
                    !CaptureFiles.hasJpegMarkers(entry.original) -> PhotoHealthProblem.ORIGINAL_UNREADABLE
                    else -> {
                        val size = inspector.dimensions(entry.original)
                        if (size == null || size.first <= 0 || size.second <= 0 || size.first.toLong() * size.second > 24_000_000 ||
                            entry.source.record.width?.let { it != size.first } == true ||
                            entry.source.record.height?.let { it != size.second } == true) PhotoHealthProblem.ORIGINAL_UNREADABLE else null
                    }
                }
            } catch (_: Exception) { PhotoHealthProblem.ORIGINAL_UNREADABLE }
            originalProblem?.let { issues += PhotoHealthIssue(entry.draft.photoId, it) }
            val review = entry.draft.photoReview ?: continue
            val reviewProblem = try {
                val file = entry.reviewFile(review.fileName)
                when {
                    review.originalSha256 != entry.importedSha256 -> PhotoHealthProblem.REVIEW_STALE
                    !file.isFile -> PhotoHealthProblem.REVIEW_MISSING
                    file.length() !in 1..MaterialProfile().maxPhotoBytes || file.length() != review.byteCount ||
                        PhotoDigest.sha256(file) != review.outputSha256 -> PhotoHealthProblem.REVIEW_CHANGED
                    JpegPrivacy.hasPrivateMetadata(file) || inspector.dimensions(file) != (review.width to review.height) ||
                        maxOf(review.width, review.height) > MaterialProfile().maxLongEdge -> PhotoHealthProblem.REVIEW_UNSAFE
                    else -> null
                }
            } catch (_: Exception) { PhotoHealthProblem.REVIEW_UNSAFE }
            reviewProblem?.let { issues += PhotoHealthIssue(entry.draft.photoId, it) }
        }
        return LibraryHealthReport(listing.entries.size, listing.unreadableCount,
            listing.entries.count { it.draft.photoReview == null }, issues.toList())
    }
}
