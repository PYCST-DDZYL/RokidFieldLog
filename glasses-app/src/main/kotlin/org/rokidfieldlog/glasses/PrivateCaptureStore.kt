package org.rokidfieldlog.glasses

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import org.rokidfieldlog.core.CaptureFiles
import org.rokidfieldlog.core.CaptureStamp
import org.rokidfieldlog.core.CaptureTimeQuality
import org.rokidfieldlog.core.IssueCategory
import org.rokidfieldlog.core.PhotoDigest
import java.util.UUID

class PrivateCaptureStore(context: Context, rootOverride: File? = null) {
    private val filesRoot = context.filesDir.canonicalFile
    private val root = (rootOverride ?: File(filesRoot, "fieldlog")).canonicalFile
    private val records = File(root, "records")
    private val captureFiles = CaptureFiles(root)

    init {
        check(root.toPath().startsWith(File(filesRoot, "fieldlog").toPath())) { "Capture storage must stay inside FieldLog private data" }
    }

    data class RecoveryReport(val recoveredRecords: Int, val incompleteCaptures: Int, val errors: Int)

    companion object {
        private val storageLock = Any()
        // Activity recreation can overlap a callback still saving in this process.
        private val activeCaptures = mutableSetOf<String>()
        private val verifiedByRoot = mutableMapOf<String, MutableSet<String>>()
    }

    private val verifiedPhotos = synchronized(storageLock) { verifiedByRoot.getOrPut(root.path) { mutableSetOf() } }

    fun pendingPhoto(photoId: String, requestedAt: Long, requestedElapsed: Long): File = synchronized(storageLock) {
        val pending = captureFiles.begin(CaptureStamp(photoId, requestedAt, requestedElapsed))
        activeCaptures.add(photoId)
        pending
    }

    fun finishPhoto(photoId: String, pending: File, requestedAt: Long, requestedElapsed: Long,
        category: IssueCategory? = null): File = synchronized(storageLock) {
        try {
            check(pending.canonicalFile == captureFiles.pending(photoId).canonicalFile) { "Unexpected photo path" }
            val stamp = captureFiles.stamp(photoId)
            check(stamp == CaptureStamp(photoId, requestedAt, requestedElapsed)) { "Capture request journal unavailable" }
            val completedAt = System.currentTimeMillis()
            val completedElapsed = android.os.SystemClock.elapsedRealtimeNanos()
            val timeQuality = CaptureTimeQuality.assess(requestedAt, completedAt, requestedElapsed, completedElapsed)
            val dimensions = checkNotNull(imageDimensions(pending)) { "Photo cannot be decoded completely" }
            val photo = captureFiles.promote(photoId)
            verifiedPhotos.add(photoId)
            val metadata = baseRecord(photoId, stamp)
                .put("photoSha256", PhotoDigest.sha256(photo))
                .put("photoSha256Source", "CAPTURE_SAVE")
                .put("captureState", "SAVED")
                .put("photoIntegrityStatus", "VERIFIED")
                .put("captureCompletedAtUtcMillis", completedAt)
                .put("captureCompletedElapsedRealtimeNanos", completedElapsed)
                .put("captureTimeSource", "APP_REQUEST_INTERVAL_NOT_EXPOSURE_TIME")
                .put("captureWallClockStatus", timeQuality.status.name)
                .put("captureDurationMillis", timeQuality.durationMillis ?: JSONObject.NULL)
                .put("issueCategory", category?.name ?: JSONObject.NULL)
                .put("issueCategorySource", if (category == null) "UNCONFIRMED" else "USER_TAGGED")
                .put("issueCategoryNeedsReview", true)
                .put("recognitionStatus", "NOT_RUN")
                .put("width", dimensions.first)
                .put("height", dimensions.second)
            writeRecord(photoId, metadata)
            photo
        } finally { activeCaptures.remove(photoId) }
    }

    fun abandonCapture(photoId: String) = synchronized(storageLock) {
        activeCaptures.remove(photoId)
        // Preserve bytes and the original journal. Recovery decides their validity.
        Unit
    }

