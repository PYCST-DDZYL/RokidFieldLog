package org.rokidfieldlog.phone

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import org.rokidfieldlog.core.PhotographerReference
import org.rokidfieldlog.core.CaptureRecordCodec
import org.rokidfieldlog.core.RecordText
import java.util.concurrent.Executors

class PrivateProbeStore(context: Context) {
    companion object {
        val fileExecutor = Executors.newSingleThreadExecutor { work -> Thread(work, "FieldLogProbeFiles") }
    }
    private val filesRoot = context.filesDir.canonicalFile
    private val root = File(filesRoot, "fieldlog")
    private val cacheRoot = context.cacheDir.canonicalFile
    private val shares = File(cacheRoot, "reviewed-shares")
    private val lastProbe = File(root, "last-probe.json")

    fun save(data: JSONObject) {
        writeProbe(lastProbe, data)
        if (reference(data) != null) writeProbe(File(root, "last-successful-probe.json"), data)
    }

    private fun writeProbe(target: File, data: JSONObject) {
        check(root.isDirectory || root.mkdirs()) { "Private storage unavailable" }
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(data.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (exception: Exception) {
            atomic.failWrite(output)
            throw exception
        }
    }

    fun last(): JSONObject? = readProbe(lastProbe)

    private fun readProbe(target: File): JSONObject? = try {
        // AtomicFile must be allowed to restore a backup after an interrupted write.
        AtomicFile(target).openRead().use { input ->
            val text = RecordText.read(input, 256 * 1024)
            JSONObject(CaptureRecordCodec.decodeProbeHistory(text, System.currentTimeMillis()))
        }
    } catch (exception: Exception) { null }

    fun reference(data: JSONObject): PhotographerReference? =
        CaptureRecordCodec.referenceFromProbe(data.toString(), System.currentTimeMillis())

    fun lastReference(): PhotographerReference? {
        val saved = readProbe(File(root, "last-successful-probe.json"))
        return saved?.let(::reference) ?: last()?.let(::reference)
    }

    fun deleteAllProjectData() {
        check(root.canonicalFile.parentFile == filesRoot) { "Unsafe data path" }
        check(shares.canonicalFile.parentFile == cacheRoot) { "Unsafe share cache path" }
        check(!shares.exists() || shares.deleteRecursively()) { "Share cache deletion failed" }
        check(!root.exists() || root.deleteRecursively()) { "Private data deletion failed" }
    }
}
