package org.rokidfieldlog.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class IncomingCapture(val document: RecordDocument, val photo: File, val originalSha256: String)
data class IncomingPackage(val captures: List<IncomingCapture>, val isSynthetic: Boolean)

/** Transport-neutral private backup format, not a government upload format. */
object CapturePackage {
    const val MAX_RECORDS = 100
    const val MAX_PHOTO_BYTES = 16L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    const val MAX_JSON_BYTES = 256L * 1024
    private val path = Regex("(records|photos)/([0-9a-f-]{36})\\.(json|jpg)")

    internal fun manifestBytes(count: Int, isSynthetic: Boolean = false) =
        ("{\"format\":\"FIELDLOG_CAPTURE_PACKAGE\",\"version\":1,\"recordCount\":" + count + ",\"isSynthetic\":" + isSynthetic + "}").toByteArray(Charsets.UTF_8)

    fun read(input: InputStream, staging: File, inspector: ImageInspector): IncomingPackage {
        check(staging.isDirectory && staging.list()?.isEmpty() == true) { "Fresh staging directory required" }
        val names = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && names.add(entry.name)) { "Duplicate or unsupported ZIP entry" }
                require(names.size <= MAX_RECORDS * 2 + 1) { "Too many ZIP entries" }
                val manifest = entry.name == "fieldlog-package.json"
                val match = path.matchEntire(entry.name)
                require(manifest || match != null) { "Unsupported capture package path" }
                if (match != null) {
                    require(CaptureFiles.validId(match.groupValues[2]))
                    require(match.groupValues[1] == "records" && match.groupValues[3] == "json" ||
                        match.groupValues[1] == "photos" && match.groupValues[3] == "jpg")
                }
                val target = File(staging, entry.name).canonicalFile
                require(target.toPath().startsWith(staging.canonicalFile.toPath()))
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                val limit = if (entry.name.endsWith(".jpg")) MAX_PHOTO_BYTES else MAX_JSON_BYTES
                check(staging.usableSpace >= CaptureRepository.MIN_FREE_BYTES + limit) { "空间不足，无法继续导入" }
                var entryBytes = 0L
                target.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count; total += count
                        require(entryBytes <= limit && total <= MAX_TOTAL_BYTES) { "Capture package exceeds size limit" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
                zip.closeEntry()
            }
        }
        val manifestFile = File(staging, "fieldlog-package.json")
        require(manifestFile.isFile) { "Missing capture package manifest" }
        val manifest = RecordJson.objectValue(manifestFile.readText(Charsets.UTF_8), MAX_JSON_BYTES.toInt())
        require(manifest["format"]?.asString == "FIELDLOG_CAPTURE_PACKAGE")
        val version = manifest["version"]
        require(version != null && version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.toString() == "1") {
            "Unsupported capture package version"
        }
        require(manifest["isSynthetic"]?.isJsonPrimitive == true && manifest["isSynthetic"].asJsonPrimitive.isBoolean)
        val synthetic = manifest["isSynthetic"].asBoolean
        val ids = names.filter { it.startsWith("records/") }.map { it.substringAfter('/').substringBefore('.') }.toSet()
        val recordCount = manifest["recordCount"]
        require(ids.isNotEmpty() && ids.size <= MAX_RECORDS && recordCount != null &&
            recordCount.isJsonPrimitive && recordCount.asJsonPrimitive.isNumber && recordCount.toString() == ids.size.toString())
        require(names == ids.flatMap { listOf("records/$it.json", "photos/$it.jpg") }.toSet() + "fieldlog-package.json") { "Records and photos do not match" }
        val captures = ids.map { id ->
            val document = CaptureRecordCodec.decode(File(staging, "records/$id.json").readText(Charsets.UTF_8))
            val record = document.record
            require(record.photoId == id && record.photoFile == "photos/$id.jpg")
            require(record.captureState in setOf(CaptureState.SAVED, CaptureState.RECOVERED) && record.photoIntegrityStatus == PhotoIntegrity.VERIFIED)
            val photo = File(staging, "photos/$id.jpg")
            require(CaptureFiles.hasJpegMarkers(photo)) { "Incomplete JPEG" }
            val dimensions = checkNotNull(inspector.dimensions(photo)) { "JPEG pixel validation failed" }
            require(record.width == dimensions.first && record.height == dimensions.second) { "Photo dimensions changed" }
            val hash = PhotoDigest.sha256(photo)
            record.photoSha256?.let { require(it.equals(hash, true)) { "Photo fingerprint mismatch" } }
            IncomingCapture(document, photo, hash)
        }
        return IncomingPackage(captures, synthetic)
    }

    fun write(output: OutputStream, captures: List<IncomingCapture>, isSynthetic: Boolean = false) {
        require(captures.isNotEmpty() && captures.size <= MAX_RECORDS)
        require(captures.map { it.document.record.photoId }.toSet().size == captures.size)
        ZipOutputStream(output.buffered()).use { zip ->
            val manifest = manifestBytes(captures.size, isSynthetic)
            zip.putNextEntry(ZipEntry("fieldlog-package.json"))
            zip.write(manifest)
            zip.closeEntry()
            var total = manifest.size.toLong()
            for (capture in captures) {
                val id = capture.document.record.photoId
                require(CaptureFiles.validId(id) && capture.photo.length() in 1..MAX_PHOTO_BYTES)
                require(PhotoDigest.sha256(capture.photo) == capture.originalSha256)
                val json = CaptureRecordCodec.encode(capture.document).toByteArray(Charsets.UTF_8)
                require(json.size <= MAX_JSON_BYTES)
                total += json.size + capture.photo.length()
                require(total <= MAX_TOTAL_BYTES)
                zip.putNextEntry(ZipEntry("records/$id.json")); zip.write(json); zip.closeEntry()
                zip.putNextEntry(ZipEntry("photos/$id.jpg")); capture.photo.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}
