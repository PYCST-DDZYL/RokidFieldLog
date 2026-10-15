package org.rokidfieldlog.core

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID

interface AtomicTextFiles {
    fun read(file: File): String
    fun write(file: File, text: String)
}
data class LibraryEntry(val source: RecordDocument, val draft: IssueDraft, val directory: File, val importedSha256: String) {
    val original: File get() = File(directory, "original.jpg")
    fun reviewFile(name: String): File {
        require(name.matches(Regex("review-[0-9a-f-]{36}\\.jpg")))
        return File(directory, name)
    }
}
data class LibraryListing(val entries: List<LibraryEntry>, val unreadableCount: Int)
data class ImportReport(val added: Int, val duplicates: Int)
data class PreparedMaterial(val photoId: String, val revision: Long, val file: File, val sha256: String,
    val profile: MaterialProfile = MaterialProfile())

/** All callers use one background queue. Imports commit as one folder rename. */
class OfflineLibrary(private val root: File, private val texts: AtomicTextFiles,
    private val inspector: ImageInspector, private val clock: FieldClock,
    private val beforeDelete: (String) -> Unit = {}) {
    private val collections = File(root, "collections")
    private fun itemDirectories(): List<File> = collections.listFiles()?.filter { it.isDirectory && CaptureFiles.validId(it.name) }
        ?.flatMap { File(it, "items").listFiles()?.toList() ?: emptyList() }
        ?.filter { it.isDirectory && CaptureFiles.validId(it.name) } ?: emptyList()

    fun scan(): LibraryListing {
        var unreadable = 0
        val directories = itemDirectories()
        val duplicateIds = directories.groupBy { it.name }.filterValues { it.size > 1 }.keys
        val entries = directories.mapNotNull { directory ->
            if (directory.name in duplicateIds) { unreadable++; null }
            else try { loadDirectory(directory) } catch (_: Exception) { unreadable++; null }
        }.sortedBy { it.draft.photoId }
        return LibraryListing(entries, unreadable)
    }
    fun list(): List<LibraryEntry> = scan().entries
    fun load(id: String): LibraryEntry {
        require(CaptureFiles.validId(id))
        val directory = itemDirectories().singleOrNull { it.name == id } ?: error("记录不存在或已被删除")
        return loadDirectory(directory)
    }
    private fun loadDirectory(directory: File): LibraryEntry {
        val id = directory.name
        val source = CaptureRecordCodec.decode(texts.read(File(directory, "source.json")))
        require(source.record.photoId == id)
        val fingerprint = texts.read(File(directory, "source-photo.sha256")).trim()
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        source.record.photoSha256?.let { require(it.equals(fingerprint, ignoreCase = true)) }
        return LibraryEntry(source, DraftCodec.decode(texts.read(File(directory, "draft.json")), id), directory, fingerprint)
    }

    fun importPackage(input: InputStream): ImportReport {
        check(root.isDirectory || root.mkdirs())
        check(root.usableSpace >= CaptureRepository.MIN_FREE_BYTES) { "空间不足" }
        val staging = File(root, "staging/" + UUID.randomUUID()).apply { check(mkdirs()) }
        try {
            val unpacked = File(staging, "unpacked").apply { check(mkdirs()) }
            val incoming = CapturePackage.read(input, unpacked, inspector)
            val existing = itemDirectories().groupBy { it.name }
            val fresh = incoming.captures.filter { capture ->
                val matching = existing[capture.document.record.photoId]
                val old = matching?.let { directories ->
                    require(directories.size == 1) { "现有记录编号重复，无法合并" }
                    try { loadDirectory(directories.single()) } catch (error: Exception) {
                        throw IllegalArgumentException("现有记录损坏，无法合并；原文件已保留", error)
                    }
                }
                if (old != null) {
                    require(PhotoDigest.sha256(old.original) == capture.originalSha256 &&
                        CaptureRecordCodec.encode(old.source) == CaptureRecordCodec.encode(capture.document)) { "同一记录编号的内容冲突，现有记录保留" }
                }
                old == null
            }
            if (fresh.isEmpty()) return ImportReport(0, incoming.captures.size)
            val transaction = File(staging, "collection").apply { check(mkdirs()) }
            for (capture in fresh) {
                val id = capture.document.record.photoId
                val directory = File(transaction, "items/$id").apply { check(mkdirs()) }
                capture.photo.copyTo(File(directory, "original.jpg"))
                RandomAccessFile(File(directory, "original.jpg"), "rw").use { it.fd.sync() }
                texts.write(File(directory, "source.json"), CaptureRecordCodec.encode(capture.document))
                texts.write(File(directory, "source-photo.sha256"), capture.originalSha256)
                texts.write(File(directory, "draft.json"), DraftCodec.encode(IssueDraft(photoId = id, isSynthetic = incoming.isSynthetic)))
            }
            check(collections.isDirectory || collections.mkdirs())
            check(transaction.renameTo(File(collections, UUID.randomUUID().toString()))) { "Cannot commit imported collection" }
            return ImportReport(fresh.size, incoming.captures.size - fresh.size)
        } finally {
            check(staging.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
            staging.deleteRecursively()
        }
    }

    fun save(draft: IssueDraft, expectedRevision: Long): LibraryEntry {
        val entry = load(draft.photoId)
        require(entry.draft.revision == expectedRevision) { "记录已更新，请返回列表重新打开" }
        val old = entry.draft
        val contentChanged = draft.category != old.category || draft.address != old.address ||
            draft.addressConfirmed != old.addressConfirmed || draft.description != old.description ||
            draft.eventDate != old.eventDate || draft.issuePosition != old.issuePosition ||
            draft.photoReview != old.photoReview
        val updated = draft.copy(revision = expectedRevision + 1, isSynthetic = old.isSynthetic,
            stage = if (contentChanged) SubmissionStage.DRAFT else draft.stage)
        DraftCodec.decode(DraftCodec.encode(updated), draft.photoId)
        texts.write(File(entry.directory, "draft.json"), DraftCodec.encode(updated))
        return entry.copy(draft = updated)
    }

    fun approvePhoto(id: String, expectedRevision: Long, review: PhotoReview): LibraryEntry {
        val entry = load(id)
        require(entry.draft.revision == expectedRevision) { "照片记录已更新，请重新打开审阅" }
        val file = entry.reviewFile(review.fileName)
        require(review.masks.isNotEmpty() || review.noThirdPartyInformation) { "请确认照片是否需要遮挡" }
        review.masks.forEach { it.validate() }
        require(review.originalSha256 == entry.importedSha256 && PhotoDigest.sha256(entry.original) == entry.importedSha256) { "原图与导入时不一致，请保留记录并核查原始采集包" }
        require(PhotoDigest.sha256(file) == review.outputSha256 && file.length() == review.byteCount)
        require(inspector.dimensions(file) == (review.width to review.height) && !JpegPrivacy.hasPrivateMetadata(file))
        val saved = save(entry.draft.copy(photoReview = review.copy(reviewedAtUtcMillis = clock.utcMillis())), expectedRevision)
        // Only retire the previous approved copy after the replacement is durably committed.
        entry.draft.photoReview?.fileName?.takeIf { it != review.fileName }?.let { previous ->
            runCatching { entry.reviewFile(previous).delete() }
        }
        return saved
    }

    /** Discard only this screen's unused preview; an approved copy must survive delayed cleanup. */
    fun discardReview(id: String, fileName: String): Boolean {
        val entry = load(id)
        val file = entry.reviewFile(fileName)
        if (entry.draft.photoReview?.fileName == fileName) return false
        return !file.exists() || file.delete()
    }

    fun prepareMaterial(id: String, profile: MaterialProfile = MaterialProfile()): PreparedMaterial {
        val entry = load(id)
        val review = checkNotNull(entry.draft.photoReview) { "请先完成照片隐私审阅" }
        require(PhotoDigest.sha256(entry.original) == entry.importedSha256) { "原图与导入时不一致，无法导出" }
        val exports = File(root, "exports").apply { check(isDirectory || mkdirs()) }
        val target = File(exports, UUID.randomUUID().toString() + ".zip")
        try {
            target.outputStream().use { MaterialExporter.write(it, entry.draft, entry.original, entry.reviewFile(review.fileName), inspector, profile) }
            RandomAccessFile(target, "rw").use { it.fd.sync() }
            return PreparedMaterial(id, entry.draft.revision, target, PhotoDigest.sha256(target), profile)
        } catch (error: Exception) { target.delete(); throw error }
    }

    /** Run on the shared queue immediately before copying into the system-selected document. */
    fun validatePrepared(prepared: PreparedMaterial) {
        val exports = File(root, "exports").canonicalFile
        require(prepared.file.canonicalFile.parentFile == exports &&
            prepared.file.extension == "zip" && CaptureFiles.validId(prepared.file.nameWithoutExtension))
        val entry = load(prepared.photoId)
        require(entry.draft.revision == prepared.revision) { "说明或照片审阅已更新，请重新生成材料" }
        val review = checkNotNull(entry.draft.photoReview) { "请先完成照片隐私审阅" }
        require(review.originalSha256 == entry.importedSha256) { "原图与导入时不一致，无法导出" }
        MaterialExporter.validate(entry.draft, entry.original, entry.reviewFile(review.fileName), inspector, prepared.profile)
        require(PhotoDigest.sha256(prepared.file) == prepared.sha256) { "临时材料发生变化，请重新生成" }
    }

    fun discardMaterial(prepared: PreparedMaterial) {
        require(prepared.file.canonicalFile.parentFile == File(root, "exports").canonicalFile &&
            prepared.file.extension == "zip" && CaptureFiles.validId(prepared.file.nameWithoutExtension))
        prepared.file.delete()
    }

    fun markExported(id: String, revision: Long) {
        val entry = load(id)
        if (entry.draft.revision != revision) return // Completion must never label a newer edit as exported.
        if (entry.draft.stage == SubmissionStage.DRAFT) save(entry.draft.copy(stage = SubmissionStage.EXPORTED), revision)
    }

    fun updateReceipt(id: String, revision: Long, stage: SubmissionStage, receipt: String): LibraryEntry {
        require(stage in setOf(SubmissionStage.SUBMITTED, SubmissionStage.RESOLVED))
        require(receipt.isNotBlank() && receipt.length <= 2000) { "请填写官方回执或处理结果" }
        val entry = load(id)
        return save(entry.draft.copy(stage = stage, receipt = receipt.trim()), revision)
    }

    fun delete(id: String) {
        beforeDelete(id)
        val entry = load(id)
        val target = entry.directory.canonicalFile
        require(target.toPath().startsWith(collections.canonicalFile.toPath()) && target.name == id)
        check(target.deleteRecursively()) { "删除未完成" }
    }
}
