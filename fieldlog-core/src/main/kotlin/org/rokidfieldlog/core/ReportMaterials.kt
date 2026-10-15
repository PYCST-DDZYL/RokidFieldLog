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
)

object ReportCodec {
    const val MAX_BYTES = 65_536
    const val MAX_PHOTOS = 5 // Product limit, not a claimed limit of any government channel.
    private val fields = setOf("schemaVersion", "reportId", "revision", "recipient", "category", "address", "landmark",
        "eventDate", "description", "locationConfirmed", "sameIssueVisitConfirmed", "photos", "isSynthetic")
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().disableJdkUnsafe().setStrictness(Strictness.STRICT)
        .registerTypeAdapter(ReportPhotoRole::class.java, JsonDeserializer { value, _, _ -> ReportPhotoRole.valueOf(value.asString) }).create()
    fun encode(draft: ReportDraft): String = gson.toJson(draft)
    fun decode(text: String, expectedId: String): ReportDraft {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "材料记录过大" }
        val tree = gson.fromJson(text, JsonObject::class.java) ?: error("缺少材料记录")
        require(tree.keySet().all { it in fields }) { "材料记录含未知字段，原文件保留" }
        require(listOf("schemaVersion", "reportId", "revision", "photos").all { tree.has(it) })
        for (key in listOf("recipient", "category", "address", "landmark", "eventDate", "description", "reportId")) {
            tree[key]?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
        }
        for (key in listOf("schemaVersion", "revision")) {
            require(tree[key].isJsonPrimitive && tree[key].asJsonPrimitive.isNumber && tree[key].toString().matches(Regex("[0-9]+")))
        }
        for (key in listOf("locationConfirmed", "sameIssueVisitConfirmed")) {
            tree[key]?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }
        }
        tree["isSynthetic"]?.takeUnless { it.isJsonNull }?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }
        require(tree["photos"].isJsonArray)
        for (photo in tree["photos"].asJsonArray) {
            require(photo.isJsonObject && photo.asJsonObject.keySet() == setOf("photoId", "role"))
            require(photo.asJsonObject["photoId"].isJsonPrimitive && photo.asJsonObject["photoId"].asJsonPrimitive.isString)
            require(photo.asJsonObject["role"].isJsonPrimitive && photo.asJsonObject["role"].asJsonPrimitive.isString)
        }
        val draft = gson.fromJson(tree, ReportDraft::class.java)
        require(CaptureFiles.validId(expectedId) && draft.reportId == expectedId && draft.schemaVersion == 1 && draft.revision >= 0)
        requireNotNull(draft.recipient); requireNotNull(draft.category); requireNotNull(draft.address)
        requireNotNull(draft.landmark); requireNotNull(draft.eventDate); requireNotNull(draft.description); requireNotNull(draft.photos)
        require(draft.recipient.length <= 120 && draft.category.length <= 120 && draft.address.length <= 200 &&
            draft.landmark.length <= 200 && draft.description.length <= 2000 && draft.eventDate.length <= 10)
        require(draft.photos.size <= MAX_PHOTOS && draft.photos.map { it.photoId }.distinct().size == draft.photos.size)
        draft.photos.forEach { require(CaptureFiles.validId(it.photoId)); requireNotNull(it.role) }
        return draft
    }
}

object ReportReadiness {
    // Shared report content is entered once. Reuse the existing actual-photo privacy checks without changing each photo's draft.
    internal fun photoDraft(report: ReportDraft, entry: LibraryEntry) = entry.draft.copy(
        category = IssueCategory.OTHER, address = report.address, addressConfirmed = report.locationConfirmed,
        eventDate = report.eventDate, description = report.description, issuePosition = null)

    fun reasons(report: ReportDraft, entries: Map<String, LibraryEntry>): List<String> {
        val reasons = mutableListOf<String>()
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
            isSynthetic = entry?.draft?.isSynthetic)
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
    private fun write(output: OutputStream, report: ReportDraft, entries: Map<String, LibraryEntry>) {
        val attachments = report.photos.mapIndexed { index, photo ->
            val review = checkNotNull(entries.getValue(photo.photoId).draft.photoReview)
            linkedMapOf<String, Any>("file" to ("photo-" + (index + 1).toString().padStart(2, '0') + ".jpg"),
                "role" to photo.role.name, "sha256" to review.outputSha256)
        }
        val text = buildString {
            if (report.isSynthetic == true) append("合成演示数据，非现场记录，不得当作真实事项提交。\n\n")
            append("接收人 / 渠道：" + report.recipient.trim() + "\n")
            append("接收方问题分类：" + report.category.trim() + "\n")
            append("问题地址：" + report.address.trim() + "\n")
            append("附近参照物 / 设施编号：" + report.landmark.trim() + "\n")
            append("现场日期（人工确认）：" + report.eventDate + "\n")
            append("问题说明及处理请求：\n" + report.description.trim() + "\n\n")
            report.photos.forEachIndexed { index, photo -> append(attachments[index].getValue("file").toString() + "：" + ReportReadiness.roleLabel(photo.role) + "\n") }
            append("\n请解压后将说明和 JPG 填入接收方现有渠道；本 ZIP 不代表官方导入格式，也未执行提交。\n")
        }
        // Public material fields are explicitly whitelisted. No original GPS, private source, receipt, or repair history.
        val summary = linkedMapOf<String, Any?>("format" to "FIELDLOG_MULTI_PHOTO_MATERIALS", "version" to 1,
            "reportId" to report.reportId, "reportRevision" to report.revision, "isSynthetic" to report.isSynthetic,
            "recipient" to report.recipient.trim(), "category" to report.category.trim(), "address" to report.address.trim(),
            "landmark" to report.landmark.trim(), "eventDate" to report.eventDate, "description" to report.description.trim(),
            "photos" to attachments, "submissionMethod" to "USER_SUBMITS_MANUALLY")
        ZipOutputStream(output.buffered()).use { zip ->
            fun entry(name: String, content: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
            entry("report.txt", text.toByteArray(Charsets.UTF_8))
            entry("summary.json", GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(summary).toByteArray(Charsets.UTF_8))
            report.photos.forEachIndexed { index, photo ->
                val source = entries.getValue(photo.photoId)
                zip.putNextEntry(ZipEntry(attachments[index].getValue("file").toString()))
                source.reviewFile(source.draft.photoReview!!.fileName).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}
