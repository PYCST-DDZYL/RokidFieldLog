package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class DraftStartupSafetyTest {
    @get:Rule val temp = TemporaryFolder(File("build/draft-startup-tests").apply { mkdirs() })
    private val root by lazy { temp.newFolder("library") }
    private var failNextWrite = false
    private val texts = object : AtomicTextFiles {
        override fun read(file: File): String {
            val backup = File(file.path + ".bak")
            return (if (backup.isFile) backup else file).readText(Charsets.UTF_8)
        }
        override fun write(file: File, text: String) {
            if (failNextWrite) { failNextWrite = false; throw java.io.IOException("SYNTHETIC storage failure") }
            file.parentFile.mkdirs(); file.writeText(text, Charsets.UTF_8)
        }
    }
    private val clock = object : FieldClock { override fun utcMillis() = 123456L; override fun elapsedNanos() = 1L }
    private val inspector = ImageInspector { null }
    private val library by lazy { OfflineLibrary(root, texts, inspector, clock) }
    private val reports by lazy { ReportRepository(root, texts, library, inspector) }

    @Test fun newSiteCannotOverwriteAnAtomicBackupOrPendingRecord() {
        for (suffix in listOf(".bak", ".new")) {
            val id = UUID.randomUUID().toString()
            val companion = File(root, "reports/$id.json$suffix")
            val original = ReportCodec.encode(ReportDraft(reportId = id, address = "SYNTHETIC retained address"))
            companion.parentFile.mkdirs(); companion.writeText(original, Charsets.UTF_8)
            assertThrows(IllegalArgumentException::class.java) { reports.createOnSite(id) }
            assertEquals(original, companion.readText(Charsets.UTF_8))
            assertFalse(File(root, "reports/$id.json").exists())
        }
    }
    @Test fun firstCreationCanResumeWithTheSameIdentityAndIsIdempotent() {
        val id = UUID.randomUUID().toString()
        val created = reports.openOnSite(id, allowCreate = true)
        val file = File(root, "reports/$id.json"); val original = file.readBytes()
        assertEquals(id, created.reportId)
        assertEquals(created, reports.openOnSite(id, allowCreate = true))
        assertArrayEquals(original, file.readBytes()); assertEquals(1, reports.scan().reports.size)
    }
    @Test fun reopeningUsesPersistedEditsInsteadOfCreatingAnEmptyReplacement() {
        val created = reports.openOnSite(UUID.randomUUID().toString(), allowCreate = true)
        val saved = reports.save(created.copy(address = "SYNTHETIC retained address", landmark = "SYNTHETIC landmark",
            handoff = ReportHandoff(receipt = "SYNTHETIC retained note")), created.revision)
        val file = File(root, "reports/${saved.reportId}.json"); val original = file.readBytes()
        assertEquals(saved, reports.openOnSite(saved.reportId, allowCreate = true))
        assertEquals(saved, reports.openOnSite(saved.reportId, allowCreate = false))
        assertArrayEquals(original, file.readBytes())
    }
    @Test fun missingExistingDraftIsNotSilentlyRecreated() {
        val id = UUID.randomUUID().toString()
        assertThrows(IllegalArgumentException::class.java) { reports.openOnSite(id, allowCreate = false) }
        assertFalse(File(root, "reports/$id.json").exists())
    }
    @Test fun backupCanBeReadAndUnreadableCompanionsRemainUntouched() {
        val id = UUID.randomUUID().toString()
        val saved = ReportDraft(reportId = id, address = "SYNTHETIC backup address")
        val backup = File(root, "reports/$id.json.bak"); backup.parentFile.mkdirs()
        backup.writeText(ReportCodec.encode(saved), Charsets.UTF_8)
        assertEquals(saved, reports.openOnSite(id, allowCreate = true))
        assertFalse(File(root, "reports/$id.json").exists())
        for (suffix in listOf("", ".bak", ".new")) {
            val brokenId = UUID.randomUUID().toString()
            val broken = File(root, "reports/$brokenId.json$suffix")
            broken.writeText("{SYNTHETIC_BROKEN", Charsets.UTF_8)
            assertThrows(Exception::class.java) { reports.openOnSite(brokenId, allowCreate = true) }
            assertEquals("{SYNTHETIC_BROKEN", broken.readText(Charsets.UTF_8))
        }
    }
    @Test fun aFailedFirstWriteCanBeRetriedWithoutAllocatingAnotherId() {
        val id = UUID.randomUUID().toString(); failNextWrite = true
        assertThrows(java.io.IOException::class.java) { reports.openOnSite(id, allowCreate = true) }
        assertEquals(id, reports.openOnSite(id, allowCreate = true).reportId)
        assertEquals(1, reports.scan().reports.size)
    }
}
