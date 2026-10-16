package org.rokidfieldlog.phone

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.*
import org.rokidfieldlog.core.*
import java.time.LocalDate
import java.util.UUID

/** Phone-only on-site step: commit a findable address before any photo transfer. */
class OnSiteActivity : InspectionScreen() {
    private lateinit var reportId: String
    private var draft: ReportDraft? = null
    private lateinit var probe: LocationProbe
    private lateinit var probeStore: PrivateProbeStore
    private var cancelLookup: (() -> Unit)? = null
    private var locating = false
    private var visible = false
    private var serial = 0
    private lateinit var address: EditText
    private lateinit var landmark: EditText
    private lateinit var confirmed: CheckBox
    private lateinit var here: Button
    private lateinit var stop: Button
    private lateinit var referenceView: TextView
    private var reference: PhotographerReference? = null
    private var suggestion = ""
    private var restored: ReportDraft? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        reportId = state?.getString("reportId") ?: intent.getStringExtra("reportId") ?: UUID.randomUUID().toString()
        if (!CaptureFiles.validId(reportId)) { finish(); return }
        restored = state?.getString("editing")?.let { runCatching { ReportCodec.decode(it, reportId) }.getOrNull() }
        probe = LocationProbe(this); probeStore = PrivateProbeStore(this)
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { leave() }
        task("正在打开现场地址…", {
            if (intent.getStringExtra("reportId") != null || state != null) store.reports.load(reportId)
            else store.reports.createOnSite(reportId)
        }) { saved -> render(restored?.takeIf { it.revision == saved.revision } ?: saved); restored = null }
    }
    private fun field(label: String, value: String): EditText {
        val count = OfflineUi.text(this, label + " · " + value.length + "/200"); content.addView(count)
        return OfflineUi.field(this, label, value, true).apply {
            filters = arrayOf(InputFilter.LengthFilter(200)); content.addView(this)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, countChanged: Int) {
                    count.text = label + " · " + (s?.length ?: 0) + "/200"
                    if (::confirmed.isInitialized) confirmed.isChecked = false
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
    }
    private fun render(saved: ReportDraft) {
        val value = saved.copy(locationConfirmed = saved.locationConfirmed && saved.address.isNotBlank() && saved.landmark.isNotBlank())
        draft = value; reference = value.photographerReference; suggestion = value.addressSuggestion
        content.removeAllViews()
        content.addView(OfflineUi.text(this, "现场先定地址", 26f))
        status.text = if (value.locationConfirmed) "地址已确认，照片可稍后加进这份材料。" else "离开前填好地址和参照物，并确认对方能找到。现在不需要照片。"
        content.addView(status)
        here = OfflineUi.button(this, "我在这里") { requestLocation() }; content.addView(here)
        content.addView(OfflineUi.text(this, "请求本次手机定位，再向系统地址服务获取文字建议。建议可能偏离现场，需要你修改；坐标只作拍摄者参考，不当作问题位置。"))
        stop = OfflineUi.button(this, "停止定位，改为手填") { stopLocation(); status.text = "已停止。请手填地址和参照物，确认后再离开。" }.apply { isEnabled = false }; content.addView(stop)
        address = field("问题详细地址（可直接手填）", value.address)
        landmark = field("附近参照物 / 设施编号", value.landmark)
        referenceView = OfflineUi.text(this, reference?.label() ?: "尚无本次拍摄者参考位置；可以手填地址。")
        content.addView(referenceView)
        if (suggestion.isNotBlank()) content.addView(OfflineUi.text(this, "系统曾建议（供核对，不代表问题地址已确认）：" + suggestion))
        confirmed = CheckBox(this).apply { text = "我已修改并核对地址、参照物，确认对方能找到"; isChecked = value.locationConfirmed }; content.addView(confirmed)
        content.addView(OfflineUi.button(this, "确认地址，留待补照片") {
            val current = snapshot()
            if (current.address.isBlank() || current.landmark.isBlank() || !current.locationConfirmed) {
                status.text = "地址和参照物不能空着；请核对并勾选对方能找到，再离开。"
            } else saveAndFinish()
        })
        content.addView(OfflineUi.button(this, "暂存，地址仍待确认") { leave() })
        content.addView(OfflineUi.text(this, "眼镜只拍同一处的环境照和细节照。回去导出采集包，手机导入后选择加入材料 #" + reportId.take(8) + "。"))
    }
    private fun snapshot() = checkNotNull(draft).copy(address = address.text.toString().trim(), landmark = landmark.text.toString().trim(),
        locationConfirmed = confirmed.isChecked && address.text.toString().isNotBlank() && landmark.text.toString().isNotBlank(),
        photographerReference = reference, addressSuggestion = suggestion,
        eventDate = draft!!.eventDate.ifBlank { LocalDate.now().toString() },
        sameIssueVisitConfirmed = draft!!.sameIssueVisitConfirmed && draft!!.address == address.text.toString().trim() && draft!!.landmark == landmark.text.toString().trim())
    private fun requestLocation() {
        if (busy || locating) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 105)
        } else startLocation()
    }
    private fun locationUi(active: Boolean) {
        locating = active
        here.isEnabled = !active; stop.isEnabled = active
        address.isEnabled = !active; landmark.isEnabled = !active; confirmed.isEnabled = !active
    }
    private fun startLocation() {
        if (busy || locating || !visible || draft == null) return
        val token = ++serial
        locationUi(true); confirmed.isChecked = false
        status.text = "正在获取本次拍摄者参考位置，最多60秒；可停止后手填。"
        probe.start(false, onProgress = { update ->
            status.text = "已收到参考位置（精度约" + (update.observation.accuracyMeters?.toInt()?.toString() ?: "未知") + "米），继续等待更准确结果…"
        }) { result ->
            if (!visible || token != serial) return@start
            PrivateProbeStore.fileExecutor.execute { runCatching { probeStore.save(result.data) } }
            val fix = probeStore.reference(result.data)
            if (fix == null) {
                locationUi(false)
                status.text = "本次定位未成功（" + result.data.optString("reason") + "）。请在这里手填地址和参照物，确认对方能找到后再离开。"
            } else {
                reference = fix; referenceView.text = fix.label()
                status.text = "已取得拍摄者参考位置，正在请求系统建议地址（最多10秒）…"
                cancelLookup = AddressSuggestionLookup(this).lookup(fix) { proposed ->
                    if (!visible || token != serial) return@lookup
                    cancelLookup = null; locationUi(false)
                    if (proposed != null) {
                        if (address.text.isBlank()) address.setText(proposed)
                        else AlertDialog.Builder(this).setTitle("用本次建议替换地址？").setMessage(proposed + "\n请核对它是否能找到问题现场。")
                            .setNegativeButton("保留手填地址", null).setPositiveButton("使用建议") { _, _ -> address.setText(proposed); confirmed.isChecked = false }.show()
                        suggestion = proposed
                        status.text = "已得到建议地址。请修改、补参照物，并确认对方能找到。"
                    } else status.text = "位置已取得，但系统没有返回可用文字地址。坐标仅作参考；请手填地址和参照物，确认后再离开。"
                    val editing = snapshot()
                    task(status.text.toString(), { store.reports.save(editing, editing.revision) }) { saved -> draft = saved; locationUi(false) }
                }
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 105 && visible) startLocation()
    }
    private fun stopLocation() {
        serial++; probe.cancel(); cancelLookup?.invoke(); cancelLookup = null
        if (::here.isInitialized) locationUi(false)
    }
    private fun saveAndFinish() {
        if (busy || locating || draft == null) return
        val current = snapshot()
        task("正在保存现场地址…", { store.reports.save(current, current.revision) }) { draft = it; finish() }
    }
    private fun leave() {
        if (busy) return
        if (draft == null) { finish(); return }
        stopLocation()
        val current = snapshot()
        if (current.address.isBlank() || current.landmark.isBlank() || !current.locationConfirmed) {
            AlertDialog.Builder(this).setTitle("地址还没确认，现场步骤尚未完成")
                .setMessage("请在离开现场前填好地址和参照物，并确认对方能找到。也可以暂存，稍后继续核对。")
                .setNegativeButton("继续填写", null).setPositiveButton("暂存待确认") { _, _ -> saveAndFinish() }.show()
        } else saveAndFinish()
    }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() {
        visible = false; stopLocation()
        // Keep edits through normal backgrounding without claiming the unfinished address was confirmed.
        if (!busy && !isFinishing && draft != null && ::address.isInitialized) {
            val current = snapshot()
            task("正在暂存现场地址…", { store.reports.save(current, current.revision) }) { draft = it; locationUi(false) }
        }
        super.onStop()
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Android 9–12 callback")
    override fun onBackPressed() { if (android.os.Build.VERSION.SDK_INT < 33) leave() else super.onBackPressed() }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("reportId", reportId)
        if (draft != null && ::address.isInitialized) out.putString("editing", ReportCodec.encode(snapshot()))
        super.onSaveInstanceState(out)
    }
}
