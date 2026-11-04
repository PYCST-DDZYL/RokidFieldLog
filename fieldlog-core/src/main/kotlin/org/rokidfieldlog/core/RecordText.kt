package org.rokidfieldlog.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** Read bounded UTF-8 records. The caller owns the input stream. */
object RecordText {
    fun read(input: InputStream, maxBytes: Int): String {
        require(maxBytes > 0)
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count <= maxBytes - bytes.size()) { "记录文件过大，原文件保留" }
            bytes.write(buffer, 0, count)
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("记录含无效 UTF-8，原文件保留", error)
        }
    }
}
