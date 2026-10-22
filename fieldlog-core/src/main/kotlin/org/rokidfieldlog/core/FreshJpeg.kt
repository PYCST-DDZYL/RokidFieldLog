package org.rokidfieldlog.core

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/** Only for a freshly encoded, single-scan, baseline sRGB bitmap, never an original photograph. */
object FreshJpeg {
    fun copyWithoutMetadata(source: File, output: OutputStream): Set<Int> = RandomAccessFile(source, "r").use { input ->
        require(input.length() in 4..64L * 1024 * 1024 && input.readUnsignedShort() == 0xffd8) { "Invalid fresh JPEG" }
        output.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        val removed = linkedSetOf<Int>()
        var baseline = false
        while (input.filePointer < input.length()) {
            require(input.readUnsignedByte() == 0xff) { "Invalid JPEG header marker" }
            var marker = input.readUnsignedByte()
            while (marker == 0xff) marker = input.readUnsignedByte()
            require(marker !in 0xd0..0xd9 && marker != 0x00 && marker != 0x01) { "Missing JPEG scan" }
            if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                require(marker == 0xc0) { "Only fresh baseline JPEG encoding is supported" }
                baseline = true
            }
            val length = input.readUnsignedShort()
            require(length >= 2 && input.filePointer + length - 2 <= input.length()) { "Invalid JPEG segment length" }
            if (marker in 0xe1..0xef || marker == 0xfe) {
                removed += marker
                input.seek(input.filePointer + length - 2)
                continue
            }
            val payload = ByteArray(length - 2); input.readFully(payload)
            // APP0 may contain an unreviewed JFXX thumbnail or arbitrary private data.
            // Keep only the fixed JFIF header with no embedded thumbnail.
            if (marker == 0xe0 && !(payload.size == 14 && payload.take(5).toByteArray().contentEquals(
                    byteArrayOf(0x4a, 0x46, 0x49, 0x46, 0)) && payload[5] == 1.toByte() &&
                    (payload[6].toInt() and 0xff) in 0..2 && (payload[7].toInt() and 0xff) in 0..2 &&
                    payload[12] == 0.toByte() && payload[13] == 0.toByte())) {
                removed += marker
                continue
            }
            output.write(byteArrayOf(0xff.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()))
            output.write(payload)
            if (marker == 0xda) {
                require(baseline) { "Missing baseline JPEG frame" }
                val buffer = ByteArray(64 * 1024)
                var afterFf = false
                var ended = false
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) {
                        val value = buffer[index].toInt() and 0xff
                        require(!ended) { "Unexpected bytes after JPEG end" }
                        if (afterFf) {
                            when (value) {
                                0x00, in 0xd0..0xd7 -> afterFf = false
                                0xff -> Unit
                                0xd9 -> { ended = true; afterFf = false }
                                else -> error("Unexpected marker after a fresh JPEG scan")
                            }
                        } else if (value == 0xff) afterFf = true
                    }
                    output.write(buffer, 0, count)
                }
                require(ended && !afterFf) { "Incomplete JPEG scan" }
                return@use removed
            }
        }
        error("Missing JPEG scan")
    }
}