    fun completedPhotoExists(photoId: String): Boolean = synchronized(storageLock) {
        captureFiles.completed(photoId).isFile
    }

    fun recoverInterruptedCaptures(): RecoveryReport = synchronized(storageLock) {
        var recovered = 0
        var incomplete = 0
        var errors = 0
        verifiedPhotos.removeAll { it !in activeCaptures }
        for (id in captureFiles.ids()) {
            if (id in activeCaptures) continue
            try {
                val photo = captureFiles.completed(id)
                val pending = captureFiles.pending(id)
                val existing = readRecord(id)
                if (existing != null && existing.optInt("schemaVersion") != 1) { errors++; continue }
                val candidate = if (photo.isFile) photo else pending
                val dimensions = imageDimensions(candidate)
                val trustedIdentity = existing?.optString("photoId") == id
                val expectedHash = if (trustedIdentity && !existing!!.isNull("photoSha256")) existing.optString("photoSha256") else null
                if (dimensions != null && expectedHash != null &&
                    (!expectedHash.matches(Regex("[0-9a-fA-F]{64}")) || !PhotoDigest.sha256(candidate).equals(expectedHash, true))) {
                    incomplete++
                    if (existing!!.optString("photoIntegrityStatus") != "HASH_MISMATCH") {
                        preservePreviousRecord(id)
                        writeRecord(id, JSONObject(existing.toString())
                            .put("photoIntegrityStatus", "HASH_MISMATCH")
                            .put("lastStorageCheckAtUtcMillis", System.currentTimeMillis()))
                    }
                    // Preserve both the current bytes and the original fingerprint.
                    continue
                }
                if (photo.isFile && dimensions != null && existing?.optString("photoId") == id &&
                    existing.optString("photoFile") == "photos/$id.jpg" &&
                    existing.optInt("width") == dimensions.first && existing.optInt("height") == dimensions.second &&
                    existing.optString("photoIntegrityStatus", "VERIFIED") == "VERIFIED" &&
                    existing.optString("captureState", "SAVED") in setOf("SAVED", "RECOVERED")) {
                    verifiedPhotos.add(id)
                    continue
                }
                if (dimensions != null) {
                    if (!photo.isFile) captureFiles.promote(id)
                    verifiedPhotos.add(id)
                    preservePreviousRecord(id)
                    val metadata = mergeExistingRecord(id, captureFiles.stamp(id), existing)
                        .put("captureState", "RECOVERED")
                        .put("photoFile", "photos/$id.jpg")
                        .put("photoIntegrityStatus", "VERIFIED")
                        .put("width", dimensions.first)
                        .put("height", dimensions.second)
                        .put("recoveredAtUtcMillis", System.currentTimeMillis())
                        .put("captureElapsedClockStatus", "ORIGINAL_CAPTURE_BOOT_UNCONFIRMED_AFTER_RECOVERY")
                    if (expectedHash == null) {
                        metadata.put("photoSha256", PhotoDigest.sha256(photo))
                            .put("photoSha256Source", "RECOVERY_VERIFICATION_CURRENT_BYTES")
                    }
                    writeRecord(id, metadata)
                    recovered++
                } else {
                    incomplete++
                    // Retain incomplete evidence; never count it as a saved image.
                    val integrity = if (candidate.exists()) "UNREADABLE" else "MISSING"
                    val trusted = existing?.optString("photoId") == id && existing.optInt("schemaVersion") == 1
                    if (!trusted || existing?.optString("photoIntegrityStatus") != integrity) {
                        preservePreviousRecord(id)
                        val metadata = mergeExistingRecord(id, captureFiles.stamp(id), existing)
                            .put("photoIntegrityStatus", integrity)
                            .put("lastStorageCheckAtUtcMillis", System.currentTimeMillis())
                        if (!trusted) {
                            metadata.put("captureState", "INCOMPLETE")
                                .put("photoFile", JSONObject.NULL)
                                .put("reason", if (candidate.exists()) "INCOMPLETE_IMAGE" else "NO_IMAGE")
                        }
                        writeRecord(id, metadata)
                    }
                }
            } catch (_: Exception) { errors++ }
        }
        RecoveryReport(recovered, incomplete, errors)
    }

