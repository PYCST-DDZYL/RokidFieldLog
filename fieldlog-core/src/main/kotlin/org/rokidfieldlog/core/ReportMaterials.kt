package org.rokidfieldlog.core

import com.google.gson.*
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ReportPhotoRole { CONTEXT, DETAIL, ADDITIONAL }
data class ReportPhoto(val photoId: String = "", val role: ReportPhotoRole = ReportPhotoRole.ADDITIONAL)

/** One manually confirmed issue on one visit, independent of longitudinal inspection history. */
data class ReportDraft(
    val schemaVersion: Int = 1,
    val reportId: String = "",
    val revision: Long = 0,
    val recipient: String = "",
    val category: String = "",
    val address: String = "",
    val landmark: String = "",
    val eventDate: String = "",
    val description: String = "",
    val locationConfirmed: Boolean = false,
    val sameIssueVisitConfirmed: Boolean = false,
    val photos: List<ReportPhoto> = emptyList(),
    val isSynthetic: Boolean? = null,
    val channelId: String = "GENERIC_MANUAL",
    val province: String = "",
    val city: String = "",
    val district: String = "",
    val purpose: String = "",
    val contactName: String = "",
    val contactPhone: String = "",
    val mediaInterview: Boolean? = null,
    val noticeAccepted: Boolean = false,
    val albumAcceptanceConfirmed: Boolean = false,
    val photographerReference: PhotographerReference? = null,
    val addressSuggestion: String = "",
    val handoff: ReportHandoff = ReportHandoff(),
)

/** Private notes about a real manual attempt; never included in outgoing materials. */
data class ReportHandoff(
    val receipt: String = "",
    val attemptedOn: String = "",
    val extraRequirements: String = "",
    val rejectedPhotos: String = "",
    val elapsedMinutes: String = "",
    val albumResult: String = "UNKNOWN",
    val inspectionIssueId: String? = null,
)

