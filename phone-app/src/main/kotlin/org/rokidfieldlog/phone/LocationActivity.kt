package org.rokidfieldlog.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

class LocationActivity : Activity() {
    private lateinit var probe: LocationProbe
    private lateinit var store: PrivateProbeStore
    private lateinit var resultView: TextView
    private lateinit var autoButton: Button
    private lateinit var systemButton: Button
    private lateinit var cancelButton: Button
    private var pendingSystemMode = false
    private var screenVisible = false
    private var requestSerial = 0
    private var deleting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probe = LocationProbe(this)
        store = PrivateProbeStore(this)
        buildView()
        PrivateProbeStore.fileExecutor.execute {
            val previous = store.last()
            runOnUiThread {
                if (previous != null && requestSerial == 0 && !isDestroyed && !isFinishing) {
                    try { showResult(previous, historical = true) }
                    catch (_: Exception) { resultView.text = "历史记录无法读取；可重新测试定位。" }
                }
            }
        }
    }

    private fun buildView() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(label("FieldLog Companion", 26f))
        content.addView(label("手机定位检查\n这里的结果不会自动关联到导入照片。", 16f))
        val googleState = if (probe.googleServicesAvailable()) "可用" else "不可用，将使用 Android 系统定位"
        content.addView(label("Google Play services：$googleState", 15f))
        autoButton = button("测试定位（自动选择）") { requestProbe(false) }
        systemButton = button("测试 Android 系统定位") { requestProbe(true) }
        cancelButton = button("停止本次定位") { probe.cancel() }.apply { isEnabled = false }
        content.addView(autoButton)
        content.addView(systemButton)
        content.addView(cancelButton)
        resultView = label("尚未定位。请在户外测试，首次定位可能需要等待。", 16f)
        resultView.setTextIsSelectable(true)
        content.addView(resultView)
        content.addView(button("删除全部项目数据") { confirmDelete() })
        val scroll = ScrollView(this).apply { addView(content) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(24, 39, 34))
        setPadding(0, 10, 0, 12)
    }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun requestProbe(systemMode: Boolean) {
        if (deleting) return
        pendingSystemMode = systemMode
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 101)
        } else startProbe(systemMode)
    }

    private fun startProbe(systemMode: Boolean) {
        if (deleting) return
        val serial = ++requestSerial
        setBusy(true)
        resultView.text = getString(R.string.locating)
        probe.start(systemMode, onProgress = { update ->
            val accuracy = update.observation.accuracyMeters?.let { "约 " + it.toInt() + " 米" } ?: "精度未知"
            resultView.text = "已收到拍摄者位置（$accuracy），继续等待更准确的结果…最多 60 秒。问题实际位置仍待确认。"
        }) { result ->
            setBusy(false)
            showResult(result.data, historical = false)
            PrivateProbeStore.fileExecutor.execute {
                val saved = runCatching { store.save(result.data) }
                if (saved.isFailure) runOnUiThread {
                    if (!isDestroyed && screenVisible && requestSerial == serial && !deleting) resultView.append("\n本次结果未能保存到私有存储。")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && screenVisible && !isFinishing && !isDestroyed) startProbe(pendingSystemMode)
    }

    private fun setBusy(busy: Boolean) {
        autoButton.isEnabled = !busy
        systemButton.isEnabled = !busy
        cancelButton.isEnabled = busy
    }

    private fun showResult(data: JSONObject, historical: Boolean) {
        val prefix = if (historical) "上次测试的历史记录（不是当前位置）\n\n" else "本次测试结果\n\n"
        val observation = data.optJSONObject("location")?.takeIf { store.reference(data) != null }
        val body = if (observation != null) {
            buildString {
                append("定位成功 · 拍摄者位置测试\n")
                if (data.optString("quality") == "BEST_AVAILABLE_AT_DEADLINE") append("等待结束，采用最近的低精度结果；仍需人工核对。\n")
                if (data.optString("quality") == "BEST_AVAILABLE_PROVIDER_UNAVAILABLE") append("定位来源暂不可用，保留最近的低精度结果；仍需人工核对。\n")
                append("纬度：${observation.getDouble("latitude")}\n")
                append("经度：${observation.getDouble("longitude")}\n")
                val accuracy = if (observation.isNull("accuracyMeters")) "未知" else String.format(Locale.ROOT, "%.1f 米", observation.getDouble("accuracyMeters"))
                append("精度估计：$accuracy\n")
                append("权限：${if (data.optString("permissionLevel") == "PRECISE") "精确" else "近似"}\n")
                append("位置时间：${Instant.ofEpochMilli(observation.getLong("locationTimeUtcMillis"))}\n")
                append("结果完成时间：${Instant.ofEpochMilli(data.getLong("receivedAtUtcMillis"))}\n")
                append("定位回调时数据年龄：${observation.getLong("ageAtReceiptMillis")} 毫秒\n")
                append("来源：${data.optString("source")} / ${observation.optString("provider")}\n")
                append("坐标系：WGS84\n")
                append("问题实际位置：待人工确认\n")
                append("眼镜连接：此阶段尚未配置\n")
                append("精度是估计值；该结果可能融合 GPS、Wi-Fi 或移动网络。")
            }
        } else {
            val reason = if (data.optString("status") == "SUCCESS") "定位记录不完整或数据异常，未采用。请重新测试定位。" else when (data.optString("reason")) {
                "PERMISSION_DENIED" -> "定位权限未授予。可再次点击测试申请权限。"
                "LOCATION_DISABLED" -> "手机定位未开启。App 不会自动修改定位设置。"
                "NO_ENABLED_PROVIDER" -> "没有可用的定位提供者；仅近似权限时，部分手机无法使用卫星定位。"
                "STALE_LOCATION" -> "位置数据过旧，未采用。"
                "MISSING_LOCATION_TIME" -> "位置数据缺少有效时间，未采用。"
                "MOCK_LOCATION_REJECTED" -> "检测到模拟位置，不能作为真实定位验证。"
                "USER_OR_SCREEN_CLOSED" -> "本次定位已停止。"
                "PROVIDER_ERROR" -> "系统定位提供者发生错误。"
                "TIMEOUT" -> "定位超时。请在户外重新测试。"
                else -> "位置时间或数据异常，未采用：${data.optString("reason")}."
            }
            "${if (data.optString("status") == "CANCELLED") "已停止" else "定位失败"}\n$reason\n经纬度：未获取（保持为空）"
        }
        resultView.text = getString(R.string.result_block, prefix, body)
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除全部 FieldLog 项目数据？")
            .setMessage("删除此 App 的全部照片、整理记录、定位检查和临时材料。已导出到自选位置的文件由你管理。此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                probe.cancel()
                deleting = true
                requestSerial++
                setBusy(true)
                cancelButton.isEnabled = false
                resultView.text = "正在删除项目数据…"
                PrivateProbeStore.fileExecutor.execute {
                    val result = runCatching { store.deleteAllProjectData() }
                    runOnUiThread {
                        deleting = false
                        if (!isDestroyed) {
                            setBusy(false)
                            resultView.text = if (result.isSuccess) "项目数据已删除。" else "删除未完成，请重试。"
                        }
                    }
                }
            }.show()
    }

    override fun onStart() {
        super.onStart()
        screenVisible = true
    }

    override fun onStop() {
        screenVisible = false
        probe.cancel()
        super.onStop()
    }
}
