package org.rokidfieldlog.phone

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
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
        check(root.isDirectory || root.mkdirs()) { "Private storage unavailable" }
        val atomic = AtomicFile(lastProbe)
        val output = atomic.startWrite()
        try {
            output.write(data.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (exception: Exception) {
            atomic.failWrite(output)
            throw exception
        }
    }

    fun last(): JSONObject? = try {
        // AtomicFile must be allowed to restore a backup after an interrupted write.
        AtomicFile(lastProbe).openRead().use { input ->
            check(input.channel.size() <= 256 * 1024) { "Probe history exceeds supported size" }
            JSONObject(input.bufferedReader(Charsets.UTF_8).readText())
        }
    } catch (exception: Exception) { null }

    fun deleteAllProjectData() {
        check(root.canonicalFile.parentFile == filesRoot) { "Unsafe data path" }
        check(shares.canonicalFile.parentFile == cacheRoot) { "Unsafe share cache path" }
        check(!shares.exists() || shares.deleteRecursively()) { "Share cache deletion failed" }
        check(!root.exists() || root.deleteRecursively()) { "Private data deletion failed" }
    }
}
