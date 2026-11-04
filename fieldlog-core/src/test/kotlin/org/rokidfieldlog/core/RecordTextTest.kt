package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException

class RecordTextTest {
    @Test fun multibyteTextAtTheByteLimitSurvivesExactly() {
        val text = "{\"note\":\"合成记录 🌳\"}"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, RecordText.read(ByteArrayInputStream(bytes), bytes.size))
        assertThrows(IllegalArgumentException::class.java) {
            RecordText.read(ByteArrayInputStream(bytes), bytes.size - 1)
        }
    }

    @Test fun invalidOrTruncatedUtf8CannotTurnIntoReplacementCharacters() {
        for (bytes in listOf(byteArrayOf(0xFF.toByte()), byteArrayOf(0xE4.toByte(), 0xB8.toByte()),
                byteArrayOf(0xC0.toByte(), 0xAF.toByte()))) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                RecordText.read(ByteArrayInputStream(bytes), 100)
            }
            assertTrue(error.cause is CharacterCodingException)
        }
    }

    @Test fun anExpandingSourceCannotBypassTheReadLimit() {
        var reads = 0
        val endless = object : InputStream() {
            override fun read() = 65
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                bytes.fill(65, offset, offset + length)
                return length
            }
        }
        assertThrows(IllegalArgumentException::class.java) { RecordText.read(endless, 8192) }
        assertEquals(2, reads)
    }

    @Test fun callersKeepOwnershipOnSuccessAndFailure() {
        for (limit in listOf(1, 10)) {
            var closed = false
            val input = object : ByteArrayInputStream("abc".toByteArray()) {
                override fun close() { closed = true }
            }
            if (limit == 1) assertThrows(IllegalArgumentException::class.java) { RecordText.read(input, limit) }
            else assertEquals("abc", RecordText.read(input, limit))
            assertFalse(closed)
        }
    }

    @Test fun readFailuresRemainVisibleToTheStorageAdapter() {
        val failure = IOException("SYNTHETIC unavailable record")
        val input = object : InputStream() { override fun read(): Int = throw failure }
        assertSame(failure, assertThrows(IOException::class.java) { RecordText.read(input, 100) })
    }
}
