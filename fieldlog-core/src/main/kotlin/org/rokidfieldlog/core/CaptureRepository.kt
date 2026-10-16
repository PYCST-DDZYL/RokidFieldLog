package org.rokidfieldlog.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

interface FieldClock {
    fun utcMillis(): Long
    fun elapsedNanos(): Long
}

fun interface ImageInspector { fun dimensions(file: File): Pair<Int, Int>? }

/** The platform owns atomic metadata writes and record directory layout. */
interface CaptureRecordStorage {
    fun ids(): Set<String>
    fun exists(id: String): Boolean
    fun read(id: String): String?
    fun backup(id: String)
    fun write(id: String, text: String)
    fun delete(id: String)
}

/** Shared by adapters for the same root; UI reads never take these I/O locks. */
class CaptureStorageState {
    internal val generation = AtomicLong()
    internal val lifecycle = ReentrantReadWriteLock(true)
    internal val locks = ConcurrentHashMap<String, Any>()
    internal val active = ConcurrentHashMap.newKeySet<String>()
    internal val verified = ConcurrentHashMap.newKeySet<String>()
    fun savedPhotoCount(): Int = verified.size
}

class LowStorageException : IllegalStateException("Insufficient space for a capture")

data class RecoveryReport(val recoveredRecords: Int, val incompleteCaptures: Int, val errors: Int)
data class SavedCapture(val photoId: String, val file: File, val category: IssueCategory?)

