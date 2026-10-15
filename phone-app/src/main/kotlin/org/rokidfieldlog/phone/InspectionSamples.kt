package org.rokidfieldlog.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.rokidfieldlog.core.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

object InspectionSamples {
    fun add(store: PhoneIssueStore): TrackedIssue {
        val work = File(store.root, "sample-input/" + UUID.randomUUID()).apply { check(mkdirs()) }
        try {
            val captures = (0..2).map { phase ->
                val id = UUID.randomUUID().toString(); val file = File(work, "$id.jpg")
                val bitmap = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(213, 221, 208))
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    paint.color = Color.rgb(122, 133, 128); canvas.drawRect(150f, 100f, 820f, 700f, paint)
                    paint.color = Color.rgb(224, 228, 221); paint.strokeWidth = 5f
                    for (x in 150..820 step 134) canvas.drawLine(x.toFloat(), 100f, x.toFloat(), 700f, paint)
                    for (y in 100..700 step 100) canvas.drawLine(150f, y.toFloat(), 820f, y.toFloat(), paint)
                    if (phase == 0) { paint.color = Color.rgb(56, 47, 37); canvas.drawOval(350f, 340f, 570f, 480f, paint) }
                    if (phase == 1) { paint.color = Color.rgb(41, 63, 97); canvas.drawRect(220f, 220f, 730f, 580f, paint) }
                    if (phase == 2) { paint.color = Color.rgb(156, 165, 157); canvas.drawRect(350f, 340f, 570f, 480f, paint) }
                    paint.color = Color.BLACK; paint.textSize = 30f; canvas.drawText("SYNTHETIC DEMO / " + phase, 24f, 48f, paint)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it)); it.fd.sync() }
                } finally { bitmap.recycle() }
                val hash = PhotoDigest.sha256(file)
                val record = CaptureRecord.pending(id, null).copy(width = 960, height = 720, photoSha256 = hash,
                    photoSha256Source = FingerprintSource.RECOVERY_VERIFICATION_CURRENT_BYTES, captureTimeSource = "SYNTHETIC_DEMO_NOT_CAMERA")
                IncomingCapture(RecordDocument(record), file, hash)
            }
            val bytes = ByteArrayOutputStream().also { CapturePackage.write(it, captures, isSynthetic = true) }.toByteArray()
            store.library.importPackage(ByteArrayInputStream(bytes))
            val site = "合成园区 / 步行道 " + captures.first().document.record.photoId.take(4)
            captures.forEachIndexed { index, capture ->
                val entry = store.library.load(capture.document.record.photoId)
                store.library.save(entry.draft.copy(category = IssueCategory.ROAD_DAMAGE, address = site, addressConfirmed = true,
                    eventDate = "2020-01-0" + (index + 1), description = "合成复查演示，非现场拍摄。"), entry.draft.revision)
            }
            val issue = store.inspections.create(site, IssueCategory.ROAD_DAMAGE, captures[0].document.record.photoId,
                "2020-01-01", true, "合成演示：最初发现步行道路破损")
            val claim = store.inspections.repairClaim(issue.issueId, issue.revision, "合成演示：维修方反馈完成，现场尚待复查")
            return store.inspections.observe(issue.issueId, claim.revision, captures[1].document.record.photoId, "2020-01-02",
                InspectionVisibility.OBSTRUCTED, InspectionFinding.UNABLE_TO_ASSESS, true, "合成演示：遮挡了原问题位置，无法确认")
        } finally {
            require(work.canonicalFile.toPath().startsWith(store.root.canonicalFile.toPath()))
            work.deleteRecursively()
        }
    }
}
