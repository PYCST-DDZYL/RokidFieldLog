package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.imageio.ImageIO

/** Synthetic host images exercise the same transaction used by the system photo picker. */
class PhotoImportTest {
    @get:Rule val temp = TemporaryFolder(File("build/photo-import-tests").apply { mkdirs() })
    private val root by lazy { temp.newFolder("library") }
    private val clock = object : FieldClock { override fun utcMillis() = 123456789L; override fun elapsedNanos() = 1L }
    private val inspector = ImageInspector { file -> runCatching { ImageIO.read(file)?.let { it.width to it.height } }.getOrNull() }
    private var failWrites = false
    private val texts = object : AtomicTextFiles {
        override fun read(file: File) = file.readText(Charsets.UTF_8)
        override fun write(file: File, text: String) {
            check(!failWrites) { "Synthetic full-storage write failure" }
            file.parentFile.mkdirs(); file.writeText(text, Charsets.UTF_8)
        }
    }
    private val library by lazy { OfflineLibrary(root, texts, inspector, clock) }
    private fun jpeg(color: Color = Color.RED): ByteArray {
        val image = BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { this.color = color; fillRect(0, 0, 80, 60); dispose() }
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "jpg", it)) }.toByteArray()
    }
    private fun selected(vararg bytes: ByteArray): List<() -> InputStream> = bytes.map { value -> { ByteArrayInputStream(value) } }
    private fun assertNoStaging() { assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty()) }

    @Test fun realIntakeKeepsUnknownCaptureFactsAndRequiresPrivacyReview() {
        val bytes = jpeg()
        assertEquals(ImportReport(1, 0), library.importPhotos(selected(bytes)))
        val entry = library.list().single()
        val record = entry.source.record
        assertEquals("UNKNOWN_SELECTED_PHOTO", record.captureTimeSource)
        assertNull(record.captureRequestedAtUtcMillis); assertNull(record.captureCompletedAtUtcMillis)
        assertNull(record.captureRequestedElapsedRealtimeNanos); assertNull(record.phoneLocation); assertNull(record.issueLocation)
        assertNull(record.photoSha256Source); assertNull(record.issueCategory)
        assertEquals(80, record.width); assertEquals(60, record.height)
        assertFalse(entry.draft.isSynthetic); assertEquals("", entry.draft.eventDate)
        assertNull(entry.draft.photoReview); assertEquals(SubmissionStage.DRAFT, entry.draft.stage)
        assertArrayEquals(bytes, entry.original.readBytes())
        assertEquals(entry.importedSha256, PhotoDigest.sha256(entry.original))
        assertNoStaging()
    }

    @Test fun repeatedSelectionPreservesEditingAndDoesNotCreateAnotherIdentity() {
        val bytes = jpeg()
        library.importPhotos(selected(bytes))
        val first = library.list().single()
        val edited = library.save(first.draft.copy(address = "Synthetic fixture site", description = "Synthetic retained edit"), 0)
        assertEquals(ImportReport(0, 1), library.importPhotos(selected(bytes)))
        assertEquals(edited.draft, library.list().single().draft)
        assertNoStaging()
    }

    @Test fun samePhotoTwiceInOneSelectionIsCountedAsADuplicate() {
        val bytes = jpeg()
        assertEquals(ImportReport(2, 1), library.importPhotos(selected(bytes, bytes, jpeg(Color.BLUE))))
        assertEquals(2, library.list().size); assertNoStaging()
    }

    @Test fun invalidSecondPhotoRollsBackTheWholeBatchAndKeepsExistingData() {
        library.importPhotos(selected(jpeg(Color.GREEN)))
        val old = library.list().single()
        assertThrows(IllegalArgumentException::class.java) { library.importPhotos(selected(jpeg(), byteArrayOf(1, 2, 3))) }
        assertEquals(old.draft, library.list().single().draft)
        assertEquals(old.importedSha256, PhotoDigest.sha256(old.original)); assertNoStaging()
    }

    @Test fun providerReadFailureClosesStreamsAndCommitsNothing() {
        var closed = false
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Synthetic provider unavailable")
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { library.importPhotos(listOf({ ByteArrayInputStream(jpeg()) }, { broken })) }
        assertTrue(closed); assertTrue(library.list().isEmpty()); assertNoStaging()
    }

    @Test fun revokedDocumentPermissionDoesNotLeaveTheFirstPhotoCommitted() {
        val sources: List<() -> InputStream> = listOf({ ByteArrayInputStream(jpeg()) }, { throw SecurityException("Synthetic revoked grant") })
        assertThrows(SecurityException::class.java) { library.importPhotos(sources) }
        assertTrue(library.list().isEmpty()); assertNoStaging()
    }

    @Test fun failedMetadataCommitDoesNotLoseExistingRecordsOrExposeNewOnes() {
        library.importPhotos(selected(jpeg(Color.GREEN)))
        val old = library.list().single()
        failWrites = true
        assertThrows(IllegalStateException::class.java) { library.importPhotos(selected(jpeg(), jpeg(Color.BLUE))) }
        assertEquals(old.draft, library.list().single().draft); assertTrue(old.original.isFile); assertNoStaging()
    }

    @Test fun excessiveSelectionIsRejectedBeforeOpeningAnyProvider() {
        var opened = false
        assertThrows(IllegalArgumentException::class.java) {
            library.importPhotos(List(OfflineLibrary.MAX_SELECTED_PHOTOS + 1) { { opened = true; ByteArrayInputStream(jpeg()) } })
        }
        assertFalse(opened); assertTrue(library.list().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { library.importPhotos(emptyList()) }
    }

    @Test fun oversizedProviderStreamIsBoundedAndClosed() {
        var closed = false
        val endless = object : InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { buffer.fill(0, offset, offset + length); return length }
            override fun close() { closed = true }
        }
        assertThrows(IllegalArgumentException::class.java) { library.importPhotos(listOf({ endless })) }
        assertTrue(closed); assertTrue(library.list().isEmpty()); assertNoStaging()
    }

    @Test fun jpegMarkersDoNotBypassPixelValidation() {
        val badPixels = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0, 0, 0xff.toByte(), 0xd9.toByte())
        assertThrows(IllegalStateException::class.java) { library.importPhotos(selected(badPixels)) }
        assertTrue(library.list().isEmpty()); assertNoStaging()
    }

    @Test fun sameIdentityWithAnAlteredExistingOriginalIsNeverOverwritten() {
        val bytes = jpeg()
        library.importPhotos(selected(bytes))
        val entry = library.list().single()
        entry.original.writeBytes(jpeg(Color.BLUE))
        assertThrows(IllegalArgumentException::class.java) { library.importPhotos(selected(bytes)) }
        assertArrayEquals(jpeg(Color.BLUE), entry.original.readBytes()); assertNoStaging()
    }
}
