package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

class ReviewedShareCacheTest {
    @get:Rule val temp = TemporaryFolder(File("build/share-cache-tests").apply { mkdirs() })
    private val root by lazy { temp.newFolder("reviewed-shares") }
    private var now = 1_234_567_890L
    private val clock = object : FieldClock { override fun utcMillis() = now; override fun elapsedNanos() = 1L }
    private val inspector = ImageInspector { file -> ImageIO.read(file)?.let { it.width to it.height } }
    private val cache by lazy { ReviewedShareCache(root, inspector, clock) }
    private fun attachment(): ReviewedAttachment {
        val file = temp.newFile("review-" + UUID.randomUUID() + ".jpg")
        val image = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = Color.BLACK; fillRect(0, 0, 32, 24); dispose() }
        check(ImageIO.write(image, "jpg", file))
        return ReviewedAttachment(file, PhotoDigest.sha256(file))
    }
    private fun prepare() = cache.prepare("SYNTHETIC manual handoff text", listOf(attachment(), attachment()))

    @Test fun snapshotsAreIndependentCopiesThatSurviveSourceChangesAndDiscard() {
        val source = attachment()
        val material = cache.prepare("SYNTHETIC manual handoff text", listOf(source, attachment()))
        assertEquals(setOf("photo-01.jpg", "photo-02.jpg"), material.directory.list()!!.toSet())
        assertEquals(source.sha256, PhotoDigest.sha256(material.photos.first()))
        source.file.appendText("Synthetic later change")
        assertEquals(source.sha256, PhotoDigest.sha256(material.photos.first()))
        cache.discard(material)
        assertFalse(material.directory.exists()); assertTrue(source.file.isFile)
    }

    @Test fun failedSecondCopyRemovesOnlyTheNewSnapshot() {
        val existing = prepare()
        val bad = attachment().copy(sha256 = "0".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { cache.prepare("SYNTHETIC", listOf(attachment(), bad)) }
        assertEquals(listOf(existing.directory), root.listFiles()!!.toList())
        assertTrue(existing.photos.all { it.isFile })
    }

    @Test fun privateMetadataCannotEnterTheShareCacheEvenWithAMatchingFingerprint() {
        val source = attachment()
        val bytes = source.file.readBytes()
        source.file.writeBytes(bytes.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 8, 69, 120, 105, 102, 0, 0) + bytes.copyOfRange(2, bytes.size))
        assertThrows(IllegalArgumentException::class.java) {
            cache.prepare("SYNTHETIC", listOf(source.copy(sha256 = PhotoDigest.sha256(source.file)), attachment()))
        }
        assertTrue(root.listFiles()!!.isEmpty()); assertTrue(source.file.isFile)
    }

    @Test fun rawOriginalFilenameCannotBeUsedAsAnApprovedSource() {
        val source = attachment()
        val original = temp.newFile("original.jpg"); source.file.copyTo(original, overwrite = true)
        assertThrows(IllegalArgumentException::class.java) { cache.prepare("SYNTHETIC", listOf(source.copy(file = original), attachment())) }
        assertTrue(original.isFile); assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun hiddenPostScanAndAppDataAreBlockedEvenWithMatchingHashes() {
        val existing = prepare()
        for (afterScan in listOf(false, true)) {
            val source = attachment()
            val bytes = source.file.readBytes()
            val metadata = byteArrayOf(0xff.toByte(), 0xe3.toByte(), 0, 8) + "SECRET".toByteArray()
            source.file.writeBytes(if (afterScan) bytes.dropLast(2).toByteArray() + metadata + bytes.takeLast(2).toByteArray()
                else bytes.take(2).toByteArray() + metadata + bytes.drop(2).toByteArray())
            assertThrows(IllegalArgumentException::class.java) {
                cache.prepare("SYNTHETIC", listOf(attachment(), source.copy(sha256 = PhotoDigest.sha256(source.file))))
            }
            assertEquals(listOf(existing.directory), root.listFiles()!!.toList())
            assertTrue(source.file.isFile)
            assertTrue(existing.photos.all { it.isFile })
        }
    }

    @Test fun cleanupRetainsRecentSharesUntilTheDocumentedDeadline() {
        val old = prepare()
        now += ReviewedShareCache.RETENTION_MILLIS - 1
        val recent = prepare()
        assertTrue(old.directory.isDirectory)
        now++
        cache.pruneExpired()
        assertFalse(old.directory.exists()); assertTrue(recent.photos.all { it.isFile })
    }

    @Test fun backwardWallClockAndUnrelatedCacheFilesArePreserved() {
        val material = prepare()
        val unrelated = File(root, "unrelated-cache").apply { mkdir(); setLastModified(1L) }
        now--
        cache.pruneExpired()
        assertTrue(material.directory.isDirectory); assertTrue(unrelated.isDirectory)
    }

    @Test fun forgedCleanupCannotRemoveAnOutsideDirectory() {
        val unrelated = temp.newFolder(UUID.randomUUID().toString())
        assertThrows(IllegalArgumentException::class.java) { cache.discard(SharedMaterial(unrelated, "", emptyList())) }
        assertTrue(unrelated.isDirectory)
    }
}
