package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CaptureRepositoryTest {
    @get:Rule val temporary = TemporaryFolder(File("build/repository-tests").apply { mkdirs() })
    private val clock = object : FieldClock {
        override fun utcMillis() = 2345L
        override fun elapsedNanos() = 2_000_000_000L
    }
    // Synthetic bytes; only the Android adapter validates real JPEG pixel data.
    private val validBytes = byteArrayOf(1, 2, 3, 4)
    private val decodes = AtomicInteger()
    private val inspector = ImageInspector { file ->
        decodes.incrementAndGet()
        if (file.isFile && file.readBytes().firstOrNull() == 1.toByte()) 16 to 16 else null
    }
    private val root by lazy { temporary.newFolder("fieldlog") }
    private val storage by lazy { TestRecords(File(root, "records")) }
    private fun repository(state: CaptureStorageState = CaptureStorageState(), images: ImageInspector = inspector,
        free: () -> Long = { Long.MAX_VALUE }) = CaptureRepository(root, storage, images, clock, state, free)
    private fun stamp() = CaptureStamp(UUID.randomUUID().toString(), 1234L, 1_000_000_000L)
    private fun photo(id: String) = File(root, "photos/$id.jpg")
    private fun record(id: String) = CaptureRecordCodec.decode(storage.read(id)!!)
    private fun save(repo: CaptureRepository = repository()): CaptureStamp {
        val stamp = stamp()
        val pending = repo.begin(stamp)
        pending.writeBytes(validBytes)
        repo.finish(stamp, pending, IssueCategory.LITTER)
        return stamp
    }

    @Test fun glassesSnapshotUsesThePhonePackageReaderAndDetectsChangedOriginals() {
        val image = java.awt.image.BufferedImage(32, 24, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val decoder = ImageInspector { file -> runCatching { javax.imageio.ImageIO.read(file)?.let { it.width to it.height } }.getOrNull() }
        val repo = repository(images = decoder)
        val stamp = stamp()
        val pending = repo.begin(stamp)
        check(javax.imageio.ImageIO.write(image, "jpg", pending))
        repo.finish(stamp, pending, IssueCategory.LITTER)
        val bytes = java.io.ByteArrayOutputStream()
        assertEquals(1, repo.writePackage(bytes))
        val incoming = CapturePackage.read(java.io.ByteArrayInputStream(bytes.toByteArray()), temporary.newFolder("incoming"), decoder)
        assertEquals(stamp.photoId, incoming.captures.single().document.record.photoId)
        assertArrayEquals(photo(stamp.photoId).readBytes(), incoming.captures.single().photo.readBytes())
        val library = OfflineLibrary(temporary.newFolder("phone"), object : AtomicTextFiles {
            override fun read(file: File) = file.readText()
            override fun write(file: File, text: String) { file.parentFile.mkdirs(); file.writeText(text) }
        }, decoder, clock)
        val imported = library.importPackage(java.io.ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(listOf(stamp.photoId), imported.photoIds)
        assertEquals(imported.photoIds, library.importPackage(java.io.ByteArrayInputStream(bytes.toByteArray())).photoIds)
        assertNull(library.load(stamp.photoId).draft.photoReview)
        photo(stamp.photoId).appendBytes(byteArrayOf(0))
        assertThrows(IllegalArgumentException::class.java) { repo.writePackage(java.io.ByteArrayOutputStream()) }
    }

    @Test fun oversizedPhotoExportFailsWithReadableMessageAndKeepsOriginal() {
        val repo = repository(images = ImageInspector { _ -> 16 to 16 })
        val stamp = save(repo)
        val oversized = ByteArray((CapturePackage.MAX_PHOTO_BYTES + 1).toInt()).apply {
            this[0] = 0xff.toByte(); this[1] = 0xd8.toByte(); this[size - 2] = 0xff.toByte(); this[size - 1] = 0xd9.toByte()
        }
        photo(stamp.photoId).writeBytes(oversized)
        val error = assertThrows(IllegalArgumentException::class.java) { repo.writePackage(java.io.ByteArrayOutputStream()) }
        assertTrue(error.message!!.contains("16 MiB"))
        assertEquals(oversized.size.toLong(), photo(stamp.photoId).length())
    }

    @Test fun moreThanOneHundredPhotosTransferInCompleteReimportableBatches() {
        val decoder = ImageInspector { file -> javax.imageio.ImageIO.read(file)?.let { it.width to it.height } }
        val repo = repository(images = decoder)
        val image = java.awt.image.BufferedImage(32, 24, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val ids = (1..101).map {
            val stamp = stamp()
            val pending = repo.begin(stamp)
            check(javax.imageio.ImageIO.write(image, "jpg", pending))
            repo.finish(stamp, pending, null)
            stamp.photoId
        }
        val before = ids.associateWith { storage.read(it) }
        val outputs = mutableListOf<java.io.ByteArrayOutputStream>()
        assertEquals(listOf(100, 1), repo.writePackages { index, total ->
            assertEquals(2, total); assertEquals(outputs.size, index)
            java.io.ByteArrayOutputStream().also(outputs::add)
        })
        val library = OfflineLibrary(temporary.newFolder("phone-batches"), object : AtomicTextFiles {
            override fun read(file: File) = file.readText()
            override fun write(file: File, text: String) { file.parentFile.mkdirs(); file.writeText(text) }
        }, decoder, clock)
        val transferred = outputs.flatMap { output ->
            val bytes = output.toByteArray()
            val imported = library.importPackage(java.io.ByteArrayInputStream(bytes))
            assertEquals(imported.photoIds, library.importPackage(java.io.ByteArrayInputStream(bytes)).photoIds)
            imported.photoIds
        }
        assertEquals(ids.toSet(), transferred.toSet()); assertEquals(101, transferred.size)
        assertEquals(101, library.list().size)
        assertEquals(before, ids.associateWith { storage.read(it) })
        for (id in ids) assertEquals(PhotoDigest.sha256(photo(id)), PhotoDigest.sha256(library.load(id).original))
        assertThrows(java.io.IOException::class.java) {
            repo.writePackages { index, _ ->
                if (index == 1) throw java.io.IOException("SYNTHETIC second output failure")
                java.io.ByteArrayOutputStream()
            }
        }
        assertEquals(before, ids.associateWith { storage.read(it) })
        assertTrue(ids.all { photo(it).isFile })
    }

    @Test fun byteLimitIncludesTheManifestAndSplitsBeforeAnUnreadableBoundaryPackage() {
        // Sparse synthetic JPEG-marker fixtures exercise byte limits only, not image decoding.
        val repo = repository(images = ImageInspector { _ -> 16 to 16 })
        val ids = (1..16).map {
            val id = UUID.randomUUID().toString()
            photo(id).parentFile.mkdirs()
            java.io.RandomAccessFile(photo(id), "rw").use { output ->
                output.setLength(CapturePackage.MAX_PHOTO_BYTES)
                output.writeShort(0xffd8); output.seek(output.length() - 2); output.writeShort(0xffd9)
            }
            storage.write(id, CaptureRecordCodec.encode(RecordDocument(CaptureRecord.pending(id, null).copy(
                width = 16, height = 16, photoSha256 = PhotoDigest.sha256(photo(id))))))
            id
        }
        val metadataBytes = ids.sumOf { CaptureRecordCodec.encode(record(it)).toByteArray(Charsets.UTF_8).size.toLong() }
        val last = photo(ids.last())
        java.io.RandomAccessFile(last, "rw").use { output ->
            output.setLength(output.length() - metadataBytes)
            output.seek(output.length() - 2); output.writeShort(0xffd9)
        }
        val document = record(ids.last())
        storage.write(ids.last(), CaptureRecordCodec.encode(document.copy(record = document.record.copy(photoSha256 = PhotoDigest.sha256(last)))))
        assertEquals(CapturePackage.MAX_TOTAL_BYTES, ids.sumOf {
            photo(it).length() + CaptureRecordCodec.encode(record(it)).toByteArray(Charsets.UTF_8).size
        })
        val single = java.io.ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { repo.writePackage(single) }
        assertEquals(0, single.size())
        val outputs = mutableListOf<java.io.ByteArrayOutputStream>()
        assertEquals(listOf(15, 1), repo.writePackages { _, total ->
            assertEquals(2, total); java.io.ByteArrayOutputStream().also(outputs::add)
        })
        for (output in outputs) {
            var total = 0L
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(output.toByteArray())).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (zip.nextEntry != null) {
                    while (true) { val count = zip.read(buffer); if (count < 0) break; total += count }
                }
            }
            assertTrue(total <= CapturePackage.MAX_TOTAL_BYTES)
        }
        assertTrue(ids.all { photo(it).isFile })
    }

    @Test fun corruptOriginalBlocksEveryBatchBeforeOpeningAnyOutput() {
        val repo = repository(images = ImageInspector { _ -> 16 to 16 })
        val saved = save(repo)
        photo(saved.photoId).writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0, 0xff.toByte(), 0xd9.toByte()))
        val before = storage.read(saved.photoId)
        var opened = false
        assertThrows(IllegalArgumentException::class.java) { repo.writePackages { _, _ ->
            opened = true; java.io.ByteArrayOutputStream()
        } }
        assertFalse(opened); assertEquals(before, storage.read(saved.photoId)); assertTrue(photo(saved.photoId).isFile)
    }
    @Test fun malformedCacheDoesNotCountAsVerifiedOrRewriteSource() {
        val saved = save()
        val tree = com.google.gson.JsonParser.parseString(storage.read(saved.photoId)).asJsonObject
        val cache = tree.getAsJsonObject("photoVerification")
        cache.addProperty("lastModifiedUtcMillis", cache["lastModifiedUtcMillis"].asString)
        val malformed = tree.toString(); storage.write(saved.photoId, malformed)
        val state = CaptureStorageState(); decodes.set(0)
        val result = repository(state).recover()
        assertEquals(1, result.errors); assertEquals(0, state.savedPhotoCount())
        assertEquals(0, decodes.get())
        assertEquals(malformed, storage.read(saved.photoId)); assertTrue(photo(saved.photoId).isFile)
    }

    @Test fun unchangedColdStartSkipsDecodeAndDoesNotRewriteRecord() {
        val saved = save()
        val original = storage.read(saved.photoId)
        decodes.set(0)
        val restarted = repository()
        assertEquals(RecoveryReport(0, 0, 0), restarted.recover())
        assertEquals(0, decodes.get())
        assertEquals(1, restarted.savedPhotoCount())
        assertEquals(original, storage.read(saved.photoId))
    }

    @Test fun forcedVerificationDetectsSameSizeSameMtimeReplacement() {
        val saved = save()
        val file = photo(saved.photoId)
        val modified = file.lastModified()
        file.writeBytes(byteArrayOf(1, 9, 8, 7))
        assertTrue(file.setLastModified(modified))
        val restarted = repository()
        assertEquals(1, restarted.recover(forceFull = true).incompleteCaptures)
        assertEquals(PhotoIntegrity.HASH_MISMATCH, record(saved.photoId).record.photoIntegrityStatus)
        assertEquals(0, restarted.savedPhotoCount())
    }

    @Test fun changedStatTriggersValidationAndRetainsOriginalFingerprint() {
        val saved = save()
        val originalHash = record(saved.photoId).record.photoSha256
        photo(saved.photoId).writeBytes(byteArrayOf(1, 5, 6, 7, 8))
        val restarted = repository()
        assertEquals(RecoveryReport(0, 1, 0), restarted.recover())
        assertEquals(originalHash, record(saved.photoId).record.photoSha256)
        assertEquals(PhotoIntegrity.HASH_MISMATCH, record(saved.photoId).record.photoIntegrityStatus)
        assertEquals(1, storage.backups.size)
    }

    @Test fun pendingRecoveryRetainsJournalAndNullCompletionTime() {
        val old = repository()
        val requested = stamp()
        old.begin(requested).writeBytes(validBytes)
        old.abandon(requested.photoId)
        assertEquals(RecoveryReport(1, 0, 0), repository().recover())
        val recovered = record(requested.photoId).record
        assertEquals(requested.requestedUtcMillis, recovered.captureRequestedAtUtcMillis)
        assertNull(recovered.captureCompletedAtUtcMillis)
        assertNull(recovered.phoneLocation)
        assertEquals(FingerprintSource.RECOVERY_VERIFICATION_CURRENT_BYTES, recovered.photoSha256Source)
    }

    @Test fun corruptJsonIsBackedUpBeforeRepair() {
        val saved = save()
        storage.write(saved.photoId, "{unfinished synthetic fixture")
        assertEquals(RecoveryReport(1, 0, 0), repository().recover())
        assertEquals("{unfinished synthetic fixture", storage.backups.single())
        assertEquals(saved.requestedUtcMillis, record(saved.photoId).record.captureRequestedAtUtcMillis)
    }

    @Test fun repairPreservesAnnotationsAndRawClocks() {
        val saved = save()
        val old = record(saved.photoId)
        storage.write(saved.photoId, CaptureRecordCodec.encode(old.copy(record = old.record.copy(
            captureState = CaptureState.INCOMPLETE, photoFile = null,
            description = "synthetic annotation", issueLocation = GeoPoint(12.3, -45.6, CoordinateSystem.GCJ02),
        ))))
        assertEquals(1, repository().recover().recoveredRecords)
        val repaired = record(saved.photoId).record
        assertEquals("synthetic annotation", repaired.description)
        assertEquals(old.record.captureCompletedAtUtcMillis, repaired.captureCompletedAtUtcMillis)
        assertEquals(CoordinateSystem.GCJ02, repaired.issueLocation!!.coordinateSystem)
        assertEquals(IssueCategory.LITTER, repaired.issueCategory)
    }

    @Test fun missingAndUnreadableImagesAreRetainedAndNotCounted() {
        val saved = save()
        photo(saved.photoId).writeBytes(byteArrayOf(0))
        assertEquals(1, repository().recover().incompleteCaptures)
        assertEquals(PhotoIntegrity.UNREADABLE, record(saved.photoId).record.photoIntegrityStatus)
        assertTrue(photo(saved.photoId).exists())
        assertTrue(photo(saved.photoId).delete())
        val restarted = repository()
        assertEquals(1, restarted.recover().incompleteCaptures)
        assertEquals(PhotoIntegrity.MISSING, record(saved.photoId).record.photoIntegrityStatus)
        assertEquals(0, restarted.savedPhotoCount())
    }

    @Test fun orphanDoesNotInventCaptureTimeFromMtime() {
        val id = stamp().photoId
        photo(id).parentFile.mkdirs()
        photo(id).writeBytes(validBytes)
        assertEquals(1, repository().recover().recoveredRecords)
        assertNull(record(id).record.captureRequestedAtUtcMillis)
        assertNull(record(id).record.captureCompletedAtUtcMillis)
    }

    @Test fun foreignRecordIsNotAttachedEvenWithInvalidTypedFields() {
        val requested = stamp()
        val foreignId = stamp().photoId
        photo(requested.photoId).parentFile.mkdirs()
        photo(requested.photoId).writeBytes(validBytes)
        storage.write(requested.photoId, """{"schemaVersion":1,"photoId":"$foreignId","issueLocation":{"latitude":12.3},"description":"foreign fixture"}""")
        assertEquals(1, repository().recover().recoveredRecords)
        assertNull(record(requested.photoId).record.description)
        assertEquals(1, storage.backups.size)
    }

    @Test fun futureSchemaIsRetainedAndReportedAsError() {
        val saved = save()
        val future = storage.read(saved.photoId)!!.replace("schemaVersion\": 1", "schemaVersion\": 2")
        storage.write(saved.photoId, future)
        assertEquals(RecoveryReport(0, 0, 1), repository().recover())
        assertEquals(future, storage.read(saved.photoId))
        assertTrue(photo(saved.photoId).exists())
    }

    @Test fun legacyCompleteWithoutFingerprintGetsCacheWithoutOriginalHashClaim() {
        val saved = save()
        val old = record(saved.photoId)
        storage.write(saved.photoId, CaptureRecordCodec.encode(old.copy(record = old.record.copy(photoSha256 = null,
            photoSha256Source = null, photoVerification = null))))
        assertEquals(RecoveryReport(0, 0, 0), repository().recover())
        assertNull(record(saved.photoId).record.photoSha256)
        assertNotNull(record(saved.photoId).record.photoVerification)
        decodes.set(0)
        repository().recover()
        assertEquals(0, decodes.get())
    }

    @Test fun insufficientSpaceFailsBeforeCreatingJournal() {
        val requested = stamp()
        assertThrows(LowStorageException::class.java) { repository(free = { CaptureRepository.MIN_FREE_BYTES - 1 }).begin(requested) }
        assertFalse(File(root, "journals").exists())
    }

    @Test fun activeCaptureIsSkippedByRecovery() {
        val shared = CaptureStorageState()
        val capturing = repository(shared)
        val requested = stamp()
        val pending = capturing.begin(requested)
        pending.writeBytes(validBytes)
        assertEquals(RecoveryReport(0, 0, 0), repository(shared).recover())
        assertNull(storage.read(requested.photoId))
        capturing.finish(requested, pending, null)
        assertEquals(1, capturing.savedPhotoCount())
    }

    @Test fun slowRecoveryDoesNotBlockNewCaptureOrUiCount() {
        val saved = save()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val shared = CaptureStorageState()
        val slow = repository(shared, ImageInspector { file ->
            if (file.name == saved.photoId + ".jpg") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            inspector.dimensions(file)
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val recovering = executor.submit<RecoveryReport> { slow.recover(forceFull = true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val newId = executor.submit<CaptureStamp> { save(repository(shared)) }.get(2, TimeUnit.SECONDS)
            assertEquals(1, slow.savedPhotoCount())
            assertTrue(photo(newId.photoId).isFile)
            release.countDown()
            assertEquals(0, recovering.get(5, TimeUnit.SECONDS).errors)
            assertEquals(2, slow.savedPhotoCount())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun undoDeletesOnlySelectedPhotoAndDoesNotResurrect() {
        val repo = repository()
        val first = save(repo)
        val second = save(repo)
        repo.deleteCapture(second.photoId)
        assertTrue(photo(first.photoId).exists())
        assertFalse(photo(second.photoId).exists())
        assertNull(storage.read(second.photoId))
        assertEquals(1, repository().apply { recover() }.savedPhotoCount())
    }

    @Test fun failedDeletionJournalWriteRetainsSavedCountAndReviewablePhoto() {
        val repo = repository()
        val saved = save(repo)
        val original = storage.read(saved.photoId)
        storage.failWrites = true
        assertThrows(IllegalStateException::class.java) { repo.deleteCapture(saved.photoId) }
        assertEquals(1, repo.savedPhotoCount())
        assertEquals(saved.photoId, repo.latestSaved()!!.photoId)
        assertEquals(original, storage.read(saved.photoId))
        assertTrue(photo(saved.photoId).isFile)
    }

    @Test fun interruptedUndoTombstoneIsReplayedWithoutRestoringPhoto() {
        val saved = save()
        val old = record(saved.photoId)
        storage.write(saved.photoId, CaptureRecordCodec.encode(old.copy(record = old.record.copy(captureState = CaptureState.DELETED))))
        val restarted = repository()
        assertEquals(RecoveryReport(0, 0, 0), restarted.recover())
        assertFalse(photo(saved.photoId).exists())
        assertNull(storage.read(saved.photoId))
        assertEquals(0, restarted.savedPhotoCount())
    }

    @Test fun deletionDuringRecoveryDoesNotRecreateADeletedRecordFromTheSnapshot() {
        val shared = CaptureStorageState()
        val repo = repository(shared)
        val ids = listOf(save(repo).photoId, save(repo).photoId)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockedId = AtomicReference<String>()
        val recovering = repository(shared, ImageInspector { file ->
            if (blockedId.compareAndSet(null, file.name.substringBefore('.'))) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            inspector.dimensions(file)
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<RecoveryReport> { recovering.recover(forceFull = true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val deletedId = ids.single { it != blockedId.get() }
            repo.deleteCapture(deletedId)
            release.countDown()
            assertEquals(RecoveryReport(0, 0, 0), future.get(5, TimeUnit.SECONDS))
            assertNull(storage.read(deletedId))
            assertFalse(photo(deletedId).exists())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun deleteAllRefusesAnActiveCaptureThenClearsAllFilesAndCachedCount() {
        val repo = repository()
        save(repo)
        val active = stamp()
        repo.begin(active)
        assertThrows(IllegalStateException::class.java) { repo.deleteAllProjectData() }
        assertEquals(1, repo.savedPhotoCount())
        repo.abandon(active.photoId)
        repo.deleteAllProjectData()
        assertFalse(root.exists())
        assertEquals(0, repo.savedPhotoCount())
        assertEquals(RecoveryReport(0, 0, 0), repo.recover())
        assertFalse(root.exists())
    }

    private class TestRecords(private val directory: File) : CaptureRecordStorage {
        val backups = mutableListOf<String>()
        var failWrites = false
        override fun ids() = directory.listFiles()?.map { it.name.substringBefore('.') }?.filter(CaptureFiles::validId)?.toSet() ?: emptySet()
        override fun exists(id: String) = File(directory, "$id.json").exists()
        override fun read(id: String) = File(directory, "$id.json").takeIf { it.exists() }?.readText()
        override fun write(id: String, text: String) {
            check(!failWrites) { "Synthetic metadata write failure" }
            directory.mkdirs(); File(directory, "$id.json").writeText(text)
        }
        override fun backup(id: String) { read(id)?.let { backups.add(it) } }
        override fun delete(id: String) { val file = File(directory, "$id.json"); check(!file.exists() || file.delete()) }
    }
}
