package org.rokidfieldlog.core

import com.google.gson.JsonElement
import java.time.Instant

/** An explicitly selected historic phone fix. Never an issue position or a capture-time claim. */
data class PhotographerReference(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val coordinateSystem: String = "WGS84",
    val accuracyMeters: Float? = null,
    val locationTimeUtcMillis: Long = 0,
    val selectedAtUtcMillis: Long = 0,
    val source: String = "",
) {
    fun validate() {
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
        require(coordinateSystem == "WGS84" && locationTimeUtcMillis > 0 && selectedAtUtcMillis > 0)
        require(accuracyMeters == null || accuracyMeters.isFinite() && accuracyMeters >= 0)
        require(source.isNotBlank() && source.length <= 120)
    }
    fun label(): String = "拍摄者参考位置（非问题位置，非拍摄时定位）：" + latitude + ", " + longitude +
        " · WGS84 · 精度估计 " + (accuracyMeters?.let { it.toString() + " 米" } ?: "未知") +
        " · 位置时间 " + Instant.ofEpochMilli(locationTimeUtcMillis) + " · 来源 " + source
}

/** Check the JSON before Gson can fill absent fields or coerce strings/numeric timestamps. */
internal object PhotographerReferenceJson {
    fun validate(value: JsonElement?) {
        if (value == null || value.isJsonNull) return
        require(value.isJsonObject) { "Invalid photographer reference" }
        val point = value.asJsonObject
        val fields = setOf("latitude", "longitude", "coordinateSystem", "accuracyMeters",
            "locationTimeUtcMillis", "selectedAtUtcMillis", "source")
        require(point.keySet().all { it in fields }) { "Unsupported photographer reference fields; preserve original record" }
        for (key in listOf("latitude", "longitude")) {
            val axis = point[key]
            require(axis != null && axis.isJsonPrimitive && axis.asJsonPrimitive.isNumber) { "Missing numeric reference coordinate" }
        }
        for (key in listOf("coordinateSystem", "source")) {
            val text = point[key]
            require(text != null && text.isJsonPrimitive && text.asJsonPrimitive.isString) { "Missing reference $key" }
        }
        for (key in listOf("locationTimeUtcMillis", "selectedAtUtcMillis")) {
            val time = point[key]
            require(time != null && time.isJsonPrimitive && time.asJsonPrimitive.isNumber &&
                time.toString().matches(Regex("[1-9][0-9]*")) && time.toString().toLongOrNull() != null) { "Invalid reference timestamp" }
        }
        point["accuracyMeters"]?.takeUnless { it.isJsonNull }?.let { accuracy ->
            require(accuracy.isJsonPrimitive && accuracy.asJsonPrimitive.isNumber) { "Invalid reference accuracy" }
        }
        // Range, finite-value, WGS84, source and optional-accuracy checks follow in validate().
    }
}

object Jiangsu12345 {
    const val ID = "JIANGSU_12345_WEB"
    const val URL = "https://12345.jszwfw.gov.cn/cns-bmfw-wsbsdt/index/pages/appeal/step3.html"
    const val NOTICE_URL = "https://12345.jszwfw.gov.cn/cns-bmfw-wsbsdt/index/pages/appeal/step2.html?type=1"
    const val CHECKED_ON = "2026-10-16"
    const val CHANNEL_MAX_BYTES = 10_000_000L
    const val CHANNEL_MAX_FILES = 5
    const val NOTICE = "本材料仅供人工填写江苏12345网页。请先阅读官方填报须知，确认属于江苏非紧急受理范围；核对登录后的个人信息和联系方式。姓名、电话将随材料交给接收方。照片需逐张人工审阅；是否接受打码后的相册照片，以本次实际提交结果为准，不预先勾选接收。生成、复制或分享材料均不登记提交或受理。"
}