object ReportCodec {
    const val MAX_BYTES = 65_536
    const val MAX_PHOTOS = 5 // Product limit, not a claimed limit of any government channel.
    private val fields = setOf("schemaVersion", "reportId", "revision", "recipient", "category", "address", "landmark",
        "eventDate", "description", "locationConfirmed", "sameIssueVisitConfirmed", "photos", "isSynthetic",
        "channelId", "province", "city", "district", "purpose", "contactName", "contactPhone", "mediaInterview",
        "noticeAccepted", "albumAcceptanceConfirmed", "photographerReference", "addressSuggestion", "handoff")
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().disableJdkUnsafe().setStrictness(Strictness.STRICT)
        .registerTypeAdapter(ReportPhotoRole::class.java, JsonDeserializer { value, _, _ -> ReportPhotoRole.valueOf(value.asString) }).create()
    fun encode(draft: ReportDraft): String = gson.toJson(draft)
    fun decode(text: String, expectedId: String): ReportDraft {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "材料记录过大" }
        val tree = RecordJson.objectValue(text, MAX_BYTES)
        require(tree.keySet().all { it in fields }) { "材料记录含未知字段，原文件保留" }
        require(listOf("schemaVersion", "reportId", "revision", "photos").all { tree.has(it) })
        for (key in listOf("recipient", "category", "address", "landmark", "eventDate", "description", "reportId", "channelId", "province", "city", "district", "purpose", "contactName", "contactPhone", "addressSuggestion")) {
            tree[key]?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
        }
        RecordJson.integer(tree["schemaVersion"], Int.MAX_VALUE.toLong())
        require(tree["schemaVersion"].toString() == "1") { "尚不支持此材料版本，原文件保留" }
        RecordJson.integer(tree["revision"])
        for (key in listOf("locationConfirmed", "sameIssueVisitConfirmed", "noticeAccepted", "albumAcceptanceConfirmed")) {
            tree[key]?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }
        }
        tree["mediaInterview"]?.takeUnless { it.isJsonNull }?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }
        tree["isSynthetic"]?.takeUnless { it.isJsonNull }?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }
        require(tree["photos"].isJsonArray)
        for (photo in tree["photos"].asJsonArray) {
            require(photo.isJsonObject && photo.asJsonObject.keySet() == setOf("photoId", "role"))
            require(photo.asJsonObject["photoId"].isJsonPrimitive && photo.asJsonObject["photoId"].asJsonPrimitive.isString)
            require(photo.asJsonObject["role"].isJsonPrimitive && photo.asJsonObject["role"].asJsonPrimitive.isString)
        }
        PhotographerReferenceJson.validate(tree["photographerReference"])
        val draft = gson.fromJson(tree, ReportDraft::class.java)
        require(CaptureFiles.validId(expectedId) && draft.reportId == expectedId && draft.schemaVersion == 1 && draft.revision >= 0)
        requireNotNull(draft.recipient); requireNotNull(draft.category); requireNotNull(draft.address)
        requireNotNull(draft.landmark); requireNotNull(draft.eventDate); requireNotNull(draft.description); requireNotNull(draft.photos)
        require(draft.recipient.length <= 120 && draft.category.length <= 120 && draft.address.length <= 200 &&
            draft.landmark.length <= 200 && draft.description.length <= 2000 && draft.eventDate.length <= 10)
        require(draft.channelId in setOf("GENERIC_MANUAL", Jiangsu12345.ID))
        require(listOf(draft.province, draft.city, draft.district).all { it.length <= 100 })
        require(draft.purpose.length <= 100 && draft.contactName.length <= 80 && draft.contactPhone.length <= 32)
        draft.photographerReference?.validate()
        require(draft.addressSuggestion.length <= 200)
        tree["handoff"]?.let { handoff ->
            require(handoff.isJsonObject && handoff.asJsonObject.keySet().all { it in setOf("receipt", "attemptedOn", "extraRequirements", "rejectedPhotos", "elapsedMinutes", "albumResult", "inspectionIssueId") })
            handoff.asJsonObject.entrySet().forEach { (key, value) ->
                require((key == "inspectionIssueId" && value.isJsonNull) || (value.isJsonPrimitive && value.asJsonPrimitive.isString))
            }
        }
        with(draft.handoff) {
            require(receipt.length <= 200 && extraRequirements.length <= 1000 && rejectedPhotos.length <= 1000)
            require(attemptedOn.isEmpty() || runCatching { LocalDate.parse(attemptedOn) }.isSuccess)
            require(elapsedMinutes.isEmpty() || (elapsedMinutes.matches(Regex("[0-9]{1,5}")) && elapsedMinutes.toInt() > 0)) { "耗时请填写大于 0 的分钟数，或留空" }
            require(albumResult in setOf("UNKNOWN", "ACCEPTED", "REJECTED", "NOT_NEEDED"))
            require(inspectionIssueId == null || CaptureFiles.validId(inspectionIssueId))
        }
        require(draft.photos.size <= MAX_PHOTOS && draft.photos.map { it.photoId }.distinct().size == draft.photos.size)
        draft.photos.forEach { require(CaptureFiles.validId(it.photoId)); requireNotNull(it.role) }
        return draft
    }
}

enum class ReportTextPart { PURPOSE, ADDRESS, BODY }
object ReportPaste {
    fun address(report: ReportDraft): String = report.address.trim() + report.landmark.trim().takeIf { it.isNotBlank() }?.let { "；参照物：" + it }.orEmpty()
}

object ReportReadiness {
    // Shared report content is entered once. Reuse the existing actual-photo privacy checks without changing each photo's draft.
    internal fun photoDraft(report: ReportDraft, entry: LibraryEntry) = entry.draft.copy(
        category = IssueCategory.OTHER, address = report.address, addressConfirmed = report.locationConfirmed,
        eventDate = report.eventDate, description = report.description, issuePosition = null)

