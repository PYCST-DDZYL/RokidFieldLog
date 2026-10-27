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
import java.util.UUID
import javax.imageio.ImageIO

class LibraryHealthCheckTest {
    @get:Rule val temp = TemporaryFolder(File("build/health-tests").apply { mkdirs() })
    private val root by lazy { temp.newFolder("library") }
    private val texts = object : AtomicTextFiles {
        override fun read(file: File) = file.readText(Charsets.UTF_8)
        override fun write(file: File, text: String) { file.parentFile.mkdirs(); file.writeText(text, Charsets.UTF_8) }
    }
    private val inspector = ImageInspector { file -> ImageIO.read(file)?.let { it.width to it.height } }
    private val clock = object : FieldClock { override fun utcMillis() = 123456789L; override fun elapsedNanos() = 1L }
    private val library by lazy { OfflineLibrary(root, texts, inspector, clock) }
    private fun jpeg(color: Color): ByteArray {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { this.color = color; fillRect(0, 0, 40, 30); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }
    private fun add(color: Color = Color.RED): LibraryEntry {
        val bytes = jpeg(color)
        val id = library.importPhotos(listOf({ ByteArrayInputStream(bytes) })).photoIds.single()
        return library.load(id)
    }
    private fun approve(entry: LibraryEntry): LibraryEntry {
        val file = entry.reviewFile("review-${UUID.randomUUID()}.jpg")
        entry.original.copyTo(file)
        return library.approvePhoto(entry.draft.photoId, entry.draft.revision, PhotoReview(file.name,
            entry.importedSha256, PhotoDigest.sha256(file), file.length(), 40, 30, emptyList(), noThirdPartyInformation = true))
    }
    private fun snapshot() = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to PhotoDigest.sha256(it) }
    private fun audit() = LibraryHealthCheck(library, inspector).check()

    @Test fun emptyLibraryDoesNotClaimPhotosWereVerified() {
        val report = audit()
        assertEquals(0, report.checkedRecords); assertTrue(report.issues.isEmpty())
        assertTrue(report.summary().contains("暂无"))
    }
    @Test fun healthyUnreviewedAndReviewedPhotosRemainUnchanged() {
        add(); approve(add(Color.BLUE))
        val before = snapshot()
        val report = audit()
        assertEquals(2, report.checkedRecords); assertEquals(1, report.unreviewedPhotos)
        assertEquals(0, report.unreadableRecords); assertTrue(report.issues.isEmpty())
        assertEquals(before, snapshot())
    }
    @Test fun missingAndModifiedOriginalsDoNotHideHealthyRecordsOrRewriteMetadata() {
        val missing = add(); val modified = add(Color.BLUE); add(Color.GREEN)
        assertTrue(missing.original.delete()); modified.original.appendBytes(byteArrayOf(1))
        val before = snapshot()
        val report = audit()
        assertEquals(3, report.checkedRecords); assertEquals(2, report.affectedPhotos)
        assertEquals(setOf(PhotoHealthProblem.ORIGINAL_MISSING, PhotoHealthProblem.ORIGINAL_CHANGED), report.issues.map { it.problem }.toSet())
        assertEquals(before, snapshot())
    }
    @Test fun missingAndModifiedApprovedCopiesAreReportedWithoutDeletingOriginals() {
        val missing = approve(add()); val changed = approve(add(Color.BLUE))
        missing.reviewFile(missing.draft.photoReview!!.fileName).delete()
        changed.reviewFile(changed.draft.photoReview!!.fileName).appendBytes(byteArrayOf(1))
        val before = snapshot()
        assertEquals(setOf(PhotoHealthProblem.REVIEW_MISSING, PhotoHealthProblem.REVIEW_CHANGED), audit().issues.map { it.problem }.toSet())
        assertEquals(before, snapshot()); assertTrue(missing.original.isFile); assertTrue(changed.original.isFile)
    }
    @Test fun privateMetadataIsDetectedEvenWhenReviewedHashesMatch() {
        val entry = approve(add()); val review = entry.draft.photoReview!!; val file = entry.reviewFile(review.fileName)
        val bytes = file.readBytes()
        file.writeBytes(bytes.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xe3.toByte(), 0, 8) +
            "SECRET".toByteArray() + bytes.drop(2).toByteArray())
        texts.write(File(entry.directory, "draft.json"), DraftCodec.encode(entry.draft.copy(photoReview =
            review.copy(outputSha256 = PhotoDigest.sha256(file), byteCount = file.length()))))
        val before = snapshot()
        assertEquals(PhotoHealthProblem.REVIEW_UNSAFE, audit().issues.single().problem)
        assertEquals(before, snapshot())
    }
    @Test fun unreadableDraftIsCountedWhileOtherRecordsContinue() {
        val broken = add(); add(Color.BLUE)
        File(broken.directory, "draft.json").writeText("{SYNTHETIC_BROKEN")
        val before = snapshot(); val report = audit()
        assertEquals(1, report.checkedRecords); assertEquals(1, report.unreadableRecords)
        assertEquals(before, snapshot())
    }
    @Test fun decoderFailureIsIsolatedAndStaleReviewBindingIsReported() {
        val entry = approve(add()); val review = entry.draft.photoReview!!
        texts.write(File(entry.directory, "draft.json"), DraftCodec.encode(entry.draft.copy(photoReview = review.copy(originalSha256 = "a".repeat(64)))))
        val before = snapshot()
        val report = LibraryHealthCheck(library, ImageInspector { throw java.io.IOException("SYNTHETIC decoder failure") }).check()
        assertEquals(setOf(PhotoHealthProblem.ORIGINAL_UNREADABLE, PhotoHealthProblem.REVIEW_STALE), report.issues.map { it.problem }.toSet())
        assertEquals(before, snapshot())
    }
}