    private fun mergeExistingRecord(id: String, stamp: CaptureStamp?, existing: JSONObject?): JSONObject {
        val metadata = baseRecord(id, stamp)
        if (existing?.optString("photoId") == id && existing.optInt("schemaVersion") == 1) {
            // Preserve original times, observations, corrections, and user annotations.
            val keys = existing.keys()
            while (keys.hasNext()) { val key = keys.next(); metadata.put(key, existing.get(key)) }
        }
        return metadata
    }

    private fun baseRecord(photoId: String, stamp: CaptureStamp?): JSONObject = JSONObject()
            .put("schemaVersion", 1)
            .put("photoId", photoId)
            .put("photoFile", "photos/$photoId.jpg")
            .put("captureRequestedAtUtcMillis", stamp?.requestedUtcMillis ?: JSONObject.NULL)
            .put("captureCompletedAtUtcMillis", JSONObject.NULL)
            .put("captureRequestedElapsedRealtimeNanos", stamp?.requestedElapsedNanos ?: JSONObject.NULL)
            .put("captureCompletedElapsedRealtimeNanos", JSONObject.NULL)
            .put("captureTimeSource", if (stamp != null) "JOURNALED_REQUEST_COMPLETION_UNKNOWN" else "UNKNOWN_RECOVERED_FILE")
            .put("width", JSONObject.NULL)
            .put("height", JSONObject.NULL)
            .put("phoneLocation", JSONObject.NULL)
            .put("issueLocation", JSONObject.NULL)
            .put("locationStatus", "PENDING_CONFIRMATION")
            .put("phoneConnectionStatus", "NOT_CONFIGURED_PHASE1")
            .put("syncStatus", "LOCAL_ONLY")

    private fun imageDimensions(file: File): Pair<Int, Int>? = try {
        if (!CaptureFiles.hasJpegMarkers(file)) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                // Small decode checks pixel data while avoiding a full 48 MB bitmap.
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bitmap == null) null else { bitmap.recycle(); bounds.outWidth to bounds.outHeight }
            }
        }
    } catch (_: Exception) { null }

    private fun readRecord(id: String): JSONObject? = try {
        AtomicFile(File(records, "$id.json")).openRead().use { input ->
            check(input.channel.size() <= 256 * 1024) { "Metadata exceeds supported size" }
            JSONObject(input.bufferedReader(Charsets.UTF_8).readText())
        }
    } catch (_: Exception) { null }

    private fun preservePreviousRecord(id: String) {
        val original = File(records, "$id.json")
        if (original.isFile) {
            val backups = File(records, "recovery-backups")
            check(backups.isDirectory || backups.mkdirs())
            original.copyTo(File(backups, "$id-${UUID.randomUUID()}.json"), overwrite = false)
        }
    }

    private fun writeRecord(photoId: String, metadata: JSONObject) {
        check(records.isDirectory || records.mkdirs()) { "Photo saved; metadata directory failed" }
        val atomic = AtomicFile(File(records, "$photoId.json"))
        val output = atomic.startWrite()
        try {
            output.write(metadata.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (exception: Exception) {
            atomic.failWrite(output)
            throw exception
        }
    }

    fun savedPhotoCount(): Int = synchronized(storageLock) {
        verifiedPhotos.count { captureFiles.completed(it).isFile }
    }

    fun deleteAllProjectData() = synchronized(storageLock) {
        check(activeCaptures.isEmpty()) { "Capture is still saving" }
        check(root.canonicalFile.parentFile == filesRoot) { "Unsafe data path" }
        check(!root.exists() || root.deleteRecursively()) { "Cannot delete all project files" }
        verifiedPhotos.clear()
    }
}