    fun reasons(report: ReportDraft, entries: Map<String, LibraryEntry>): List<String> {
        val reasons = mutableListOf<String>()
        if (report.channelId == Jiangsu12345.ID) {
            if (report.province != "江苏省" || report.city.isBlank() || report.district.isBlank()) reasons += "请填写江苏省、市、区县（以官方区划选项为准）"
            if (ReportPaste.address(report).length > 200) reasons += "详细地址与参照物合计超过网页200字，请精简"
            if (report.purpose.isBlank() || report.purpose.length > 100) reasons += "请填写100字以内的诉求目的"
            if (report.contactName.isBlank() || !report.contactPhone.matches(Regex("[+0-9() -]{5,32}")) ||
                report.contactPhone.count { it in '0'..'9' } < 5) reasons += "请填写联系人及有效联系电话，并在官方个人信息中核对"
            if (report.mediaInterview == null) reasons += "请选择是否接受媒体采访"
            if (!report.noticeAccepted) reasons += "请阅读官方须知并确认联系方式与个人信息告知"
        }
        if (report.recipient.isBlank()) reasons += "请填写实际接收人或提交渠道"
        if (report.category.isBlank()) reasons += "请按接收方的选项填写问题分类"
        if (report.address.isBlank() || !report.locationConfirmed) reasons += "请填写并确认问题地址"
        if (report.landmark.isBlank()) reasons += "请补充附近参照物或设施编号，便于找到现场"
        if (runCatching { LocalDate.parse(report.eventDate) }.isFailure) reasons += "请人工确认本次现场日期"
        if (report.description.isBlank()) reasons += "请说明具体问题和需要处理的事项"
        if (!report.sameIssueVisitConfirmed) reasons += "请核对所有照片属于同一问题、同一次现场观察"
        if (report.photos.size !in 2..ReportCodec.MAX_PHOTOS) reasons += "请选择 2–5 张不同照片"
        if (report.photos.none { it.role == ReportPhotoRole.CONTEXT }) reasons += "请补一张能找到现场的环境照"
        if (report.photos.none { it.role == ReportPhotoRole.DETAIL }) reasons += "请补一张能看清问题的细节照"
        val selected = report.photos.mapNotNull { entries[it.photoId] }
        if (selected.map { it.importedSha256 }.distinct().size != selected.size) reasons += "重复照片不能充当不同视角，请重新选图"
        for ((index, photo) in report.photos.withIndex()) {
            val entry = entries[photo.photoId]
            if (entry == null) reasons += "照片 " + (index + 1) + " 不存在或无法读取，请重新选择"
            else {
                if (report.isSynthetic == null || report.isSynthetic != entry.draft.isSynthetic) reasons += "演示照片与真实材料不能混用"
                if (entry.draft.eventDate.isNotBlank() && entry.draft.eventDate != report.eventDate) {
                    reasons += "照片 " + (index + 1) + " 已登记的日期与本次现场日期不同，请核对并更正"
                }
                val photoReasons = MaterialReadiness.reasons(photoDraft(report, entry)).filterNot {
                    it in setOf("请填写并确认问题地址", "请人工确认事发日期", "请填写符合长度要求的问题说明")
                }
                photoReasons.forEach { reasons += "照片 " + (index + 1) + "：" + it }
            }
        }
        return reasons.distinct()
    }
    fun roleLabel(role: ReportPhotoRole) = when (role) {
        ReportPhotoRole.CONTEXT -> "环境照"
        ReportPhotoRole.DETAIL -> "细节照"
        ReportPhotoRole.ADDITIONAL -> "补充照"
    }
}

data class ReportListing(val reports: List<ReportDraft>, val unreadableCount: Int)
data class PreparedReport(val reportId: String, val revision: Long, val photoRevisions: Map<String, Long>, val file: File, val sha256: String)

