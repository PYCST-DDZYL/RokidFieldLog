package org.rokidfieldlog.glasses

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import org.rokidfieldlog.core.CaptureFiles
import org.rokidfieldlog.core.IssueCategory
import java.io.File
import java.util.UUID

/** Debug only: synthetic storage fixtures, never camera/GPS validation or user records. */
class RecoveryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val output = Bundle()
        try {
            val testRoot = File(targetContext.filesDir, "fieldlog/test-fixtures/${UUID.randomUUID()}")
            val requestedUtc = 123456L // Explicit fixture timestamp, not a real capture.
            val requestedElapsed = SystemClock.elapsedRealtimeNanos()
            var passed = 0

            fun caseRoot(name: String) = File(testRoot, name)
            fun jpeg(file: File, color: Int = android.graphics.Color.BLACK) {
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
                finally { bitmap.recycle() }
            }
            fun record(root: File, id: String) = JSONObject(File(root, "records/$id.json").readText(Charsets.UTF_8))

            // Callback interrupted after complete JPEG write, before final photo rename.
            run {
                val root = caseRoot("written-jpeg")
                val id = UUID.randomUUID().toString()
                val store = PrivateCaptureStore(targetContext, root)
                jpeg(store.pendingPhoto(id, requestedUtc, requestedElapsed))
                store.abandonCapture(id)
                val report = PrivateCaptureStore(targetContext, root).recoverInterruptedCaptures()
                check(report.recoveredRecords == 1 && report.errors == 0)
                val value = record(root, id)
                check(value.getLong("captureRequestedAtUtcMillis") == requestedUtc)
                check(value.isNull("captureCompletedAtUtcMillis") && value.isNull("phoneLocation"))
                check(File(root, "photos/$id.jpg").isFile)
                passed++
            }

            // Photo committed, JSON corrupt: preserve its original bytes in a backup.
            run {
                val root = caseRoot("corrupt-record")
                val id = UUID.randomUUID().toString()
                val store = PrivateCaptureStore(targetContext, root)
                jpeg(store.pendingPhoto(id, requestedUtc, requestedElapsed))
                CaptureFiles(root).promote(id)
                File(root, "records").mkdirs()
                File(root, "records/$id.json").writeText("{unfinished fixture", Charsets.UTF_8)
                store.abandonCapture(id)
                val report = PrivateCaptureStore(targetContext, root).recoverInterruptedCaptures()
                check(report.recoveredRecords == 1 && report.errors == 0)
                val backups = File(root, "records/recovery-backups").listFiles()!!
                check(backups.size == 1 && backups[0].readText() == "{unfinished fixture")
                check(record(root, id).getLong("captureRequestedAtUtcMillis") == requestedUtc)
                passed++
            }

            // Legacy orphan image: neither mtime nor recovery time may become capture time.
            run {
                val root = caseRoot("no-journal")
                val id = UUID.randomUUID().toString()
                jpeg(File(root, "photos/$id.jpg"))
                val report = PrivateCaptureStore(targetContext, root).recoverInterruptedCaptures()
                check(report.recoveredRecords == 1 && report.errors == 0)
                val value = record(root, id)
                check(value.isNull("captureRequestedAtUtcMillis") && value.isNull("captureCompletedAtUtcMillis"))
                check(value.getString("captureTimeSource") == "UNKNOWN_RECOVERED_FILE")
                passed++
            }

            // Incomplete JPEG bytes are retained, not promoted to a successful photo.
            run {
                val root = caseRoot("incomplete-jpeg")
                val id = UUID.randomUUID().toString()
                val store = PrivateCaptureStore(targetContext, root)
                val pending = store.pendingPhoto(id, requestedUtc, requestedElapsed)
                pending.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0, 0))
                store.abandonCapture(id)
                val report = PrivateCaptureStore(targetContext, root).recoverInterruptedCaptures()
                check(report.incompleteCaptures == 1 && report.recoveredRecords == 0 && report.errors == 0)
                check(pending.exists() && !File(root, "photos/$id.jpg").exists())
                check(record(root, id).isNull("photoFile"))
                passed++
            }

            // Existing complete records survive restart byte for byte.
            run {
                val root = caseRoot("complete-record")
                val id = UUID.randomUUID().toString()
                val store = PrivateCaptureStore(targetContext, root)
                val pending = store.pendingPhoto(id, requestedUtc, requestedElapsed)
                jpeg(pending)
                store.finishPhoto(id, pending, requestedUtc, requestedElapsed)
                val original = File(root, "records/$id.json").readBytes()
                val report = PrivateCaptureStore(targetContext, root).recoverInterruptedCaptures()
                check(report.recoveredRecords == 0 && report.errors == 0)
                check(original.contentEquals(File(root, "records/$id.json").readBytes()))
                passed++
            }

            // A saved record cannot make a later-corrupted JPEG count as healthy.
            run {
                val root = caseRoot("saved-image-damaged")
                val id = UUID.randomUUID().toString()
                val store = PrivateCaptureStore(targetContext, root)
                val pending = store.pendingPhoto(id, requestedUtc, requestedElapsed)
                jpeg(pending)
                store.finishPhoto(id, pending, requestedUtc, requestedElapsed)
                File(root, "photos/$id.jpg").writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0, 0))
                val restarted = PrivateCaptureStore(targetContext, root)
                val report = restarted.recoverInterruptedCaptures()
                check(report.incompleteCaptures == 1 && report.errors == 0 && restarted.savedPhotoCount() == 0)
                val value = record(root, id)
                check(value.getString("photoIntegrityStatus") == "UNREADABLE")
                check(value.getLong("captureRequestedAtUtcMillis") == requestedUtc)
                passed++
            }

            // Only the record remains; retain fixture bytes under a different name.
            run {
                val root = caseRoot("legacy-missing-photo")
                val id = UUID.randomUUID().toString()
                val store = PrivateCaptureStore(targetContext, root)
                val pending = store.pendingPhoto(id, requestedUtc, requestedElapsed)
                jpeg(pending)
                store.finishPhoto(id, pending, requestedUtc, requestedElapsed)
                check(File(root, "photos/$id.jpg").renameTo(File(root, "retained-original.jpg")))
                check(File(root, "journals/$id.journal").renameTo(File(root, "retained-original.journal")))
                val restarted = PrivateCaptureStore(targetContext, root)
                val report = restarted.recoverInterruptedCaptures()
                check(report.incompleteCaptures == 1 && report.errors == 0 && restarted.savedPhotoCount() == 0)
                check(record(root, id).getString("photoIntegrityStatus") == "MISSING")
                check(record(root, id).getLong("captureRequestedAtUtcMillis") == requestedUtc)
                passed++
            }

            // Repair structural fields while preserving user edits and observations.
            run {
                val root = caseRoot("preserve-annotations")
                val id = UUID.randomUUID().toString()
                val store = PrivateCaptureStore(targetContext, root)
                val pending = store.pendingPhoto(id, requestedUtc, requestedElapsed)
                jpeg(pending)
                store.finishPhoto(id, pending, requestedUtc, requestedElapsed)
                val original = record(root, id)
                    .put("captureState", "INCOMPLETE")
                    .put("photoFile", JSONObject.NULL)
                    .put("issueLocation", JSONObject().put("latitude", 12.3).put("longitude", -45.6).put("source", "MANUAL_TEST_FIXTURE"))
                    .put("description", "isolated test annotation")
                File(root, "records/$id.json").writeText(original.toString(2), Charsets.UTF_8)
                val restarted = PrivateCaptureStore(targetContext, root)
                val report = restarted.recoverInterruptedCaptures()
                check(report.recoveredRecords == 1 && report.errors == 0 && restarted.savedPhotoCount() == 1)
                val value = record(root, id)
                check(value.getJSONObject("issueLocation").getDouble("latitude") == 12.3)
                check(value.getString("description") == "isolated test annotation")
                check(value.getLong("captureRequestedAtUtcMillis") == requestedUtc)
                check(value.getLong("captureCompletedAtUtcMillis") == original.getLong("captureCompletedAtUtcMillis"))
                passed++
            }

            // Metadata with a different ID must not attach that record's edits to this photo.
            run {
                val root = caseRoot("mismatched-record-id")
                val id = UUID.randomUUID().toString()
                File(root, "records").mkdirs()
                val unrelated = JSONObject().put("schemaVersion", 1)
                    .put("photoId", UUID.randomUUID().toString())
                    .put("captureState", "SAVED")
                    .put("issueLocation", JSONObject().put("latitude", 12.3))
                File(root, "records/$id.json").writeText(unrelated.toString(2), Charsets.UTF_8)
                val restarted = PrivateCaptureStore(targetContext, root)
                val report = restarted.recoverInterruptedCaptures()
                check(report.incompleteCaptures == 1 && report.errors == 0)
                val value = record(root, id)
                check(value.getString("photoId") == id && value.getString("captureState") == "INCOMPLETE")
                check(value.isNull("issueLocation") && value.isNull("captureRequestedAtUtcMillis"))
                check(File(root, "records/recovery-backups").listFiles()!!.size == 1)
                passed++
            }

            // A manual tag remains a human observation, never an AI success claim.
            run {
                val root = caseRoot("manual-category")
                val id = UUID.randomUUID().toString()
                val store = PrivateCaptureStore(targetContext, root)
                val pending = store.pendingPhoto(id, requestedUtc, requestedElapsed)
                jpeg(pending)
                store.finishPhoto(id, pending, requestedUtc, requestedElapsed, IssueCategory.LITTER)
                val original = File(root, "records/$id.json").readBytes()
                check(PrivateCaptureStore(targetContext, root).recoverInterruptedCaptures().errors == 0)
                val value = record(root, id)
                check(value.getString("issueCategory") == "LITTER")
                check(value.getString("issueCategorySource") == "USER_TAGGED")
                check(value.getBoolean("issueCategoryNeedsReview"))
                check(value.getString("recognitionStatus") == "NOT_RUN")
                check(value.getString("captureWallClockStatus") == "WALL_CLOCK_DISCONTINUITY")
                check(original.contentEquals(File(root, "records/$id.json").readBytes()))
                passed++
            }

            // A replacement JPEG with identical dimensions must not inherit old evidence.
            run {
                val root = caseRoot("valid-image-replaced")
                val id = UUID.randomUUID().toString()
                val store = PrivateCaptureStore(targetContext, root)
                val pending = store.pendingPhoto(id, requestedUtc, requestedElapsed)
                jpeg(pending)
                store.finishPhoto(id, pending, requestedUtc, requestedElapsed)
                val original = record(root, id)
                jpeg(File(root, "photos/$id.jpg"), android.graphics.Color.WHITE)
                val changedBytes = File(root, "photos/$id.jpg").readBytes()
                val restarted = PrivateCaptureStore(targetContext, root)
                val report = restarted.recoverInterruptedCaptures()
                check(report.incompleteCaptures == 1 && report.errors == 0 && restarted.savedPhotoCount() == 0)
                val value = record(root, id)
                check(value.getString("photoIntegrityStatus") == "HASH_MISMATCH")
                check(value.getString("photoSha256") == original.getString("photoSha256"))
                check(value.getLong("captureRequestedAtUtcMillis") == requestedUtc)
                check(changedBytes.contentEquals(File(root, "photos/$id.jpg").readBytes()))
                passed++
            }

            // A corrupt fingerprint is retained for review rather than silently rebound.
            run {
                val root = caseRoot("invalid-fingerprint")
                val id = UUID.randomUUID().toString()
                val store = PrivateCaptureStore(targetContext, root)
                val pending = store.pendingPhoto(id, requestedUtc, requestedElapsed)
                jpeg(pending)
                store.finishPhoto(id, pending, requestedUtc, requestedElapsed)
                val original = record(root, id).put("photoSha256", "invalid fixture fingerprint")
                File(root, "records/$id.json").writeText(original.toString(2), Charsets.UTF_8)
                val restarted = PrivateCaptureStore(targetContext, root)
                check(restarted.recoverInterruptedCaptures().incompleteCaptures == 1)
                check(restarted.savedPhotoCount() == 0)
                check(record(root, id).getString("photoIntegrityStatus") == "HASH_MISMATCH")
                check(record(root, id).getString("photoSha256") == "invalid fixture fingerprint")
                passed++
            }

            // An unrelated record's fingerprint must not be adopted or used for this ID.
            run {
                val root = caseRoot("foreign-fingerprint")
                val id = UUID.randomUUID().toString()
                jpeg(File(root, "photos/$id.jpg"))
                File(root, "records").mkdirs()
                val unrelated = JSONObject().put("schemaVersion", 1)
                    .put("photoId", UUID.randomUUID().toString())
                    .put("photoSha256", "a".repeat(64))
                    .put("description", "unrelated fixture annotation")
                File(root, "records/$id.json").writeText(unrelated.toString(2), Charsets.UTF_8)
                val restarted = PrivateCaptureStore(targetContext, root)
                val report = restarted.recoverInterruptedCaptures()
                check(report.recoveredRecords == 1 && report.errors == 0 && restarted.savedPhotoCount() == 1)
                val value = record(root, id)
                check(value.getString("photoId") == id && !value.has("description"))
                check(value.getString("photoSha256") != "a".repeat(64))
                check(value.getString("photoSha256Source") == "RECOVERY_VERIFICATION_CURRENT_BYTES")
                check(value.isNull("captureRequestedAtUtcMillis"))
                passed++
            }

            output.putString("fieldlog_test_status", "PASS")
            output.putInt("storage_fixture_tests", passed)
            output.putString("scope", "ISOLATED_SYNTHETIC_STORAGE_ONLY_NOT_CAMERA_OR_GPS")
            finish(Activity.RESULT_OK, output)
        } catch (exception: Exception) {
            output.putString("fieldlog_test_status", "FAIL")
            output.putString("error", exception.javaClass.simpleName + ": " + exception.message)
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