/** JVM recovery engine; Android supplies decoding, atomic records and its monotonic clock. */
class CaptureRepository(
    private val root: File,
    private val records: CaptureRecordStorage,
    private val images: ImageInspector,
    private val clock: FieldClock,
    private val state: CaptureStorageState = CaptureStorageState(),
    private val freeBytes: () -> Long = { root.parentFile.usableSpace },
) {
    private val files = CaptureFiles(root, records::exists)

    companion object {
        // Leaves room for the original JPEG, journal/JSON and an interrupted write.
        const val MIN_FREE_BYTES = 64L * 1024 * 1024
        private val HASH = Regex("[0-9a-fA-F]{64}")
    }

    private fun <T> withId(id: String, work: () -> T): T {
        require(CaptureFiles.validId(id))
        return state.lifecycle.read { synchronized(state.locks.getOrPut(id) { Any() }) { work() } }
    }

    fun begin(stamp: CaptureStamp): File = withId(stamp.photoId) {
        if (freeBytes() < MIN_FREE_BYTES) throw LowStorageException()
        val pending = files.begin(stamp)
        check(state.active.add(stamp.photoId))
        pending
    }

    fun finish(stamp: CaptureStamp, pending: File, category: IssueCategory?): File = withId(stamp.photoId) {
        val id = stamp.photoId
        try {
            check(id in state.active) { "No capture lease" }
            check(pending.canonicalFile == files.pending(id).canonicalFile) { "Unexpected photo path" }
            check(files.stamp(id) == stamp) { "Capture request journal unavailable" }
            val completedAt = clock.utcMillis()
            val completedElapsed = clock.elapsedNanos()
            val timing = CaptureTimeQuality.assess(stamp.requestedUtcMillis, completedAt, stamp.requestedElapsedNanos, completedElapsed)
            val dimensions = checkNotNull(images.dimensions(pending)) { "Photo cannot be decoded completely" }
            val photo = files.promote(id)
            val hash = PhotoDigest.sha256(photo)
            val record = CaptureRecord.pending(id, stamp).copy(
                captureState = CaptureState.SAVED,
                photoSha256 = hash, photoSha256Source = FingerprintSource.CAPTURE_SAVE,
                photoVerification = verification(photo, hash, dimensions),
                captureCompletedAtUtcMillis = completedAt,
                captureCompletedElapsedRealtimeNanos = completedElapsed,
                captureTimeSource = "APP_REQUEST_INTERVAL_NOT_EXPOSURE_TIME",
                captureWallClockStatus = timing.status.name, captureDurationMillis = timing.durationMillis,
                issueCategory = category,
                issueCategorySource = if (category == null) CategorySource.UNCONFIRMED else CategorySource.USER_TAGGED,
                width = dimensions.first, height = dimensions.second,
            )
            save(RecordDocument(record))
            state.verified.add(id)
            photo
        } finally { state.active.remove(id) }
    }

    fun abandon(id: String) = withId(id) { state.active.remove(id); Unit }
    fun completedPhotoExists(id: String): Boolean = withId(id) { files.completed(id).isFile }
    fun savedPhotoCount(): Int = state.savedPhotoCount()

    /** Stat-based fast path is only a startup optimization; forceFull ignores it. */
    fun recover(forceFull: Boolean = false): RecoveryReport {
        val (ids, generation) = state.lifecycle.read { files.ids(records.ids()) to state.generation.get() }
        var recovered = 0
        var incomplete = 0
        var errors = 0
        for (id in ids) {
            try {
                withId(id) {
                    if (state.generation.get() != generation || id in state.active) return@withId
                    state.verified.remove(id)
                    val text = records.read(id)
                    if (text == null && !files.exists(id)) return@withId
                    val document = text?.let { CaptureRecordCodec.readForRecovery(it, id) }
                    val trusted = document?.record?.photoId == id
                    val existing = document?.record?.takeIf { trusted }
                    if (existing?.captureState == CaptureState.DELETED) {
                        removeCaptureFiles(id)
                        return@withId
                    }
                    val photo = files.completed(id)
                    val pending = files.pending(id)
                    val candidate = if (photo.isFile) photo else pending
                    if (!forceFull && existing != null && cacheMatches(existing, photo)) {
                        state.verified.add(id)
                        return@withId
                    }
                    val dimensions = images.dimensions(candidate)
                    val expectedHash = existing?.photoSha256
                    val hash = if (dimensions != null) PhotoDigest.sha256(candidate) else null
                    if (hash != null && expectedHash != null &&
                        (!HASH.matches(expectedHash) || !hash.equals(expectedHash, true))) {
                        incomplete++
                        if (existing.photoIntegrityStatus != PhotoIntegrity.HASH_MISMATCH) {
                            records.backup(id)
                            save(document!!.copy(record = existing.copy(
                                photoIntegrityStatus = PhotoIntegrity.HASH_MISMATCH, photoVerification = null,
                                lastStorageCheckAtUtcMillis = clock.utcMillis(),
                            )))
                        }
                        return@withId
                    }
                    val complete = photo.isFile && dimensions != null && existing != null &&
                        existing.photoFile == "photos/$id.jpg" && existing.width == dimensions.first &&
                        existing.height == dimensions.second && existing.photoIntegrityStatus == PhotoIntegrity.VERIFIED &&
                        existing.captureState in setOf(CaptureState.SAVED, CaptureState.RECOVERED)
                    if (complete) {
                        // Upgrade old records once. An absent capture fingerprint is never called an original hash.
                        save(document!!.copy(record = existing!!.copy(photoVerification = verification(photo, hash!!, dimensions!!))))
                        state.verified.add(id)
                        return@withId
                    }
                    val base = if (trusted) document!! else RecordDocument(CaptureRecord.pending(id, files.stamp(id)))
                    if (dimensions != null) {
                        if (!photo.isFile) files.promote(id)
                        records.backup(id)
                        save(base.copy(record = base.record.copy(
                            captureState = CaptureState.RECOVERED, photoFile = "photos/$id.jpg",
                            photoIntegrityStatus = PhotoIntegrity.VERIFIED,
                            width = dimensions.first, height = dimensions.second,
                            photoSha256 = expectedHash ?: hash,
                            photoSha256Source = if (expectedHash == null) FingerprintSource.RECOVERY_VERIFICATION_CURRENT_BYTES else base.record.photoSha256Source,
                            photoVerification = verification(photo, hash!!, dimensions),
                            recoveredAtUtcMillis = clock.utcMillis(),
                            captureElapsedClockStatus = "ORIGINAL_CAPTURE_BOOT_UNCONFIRMED_AFTER_RECOVERY",
                        )))
                        state.verified.add(id)
                        recovered++
                    } else {
                        incomplete++
                        val integrity = if (candidate.exists()) PhotoIntegrity.UNREADABLE else PhotoIntegrity.MISSING
                        if (!trusted || existing!!.photoIntegrityStatus != integrity) {
                            records.backup(id)
                            val broken = base.record.copy(photoIntegrityStatus = integrity, photoVerification = null,
                                lastStorageCheckAtUtcMillis = clock.utcMillis())
                            save(base.copy(record = if (trusted) broken else broken.copy(
                                captureState = CaptureState.INCOMPLETE, photoFile = null,
                                reason = if (candidate.exists()) "INCOMPLETE_IMAGE" else "NO_IMAGE",
                            )))
                        }
                    }
                }
            } catch (_: Exception) { errors++ }
        }
        return RecoveryReport(recovered, incomplete, errors)
    }

    private fun cacheMatches(record: CaptureRecord, photo: File): Boolean {
        val cache = record.photoVerification ?: return false
        return photo.isFile && record.photoFile == "photos/" + record.photoId + ".jpg" &&
            record.captureState in setOf(CaptureState.SAVED, CaptureState.RECOVERED) &&
            record.photoIntegrityStatus == PhotoIntegrity.VERIFIED &&
            cache.byteCount > 0 && cache.lastModifiedUtcMillis > 0 && HASH.matches(cache.sha256) &&
            (record.photoSha256 == null || HASH.matches(record.photoSha256) && record.photoSha256.equals(cache.sha256, true)) &&
            cache.width > 0 && cache.height > 0 && cache.width == record.width && cache.height == record.height &&
            cache.byteCount == photo.length() && cache.lastModifiedUtcMillis == photo.lastModified()
    }

    private fun verification(photo: File, hash: String, dimensions: Pair<Int, Int>) =
        PhotoVerification(photo.length(), photo.lastModified(), hash, dimensions.first, dimensions.second)

    private fun save(document: RecordDocument) = records.write(document.record.photoId, CaptureRecordCodec.encode(document))

    fun latestSaved(): SavedCapture? = state.lifecycle.read {
        state.verified.mapNotNull { id -> withId(id) {
            if (!files.completed(id).isFile) null else records.read(id)?.let {
                CaptureRecordCodec.decode(it).record.let { record ->
                    record to SavedCapture(id, files.completed(id), record.issueCategory)
                }
            }
        } }.maxByOrNull { (_, capture) -> capture.file.lastModified() }?.second
    }

    /** Snapshot is held against capture/delete/recovery while the original archive is written. */
    fun writePackage(output: java.io.OutputStream): Int = state.lifecycle.write {
        check(state.active.isEmpty()) { "请等待照片保存后再导出" }
        val captures = records.ids().sorted().mapNotNull { id ->
            val document = records.read(id)?.let(CaptureRecordCodec::decode) ?: return@mapNotNull null
            val record = document.record
            if (record.captureState !in setOf(CaptureState.SAVED, CaptureState.RECOVERED) || record.photoIntegrityStatus != PhotoIntegrity.VERIFIED) return@mapNotNull null
            require(record.photoId == id && record.photoFile == "photos/$id.jpg") { "记录路径异常，原文件保留" }
            val photo = files.completed(id)
            require(photo.isFile && CaptureFiles.hasJpegMarkers(photo)) { "照片不完整，未导出" }
            require(images.dimensions(photo) == (record.width to record.height)) { "照片尺寸变化，未导出" }
            val hash = PhotoDigest.sha256(photo)
            require(record.photoSha256 == null || record.photoSha256.equals(hash, true)) { "照片指纹变化，未导出" }
            IncomingCapture(document, photo, hash)
        }
        require(captures.isNotEmpty()) { "暂无可导出的已验证照片" }
        require(captures.size <= CapturePackage.MAX_RECORDS) { "采集包最多100张，请先分批转存" }
        CapturePackage.write(output, captures)
        captures.size
    }

    /** Caller selects the ID shown in the review dialog, so a later shot cannot change the target. */
    fun deleteCapture(id: String) = withId(id) {
        check(id !in state.active) { "Capture is still saving" }
        val previous = checkNotNull(records.read(id)).let(CaptureRecordCodec::decode)
        save(previous.copy(record = previous.record.copy(captureState = CaptureState.DELETED, photoVerification = null)))
        state.verified.remove(id)
        removeCaptureFiles(id)
    }

    private fun removeCaptureFiles(id: String) {
        // A durable tombstone prevents recovery from resurrecting a half-deleted capture.
        for (file in listOf(files.completed(id), files.pending(id), File(root, "journals/$id.journal"), File(root, "journals/$id.journal.new"))) {
            check(!file.exists() || file.delete()) { "Cannot delete capture" }
        }
        records.delete(id)
    }

    fun deleteAllProjectData() = state.lifecycle.write {
        check(state.active.isEmpty()) { "Capture is still saving" }
        val target = root.canonicalFile
        check(target == root.absoluteFile && target.parentFile != null && target.name == "fieldlog") { "Unsafe data path" }
        state.generation.incrementAndGet()
        try { check(!target.exists() || target.deleteRecursively()) { "Cannot delete all project files" } }
        finally { state.verified.clear() }
    }
}
