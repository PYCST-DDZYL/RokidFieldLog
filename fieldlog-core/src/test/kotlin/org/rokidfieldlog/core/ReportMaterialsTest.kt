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
    @Test fun importedBatchBuildsOneDraftAndDoesNotAssertPhotoRolesOrLocation() {
        val first = imported(Color.RED); val second = imported(Color.BLUE)
        val batch = reports.createBatch(listOf(first.draft.photoId, second.draft.photoId))
        assertEquals("GENERIC_MANUAL", batch.channelId)
        assertEquals(listOf(first.draft.photoId, second.draft.photoId), batch.photos.map { it.photoId })
        assertTrue(batch.photos.all { it.role == ReportPhotoRole.ADDITIONAL })
        assertFalse(batch.locationConfirmed); assertFalse(batch.sameIssueVisitConfirmed)
        assertNull(batch.photographerReference)
        assertThrows(IllegalArgumentException::class.java) { reports.createBatch(List(6) { first.draft.photoId }) }
        val real = imported(Color.GREEN, synthetic = false)
        assertThrows(IllegalArgumentException::class.java) { reports.createBatch(listOf(first.draft.photoId, real.draft.photoId)) }
    }
    @Test fun jiangsuChecklistAndExportSeparateOfficialFieldsAndOptionalReference() {
        val initial = ready()
        val incomplete = initial.copy(channelId = Jiangsu12345.ID)
        val missing = ReportReadiness.reasons(incomplete, reports.selected(initial)).joinToString()
        for (word in listOf("区县", "诉求目的", "联系人", "媒体", "须知")) assertTrue(word, missing.contains(word))
        val fix = PhotographerReference(12.4, -45.7, accuracyMeters = 20f, locationTimeUtcMillis = 123000, selectedAtUtcMillis = 124000, source = "SYNTHETIC_ONLY")
        val complete = reports.save(incomplete.copy(province = "江苏省", city = "合成市", district = "合成区", purpose = "请检查并修复合成问题",
            contactName = "合成联系人", contactPhone = "00000000000", mediaInterview = false, noticeAccepted = true,
            albumAcceptanceConfirmed = true, photographerReference = fix), initial.revision)
        assertTrue(ReportReadiness.reasons(complete, reports.selected(complete)).isEmpty())
        val zip = reports.prepare(complete.reportId)
        val archive = unzip(zip.file)
        val text = archive.getValue("report.txt").toString(Charsets.UTF_8)
        assertTrue(text.contains(fix.label())); assertTrue(text.contains("非问题位置"))
        assertTrue(text.contains("单个10 MB")); assertTrue(text.contains("最多5 MB"))
        val summary = com.google.gson.JsonParser.parseString(archive.getValue("summary.json").toString(Charsets.UTF_8)).asJsonObject
        assertEquals(12.4, summary["photographerReference"].asJsonObject["latitude"].asDouble, 0.0)
        assertFalse(summary.has("issuePosition")); assertEquals(complete, reports.load(complete.reportId))
        assertTrue(reports.selected(complete).values.all { it.draft.stage == SubmissionStage.DRAFT })
        assertThrows(IllegalArgumentException::class.java) { ReportCodec.decode(ReportCodec.encode(complete.copy(purpose = "字".repeat(101))), complete.reportId) }
    }
    @Test fun oldMaterialDefaultsRemainReadableAndInvalidReferencesAreRejected() {
        val draft = reports.create()
        val tree = com.google.gson.JsonParser.parseString(ReportCodec.encode(draft)).asJsonObject
        for (key in listOf("channelId", "province", "city", "district", "purpose", "contactName", "contactPhone", "mediaInterview", "noticeAccepted", "albumAcceptanceConfirmed", "photographerReference", "addressSuggestion", "handoff")) tree.remove(key)
        assertEquals(draft, ReportCodec.decode(tree.toString(), draft.reportId))
        val invalid = PhotographerReference(91.0, 0.0, locationTimeUtcMillis = 123, selectedAtUtcMillis = 124, source = "SYNTHETIC_ONLY")
        assertThrows(IllegalArgumentException::class.java) { reports.save(draft.copy(photographerReference = invalid), draft.revision) }
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
    private fun shareCache() = ReviewedShareCache(File(root, "test-share-cache"), inspector, clock)

    @Test fun nativeShareContainsOnlyIndependentReviewedPixelsAndPublicText() {
        val report = ready()
        val material = reports.prepareShare(report.reportId, shareCache())
        assertEquals(2, material.photos.size)
        assertEquals(setOf("photo-01.jpg", "photo-02.jpg"), material.directory.list()!!.toSet())
        assertTrue(material.text.contains("合成演示")); assertTrue(material.text.contains(report.landmark))
        for (privateValue in listOf("PRIVATE_REPORT_SENTINEL", "phoneLocation", "-45.6", "receipt", "original.jpg", "review-")) assertFalse(privateValue, material.text.contains(privateValue))
        report.photos.forEachIndexed { index, photo ->
            val entry = library.load(photo.photoId)
            assertEquals(entry.draft.photoReview!!.outputSha256, PhotoDigest.sha256(material.photos[index]))
            assertTrue(Color(ImageIO.read(material.photos[index]).getRGB(4, 4)).red < 20)
            assertEquals(SubmissionStage.DRAFT, entry.draft.stage)
            assertTrue(entry.original.isFile)
        }
        assertEquals(report, reports.load(report.reportId))
        assertEquals(material.text, reports.submissionText(report.reportId))
    }

    @Test fun nativeShareRejectsAnyUnreviewedPhotoBeforeCreatingACache() {
        val report = complete(review(imported(Color.RED)), imported(Color.BLUE))
        assertThrows(IllegalArgumentException::class.java) { reports.prepareShare(report.reportId, shareCache()) }
        assertFalse(File(root, "test-share-cache").exists())
    }

    @Test fun nativeShareRechecksBothOriginalAndApprovedBytes() {
        val report = ready()
        val entry = reports.selected(report).values.first()
        val original = entry.original.readBytes()
        jpeg(entry.original, Color.GREEN)
        assertThrows(IllegalArgumentException::class.java) { reports.prepareShare(report.reportId, shareCache()) }
        entry.original.writeBytes(original)
        jpeg(entry.reviewFile(entry.draft.photoReview!!.fileName), Color.GREEN)
        assertThrows(IllegalArgumentException::class.java) { reports.prepareShare(report.reportId, shareCache()) }
    }

    @Test fun copiedSubmissionTextUsesLatestSavedContentWithoutMarkingSubmission() {
        val report = ready()
        val updated = reports.save(report.copy(description = "SYNTHETIC revised recipient request"), report.revision)
        val text = reports.submissionText(report.reportId)
        assertTrue(text.contains(updated.description)); assertFalse(text.contains(report.description))
        assertTrue(text.contains("不表示已提交或受理"))
        assertEquals(updated, reports.load(report.reportId))
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

    @Test fun onsiteAddressSurvivesLaterPhotoImportAndBatchMerge() {
        val blank = reports.createOnSite()
        assertTrue(reports.load(blank.reportId).photos.isEmpty())
        assertEquals(false, blank.isSynthetic)
        assertEquals("GENERIC_MANUAL", blank.channelId)
        val fix = PhotographerReference(12.4, -45.7, accuracyMeters = 20f, locationTimeUtcMillis = 123000, selectedAtUtcMillis = 124000, source = "SYNTHETIC_ONLY")
        val onSite = reports.save(blank.copy(address = "Synthetic overseas park, east gate", landmark = "Marker A-017",
            eventDate = "2020-01-02", locationConfirmed = true, photographerReference = fix, addressSuggestion = "Synthetic system suggestion"), blank.revision)
        val first = imported(Color.RED, synthetic = false); val second = imported(Color.BLUE, synthetic = false)
        val merged = reports.attachBatch(onSite.reportId, onSite.revision, listOf(first.draft.photoId, second.draft.photoId))
        assertEquals(onSite.reportId, merged.reportId)
        assertEquals(onSite.address, merged.address); assertEquals(onSite.landmark, merged.landmark)
        assertEquals(onSite.addressSuggestion, merged.addressSuggestion); assertEquals(fix, merged.photographerReference)
        assertTrue(merged.locationConfirmed); assertFalse(merged.sameIssueVisitConfirmed)
        assertTrue(merged.photos.all { it.role == ReportPhotoRole.ADDITIONAL })
        assertTrue(reports.selected(merged).values.all { it.draft.photoReview == null })
        assertEquals(merged, reports.attachBatch(merged.reportId, merged.revision, listOf(first.draft.photoId, second.draft.photoId)))
        assertEquals(1, reports.scan().reports.size)
        assertThrows(IllegalArgumentException::class.java) { reports.attachBatch(merged.reportId, onSite.revision, listOf(first.draft.photoId)) }
    }
    @Test fun attachingBatchRequiresConfirmedAddressAndNeverMixesDemoWithRealSite() {
        val blank = reports.createOnSite()
        val real = imported(Color.RED, synthetic = false)
        assertThrows(IllegalArgumentException::class.java) { reports.attachBatch(blank.reportId, blank.revision, listOf(real.draft.photoId)) }
        val confirmed = reports.save(blank.copy(address = "Synthetic address", landmark = "Synthetic landmark", locationConfirmed = true), blank.revision)
        val demo = imported(Color.BLUE)
        assertThrows(IllegalArgumentException::class.java) { reports.attachBatch(confirmed.reportId, confirmed.revision, listOf(demo.draft.photoId)) }
        assertEquals(confirmed, reports.load(confirmed.reportId))
        val ids = (1..6).map { imported(Color(it * 25, 50, 60), synthetic = false).draft.photoId }
        assertThrows(IllegalArgumentException::class.java) { reports.attachBatch(confirmed.reportId, confirmed.revision, ids) }
        assertEquals(confirmed, reports.load(confirmed.reportId))
    }
    @Test fun firstJiangsuHandoffDoesNotRequirePriorAcceptanceOfRedactedPhotos() {
        val initial = ready()
        val report = reports.save(initial.copy(channelId = Jiangsu12345.ID, province = "江苏省", city = "合成市", district = "合成区",
            purpose = "合成目的，仅测试", contactName = "合成联系人", contactPhone = "00000000000", mediaInterview = false,
            noticeAccepted = true, albumAcceptanceConfirmed = false,
            handoff = ReportHandoff(receipt = "PRIVATE_RECEIPT_123", attemptedOn = "2020-01-03", extraRequirements = "PRIVATE_EXTRA",
                rejectedPhotos = "PRIVATE_REJECTED", elapsedMinutes = "17", albumResult = "UNKNOWN")), initial.revision)
        assertTrue(ReportReadiness.reasons(report, reports.selected(report)).isEmpty())
        val text = reports.submissionText(report.reportId)
        assertFalse(text.contains("已人工确认接收方接受"))
        assertTrue(text.indexOf("诉求目的：") < text.indexOf("详细地址："))
        assertTrue(text.indexOf("详细地址：") < text.indexOf("正文："))
        assertEquals("[合成演示，不得真实提交]\n" + report.purpose, reports.submissionPart(report.reportId, ReportTextPart.PURPOSE))
        assertEquals("[合成演示，不得真实提交]\n" + ReportPaste.address(report), reports.submissionPart(report.reportId, ReportTextPart.ADDRESS))
        val archive = unzip(reports.prepare(report.reportId).file)
        val outgoing = text + archive.getValue("summary.json").toString(Charsets.UTF_8)
        for (value in listOf("PRIVATE_RECEIPT_123", "PRIVATE_EXTRA", "PRIVATE_REJECTED", "albumAcceptanceConfirmed", "elapsedMinutes")) assertFalse(value, outgoing.contains(value))
        assertEquals(report.handoff, reports.load(report.reportId).handoff)
        assertTrue(reports.selected(report).values.all { it.draft.stage == SubmissionStage.DRAFT })
        val tooLong = report.copy(address = "字".repeat(180), landmark = "字".repeat(30))
        assertTrue(ReportReadiness.reasons(tooLong, reports.selected(report)).any { it.contains("合计超过") })
    }
    @Test fun invalidPrivateAttemptNotesDoNotEnterStoredContract() {
        val draft = reports.createOnSite()
        for (handoff in listOf(ReportHandoff(attemptedOn = "yesterday"), ReportHandoff(elapsedMinutes = "0"),
            ReportHandoff(albumResult = "ASSUMED_ACCEPTED"), ReportHandoff(inspectionIssueId = "../bad"))) {
            assertThrows(IllegalArgumentException::class.java) { reports.save(draft.copy(handoff = handoff), draft.revision) }
        }
    }

    @Test fun incompleteReportReferenceIsPreservedAndBlockedFromExport() {
        val report = ready()
        val reference = PhotographerReference(12.3, -45.6, locationTimeUtcMillis = 123000,
            selectedAtUtcMillis = 124000, source = "SYNTHETIC_TEST")
        val saved = reports.save(report.copy(photographerReference = reference), report.revision)
        val file = File(root, "reports/" + report.reportId + ".json")
        val tree = com.google.gson.JsonParser.parseString(ReportCodec.encode(saved)).asJsonObject
        tree.getAsJsonObject("photographerReference").remove("latitude")
        val broken = tree.toString().toByteArray(Charsets.UTF_8)
        file.writeBytes(broken)

        assertEquals(1, reports.scan().unreadableCount)
        assertThrows(IllegalArgumentException::class.java) { reports.prepare(report.reportId) }
        assertThrows(IllegalArgumentException::class.java) { reports.submissionText(report.reportId) }
        assertArrayEquals(broken, file.readBytes())
        assertFalse(File(root, "report-exports").exists())
    }

    @Test fun incompletePhotoDraftReferenceDoesNotOverwritePhotoOrOtherRecords() {
        val first = imported(Color.RED); val other = imported(Color.BLUE)
        val reference = PhotographerReference(12.3, -45.6, locationTimeUtcMillis = 123000,
            selectedAtUtcMillis = 124000, source = "SYNTHETIC_TEST")
        val saved = library.save(first.draft.copy(photographerReference = reference), first.draft.revision)
        val file = File(saved.directory, "draft.json")
        val tree = com.google.gson.JsonParser.parseString(DraftCodec.encode(saved.draft)).asJsonObject
        tree.getAsJsonObject("photographerReference").remove("longitude")
        val broken = tree.toString().toByteArray(Charsets.UTF_8)
        file.writeBytes(broken)

        val listing = library.scan()
        assertEquals(1, listing.unreadableCount)
        assertEquals(listOf(other.draft.photoId), listing.entries.map { it.draft.photoId })
        assertThrows(IllegalArgumentException::class.java) { library.load(first.draft.photoId) }
        assertArrayEquals(broken, file.readBytes())
        assertEquals(first.importedSha256, PhotoDigest.sha256(first.original))
        assertEquals(other, library.load(other.draft.photoId))
    }
}
