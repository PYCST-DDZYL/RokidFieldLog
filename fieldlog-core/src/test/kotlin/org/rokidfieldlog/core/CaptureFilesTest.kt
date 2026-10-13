package org.rokidfieldlog.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CaptureFilesTest {
    @get:Rule val temporary = TemporaryFolder(File("build/capture-tests").apply { mkdirs() })
    private val id = "00000000-0000-4000-8000-000000000001"
    private val stamp = CaptureStamp(id, 1234L, 5678L)
    private fun store() = CaptureFiles(temporary.root)

    @Test fun crashBeforePhotoWritePreservesOriginalRequestWithoutInventingPhoto() {
        store().begin(stamp)
        val restarted = store()
        assertEquals(stamp, restarted.stamp(id))
        assertFalse(restarted.completed(id).exists())
        assertTrue(restarted.ids().contains(id))
    }

    @Test fun crashAfterPhotoRenameLeavesBytesAndOriginalTimeForMetadataRecovery() {
        val files = store()
        files.begin(stamp).writeBytes(byteArrayOf(1, 2, 3)) // File transaction fixture, not a camera test.
        files.promote(id)
        val restarted = store()
        assertArrayEquals(byteArrayOf(1, 2, 3), restarted.completed(id).readBytes())
        assertEquals(stamp, restarted.stamp(id))
        assertFalse(restarted.pending(id).exists())
    }

    @Test fun corruptedJournalCannotCreateFabricatedCaptureTime() {
        store().begin(stamp)
        File(temporary.root, "journals/$id.journal").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store().stamp(id))
        assertTrue(File(temporary.root, "journals/$id.journal").exists())
    }

    @Test fun promotionRefusesToOverwriteExistingPhotoAndRetainsBothFiles() {
        val files = store()
        files.begin(stamp).writeBytes(byteArrayOf(1))
        files.completed(id).writeBytes(byteArrayOf(2))
        assertThrows(IllegalStateException::class.java) { files.promote(id) }
        assertArrayEquals(byteArrayOf(1), files.pending(id).readBytes())
        assertArrayEquals(byteArrayOf(2), files.completed(id).readBytes())
    }

    @Test fun pathTraversalIdsAreRejectedBeforeCreatingDirectories() {
        val files = store()
        assertThrows(IllegalArgumentException::class.java) { files.begin(CaptureStamp("../outside", 1L, 1L)) }
        assertFalse(File(temporary.root, "photos").exists())
    }

    @Test fun crashImmediatelyBeforeJournalRenameRetainsOriginalRequest() {
        store().begin(stamp)
        val journal = File(temporary.root, "journals/$id.journal")
        assertTrue(journal.renameTo(File(temporary.root, "journals/$id.journal.new")))
        assertEquals(stamp, store().stamp(id))
    }

    @Test fun missingJpegEndMarkerIsRejectedEvenWhenHeaderIsPresent() {
        val file = File(temporary.root, "incomplete.jpg")
        file.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0, 0))
        assertFalse(CaptureFiles.hasJpegMarkers(file))
    }

    @Test fun legacyRecordWithoutPhotoOrJournalIsIncludedInRecoveryScan() {
        val records = File(temporary.root, "records").apply { mkdirs() }
        File(records, "$id.json").writeText("{}")
        assertTrue(store().ids().contains(id))
        assertFalse(store().completed(id).exists())
    }

    @Test fun newCaptureCannotReuseIdOfLegacyRecordWithoutPhotoOrJournal() {
        val records = File(temporary.root, "records").apply { mkdirs() }
        val original = File(records, "$id.json").apply { writeText("original annotation fixture") }
        assertThrows(IllegalStateException::class.java) { store().begin(stamp) }
        assertEquals("original annotation fixture", original.readText())
        assertFalse(File(temporary.root, "journals/$id.journal").exists())
    }

    @Test fun atomicBackupAloneAlsoReservesItsCaptureId() {
        val records = File(temporary.root, "records").apply { mkdirs() }
        File(records, "$id.json.bak").writeText("recoverable annotation fixture")
        assertThrows(IllegalStateException::class.java) { store().begin(stamp) }
        assertTrue(store().ids().contains(id))
        assertEquals("recoverable annotation fixture", File(records, "$id.json.bak").readText())
    }
}
