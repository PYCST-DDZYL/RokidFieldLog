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
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO

/** Real library/import/export integration, using only synthetic host-generated images and locations. */
class ReportMaterialsTest {
    @get:Rule val temp = TemporaryFolder(File("build/report-tests").apply { mkdirs() })
    private val root by lazy { temp.newFolder("library") }
    private val clock = object : FieldClock { override fun utcMillis() = 123456789L; override fun elapsedNanos() = 1L }
    private val inspector = ImageInspector { file -> runCatching { ImageIO.read(file)?.let { it.width to it.height } }.getOrNull() }
    private val texts = object : AtomicTextFiles {
        override fun read(file: File): String {
            if (!file.exists() && File(file.path + ".bak").exists()) File(file.path + ".bak").renameTo(file)
            return file.readText(Charsets.UTF_8)
        }
        override fun write(file: File, text: String) {
            file.parentFile.mkdirs()
            val next = File(file.path + ".new"); next.writeText(text, Charsets.UTF_8)
            Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private val library: OfflineLibrary by lazy { OfflineLibrary(root, texts, inspector, clock, beforeDelete = { reports.requireUnlinked(it) }) }
    private val reports: ReportRepository by lazy { ReportRepository(root, texts, library, inspector) }
    private fun jpeg(file: File, color: Color) {
        val image = BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { this.color = color; fillRect(0, 0, 96, 64); this.color = Color.BLACK; fillRect(24, 16, 24, 16); dispose() }
        check(ImageIO.write(image, "jpg", file))
    }
    private fun imported(color: Color, synthetic: Boolean = true): LibraryEntry {
        val id = UUID.randomUUID().toString()
        val file = temp.newFile(id + ".jpg"); jpeg(file, color)
        val hash = PhotoDigest.sha256(file)
        val record = CaptureRecord.pending(id, null).copy(width = 96, height = 64, photoSha256 = hash,
            photoSha256Source = FingerprintSource.CAPTURE_SAVE,
            phoneLocation = LocationObservation(12.3, -45.6, provider = "SYNTHETIC_ONLY"))
        val document = CaptureRecordCodec.decode(CaptureRecordCodec.encode(RecordDocument(record)).dropLast(1) +
            ",\"privateHint\":\"PRIVATE_REPORT_SENTINEL\"}")
        val bytes = ByteArrayOutputStream().also { CapturePackage.write(it, listOf(IncomingCapture(document, file, hash)), synthetic) }.toByteArray()
        library.importPackage(ByteArrayInputStream(bytes))
        return library.load(id)
    }
    private fun review(entry: LibraryEntry): LibraryEntry {
        val file = entry.reviewFile("review-" + UUID.randomUUID() + ".jpg")
        val image = ImageIO.read(entry.original)
        image.createGraphics().apply { color = Color.BLACK; fillRect(0, 0, 20, 20); dispose() }
        check(ImageIO.write(image, "jpg", file))
        return library.approvePhoto(entry.draft.photoId, entry.draft.revision,
            PhotoReview(file.name, entry.importedSha256, PhotoDigest.sha256(file), file.length(), 96, 64,
                masks = listOf(MaskRect(0f, 0f, 0.21f, 0.32f))))
    }
    private fun complete(first: LibraryEntry, second: LibraryEntry): ReportDraft {
        val draft = reports.create(first.draft.photoId)
        return reports.save(draft.copy(recipient = "Synthetic site maintenance desk", category = "Synthetic receiver / pavement damage",
            address = "Synthetic fixture park, east entrance", landmark = "Fixture marker A-017", eventDate = "2020-01-02",
            description = "Synthetic surface defect. Please inspect and arrange repair.", locationConfirmed = true, sameIssueVisitConfirmed = true,
            photos = listOf(ReportPhoto(first.draft.photoId, ReportPhotoRole.CONTEXT), ReportPhoto(second.draft.photoId, ReportPhotoRole.DETAIL))), draft.revision)
    }
    private fun ready(): ReportDraft = complete(review(imported(Color.RED)), review(imported(Color.BLUE)))
    private fun unzip(file: File) = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(file.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()); zip.closeEntry() } }
    }
    @Test fun twoReviewedViewsExportWithOneSharedDescriptionAndWithoutPrivateSource() {
        val report = ready()
        val prepared = reports.prepare(report.reportId)
        reports.validatePrepared(prepared)
        val archive = unzip(prepared.file)
        assertEquals(setOf("report.txt", "summary.json", "photo-01.jpg", "photo-02.jpg"), archive.keys)
        val text = archive.getValue("report.txt").toString(Charsets.UTF_8)
        assertTrue(text.contains("合成演示")); assertTrue(text.contains(report.landmark)); assertTrue(text.contains("环境照")); assertTrue(text.contains("细节照"))
        val manifest = archive.getValue("summary.json").toString(Charsets.UTF_8)
        for (privateValue in listOf("PRIVATE_REPORT_SENTINEL", "phoneLocation", "-45.6", "receipt", "original.jpg", "review-")) assertFalse(privateValue, manifest.contains(privateValue))
        for (name in listOf("photo-01.jpg", "photo-02.jpg")) {
            val actual = ImageIO.read(ByteArrayInputStream(archive.getValue(name)))
            assertTrue(Color(actual.getRGB(4, 4)).red < 20)
        }
        assertTrue(reports.selected(report).values.all { it.draft.address.isEmpty() && it.draft.stage == SubmissionStage.DRAFT })
        System.getProperty("fieldlog.demoOutput")?.let { directory ->
            val target = File(directory, "multi-photo-materials").apply { check(isDirectory || mkdirs()) }
            prepared.file.copyTo(File(target, "SYNTHETIC-two-view-report.zip"), overwrite = true)
            archive.forEach { (name, bytes) -> File(target, name).writeBytes(bytes) }
        }
        reports.discard(prepared); assertFalse(prepared.file.exists())
    }
    @Test fun onePhotoHasActionableChecklistAndCannotExport() {
        val first = review(imported(Color.RED))
        val report = reports.create(first.draft.photoId)
        val reasons = ReportReadiness.reasons(report, reports.selected(report)).joinToString()
        for (word in listOf("接收", "分类", "地址", "参照物", "日期", "2–5", "环境照", "同一次")) assertTrue(word, reasons.contains(word))
        assertThrows(IllegalArgumentException::class.java) { reports.prepare(report.reportId) }
        assertFalse(File(root, "report-exports").exists())
    }
    @Test fun twoDetailPhotosDoNotSatisfyEnvironmentViewRequirement() {
        val report = ready()
        val changed = reports.save(report.copy(photos = report.photos.map { it.copy(role = ReportPhotoRole.DETAIL) }), report.revision)
        assertTrue(ReportReadiness.reasons(changed, reports.selected(changed)).any { it.contains("环境照") })
        assertThrows(IllegalArgumentException::class.java) { reports.prepare(changed.reportId) }
    }
    @Test fun everyPhotoNeedsIndependentPrivacyApproval() {
        val report = complete(review(imported(Color.RED)), imported(Color.BLUE))
        assertTrue(ReportReadiness.reasons(report, reports.selected(report)).any { it.contains("照片 2") && it.contains("隐私") })
        assertThrows(IllegalArgumentException::class.java) { reports.prepare(report.reportId) }
    }
    @Test fun duplicateImageBytesUnderDifferentIdsCannotCountAsTwoViews() {
        val report = complete(review(imported(Color.RED)), review(imported(Color.RED)))
        assertTrue(ReportReadiness.reasons(report, reports.selected(report)).any { it.contains("重复照片") })
        assertThrows(IllegalArgumentException::class.java) { reports.prepare(report.reportId) }
    }
    @Test fun alteredOriginalAndReviewedPhotoEachPreventExport() {
        val report = ready()
        val entry = reports.selected(report).values.first()
        val bytes = entry.original.readBytes()
        jpeg(entry.original, Color.GREEN)
        assertThrows(IllegalArgumentException::class.java) { reports.prepare(report.reportId) }
        entry.original.writeBytes(bytes)
        jpeg(entry.reviewFile(entry.draft.photoReview!!.fileName), Color.GREEN)
        assertThrows(IllegalArgumentException::class.java) { reports.prepare(report.reportId) }
    }
    @Test fun newMetadataInReviewedJpegCannotBeExportedEvenWithUpdatedHash() {
        val report = ready()
        val entry = reports.selected(report).values.first()
        val file = entry.reviewFile(entry.draft.photoReview!!.fileName)
        val bytes = file.readBytes()
        file.writeBytes(bytes.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 8) + "Exif!!".toByteArray() + bytes.drop(2).toByteArray())
        library.save(entry.draft.copy(photoReview = entry.draft.photoReview!!.copy(outputSha256 = PhotoDigest.sha256(file), byteCount = file.length())), entry.draft.revision)
        assertThrows(IllegalArgumentException::class.java) { reports.prepare(report.reportId) }
    }
    @Test fun changedReportWhileDocumentPickerIsOpenInvalidatesPreparedMaterial() {
        val report = ready(); val prepared = reports.prepare(report.reportId)
        reports.save(report.copy(address = "New synthetic fixture address"), report.revision)
        assertThrows(IllegalArgumentException::class.java) { reports.validatePrepared(prepared) }
    }
    @Test fun changedPhotoReviewWhileDocumentPickerIsOpenInvalidatesPreparedMaterial() {
        val report = ready(); val prepared = reports.prepare(report.reportId)
        review(reports.selected(report).values.first())
        assertThrows(IllegalArgumentException::class.java) { reports.validatePrepared(prepared) }
    }
    @Test fun changedOriginalAfterPreparationIsRecheckedBeforeExternalCopy() {
        val report = ready(); val prepared = reports.prepare(report.reportId)
        jpeg(reports.selected(report).values.first().original, Color.GREEN)
        assertThrows(IllegalArgumentException::class.java) { reports.validatePrepared(prepared) }
    }
    @Test fun changedTemporaryZipAndOutsidePathAreRejected() {
        val report = ready(); val prepared = reports.prepare(report.reportId)
        prepared.file.appendText("changed")
        assertThrows(IllegalArgumentException::class.java) { reports.validatePrepared(prepared) }
        val outside = temp.newFile("outside.zip")
        assertThrows(IllegalArgumentException::class.java) { reports.discard(prepared.copy(file = outside)) }
        assertTrue(outside.exists())
    }
    @Test fun mixedDemoAndRealPhotosAreRejectedAndDemoLabelCannotBeCleared() {
        val demo = imported(Color.RED); val real = imported(Color.BLUE, synthetic = false)
        val report = reports.create(demo.draft.photoId)
        assertThrows(IllegalArgumentException::class.java) { reports.save(report.copy(photos = report.photos + ReportPhoto(real.draft.photoId)), report.revision) }
        val empty = reports.save(report.copy(photos = emptyList(), isSynthetic = false), report.revision)
        assertEquals(true, empty.isSynthetic)
        assertThrows(IllegalArgumentException::class.java) { reports.save(empty.copy(photos = listOf(ReportPhoto(real.draft.photoId))), empty.revision) }
    }
    @Test fun staleEditorCannotOverwriteNewerMaterial() {
        val report = reports.create()
        val newer = reports.save(report.copy(recipient = "New synthetic receiver"), report.revision)
        assertThrows(IllegalArgumentException::class.java) { reports.save(report.copy(recipient = "Old"), report.revision) }
        assertEquals(newer, reports.load(report.reportId))
    }
    @Test fun reportReferencesProtectPhotosAndDeletingReportRetainsOriginals() {
        val report = ready(); val id = report.photos.first().photoId; val original = library.load(id).original
        assertThrows(IllegalArgumentException::class.java) { library.delete(id) }
        reports.delete(report.reportId, report.revision)
        assertTrue(original.isFile); assertEquals(2, library.list().size)
        library.delete(id); assertFalse(original.exists())
    }
    @Test fun corruptedAndFutureReportsStayOnDiskAndPreventUnsafePhotoDeletion() {
        val photo = imported(Color.RED)
        val report = reports.create(photo.draft.photoId)
        val file = File(root, "reports/" + report.reportId + ".json")
        for (bad in listOf("{broken", ReportCodec.encode(report).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            ReportCodec.encode(report).dropLast(1) + ",\"futureLinks\":[]}")) {
            file.writeText(bad)
            assertEquals(1, reports.scan().unreadableCount)
            assertThrows(IllegalStateException::class.java) { library.delete(photo.draft.photoId) }
            assertEquals(bad, file.readText()); assertTrue(photo.original.exists())
        }
    }
    @Test fun atomicBackupOnlyReportStillProtectsLinkedPhoto() {
        val photo = imported(Color.RED); val report = reports.create(photo.draft.photoId)
        val file = File(root, "reports/" + report.reportId + ".json")
        assertTrue(file.renameTo(File(file.path + ".bak")))
        assertThrows(IllegalArgumentException::class.java) { library.delete(photo.draft.photoId) }
        assertEquals(report, reports.load(report.reportId))
    }
    @Test fun invalidIdentityRolesAndTooManyPhotosCannotEnterStoredContract() {
        val report = reports.create()
        val invalid = listOf(report.copy(photos = listOf(ReportPhoto("../escape"))),
            report.copy(photos = List(6) { ReportPhoto(UUID.randomUUID().toString()) }),
            report.copy(photos = List(2) { ReportPhoto("00000000-0000-4000-8000-000000000001") }))
        for (draft in invalid) assertThrows(IllegalArgumentException::class.java) { ReportCodec.decode(ReportCodec.encode(draft), report.reportId) }
        assertThrows(IllegalArgumentException::class.java) { ReportCodec.decode(ReportCodec.encode(report).replace("\"revision\": 0", "\"revision\": \"0\""), report.reportId) }
    }
    @Test fun missingRecordAndUnconfirmedVisitCannotProduceMaterial() {
        val report = ready()
        val changed = reports.save(report.copy(sameIssueVisitConfirmed = false), report.revision)
        assertThrows(IllegalArgumentException::class.java) { reports.prepare(changed.reportId) }
        val selected = reports.selected(changed).toMutableMap().apply { remove(changed.photos.first().photoId) }
        assertTrue(ReportReadiness.reasons(changed, selected).any { it.contains("不存在") })
    }

    @Test fun knownPhotoDateConflictMustBeCorrectedBeforeGroupingOneVisit() {
        val report = ready()
        val entry = reports.selected(report).values.first()
        library.save(entry.draft.copy(eventDate = "2020-01-03"), entry.draft.revision)
        assertTrue(ReportReadiness.reasons(report, reports.selected(report)).any { it.contains("日期不同") })
        assertThrows(IllegalArgumentException::class.java) { reports.prepare(report.reportId) }
    }
    @Test fun newMaterialReusesExistingManualTextButRequiresReceiverAndLocationConfirmation() {
        val entry = imported(Color.RED)
        val edited = library.save(entry.draft.copy(address = "Synthetic existing address", description = "Synthetic existing description",
            eventDate = "2020-01-02", addressConfirmed = true, category = IssueCategory.ROAD_DAMAGE), entry.draft.revision)
        val report = reports.create(edited.draft.photoId)
        assertEquals(edited.draft.address, report.address); assertEquals(edited.draft.description, report.description)
        assertEquals(edited.draft.eventDate, report.eventDate)
        assertTrue(report.category.isBlank()); assertTrue(report.recipient.isBlank())
        assertFalse(report.locationConfirmed); assertFalse(report.sameIssueVisitConfirmed)
    }
}
