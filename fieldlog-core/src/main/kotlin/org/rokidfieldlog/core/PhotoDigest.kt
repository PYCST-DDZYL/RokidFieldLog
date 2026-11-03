package org.rokidfieldlog.core

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/** A content fingerprint, not a signature or proof of capture time. */
object PhotoDigest {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    /** Compare the bytes actually written. The caller discards its incomplete output on failure. */
    internal fun copyVerified(file: File, output: OutputStream, expectedBytes: Long, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val changed = "照片在写入时发生变化，请重新检查后再导出"
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                require(bytes <= expectedBytes) { changed }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
        require(bytes == expectedBytes && hex(digest.digest()) == expectedSha256) { changed }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
