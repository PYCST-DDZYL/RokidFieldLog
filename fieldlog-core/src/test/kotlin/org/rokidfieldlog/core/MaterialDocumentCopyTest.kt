package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class MaterialDocumentCopyTest {
    @Test fun staleMaterialNeverOpensOrDeletesTheSelectedDocument() {
        var opened = false; var deleted = false
        assertThrows(IllegalArgumentException::class.java) {
            MaterialDocumentCopy.copy({ throw IllegalArgumentException("Synthetic stale revision") },
                { ByteArrayInputStream(byteArrayOf(1)) }, { opened = true; ByteArrayOutputStream() }, { deleted = true })
        }
        assertFalse(opened); assertFalse(deleted)
    }
    @Test fun destinationPermissionFailureNeverDeletesTheSelectedDocument() {
        var deleted = false
        assertThrows(SecurityException::class.java) {
            MaterialDocumentCopy.copy({}, { ByteArrayInputStream(byteArrayOf(1)) },
                { throw SecurityException("Synthetic revoked destination grant") }, { deleted = true })
        }
        assertFalse(deleted)
    }
    @Test fun failedCopyAttemptsCleanupOfThePartiallyWrittenDocument() {
        var deleted = false; var closed = false
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("Synthetic disk full") }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) {
            MaterialDocumentCopy.copy({}, { ByteArrayInputStream(byteArrayOf(1, 2)) }, { output }, { deleted = true })
        }
        assertTrue(deleted); assertTrue(closed)
    }
    @Test fun destinationCloseFailureAlsoCleansUpAnUnconfirmedWrite() {
        var deleted = false
        val output = object : ByteArrayOutputStream() { override fun close() { throw IOException("Synthetic flush failure") } }
        assertThrows(IOException::class.java) {
            MaterialDocumentCopy.copy({}, { ByteArrayInputStream(byteArrayOf(1)) }, { output }, { deleted = true })
        }
        assertTrue(deleted)
    }
    @Test fun failedCleanupPreservesTheActualCopyError() {
        val failure = IOException("Synthetic original error")
        val result = assertThrows(IOException::class.java) {
            MaterialDocumentCopy.copy({}, { throw failure }, { ByteArrayOutputStream() }, { throw SecurityException("Synthetic cleanup denial") })
        }
        assertSame(failure, result)
    }
    @Test fun successfulCopyClosesStreamsAndDoesNotDeleteTheResult() {
        var inputClosed = false; var outputClosed = false; var deleted = false
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) { override fun close() { inputClosed = true } }
        val output = object : ByteArrayOutputStream() { override fun close() { outputClosed = true } }
        MaterialDocumentCopy.copy({}, { input }, { output }, { deleted = true })
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertTrue(inputClosed); assertTrue(outputClosed); assertFalse(deleted)
    }
}
