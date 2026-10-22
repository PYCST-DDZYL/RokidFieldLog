package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.ImageWriteParam

class FreshJpegTest {
    @get:Rule val temp = TemporaryFolder(File("build/fresh-jpeg-tests").apply { mkdirs() })
    private fun image() = BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply {
            color = Color.GREEN; fillRect(0, 0, 96, 64)
            color = Color.BLACK; fillRect(55, 32, 40, 16); dispose()
        }
    }
    private fun jpeg() = temp.newFile().also { check(ImageIO.write(image(), "jpg", it)) }
    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val size = payload.size + 2
        return byteArrayOf(0xff.toByte(), marker.toByte(), (size shr 8).toByte(), size.toByte()) + payload
    }
    @Test fun removesEncoderIccExifIptcAndCommentsWithoutChangingPixelsOrSource() {
        val clean = jpeg()
        val metadata = listOf(0xe1, 0xe2, 0xed, 0xfe, 0xe3).fold(ByteArray(0)) { bytes, marker ->
            bytes + segment(marker, "SYNTHETIC_ENCODER_METADATA".toByteArray())
        }
        val source = temp.newFile()
        source.writeBytes(clean.readBytes().let { it.take(2).toByteArray() + metadata + it.drop(2).toByteArray() })
        val originalHash = PhotoDigest.sha256(source)
        assertTrue(JpegPrivacy.hasPrivateMetadata(source))
        val target = temp.newFile()
        val removed = target.outputStream().use { FreshJpeg.copyWithoutMetadata(source, it) }
        assertEquals(setOf(0xe1, 0xe2, 0xed, 0xfe, 0xe3), removed)
        assertFalse(JpegPrivacy.hasPrivateMetadata(target))
        assertArrayEquals(clean.readBytes(), target.readBytes())
        assertEquals(originalHash, PhotoDigest.sha256(source))
        val before = ImageIO.read(clean); val after = ImageIO.read(target)
        assertEquals(before.width, after.width); assertEquals(before.height, after.height)
        assertArrayEquals(before.getRGB(0, 0, 96, 64, null, 0, 96), after.getRGB(0, 0, 96, 64, null, 0, 96))
    }
    @Test fun cleanEncoderOutputIsByteIdentical() {
        val source = jpeg(); val output = ByteArrayOutputStream()
        assertTrue(FreshJpeg.copyWithoutMetadata(source, output).isEmpty())
        assertArrayEquals(source.readBytes(), output.toByteArray())
    }
    @Test fun largestHeaderSegmentIsRemovedWithoutTruncatingImage() {
        val source = jpeg(); val clean = source.readBytes()
        source.writeBytes(clean.take(2).toByteArray() + segment(0xe2, ByteArray(65533) { 65 }) + clean.drop(2).toByteArray())
        val output = ByteArrayOutputStream()
        assertEquals(setOf(0xe2), FreshJpeg.copyWithoutMetadata(source, output))
        assertArrayEquals(clean, output.toByteArray())
    }
    @Test fun invalidLengthsMissingScanAndTruncatedEntropyAreRejected() {
        val valid = jpeg().readBytes()
        for (bytes in listOf(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()),
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe2.toByte(), 0xff.toByte(), 0xff.toByte()), valid.dropLast(2).toByteArray())) {
            val source = temp.newFile(); source.writeBytes(bytes)
            assertThrows(Exception::class.java) { FreshJpeg.copyWithoutMetadata(source, ByteArrayOutputStream()) }
        }
    }
    @Test fun postScanMetadataAndBytesAfterEndAreRejected() {
        val clean = jpeg().readBytes()
        for (bytes in listOf(clean.dropLast(2).toByteArray() + segment(0xfe, "SYNTHETIC_POST_SCAN".toByteArray()) + clean.takeLast(2).toByteArray(), clean + byteArrayOf(65))) {
            val source = temp.newFile(); source.writeBytes(bytes)
            assertThrows(Exception::class.java) { FreshJpeg.copyWithoutMetadata(source, ByteArrayOutputStream()) }
        }
    }
    @Test fun progressiveOutputIsRejectedRatherThanCopyingUnexaminedLaterScans() {
        val source = temp.newFile()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(source).use { output ->
                writer.output = output
                val options = writer.defaultWriteParam.apply { progressiveMode = ImageWriteParam.MODE_DEFAULT }
                writer.write(null, IIOImage(image(), null, null), options)
            }
        } finally { writer.dispose() }
        assertNotNull(ImageIO.read(source))
        assertThrows(IllegalArgumentException::class.java) { FreshJpeg.copyWithoutMetadata(source, ByteArrayOutputStream()) }
    }

    @Test fun outgoingPrivacyCheckRejectsPostScanMetadataAndUnrecognizedAppSegments() {
        val clean = jpeg().readBytes()
        for (bytes in listOf(
            clean.dropLast(2).toByteArray() + segment(0xfe, "SYNTHETIC_POST_SCAN".toByteArray()) + clean.takeLast(2).toByteArray(),
            clean + "SYNTHETIC_TRAILING_SECRET".toByteArray() + clean.takeLast(2).toByteArray(),
            clean.take(2).toByteArray() + segment(0xe3, "SYNTHETIC_APP_SECRET".toByteArray()) + clean.drop(2).toByteArray(),
            clean.take(2).toByteArray() + segment(0xe0, "SYNTHETIC_THUMBNAIL".toByteArray()) + clean.drop(2).toByteArray())) {
            val source = temp.newFile(); source.writeBytes(bytes)
            val unsafe = runCatching { JpegPrivacy.hasPrivateMetadata(source) }.getOrElse { true }
            assertTrue("Outgoing JPEG must reject hidden or unexamined bytes", unsafe)
        }
    }

    @Test fun appZeroThumbnailsAreRemovedWithoutChangingTheReviewedPixels() {
        val clean = jpeg().readBytes()
        val jfifWithThumbnail = byteArrayOf(0x4a, 0x46, 0x49, 0x46, 0, 1, 2, 0, 0, 1, 0, 1, 1, 1, 1, 2, 3)
        val source = temp.newFile().apply {
            writeBytes(clean.take(2).toByteArray() + segment(0xe0, jfifWithThumbnail) + clean.drop(2).toByteArray())
        }
        val before = PhotoDigest.sha256(source)
        val target = temp.newFile()
        assertEquals(setOf(0xe0), target.outputStream().use { FreshJpeg.copyWithoutMetadata(source, it) })
        assertArrayEquals(clean, target.readBytes())
        assertEquals(before, PhotoDigest.sha256(source))
        assertFalse(JpegPrivacy.hasPrivateMetadata(target))
    }
}
