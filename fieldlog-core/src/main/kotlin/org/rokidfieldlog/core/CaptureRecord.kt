package org.rokidfieldlog.core

import com.google.gson.*
import com.google.gson.annotations.SerializedName

enum class CaptureState { SAVED, RECOVERED, INCOMPLETE, DELETED }
enum class PhotoIntegrity { VERIFIED, HASH_MISMATCH, UNREADABLE, MISSING }
enum class CategorySource { UNCONFIRMED, USER_TAGGED }
enum class FingerprintSource { CAPTURE_SAVE, RECOVERY_VERIFICATION_CURRENT_BYTES }
enum class CoordinateSystem { WGS84, GCJ02, BD09 }

/** Coordinates always carry their reference system; never relabel values as a conversion. */
data class GeoPoint(
    @field:SerializedName("latitude") val latitude: Double,
    @field:SerializedName("longitude") val longitude: Double,
    @field:SerializedName("coordinateSystem") val coordinateSystem: CoordinateSystem? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

data class LocationObservation(
    @field:SerializedName("latitude") val latitude: Double,
    @field:SerializedName("longitude") val longitude: Double,
    @field:SerializedName("coordinateSystem") val coordinateSystem: CoordinateSystem = CoordinateSystem.WGS84,
    @field:SerializedName("accuracyMeters") val accuracyMeters: Float? = null,
    @field:SerializedName("locationTimeUtcMillis") val locationTimeUtcMillis: Long? = null,
    @field:SerializedName("locationElapsedRealtimeNanos") val locationElapsedRealtimeNanos: Long? = null,
    @field:SerializedName("ageAtReceiptMillis") val ageAtReceiptMillis: Long? = null,
    @field:SerializedName("provider") val provider: String? = null,
    @field:SerializedName("isMock") val isMock: Boolean = false,
)

/** A performance cache, not a proof of authenticity. Full validation is required before export. */
data class PhotoVerification(
    @field:SerializedName("byteCount") val byteCount: Long,
    @field:SerializedName("lastModifiedUtcMillis") val lastModifiedUtcMillis: Long,
    @field:SerializedName("sha256") val sha256: String,
    @field:SerializedName("width") val width: Int,
    @field:SerializedName("height") val height: Int,
)

/** Version 1 remains readable by the phone; all new fields are additive. Unknowns stay null. */
data class CaptureRecord(
    @field:SerializedName("schemaVersion") val schemaVersion: Int = 1,
    @field:SerializedName("photoId") val photoId: String,
    @field:SerializedName("photoFile") val photoFile: String? = null,
    @field:SerializedName("captureRequestedAtUtcMillis") val captureRequestedAtUtcMillis: Long? = null,
    @field:SerializedName("captureCompletedAtUtcMillis") val captureCompletedAtUtcMillis: Long? = null,
    @field:SerializedName("captureRequestedElapsedRealtimeNanos") val captureRequestedElapsedRealtimeNanos: Long? = null,
    @field:SerializedName("captureCompletedElapsedRealtimeNanos") val captureCompletedElapsedRealtimeNanos: Long? = null,
    @field:SerializedName("captureTimeSource") val captureTimeSource: String = "UNKNOWN_RECOVERED_FILE",
    @field:SerializedName("captureWallClockStatus") val captureWallClockStatus: String? = null,
    @field:SerializedName("captureElapsedClockStatus") val captureElapsedClockStatus: String? = null,
    @field:SerializedName("captureDurationMillis") val captureDurationMillis: Long? = null,
    @field:SerializedName("captureState") val captureState: CaptureState = CaptureState.SAVED,
    @field:SerializedName("photoIntegrityStatus") val photoIntegrityStatus: PhotoIntegrity = PhotoIntegrity.VERIFIED,
    @field:SerializedName("photoSha256") val photoSha256: String? = null,
    @field:SerializedName("photoSha256Source") val photoSha256Source: FingerprintSource? = null,
    @field:SerializedName("photoVerification") val photoVerification: PhotoVerification? = null,
    @field:SerializedName("width") val width: Int? = null,
    @field:SerializedName("height") val height: Int? = null,
    @field:SerializedName("issueCategory") val issueCategory: IssueCategory? = null,
    @field:SerializedName("issueCategorySource") val issueCategorySource: CategorySource = CategorySource.UNCONFIRMED,
    @field:SerializedName("issueCategoryNeedsReview") val issueCategoryNeedsReview: Boolean = true,
    @field:SerializedName("recognitionStatus") val recognitionStatus: String = "NOT_RUN",
    @field:SerializedName("phoneLocation") val phoneLocation: LocationObservation? = null,
    @field:SerializedName("issueLocation") val issueLocation: GeoPoint? = null,
    @field:SerializedName("locationStatus") val locationStatus: String = "PENDING_CONFIRMATION",
    @field:SerializedName("phoneConnectionStatus") val phoneConnectionStatus: String = "NOT_CONFIGURED_PHASE1",
    @field:SerializedName("syncStatus") val syncStatus: String = "LOCAL_ONLY",
    @field:SerializedName("description") val description: String? = null,
    @field:SerializedName("address") val address: String? = null,
    @field:SerializedName("privacyReviewStatus") val privacyReviewStatus: String = "NOT_REVIEWED",
    @field:SerializedName("recoveredAtUtcMillis") val recoveredAtUtcMillis: Long? = null,
    @field:SerializedName("lastStorageCheckAtUtcMillis") val lastStorageCheckAtUtcMillis: Long? = null,
    @field:SerializedName("reason") val reason: String? = null,
) {
    init { require(CaptureFiles.validId(photoId)) }

    companion object {
        fun pending(id: String, stamp: CaptureStamp?) = CaptureRecord(
            photoId = id, photoFile = "photos/$id.jpg",
            captureRequestedAtUtcMillis = stamp?.requestedUtcMillis,
            captureRequestedElapsedRealtimeNanos = stamp?.requestedElapsedNanos,
            captureTimeSource = if (stamp == null) "UNKNOWN_RECOVERED_FILE" else "JOURNALED_REQUEST_COMPLETION_UNKNOWN",
        )
    }
}

data class LocationProbeRecord(
    @field:SerializedName("schemaVersion") val schemaVersion: Int = 1,
    @field:SerializedName("probeId") val probeId: String,
    @field:SerializedName("status") val status: String,
    @field:SerializedName("requestedAtUtcMillis") val requestedAtUtcMillis: Long,
    @field:SerializedName("requestedElapsedRealtimeNanos") val requestedElapsedRealtimeNanos: Long,
    @field:SerializedName("receivedAtUtcMillis") val receivedAtUtcMillis: Long,
    @field:SerializedName("receivedElapsedRealtimeNanos") val receivedElapsedRealtimeNanos: Long,
    @field:SerializedName("permissionLevel") val permissionLevel: String,
    @field:SerializedName("source") val source: String,
    @field:SerializedName("glassesConnectionStatus") val glassesConnectionStatus: String = "NOT_CONFIGURED_PHASE1",
    @field:SerializedName("location") val location: LocationObservation? = null,
    @field:SerializedName("reason") val reason: String? = null,
    @field:SerializedName("quality") val quality: String? = null,
)

/** Retain unrecognized annotations, including nested extension fields, during repairs. */
data class RecordDocument(val record: CaptureRecord, val original: JsonObject = JsonObject())

object CaptureRecordCodec {
    private val placeholderId = "00000000-0000-4000-8000-000000000000"
    // Explicit creators apply Kotlin defaults; Unsafe must never bypass constructors.
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().disableJdkUnsafe()
        .setStrictness(Strictness.STRICT)
        .registerTypeAdapter(CaptureRecord::class.java, InstanceCreator<CaptureRecord> { CaptureRecord(photoId = placeholderId) })
        .registerTypeAdapter(LocationObservation::class.java, InstanceCreator<LocationObservation> { LocationObservation(0.0, 0.0) })
        .registerTypeAdapter(GeoPoint::class.java, InstanceCreator<GeoPoint> { GeoPoint(0.0, 0.0) })
        .registerTypeAdapter(PhotoVerification::class.java, InstanceCreator<PhotoVerification> { PhotoVerification(0, 0, "", 0, 0) })
        .registerTypeAdapter(CaptureState::class.java, JsonDeserializer { value, _, _ -> CaptureState.valueOf(value.asString) })
        .registerTypeAdapter(PhotoIntegrity::class.java, JsonDeserializer { value, _, _ -> PhotoIntegrity.valueOf(value.asString) })
        .registerTypeAdapter(CategorySource::class.java, JsonDeserializer { value, _, _ -> CategorySource.valueOf(value.asString) })
        .registerTypeAdapter(FingerprintSource::class.java, JsonDeserializer { value, _, _ -> FingerprintSource.valueOf(value.asString) })
        .registerTypeAdapter(CoordinateSystem::class.java, JsonDeserializer { value, _, _ -> CoordinateSystem.valueOf(value.asString) })
        .registerTypeAdapter(IssueCategory::class.java, JsonDeserializer { value, _, _ -> IssueCategory.valueOf(value.asString) })
        .create()

    fun encodeProbe(record: LocationProbeRecord): String = gson.toJson(record)

    /** Read an explicitly selected historical result, without claiming it is a current fix. */
    fun referenceFromProbe(text: String, selectedAtUtcMillis: Long): PhotographerReference? = runCatching {
        require(text.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
        val original = parse(text)
        requireVersion(original)
        require(original["status"] == JsonPrimitive("SUCCESS"))
        val permission = original["permissionLevel"]
        require(permission != null && permission.isJsonPrimitive && permission.asJsonPrimitive.isString &&
            permission.asString in setOf("PRECISE", "APPROXIMATE"))
        val source = original["source"]
        require(source != null && source.isJsonPrimitive && source.asJsonPrimitive.isString &&
            source.asString in setOf("FUSED", "ANDROID_SYSTEM"))
        val location = original["location"]
        require(location != null && location.isJsonObject)
        val fix = location.asJsonObject
        require(fix["isMock"] == JsonPrimitive(false)) { "Mock status must be explicitly false" }
        val age = fix["ageAtReceiptMillis"]
        require(age != null && age.isJsonPrimitive && age.asJsonPrimitive.isNumber && age.toString().matches(Regex("[0-9]+")))
        require(age.toString().toLongOrNull()?.let { it in 0..LocationQuality.MAX_AGE_MILLIS } == true)
        val completed = original["receivedAtUtcMillis"]
        require(completed != null && completed.isJsonPrimitive && completed.asJsonPrimitive.isNumber &&
            completed.toString().matches(Regex("[1-9][0-9]*")) && completed.toString().toLongOrNull() != null)
        val provider = fix["provider"]
        require(provider == null || provider.isJsonPrimitive && provider.asJsonPrimitive.isString)
        val reference = JsonObject().apply {
            for (key in listOf("latitude", "longitude", "coordinateSystem", "accuracyMeters", "locationTimeUtcMillis")) {
                fix[key]?.let { add(key, it) }
            }
            addProperty("selectedAtUtcMillis", selectedAtUtcMillis)
            addProperty("source", source.asString + "/" + (provider?.asString ?: "unknown"))
        }
        PhotographerReferenceJson.validate(reference)
        gson.fromJson(reference, PhotographerReference::class.java).also { it.validate() }
    }.getOrNull()

    private fun parse(text: String): JsonObject = gson.fromJson(text, JsonObject::class.java)
        ?: throw IllegalArgumentException("Missing JSON object")

    fun decode(text: String): RecordDocument = decodeObject(parse(text))

    private fun requireVersion(original: JsonObject) {
        val version = original["schemaVersion"]
        require(version != null && version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.toString() == "1") {
            "Unsupported record schema"
        }
    }

    private fun decodeObject(original: JsonObject): RecordDocument {
        requireVersion(original)
        require(original["photoId"]?.isJsonPrimitive == true && CaptureFiles.validId(original["photoId"].asString))
        for (key in listOf("phoneLocation", "issueLocation")) {
            val location = original[key]?.takeUnless { it.isJsonNull } ?: continue
            require(location.isJsonObject) { "Invalid location object" }
            for (axis in listOf("latitude", "longitude")) {
                val number = location.asJsonObject[axis]
                require(number != null && number.isJsonPrimitive && number.asJsonPrimitive.isNumber) { "Missing coordinate" }
            }
        }
        val record = gson.fromJson(original, CaptureRecord::class.java)
        require(CaptureFiles.validId(record.photoId))
        requireNotNull(record.captureState)
        requireNotNull(record.photoIntegrityStatus)
        requireNotNull(record.issueCategorySource)
        record.phoneLocation?.let { requireNotNull(it.coordinateSystem) }
        record.issueLocation?.let { point ->
            require(point.latitude.isFinite() && point.latitude in -90.0..90.0)
            require(point.longitude.isFinite() && point.longitude in -180.0..180.0)
        }
        return RecordDocument(record, original)
    }

    fun readForRecovery(text: String, id: String): RecordDocument? {
        val original = try { parse(text) } catch (_: JsonParseException) { return null }
        requireVersion(original)
        if (original["photoId"]?.asString != id) return null
        return decodeObject(original)
    }

    fun encode(document: RecordDocument): String {
        fun merge(old: JsonObject, fresh: JsonObject): JsonObject = old.deepCopy().apply {
            for ((key, value) in fresh.entrySet()) {
                val previous = old[key]
                add(key, if (previous is JsonObject && value is JsonObject) merge(previous, value) else value)
            }
        }
        return gson.toJson(merge(document.original, gson.toJsonTree(document.record).asJsonObject))
    }
}
