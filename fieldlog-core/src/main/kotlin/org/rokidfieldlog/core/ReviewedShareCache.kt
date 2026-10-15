package org.rokidfieldlog.core

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class SharedMaterial(val directory: File, val text: String, val photos: List<File>)
internal data class ReviewedAttachment(val file: File, val sha256: String)

/** Only reviewed JPEG copies enter this directory. Android exposes this cache, never the library. */
class ReviewedShareCache(private val root: File, private val inspector: ImageInspector, private val clock: FieldClock) {
    companion object { const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L }

    internal fun prepare(text: String, attachments: List<ReviewedAttachment>): SharedMaterial {
        require(attachments.size in 2..ReportCodec.MAX_PHOTOS && text.toByteArray(Charsets.UTF_8).size <= 32_768)
        check(root.isDirectory || root.mkdirs())
        pruneExpired()
        val profile = MaterialProfile()
        val bytes = attachments.sumOf { it.file.length() }
        require(root.usableSpace >= bytes + CaptureRepository.MIN_FREE_BYTES) { "空间不足，无法准备分享照片" }
        val directory = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
        try {
            val photos = attachments.mapIndexed { index, attachment ->
                require(attachment.file.name.matches(Regex("review-[0-9a-f-]{36}\\.jpg")))
                require(attachment.file.length() in 1..profile.maxPhotoBytes)
                val target = File(directory, "photo-" + (index + 1).toString().padStart(2, '0') + ".jpg")
                attachment.file.copyTo(target)
                RandomAccessFile(target, "rw").use { it.fd.sync() }
                require(PhotoDigest.sha256(target) == attachment.sha256) { "照片在准备分享时发生变化，请重新审阅" }
                require(CaptureFiles.hasJpegMarkers(target) && !JpegPrivacy.hasPrivateMetadata(target)) { "分享照片含无关元数据" }
                val size = checkNotNull(inspector.dimensions(target)) { "分享照片无法读取" }
                require(size.first > 0 && size.second > 0 && maxOf(size.first, size.second) <= profile.maxLongEdge)
                target
            }
            check(directory.setLastModified(clock.utcMillis()))
            return SharedMaterial(directory, text, photos)
        } catch (error: Exception) {
            remove(directory)
            throw error
        }
    }

    /** Keep snapshots alive when a recipient reads them after the chooser closes. */
    fun pruneExpired() {
        val now = clock.utcMillis()
        root.listFiles()?.filter { it.isDirectory && CaptureFiles.validId(it.name) }?.forEach { directory ->
            val modified = directory.lastModified()
            if (modified > 0 && now >= modified && now - modified >= RETENTION_MILLIS) remove(directory)
        }
    }

    fun discard(material: SharedMaterial) = remove(material.directory)

    private fun remove(directory: File) {
        require(directory.canonicalFile.parentFile == root.canonicalFile && CaptureFiles.validId(directory.name))
        check(!directory.exists() || directory.deleteRecursively()) { "未能清理分享暂存照片" }
    }
}
