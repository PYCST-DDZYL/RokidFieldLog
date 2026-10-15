package org.rokidfieldlog.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.rokidfieldlog.core.*
import java.io.File
import java.util.UUID

object SyntheticSamples {
    fun add(store: PhoneIssueStore): ImportReport {
        val temporary = File(store.root, "sample-input/" + UUID.randomUUID()).apply { check(mkdirs()) }
        try {
            val captures = (0..1).map { index ->
                val id = UUID.randomUUID().toString()
                val file = File(temporary, "$id.jpg")
                val bitmap = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                canvas.drawColor(Color.rgb(113, 120, 121))
                paint.color = Color.rgb(220, 220, 195); paint.strokeWidth = 24f
                canvas.drawLine(180f, 0f, 180f, 960f, paint)
                paint.color = Color.rgb(35, 40, 40); paint.strokeWidth = 18f
                canvas.drawLine(450f, 220f, 630f, 460f, paint); canvas.drawLine(630f, 460f, 580f, 650f, paint)
                paint.color = Color.WHITE
                canvas.drawRect(780f, 530f, 1170f, 650f, paint)
                paint.color = Color.BLACK; paint.textSize = 46f
                canvas.drawText("DEMO PLATE", 800f, 605f, paint)
                paint.color = Color.YELLOW; paint.textSize = 48f
                canvas.drawText("SYNTHETIC DEMO " + (index + 1), 60f, 90f, paint)
                try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it)); it.fd.sync() } }
                finally { bitmap.recycle() }
                val hash = PhotoDigest.sha256(file)
                val record = CaptureRecord.pending(id, null).copy(width = 1280, height = 960, photoSha256 = hash,
                    photoSha256Source = FingerprintSource.RECOVERY_VERIFICATION_CURRENT_BYTES,
                    captureTimeSource = "SYNTHETIC_DEMO_NOT_CAMERA", description = "合成演示图，不是真实现场问题")
                IncomingCapture(RecordDocument(record), file, hash)
            }
            val zip = File(temporary, "demo.fieldlog.zip")
            zip.outputStream().use { CapturePackage.write(it, captures, isSynthetic = true) }
            return zip.inputStream().use { store.library.importPackage(it) }
        } finally {
            check(temporary.canonicalFile.toPath().startsWith(store.root.canonicalFile.toPath()))
            temporary.deleteRecursively()
        }
    }
}
