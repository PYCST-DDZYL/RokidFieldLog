package org.rokidfieldlog.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

data class CaptureStamp(val photoId: String, val requestedUtcMillis: Long, val requestedElapsedNanos: Long)

/** Journal and photo rename only. The Android caller validates JPEGs and writes JSON. */
class CaptureFiles(private val root: File) {
    private val photos = File(root, "photos")
    private val journals = File(root, "journals")

    companion object {
        private const val MAGIC = 0x464C4A31
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun validId(value: String) = ID.matches(value)
        fun hasJpegMarkers(file: File): Boolean = try {
            file.isFile && file.length() >= 4 && RandomAccessFile(file, "r").use {
                val startsWithJpeg = it.readUnsignedByte() == 0xff && it.readUnsignedByte() == 0xd8
                it.seek(it.length() - 2)
                startsWithJpeg && it.readUnsignedByte() == 0xff && it.readUnsignedByte() == 0xd9
            }
        } catch (_: Exception) { false }
    }

    fun pending(id: String): File { require(validId(id)); return File(photos, "$id.pending.jpg") }
    fun completed(id: String): File { require(validId(id)); return File(photos, "$id.jpg") }

    fun begin(stamp: CaptureStamp): File {
        require(validId(stamp.photoId) && stamp.requestedElapsedNanos > 0)
        check(photos.isDirectory || photos.mkdirs()) { "Cannot create private photo directory" }
        check(journals.isDirectory || journals.mkdirs()) { "Cannot create private journal directory" }
        val journal = File(journals, "${stamp.photoId}.journal")
        val temporary = File(journals, "${stamp.photoId}.journal.new")
        check(!pending(stamp.photoId).exists() && !completed(stamp.photoId).exists() && !journal.exists() && !temporary.exists()) { "Capture ID already exists" }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeInt(MAGIC)
            it.writeUTF(stamp.photoId)
            it.writeLong(stamp.requestedUtcMillis)
            it.writeLong(stamp.requestedElapsedNanos)
        }
        val payload = buffer.toByteArray()
        FileOutputStream(temporary).use { output ->
            output.write(payload)
            DataOutputStream(output).apply { writeLong(CRC32().apply { update(payload) }.value); flush() }
            output.fd.sync()
        }
        check(temporary.renameTo(journal)) { "Cannot persist capture request journal" }
        return pending(stamp.photoId)
    }

    fun stamp(id: String): CaptureStamp? {
        require(validId(id))
        // A complete .new file can survive a crash immediately before rename.
        val journal = File(journals, "$id.journal").takeIf { it.isFile } ?: File(journals, "$id.journal.new")
        return try {
            check(journal.length() in 1..256) { "Invalid journal size" }
            val bytes = journal.readBytes()
            check(bytes.size >= 8)
            val payload = bytes.copyOfRange(0, bytes.size - 8)
            val storedChecksum = DataInputStream(bytes.copyOfRange(bytes.size - 8, bytes.size).inputStream()).use { it.readLong() }
            check(CRC32().apply { update(payload) }.value == storedChecksum)
            DataInputStream(payload.inputStream()).use {
                check(it.readInt() == MAGIC)
                val value = CaptureStamp(it.readUTF(), it.readLong(), it.readLong())
                check(value.photoId == id && value.requestedElapsedNanos > 0 && it.available() == 0)
                value
            }
        } catch (_: Exception) { null }
    }

    /** Never replaces an existing photo and never deletes incomplete evidence. */
    fun promote(id: String): File {
        val pending = pending(id)
        val completed = completed(id)
        check(pending.isFile && pending.length() > 0) { "No photo bytes to persist" }
        check(!completed.exists()) { "Completed photo already exists" }
        RandomAccessFile(pending, "rw").use { it.fd.sync() }
        check(pending.renameTo(completed)) { "Cannot persist photo" }
        return completed
    }

    fun ids(): Set<String> {
        val names = listOf(photos, journals, File(root, "records")).flatMap { directory ->
            if (!directory.exists()) emptyList() else {
                check(directory.isDirectory) { "Invalid private capture directory" }
                checkNotNull(directory.listFiles()) { "Cannot read private capture directory" }.map { it.name }
            }
        }
        return names.map { it.substringBefore('.') }.filter(::validId).toSet()
    }
}
