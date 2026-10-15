package org.rokidfieldlog.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AtomicFile
import androidx.exifinterface.media.ExifInterface
import org.rokidfieldlog.core.*
import java.io.File
import java.util.UUID

/** Background-only adapter; shares one queue with the location history store. */
class PhoneIssueStore(context: Context) {
    val root = File(context.filesDir, "fieldlog/offline").canonicalFile
    val inspector = ImageInspector(::dimensions)
    private val texts = object : AtomicTextFiles {
        override fun read(file: File): String = AtomicFile(file).openRead().use {
            check(it.channel.size() <= InspectionCodec.MAX_BYTES) { "记录文件过大" }
            it.bufferedReader(Charsets.UTF_8).readText()
        }
        override fun write(file: File, text: String) {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
        }
    }
    private val clock = object : FieldClock {
        override fun utcMillis() = System.currentTimeMillis()
        override fun elapsedNanos() = android.os.SystemClock.elapsedRealtimeNanos()
    }
    val library = OfflineLibrary(root, texts, inspector, clock, beforeDelete = { inspections.requireUnlinked(it); reports.requireUnlinked(it) })
    val reports: ReportRepository by lazy { ReportRepository(root, texts, library, inspector) }
    val inspections: InspectionRepository by lazy {
        InspectionRepository(File(root, "issues"), texts, clock, InspectionEvidenceSource { id ->
            val entry = library.load(id)
            InspectionEvidence(entry.original, entry.importedSha256, entry.draft.isSynthetic)
        })
    }
    fun clearSyntheticRecords(): Int {
        val materials = reports.scan()
        check(materials.unreadableCount == 0) { "有报修材料无法读取，请保留数据并核查后再清除演示" }
        val listing = inspections.scan()
        check(listing.unreadableCount == 0) { "有问题历史无法读取，请保留数据并核查后再清除演示" }
        materials.reports.filter { it.isSynthetic == true }.forEach { reports.delete(it.reportId, it.revision) }
        listing.issues.filter { it.isSynthetic }.forEach { inspections.deleteTracking(it.issueId, it.revision) }
        val ids = library.list().filter { it.draft.isSynthetic }.map { it.draft.photoId }
        ids.forEach { library.delete(it) }
        return ids.size
    }

    fun dimensions(file: File): Pair<Int, Int>? = runCatching {
        if (!CaptureFiles.hasJpegMarkers(file)) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 24_000_000)
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        bitmap.recycle()
        bounds.outWidth to bounds.outHeight
    }.getOrNull()

    fun uprightPreview(file: File, longestEdge: Int = 2048): Bitmap {
        checkNotNull(dimensions(file)) { "照片无法解码或尺寸过大" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > longestEdge) sample *= 2
        val decoded = checkNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (oriented !== decoded) decoded.recycle()
        val ratio = minOf(1f, longestEdge.toFloat() / maxOf(oriented.width, oriented.height))
        if (ratio == 1f) return oriented
        val scaled = Bitmap.createScaledBitmap(oriented, (oriented.width * ratio).toInt().coerceAtLeast(1),
            (oriented.height * ratio).toInt().coerceAtLeast(1), true)
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    fun renderReview(entry: LibraryEntry, masks: List<MaskRect>): PhotoReview {
        require(masks.size <= 100)
        masks.forEach { it.validate() }
        require(PhotoDigest.sha256(entry.original) == entry.importedSha256) { "原图与导入时不一致，请核查采集包" }
        val profile = MaterialProfile()
        val preview = uprightPreview(entry.original, profile.maxLongEdge)
        var bitmap = preview.copy(Bitmap.Config.ARGB_8888, true)
        preview.recycle()
        val target = entry.reviewFile("review-" + UUID.randomUUID() + ".jpg")
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
            for (mask in masks) {
                val box = mask.pixels(bitmap.width, bitmap.height)
                canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), paint)
            }
            var quality = 93
            while (true) {
                target.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                    output.fd.sync()
                }
                if (target.length() <= profile.maxPhotoBytes) break
                if (quality > 70) { quality -= 8; continue }
                require(maxOf(bitmap.width, bitmap.height) > 512) { "照片无法满足材料大小限制" }
                val next = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 0.8f).toInt().coerceAtLeast(1),
                    (bitmap.height * 0.8f).toInt().coerceAtLeast(1), true)
                bitmap.recycle(); bitmap = next; quality = 90
            }
            check(!JpegPrivacy.hasPrivateMetadata(target))
            checkNotNull(dimensions(target))
            require(PhotoDigest.sha256(entry.original) == entry.importedSha256) { "原图在处理时发生变化" }
            return PhotoReview(target.name, entry.importedSha256, PhotoDigest.sha256(target),
                target.length(), bitmap.width, bitmap.height, masks.toList())
        } catch (error: Exception) { target.delete(); throw error }
        finally { bitmap.recycle() }
    }
}
