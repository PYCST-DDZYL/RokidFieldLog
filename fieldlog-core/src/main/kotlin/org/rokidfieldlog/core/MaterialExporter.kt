package org.rokidfieldlog.core

import com.google.gson.GsonBuilder
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object JpegPrivacy {
    /** Fresh report JPEGs must not carry EXIF, XMP, ICC, IPTC or comment segments. */
    fun hasPrivateMetadata(file: File): Boolean = RandomAccessFile(file, "r").use { input ->
        require(input.length() >= 4 && input.readUnsignedShort() == 0xffd8) { "Invalid JPEG" }
        while (input.filePointer < input.length()) {
            require(input.readUnsignedByte() == 0xff) { "Invalid JPEG marker" }
            var marker = input.readUnsignedByte()
            while (marker == 0xff) marker = input.readUnsignedByte()
            if (marker == 0xda || marker == 0xd9) return@use false
            if (marker in 0xd0..0xd7 || marker == 0x01) continue
            val length = input.readUnsignedShort()
            require(length >= 2 && input.filePointer + length - 2 <= input.length())
            if (marker in setOf(0xe1, 0xe2, 0xed, 0xfe)) return@use true
            input.seek(input.filePointer + length - 2)
        }
        error("Incomplete JPEG header")
    }
}

object MaterialExporter {
    fun validate(draft: IssueDraft, original: File, reviewed: File, inspector: ImageInspector,
        profile: MaterialProfile = MaterialProfile()) {
        val reasons = MaterialReadiness.reasons(draft, profile)
        require(reasons.isEmpty()) { reasons.joinToString("；") }
        val review = checkNotNull(draft.photoReview)
        require(review.fileName.matches(Regex("review-[0-9a-f-]{36}\\.jpg")) && reviewed.name == review.fileName)
        require(PhotoDigest.sha256(original) == review.originalSha256) { "原图变化，请重新审阅" }
        require(reviewed.isFile && reviewed.length() == review.byteCount && reviewed.length() <= profile.maxPhotoBytes)
        require(PhotoDigest.sha256(reviewed) == review.outputSha256) { "导出副本变化，请重新审阅" }
        require(CaptureFiles.hasJpegMarkers(reviewed) && !JpegPrivacy.hasPrivateMetadata(reviewed)) { "导出副本包含无关元数据" }
        require(inspector.dimensions(reviewed) == (review.width to review.height)) { "导出副本无法完整解码" }
    }

    fun write(output: OutputStream, draft: IssueDraft, original: File, reviewed: File,
        inspector: ImageInspector, profile: MaterialProfile = MaterialProfile()) {
        validate(draft, original, reviewed, inspector, profile)
        val imageName = "photo-reviewed.jpg"
        val category = categoryLabel(draft.category)
        val text = buildString {
            if (draft.isSynthetic) append("合成演示数据，非现场记录，不得当作真实事项提交。\n\n")
            append("问题类别：" + category + "\n")
            append("问题地址：" + draft.address.trim() + "\n")
            append("事发日期（人工确认）：" + draft.eventDate + "\n")
            append("情况说明：\n" + draft.description.trim() + "\n")
            draft.issuePosition?.let { append("人工确认问题坐标：" + it.latitude + ", " + it.longitude + "（" + it.coordinateSystem + "）\n") }
            append("\n附件：" + imageName + "\n")
            append("此材料供人工填写官方表单；请核对当地渠道的分类、附件和文字要求。\n")
        }
        // Whitelist fields. Never serialize the source record, raw phone GPS or a receipt into a report.
        val summary = linkedMapOf<String, Any?>("format" to "FIELDLOG_MANUAL_MATERIALS", "version" to 1,
            "recordId" to draft.photoId, "draftRevision" to draft.revision, "isSynthetic" to draft.isSynthetic,
            "category" to draft.category?.name, "address" to draft.address.trim(), "eventDate" to draft.eventDate,
            "description" to draft.description.trim(), "issuePosition" to draft.issuePosition,
            "photoFile" to imageName, "photoSha256" to draft.photoReview!!.outputSha256,
            "profile" to profile.displayName, "submissionMethod" to "USER_SUBMITS_MANUALLY")
        ZipOutputStream(output.buffered()).use { zip ->
            fun textEntry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            textEntry("report.txt", text)
            textEntry("summary.json", GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(summary))
            zip.putNextEntry(ZipEntry(imageName)); reviewed.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        }
    }

    fun categoryLabel(category: IssueCategory?): String = when (category) {
        IssueCategory.ROAD_DAMAGE -> "坑洞 / 路面损坏"
        IssueCategory.DAMAGED_FACILITY -> "设施损坏"
        IssueCategory.LITTER -> "垃圾堆积"
        IssueCategory.OTHER -> "其他问题"
        null -> "待确认"
    }
}
