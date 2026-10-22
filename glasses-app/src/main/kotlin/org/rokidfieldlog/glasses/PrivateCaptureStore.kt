package org.rokidfieldlog.glasses

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.AtomicFile
import org.rokidfieldlog.core.*
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Android adapter. All disk operations are called on background executors. */
class PrivateCaptureStore(private val context: Context, rootOverride: File? = null) {
    private val filesRoot = context.filesDir.canonicalFile
    private val root = (rootOverride ?: File(filesRoot, "fieldlog")).canonicalFile
    private val records = File(root, "records")

    companion object {
        private val states = ConcurrentHashMap<String, CaptureStorageState>()
    }

    init {
        check(root.toPath().startsWith(File(filesRoot, "fieldlog").toPath())) { "Capture storage must stay inside FieldLog private data" }
    }

    private val recordStorage = object : CaptureRecordStorage {
        private fun file(id: String): File { require(CaptureFiles.validId(id)); return File(records, "$id.json") }
        override fun ids(): Set<String> {
            if (!records.exists()) return emptySet()
            check(records.isDirectory)
            return checkNotNull(records.listFiles()).map { it.name.substringBefore('.') }.filter(CaptureFiles::validId).toSet()
        }
        override fun exists(id: String): Boolean = listOf("", ".bak", ".new").any { File(file(id).path + it).exists() }
        override fun read(id: String): String? = try {
            AtomicFile(file(id)).openRead().use { input ->
                check(input.channel.size() <= 256 * 1024) { "Metadata exceeds supported size" }
                input.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (_: FileNotFoundException) {
            check(!exists(id)) { "Record exists but cannot be read" }
            null
        }
        override fun backup(id: String) {
            val original = file(id)
            if (original.isFile) {
                val backups = File(records, "recovery-backups")
                check(backups.isDirectory || backups.mkdirs())
                original.copyTo(File(backups, "$id-" + UUID.randomUUID() + ".json"), overwrite = false)
            }
        }
        override fun write(id: String, text: String) {
            check(records.isDirectory || records.mkdirs()) { "Photo saved; metadata directory failed" }
            val atomic = AtomicFile(file(id))
            val output = atomic.startWrite()
            try {
                output.write(text.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (exception: Exception) { atomic.failWrite(output); throw exception }
        }
        override fun delete(id: String) {
            for (suffix in listOf("", ".bak", ".new")) {
                val target = File(file(id).path + suffix)
                check(!target.exists() || target.delete()) { "Cannot delete metadata" }
            }
            File(records, "recovery-backups").listFiles()?.filter { it.name.startsWith("$id-") }?.forEach {
                check(it.delete()) { "Cannot delete recovery backup" }
            }
        }
    }

    private val repository = CaptureRepository(root, recordStorage, ImageInspector(::imageDimensions), object : FieldClock {
        override fun utcMillis() = System.currentTimeMillis()
        override fun elapsedNanos() = SystemClock.elapsedRealtimeNanos()
    }, states.getOrPut(root.path) { CaptureStorageState() })

    fun pendingPhoto(photoId: String, requestedAt: Long, requestedElapsed: Long): File =
        repository.begin(CaptureStamp(photoId, requestedAt, requestedElapsed))
    fun finishPhoto(photoId: String, pending: File, requestedAt: Long, requestedElapsed: Long, category: IssueCategory? = null): File =
        repository.finish(CaptureStamp(photoId, requestedAt, requestedElapsed), pending, category)
    fun abandonCapture(photoId: String) = repository.abandon(photoId)
    fun completedPhotoExists(photoId: String): Boolean = repository.completedPhotoExists(photoId)
    fun recoverInterruptedCaptures(forceFull: Boolean = false): RecoveryReport = repository.recover(forceFull)
    fun savedPhotoCount(): Int = repository.savedPhotoCount()
    fun latestSaved(): SavedCapture? = repository.latestSaved()
    fun deleteCapture(photoId: String) = repository.deleteCapture(photoId)
    /** Public USB-transfer file; MediaStore pending rows are removed on failure. Originals are unredacted. */
    fun exportPackages(): Pair<List<String>, Int> {
        val prefix = "FieldLog_capture_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(8)
        val resolver = context.contentResolver
        val outputs = mutableListOf<Pair<android.net.Uri, String>>()
        try {
            val counts = repository.writePackages { index, total ->
                val name = prefix + (if (total == 1) "" else "_" + (index + 1).toString().padStart(3, '0') + "of" + total) + ".zip"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/FieldLog")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "下载目录不可写" }
                outputs += uri to name
                checkNotNull(resolver.openOutputStream(uri, "w")) { "采集包不可写" }
            }
            for ((uri, _) in outputs) check(resolver.update(uri, android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            }, null, null) == 1) { "采集包发布未完成" }
            return outputs.map { it.second } to counts.sum()
        } catch (error: Exception) {
            val cleaned = outputs.map { (uri, _) -> runCatching { resolver.delete(uri, null, null) == 1 }.getOrDefault(false) }
            if (cleaned.any { !it }) throw IllegalStateException("导出未完成，部分下载文件未能清理，请检查Download/FieldLog；原照片保留", error)
            throw error
        }
    }

    fun deleteAllProjectData() {
        check(root.canonicalFile.parentFile == filesRoot) { "Unsafe data path" }
        repository.deleteAllProjectData()
    }

    private fun imageDimensions(file: File): Pair<Int, Int>? = try {
        if (!CaptureFiles.hasJpegMarkers(file)) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bitmap == null) null else { bitmap.recycle(); bounds.outWidth to bounds.outHeight }
            }
        }
    } catch (_: Exception) { null }
}
