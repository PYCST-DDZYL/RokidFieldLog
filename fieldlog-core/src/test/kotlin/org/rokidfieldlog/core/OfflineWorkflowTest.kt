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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Host-only synthetic image fixtures; no camera, GPS or phone success is inferred. */
class OfflineWorkflowTest {
    @get:Rule val temp = TemporaryFolder(File("build/offline-tests").apply { mkdirs() })
    private val id = "00000000-0000-4000-8000-000000000071"
    private val clock = object : FieldClock {
        override fun utcMillis() = 123456789L
        override fun elapsedNanos() = 999999L
    }
    private val inspector = ImageInspector { file -> runCatching { ImageIO.read(file)?.let { it.width to it.height } }.getOrNull() }
    private val texts = object : AtomicTextFiles {
        override fun read(file: File) = file.readText(Charsets.UTF_8)
        override fun write(file: File, text: String) {
            file.parentFile.mkdirs()
            val pending = File(file.path + ".new")
            pending.writeText(text, Charsets.UTF_8)
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private val library by lazy { OfflineLibrary(temp.newFolder("library"), texts, inspector, clock) }
    private fun jpeg(file: File, color: Color = Color.RED) {
        val image = BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { this.color = color; fillRect(0, 0, 80, 60); dispose() }
        check(ImageIO.write(image, "jpg", file))
    }
    private fun capture(color: Color = Color.RED): IncomingCapture {
        val file = temp.newFile(UUID.randomUUID().toString() + ".jpg")
        jpeg(file, color)
        val hash = PhotoDigest.sha256(file)
        val record = CaptureRecord.pending(id, null).copy(width = 80, height = 60,
            photoSha256 = hash, photoSha256Source = FingerprintSource.CAPTURE_SAVE,
            phoneLocation = LocationObservation(12.3, -45.6, provider = "SYNTHETIC_ONLY"))
        val document = CaptureRecordCodec.decode(CaptureRecordCodec.encode(RecordDocument(record))
            .dropLast(1) + ",\"privateDeviceHint\":\"PRIVATE_SOURCE_SENTINEL\"}")
        return IncomingCapture(document, file, hash)
    }
    private fun bundle(capture: IncomingCapture = capture()): ByteArray = ByteArrayOutputStream().also {
        CapturePackage.write(it, listOf(capture), isSynthetic = true)
    }.toByteArray()
    private fun imported(): LibraryEntry { library.importPackage(ByteArrayInputStream(bundle())); return library.load(id) }
    private fun completedDraft(entry: LibraryEntry): LibraryEntry = library.save(entry.draft.copy(
        category = IssueCategory.ROAD_DAMAGE, address = "Synthetic test district, fixture road",
        addressConfirmed = true, description = "Synthetic fixture: road damage for offline workflow test.", eventDate = "2020-01-02",
    ), entry.draft.revision)
    private fun reviewed(entry: LibraryEntry): LibraryEntry {
        val masks = listOf(MaskRect(0.25f, 0.25f, 0.75f, 0.75f))
        val target = entry.reviewFile("review-" + UUID.randomUUID() + ".jpg")
        val image = ImageIO.read(entry.original)
        image.createGraphics().apply {
            color = Color.BLACK
            val box = masks.single().pixels(image.width, image.height)
            fillRect(box.left, box.top, box.right - box.left, box.bottom - box.top); dispose()
        }
        check(ImageIO.write(image, "jpg", target))
        val review = PhotoReview(target.name, PhotoDigest.sha256(entry.original), PhotoDigest.sha256(target),
            target.length(), image.width, image.height, masks)
        return library.approvePhoto(id, entry.draft.revision, review)
    }
    private fun zipEntries(file: File): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()); zip.closeEntry() }
        }
    }
    private fun customZip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> for ((name, data) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
    }.toByteArray()

    @Test fun syntheticImportEditRedactExportAndReceiptCompleteWithoutPhone() {
        val entry = reviewed(completedDraft(imported()))
        val prepared = library.prepareMaterial(id)
        val entries = zipEntries(prepared.file)
        System.getProperty("fieldlog.demoOutput")?.takeIf { it.isNotBlank() }?.let { path ->
            val output = File(path).apply { check(isDirectory || mkdirs()) }
            prepared.file.copyTo(File(output, "SYNTHETIC-material.zip"), overwrite = true)
            for ((name, bytes) in entries) File(output, name).writeBytes(bytes)
            File(output, "SYNTHETIC-capture-package.zip").writeBytes(bundle())
        }
        assertEquals(setOf("report.txt", "summary.json", "photo-reviewed.jpg"), entries.keys)
        assertTrue(entries.getValue("report.txt").toString(Charsets.UTF_8).contains("合成演示"))
        val summary = entries.getValue("summary.json").toString(Charsets.UTF_8)
        assertFalse(summary.contains("PRIVATE_SOURCE_SENTINEL"))
        assertFalse(summary.contains("phoneLocation"))
        assertFalse(summary.contains("-45.6"))
        assertFalse(summary.contains("receipt"))
        val actualPhoto = ImageIO.read(ByteArrayInputStream(entries.getValue("photo-reviewed.jpg")))
        assertTrue(Color(actualPhoto.getRGB(40, 30)).red < 20)
        assertTrue(Color(actualPhoto.getRGB(2, 2)).red > 200)
        assertEquals(entry.draft.photoReview!!.outputSha256, PhotoDigest.sha256(entry.reviewFile(entry.draft.photoReview!!.fileName)))
        assertEquals(SubmissionStage.DRAFT, library.load(id).draft.stage)
        library.markExported(id, prepared.revision)
        assertEquals(SubmissionStage.EXPORTED, library.load(id).draft.stage)
        val exported = library.load(id)
        val submitted = library.updateReceipt(id, exported.draft.revision, SubmissionStage.SUBMITTED, "SYNTHETIC-RECEIPT")
        assertEquals(SubmissionStage.SUBMITTED, submitted.draft.stage)
        assertNull(submitted.source.record.captureRequestedAtUtcMillis)
    }

    @Test fun repeatedImportPreservesEditsAndUnknownSourceAnnotations() {
        val bytes = bundle()
        assertEquals(ImportReport(1, 0), library.importPackage(ByteArrayInputStream(bytes)))
        completedDraft(library.load(id))
        assertEquals(ImportReport(0, 1), library.importPackage(ByteArrayInputStream(bytes)))
        assertTrue(library.load(id).draft.addressConfirmed)
        assertTrue(CaptureRecordCodec.encode(library.load(id).source).contains("PRIVATE_SOURCE_SENTINEL"))
    }

    @Test fun conflictingIdDoesNotOverwriteExistingPhotoOrDraft() {
        val entry = completedDraft(imported())
        val original = entry.original.readBytes()
        assertThrows(IllegalArgumentException::class.java) { library.importPackage(ByteArrayInputStream(bundle(capture(Color.BLUE)))) }
        assertArrayEquals(original, library.load(id).original.readBytes())
        assertEquals(entry.draft, library.load(id).draft)
        assertEquals(1, library.list().size)
    }

    @Test fun unsafeZipPathsAreRejectedBeforeAnyRecordIsCommitted() {
        for (name in listOf("../outside.txt", "/absolute.jpg", "photos/../../outside.jpg", "photos\\outside.jpg")) {
            assertThrows(IllegalArgumentException::class.java) {
                library.importPackage(ByteArrayInputStream(customZip(listOf(name to byteArrayOf(1)))))
            }
            assertTrue(library.list().isEmpty())
        }
    }

    @Test fun unmatchedPhotoAndMissingManifestAreRejected() {
        val photo = capture().photo.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            library.importPackage(ByteArrayInputStream(customZip(listOf("photos/$id.jpg" to photo))))
        }
        assertTrue(library.list().isEmpty())
    }

    @Test fun oversizedExpandedMetadataIsRejectedEvenWhenZipCompressesItWell() {
        val bytes = customZip(listOf("fieldlog-package.json" to ByteArray((CapturePackage.MAX_JSON_BYTES + 1).toInt()) { 32 }))
        assertTrue(bytes.size < 10_000)
        assertThrows(IllegalArgumentException::class.java) { library.importPackage(ByteArrayInputStream(bytes)) }
        assertTrue(library.list().isEmpty())
    }

    @Test fun changedFingerprintInPackagePreventsAllImport() {
        val original = capture()
        val changed = original.copy(document = original.document.copy(record = original.document.record.copy(photoSha256 = "a".repeat(64))))
        assertThrows(IllegalArgumentException::class.java) { library.importPackage(ByteArrayInputStream(bundle(changed))) }
        assertTrue(library.list().isEmpty())
    }

    @Test fun exportRequiresAddressDateCategoryAndActualPhotoReview() {
        val entry = imported()
        assertTrue(MaterialReadiness.reasons(entry.draft).size >= 4)
        assertThrows(IllegalStateException::class.java) { library.prepareMaterial(id) }
        val complete = completedDraft(entry)
        assertEquals(listOf("请完成照片隐私审阅"), MaterialReadiness.reasons(complete.draft))
    }

    @Test fun changedOriginalBeforeFirstReviewCannotEstablishANewBaseline() {
        val entry = completedDraft(imported())
        jpeg(entry.original, Color.BLUE)
        assertThrows(IllegalArgumentException::class.java) { reviewed(entry) }
        assertNull(library.load(id).draft.photoReview)
        assertNotEquals(entry.importedSha256, PhotoDigest.sha256(entry.original))
    }

    @Test fun changingOriginalAfterReviewPreventsExport() {
        val entry = reviewed(completedDraft(imported()))
        jpeg(entry.original, Color.BLUE)
        assertThrows(IllegalArgumentException::class.java) { library.prepareMaterial(id) }
    }

    @Test fun changingReviewedBytesAfterApprovalPreventsExport() {
        val entry = reviewed(completedDraft(imported()))
        jpeg(entry.reviewFile(entry.draft.photoReview!!.fileName), Color.GREEN)
        assertThrows(IllegalArgumentException::class.java) { library.prepareMaterial(id) }
    }

    @Test fun exifAndCommentsAreDetectedInOutputJpegHeaders() {
        val original = capture().photo
        assertFalse(JpegPrivacy.hasPrivateMetadata(original))
        val source = original.readBytes()
        val target = temp.newFile("metadata.jpg")
        target.writeBytes(source.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 8) + "Exif!!".toByteArray() + source.drop(2).toByteArray())
        assertTrue(JpegPrivacy.hasPrivateMetadata(target))
    }

    @Test fun staleEditorCannotOverwriteNewerDraftOrRemoveSyntheticLabel() {
        val entry = imported()
        val current = library.save(entry.draft.copy(isSynthetic = false, description = "new draft"), entry.draft.revision)
        assertTrue(current.draft.isSynthetic)
        assertThrows(IllegalArgumentException::class.java) { library.save(entry.draft.copy(description = "stale draft"), entry.draft.revision) }
        assertEquals("new draft", library.load(id).draft.description)
    }

    @Test fun exportCompletionDoesNotMarkNewerUnsavedSnapshotAsExported() {
        val entry = reviewed(completedDraft(imported()))
        val prepared = library.prepareMaterial(id)
        library.save(entry.draft.copy(description = "changed after preparing export"), entry.draft.revision)
        library.markExported(id, prepared.revision)
        assertEquals(SubmissionStage.DRAFT, library.load(id).draft.stage)
    }

    @Test fun receiptsRequireExplicitStageAndNonemptyText() {
        val entry = imported()
        assertThrows(IllegalArgumentException::class.java) { library.updateReceipt(id, entry.draft.revision, SubmissionStage.SUBMITTED, " ") }
        assertEquals(SubmissionStage.DRAFT, library.load(id).draft.stage)
    }

    @Test fun damagedDraftDoesNotHideHealthyRecordsOrPermitReimportOverwrite() {
        val entry = imported()
        val healthyId = "00000000-0000-4000-8000-000000000072"
        val second = capture().let { capture -> capture.copy(document = capture.document.copy(
            record = capture.document.record.copy(photoId = healthyId, photoFile = "photos/$healthyId.jpg"))) }
        library.importPackage(ByteArrayInputStream(bundle(second)))
        File(entry.directory, "draft.json").writeText("{broken")
        assertEquals(1, library.scan().unreadableCount)
        assertEquals(listOf(healthyId), library.list().map { it.draft.photoId })
        assertThrows(IllegalArgumentException::class.java) { library.importPackage(ByteArrayInputStream(bundle())) }
        assertEquals("{broken", File(entry.directory, "draft.json").readText())
        assertTrue(entry.original.isFile)
    }

    @Test fun changingReportAfterExportReturnsToDraftAndRetainsReceiptHistory() {
        val entry = reviewed(completedDraft(imported()))
        library.markExported(id, entry.draft.revision)
        val exported = library.load(id)
        val submitted = library.updateReceipt(id, exported.draft.revision, SubmissionStage.SUBMITTED, "SYNTHETIC-OLD-RECEIPT")
        val edited = library.save(submitted.draft.copy(address = "Updated synthetic address"), submitted.draft.revision)
        assertEquals(SubmissionStage.DRAFT, edited.draft.stage)
        assertEquals("SYNTHETIC-OLD-RECEIPT", edited.draft.receipt)
        assertNotNull(edited.draft.photoReview)
    }

    @Test fun reviewWithoutMasksRequiresExplicitNoThirdPartyDeclaration() {
        val entry = imported()
        val file = entry.reviewFile("review-" + UUID.randomUUID() + ".jpg")
        entry.original.copyTo(file)
        val review = PhotoReview(file.name, PhotoDigest.sha256(entry.original), PhotoDigest.sha256(file), file.length(), 80, 60)
        assertThrows(IllegalArgumentException::class.java) { library.approvePhoto(id, entry.draft.revision, review) }
        assertNull(library.load(id).draft.photoReview)
        assertNotNull(library.approvePhoto(id, entry.draft.revision, review.copy(noThirdPartyInformation = true)).draft.photoReview)
    }

    @Test fun normalizedMasksCoverBoundaryPixelsAndRejectInvalidRegions() {
        assertEquals(PixelBounds(0, 0, 3, 3), MaskRect(0.01f, 0.01f, 0.21f, 0.21f).pixels(10, 10))
        assertThrows(IllegalArgumentException::class.java) { MaskRect(Float.NaN, 0f, 1f, 1f).validate() }
        assertThrows(IllegalArgumentException::class.java) { MaskRect(0.8f, 0f, 0.2f, 1f).validate() }
    }
}