/** Uses the same process-owned background queue as OfflineLibrary. Original records remain independent. */
class ReportRepository(private val root: File, private val texts: AtomicTextFiles,
    private val library: OfflineLibrary, private val inspector: ImageInspector) {
    private val reports = File(root, "reports")
    private val exports = File(root, "report-exports")
    private fun path(id: String): File { require(CaptureFiles.validId(id)); return File(reports, id + ".json") }
    fun load(id: String) = ReportCodec.decode(texts.read(path(id)), id)
    fun scan(): ReportListing {
        var unreadable = 0
        val ids = reports.listFiles()?.map { it.name.removeSuffix(".bak").removeSuffix(".new") }
            ?.filter { it.endsWith(".json") && CaptureFiles.validId(it.removeSuffix(".json")) }
            ?.map { it.removeSuffix(".json") }?.distinct() ?: emptyList()
        val loaded = ids.mapNotNull { id -> try { load(id) } catch (_: Exception) { unreadable++; null } }
        return ReportListing(loaded.sortedBy { it.reportId }, unreadable)
    }
    fun create(photoId: String? = null): ReportDraft {
        val entry = photoId?.let(library::load)
        val draft = ReportDraft(reportId = UUID.randomUUID().toString(),
            address = entry?.draft?.address ?: "", description = entry?.draft?.description ?: "", eventDate = entry?.draft?.eventDate ?: "",
            photos = entry?.let { listOf(ReportPhoto(it.draft.photoId, ReportPhotoRole.DETAIL)) } ?: emptyList(),
            isSynthetic = entry?.draft?.isSynthetic, photographerReference = entry?.draft?.photographerReference)
        texts.write(path(draft.reportId), ReportCodec.encode(draft))
        return draft
    }
    fun createOnSite(id: String = UUID.randomUUID().toString()): ReportDraft {
        require(CaptureFiles.validId(id) && !path(id).exists())
        val draft = ReportDraft(reportId = id, isSynthetic = false)
        texts.write(path(id), ReportCodec.encode(draft))
        return draft
    }
    /** Attach after USB transfer without changing the address confirmed on site. Duplicate IDs are no-ops. */
    fun attachBatch(id: String, expectedRevision: Long, photoIds: List<String>): ReportDraft {
        val old = load(id)
        require(old.revision == expectedRevision) { "材料已更新，请重新选择" }
        require(old.address.isNotBlank() && old.landmark.isNotBlank() && old.locationConfirmed) { "请先在现场填好地址和参照物，并确认对方能找到" }
        require(photoIds.isNotEmpty() && photoIds.size <= 100 && photoIds.distinct().size == photoIds.size)
        val added = photoIds.filter { next -> old.photos.none { it.photoId == next } }
        if (added.isEmpty()) return old
        require(old.photos.size + added.size <= ReportCodec.MAX_PHOTOS) { "这份材料最多5张，请从本批中选择可加入的照片" }
        return save(old.copy(photos = old.photos + added.map { ReportPhoto(it) }, sameIssueVisitConfirmed = false), old.revision)
    }
    fun createBatch(photoIds: List<String>, channelId: String = "GENERIC_MANUAL"): ReportDraft {
        require(photoIds.size in 1..ReportCodec.MAX_PHOTOS && photoIds.distinct().size == photoIds.size) { "每份材料请选择1–5张照片" }
        val selected = photoIds.map(library::load)
        require(selected.all { it.draft.isSynthetic == selected.first().draft.isSynthetic }) { "演示照片与真实材料不能混用" }
        val first = selected.first().draft
        val draft = ReportDraft(reportId = UUID.randomUUID().toString(), channelId = channelId,
            recipient = if (channelId == Jiangsu12345.ID) "江苏12345网页个人诉求" else "",
            province = if (channelId == Jiangsu12345.ID) "江苏省" else "",
            address = first.address, description = first.description, eventDate = first.eventDate,
            photographerReference = first.photographerReference,
            photos = photoIds.map { ReportPhoto(it) }, isSynthetic = first.isSynthetic)
        ReportCodec.decode(ReportCodec.encode(draft), draft.reportId)
        texts.write(path(draft.reportId), ReportCodec.encode(draft))
        return draft
    }
    fun save(draft: ReportDraft, expectedRevision: Long): ReportDraft {
        val old = load(draft.reportId)
        require(old.revision == expectedRevision) { "材料已更新，请重新打开" }
        ReportCodec.decode(ReportCodec.encode(draft), draft.reportId)
        val selected = draft.photos.map { library.load(it.photoId) }
        val synthetic = old.isSynthetic ?: selected.firstOrNull()?.draft?.isSynthetic
        require(selected.all { it.draft.isSynthetic == synthetic }) { "演示照片与真实材料不能混用" }
        val updated = draft.copy(revision = expectedRevision + 1, isSynthetic = synthetic)
        ReportCodec.decode(ReportCodec.encode(updated), updated.reportId)
        texts.write(path(updated.reportId), ReportCodec.encode(updated))
        return updated
    }
    fun selected(report: ReportDraft): Map<String, LibraryEntry> = report.photos.mapNotNull { photo ->
        runCatching { library.load(photo.photoId) }.getOrNull()?.let { photo.photoId to it }
    }.toMap()
    fun requireUnlinked(photoId: String) {
        val listing = scan()
        check(listing.unreadableCount == 0) { "有报修材料无法读取，请保留数据并核查后再删除照片" }
        require(listing.reports.none { report -> report.photos.any { it.photoId == photoId } }) { "此照片已加入报修材料，请先从材料中移除" }
    }
    fun delete(id: String, expectedRevision: Long) {
        require(load(id).revision == expectedRevision) { "材料已更新，请重新打开" }
        val file = path(id)
        for (target in listOf(File(file.path + ".bak"), File(file.path + ".new"), file)) {
            require(target.canonicalFile.parentFile == reports.canonicalFile)
            check(!target.exists() || target.delete()) { "材料删除未完成" }
        }
    }
    private fun validate(report: ReportDraft, entries: Map<String, LibraryEntry>) {
        ReportCodec.decode(ReportCodec.encode(report), report.reportId)
        val reasons = ReportReadiness.reasons(report, entries)
        require(reasons.isEmpty()) { reasons.joinToString("；") }
        for (photo in report.photos) {
            val entry = entries.getValue(photo.photoId)
            val review = checkNotNull(entry.draft.photoReview)
            require(review.originalSha256 == entry.importedSha256) { "照片与导入时指纹不符" }
            MaterialExporter.validate(ReportReadiness.photoDraft(report, entry), entry.original, entry.reviewFile(review.fileName), inspector)
        }
    }
    fun prepare(id: String): PreparedReport {
        val report = load(id)
        val entries = selected(report)
        validate(report, entries)
        check(exports.isDirectory || exports.mkdirs())
        val photoBytes = entries.values.sumOf { it.draft.photoReview!!.byteCount }
        require(exports.usableSpace >= photoBytes + CaptureRepository.MIN_FREE_BYTES) { "空间不足，请清理后重试" }
        val target = File(exports, UUID.randomUUID().toString() + ".zip")
        try {
            target.outputStream().use { write(it, report, entries) }
            RandomAccessFile(target, "rw").use { it.fd.sync() }
            return PreparedReport(id, report.revision, entries.mapValues { it.value.draft.revision }, target, PhotoDigest.sha256(target))
        } catch (error: Exception) { target.delete(); throw error }
    }
    /** Call immediately before copying to the system document picker, on the shared queue. */
    fun validatePrepared(prepared: PreparedReport) {
        require(prepared.file.canonicalFile.parentFile == exports.canonicalFile && prepared.file.name.matches(Regex("[0-9a-f-]{36}\\.zip")))
        val current = load(prepared.reportId)
        require(current.revision == prepared.revision) { "材料已修改，请重新生成" }
        val entries = selected(current)
        require(entries.mapValues { it.value.draft.revision } == prepared.photoRevisions) { "照片审阅或记录已更新，请重新生成" }
        validate(current, entries)
        require(PhotoDigest.sha256(prepared.file) == prepared.sha256) { "临时材料发生变化，请重新生成" }
    }
    fun discard(prepared: PreparedReport) {
        require(prepared.file.canonicalFile.parentFile == exports.canonicalFile)
        prepared.file.delete()
    }
    fun submissionText(id: String): String {
        val report = load(id)
        validate(report, selected(report))
        return text(report)
    }
    fun submissionPart(id: String, part: ReportTextPart): String {
        val report = load(id)
        validate(report, selected(report))
        val prefix = if (report.isSynthetic == true) "[合成演示，不得真实提交]\n" else ""
        return prefix + when (part) {
            ReportTextPart.PURPOSE -> report.purpose.trim()
            ReportTextPart.ADDRESS -> ReportPaste.address(report)
            ReportTextPart.BODY -> report.description.trim()
        }
    }
    fun prepareShare(id: String, cache: ReviewedShareCache): SharedMaterial {
        val report = load(id)
        val entries = selected(report)
        validate(report, entries)
        return cache.prepare(text(report), report.photos.map { photo ->
            val entry = entries.getValue(photo.photoId)
            val review = checkNotNull(entry.draft.photoReview)
            ReviewedAttachment(entry.reviewFile(review.fileName), review.outputSha256)
        })
    }
    private fun text(report: ReportDraft): String = buildString {
        if (report.isSynthetic == true) append("合成演示数据，非现场记录，不得当作真实事项提交。\n\n")
        append("接收人 / 渠道：" + report.recipient.trim() + "\n")
        append("接收方问题分类：" + report.category.trim() + "\n")
        if (report.channelId == Jiangsu12345.ID) {
            append("材料字段依据：江苏12345网页（公开字段核对 " + Jiangsu12345.CHECKED_ON + "）\n" + Jiangsu12345.URL + "\n")
            append("行政区划：" + report.province + " / " + report.city + " / " + report.district + "\n")
            append("联系人：" + report.contactName.trim() + " · 联系电话：" + report.contactPhone.trim() + "\n")
            append("是否接受媒体采访：" + if (report.mediaInterview == true) "是\n" else "否\n")
            append("相册或打码照片是否被接收，须以本次实际提交结果为准。\n")
            append("官方附件：最多5个、单个10 MB；本应用每张JPG最多5 MB、长边2048像素。ZIP仅供交接解压，不是表单附件。\n")
        } else {
            if (report.contactName.isNotBlank()) append("联系人：" + report.contactName.trim() + "\n")
            if (report.contactPhone.isNotBlank()) append("联系电话：" + report.contactPhone.trim() + "\n")
        }
        append("\n诉求目的：\n" + report.purpose.trim() + "\n\n")
        append("详细地址：\n" + ReportPaste.address(report) + "\n\n")
        append("正文：\n" + report.description.trim() + "\n\n")
        append("现场日期（人工确认）：" + report.eventDate + "\n")
        report.photographerReference?.let { append(it.label() + "\n") }
        report.photos.forEachIndexed { index, photo ->
            append("photo-" + (index + 1).toString().padStart(2, '0') + ".jpg：" + ReportReadiness.roleLabel(photo.role) + "\n")
        }
        append("\n请将说明和已审阅 JPG 填入接收方现有渠道；此材料不表示已提交或受理。\n")
    }
    private fun write(output: OutputStream, report: ReportDraft, entries: Map<String, LibraryEntry>) {
        val attachments = report.photos.mapIndexed { index, photo ->
            val review = checkNotNull(entries.getValue(photo.photoId).draft.photoReview)
            linkedMapOf<String, Any>("file" to ("photo-" + (index + 1).toString().padStart(2, '0') + ".jpg"),
                "role" to photo.role.name, "sha256" to review.outputSha256)
        }
        // Public material fields are explicitly whitelisted. No original GPS, private source, receipt, or repair history.
        val summary = linkedMapOf<String, Any?>("format" to "FIELDLOG_MULTI_PHOTO_MATERIALS", "version" to 1,
            "reportId" to report.reportId, "reportRevision" to report.revision, "isSynthetic" to report.isSynthetic,
            "recipient" to report.recipient.trim(), "category" to report.category.trim(), "address" to report.address.trim(),
            "landmark" to report.landmark.trim(), "eventDate" to report.eventDate, "description" to report.description.trim(),
            "channelId" to report.channelId,
            "province" to report.province.takeIf { report.channelId == Jiangsu12345.ID },
            "city" to report.city.takeIf { report.channelId == Jiangsu12345.ID },
            "district" to report.district.takeIf { report.channelId == Jiangsu12345.ID },
            "purpose" to report.purpose, "contactName" to report.contactName, "contactPhone" to report.contactPhone,
            "mediaInterview" to report.mediaInterview.takeIf { report.channelId == Jiangsu12345.ID },
            "noticeAccepted" to report.noticeAccepted.takeIf { report.channelId == Jiangsu12345.ID },
            "photographerReference" to report.photographerReference, "photographerReferenceRole" to "PHOTOGRAPHER_REFERENCE_NOT_ISSUE_LOCATION",
            "photos" to attachments, "submissionMethod" to "USER_SUBMITS_MANUALLY")
        ZipOutputStream(output.buffered()).use { zip ->
            fun entry(name: String, content: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
            entry("report.txt", text(report).toByteArray(Charsets.UTF_8))
            entry("summary.json", GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(summary).toByteArray(Charsets.UTF_8))
            report.photos.forEachIndexed { index, photo ->
                val source = entries.getValue(photo.photoId)
                zip.putNextEntry(ZipEntry(attachments[index].getValue("file").toString()))
                source.reviewFile(source.draft.photoReview!!.fileName).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}
