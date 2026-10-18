package org.rokidfieldlog.core

import com.google.gson.*
import java.time.LocalDate

/** Rectangles refer to the upright image, independent of preview size and zoom. */
data class MaskRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    fun validate() {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom) { "Empty redaction rectangle" }
    }
    fun pixels(width: Int, height: Int): PixelBounds {
        validate(); require(width > 0 && height > 0)
        return PixelBounds(kotlin.math.floor(left * width).toInt(), kotlin.math.floor(top * height).toInt(),
            kotlin.math.ceil(right * width).toInt().coerceAtMost(width), kotlin.math.ceil(bottom * height).toInt().coerceAtMost(height))
    }
}
data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

enum class SubmissionStage { DRAFT, EXPORTED, SUBMITTED, RESOLVED }

data class PhotoReview(
    val fileName: String = "",
    val originalSha256: String = "",
    val outputSha256: String = "",
    val byteCount: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val masks: List<MaskRect> = emptyList(),
    val noThirdPartyInformation: Boolean = false,
    val reviewedAtUtcMillis: Long = 0,
    val rendererVersion: String = "upright-opaque-v1",
)

data class IssueDraft(
    val schemaVersion: Int = 1,
    val photoId: String = "",
    val revision: Long = 0,
    val category: IssueCategory? = null,
    val address: String = "",
    val addressConfirmed: Boolean = false,
    val description: String = "",
    val eventDate: String = "",
    val issuePosition: GeoPoint? = null,
    val photographerReference: PhotographerReference? = null,
    val photoReview: PhotoReview? = null,
    val stage: SubmissionStage = SubmissionStage.DRAFT,
    val receipt: String = "",
    val isSynthetic: Boolean = false,
)

data class MaterialProfile(
    val id: String = "GENERIC_MANUAL",
    val displayName: String = "通用人工提交材料",
    val maxPhotoBytes: Long = 5_000_000,
    val maxLongEdge: Int = 2048,
    val maxAddressChars: Int = 200,
    val maxDescriptionChars: Int = 2000,
) {
    fun validate() {
        require(id == "GENERIC_MANUAL") { "Unverified government channel profile" }
        require(maxPhotoBytes in 100_000..10_000_000 && maxLongEdge in 512..4096)
        require(maxAddressChars in 1..2000 && maxDescriptionChars in 1..10000)
    }
}

object DraftCodec {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().disableJdkUnsafe().setStrictness(Strictness.STRICT)
        .registerTypeAdapter(GeoPoint::class.java, InstanceCreator<GeoPoint> { GeoPoint(0.0, 0.0) })
        .registerTypeAdapter(IssueCategory::class.java, JsonDeserializer { value, _, _ -> IssueCategory.valueOf(value.asString) })
        .registerTypeAdapter(CoordinateSystem::class.java, JsonDeserializer { value, _, _ -> CoordinateSystem.valueOf(value.asString) })
        .registerTypeAdapter(SubmissionStage::class.java, JsonDeserializer { value, _, _ -> SubmissionStage.valueOf(value.asString) })
        .create()
    fun encode(draft: IssueDraft): String = gson.toJson(draft)
    fun decode(text: String, expectedId: String): IssueDraft {
        val tree = gson.fromJson(text, JsonObject::class.java) ?: error("Missing draft")
        val version = tree["schemaVersion"]
        require(version != null && version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.toString() == "1") {
            "Unsupported draft schema"
        }
        tree["issuePosition"]?.takeUnless { it.isJsonNull }?.asJsonObject?.let { point ->
            for (axis in listOf("latitude", "longitude")) {
                val value = point[axis]
                require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Missing numeric coordinate" }
            }
            require(point.has("coordinateSystem"))
        }
        PhotographerReferenceJson.validate(tree["photographerReference"])
        val draft = gson.fromJson(tree, IssueDraft::class.java) ?: error("Missing draft")
        require(draft.schemaVersion == 1 && draft.photoId == expectedId && CaptureFiles.validId(expectedId))
        require(draft.revision >= 0)
        requireNotNull(draft.stage); requireNotNull(draft.address); requireNotNull(draft.description)
        requireNotNull(draft.eventDate); requireNotNull(draft.receipt)
        require(draft.address.length <= 2000 && draft.description.length <= 10000 && draft.receipt.length <= 2000)
        draft.issuePosition?.let {
            require(it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0)
            requireNotNull(it.coordinateSystem)
        }
        draft.photographerReference?.validate()
        draft.photoReview?.let { review ->
            requireNotNull(review.fileName); requireNotNull(review.originalSha256)
            requireNotNull(review.outputSha256); requireNotNull(review.rendererVersion)
            requireNotNull(review.masks); require(review.masks.size <= 100)
            review.masks.forEach { it.validate() }
        }
        return draft
    }
}

object MaterialReadiness {
    private val hash = Regex("[0-9a-f]{64}")
    fun reasons(draft: IssueDraft, profile: MaterialProfile = MaterialProfile()): List<String> {
        profile.validate()
        val problems = mutableListOf<String>()
        if (draft.category == null) problems += "请选择并核对问题类别"
        if (draft.address.isBlank() || !draft.addressConfirmed) problems += "请填写并确认问题地址"
        if (draft.address.length > profile.maxAddressChars) problems += "问题地址过长"
        if (draft.description.isBlank() || draft.description.length > profile.maxDescriptionChars) problems += "请填写符合长度要求的问题说明"
        if (runCatching { LocalDate.parse(draft.eventDate) }.isFailure) problems += "请人工确认事发日期"
        draft.issuePosition?.let { if (it.coordinateSystem == null) problems += "问题坐标缺少坐标系" }
        val review = draft.photoReview
        if (review == null || review.reviewedAtUtcMillis <= 0) problems += "请完成照片隐私审阅"
        else {
            if (!hash.matches(review.originalSha256) || !hash.matches(review.outputSha256)) problems += "照片指纹无效"
            if (review.byteCount !in 1..profile.maxPhotoBytes || review.width !in 1..profile.maxLongEdge || review.height !in 1..profile.maxLongEdge) problems += "照片不符合材料大小要求"
            if (review.masks.isEmpty() && !review.noThirdPartyInformation) problems += "请确认照片无须遮挡或添加遮挡框"
            if (review.rendererVersion != "upright-opaque-v1") problems += "照片处理版本需要重新审阅"
        }
        return problems
    }
}
