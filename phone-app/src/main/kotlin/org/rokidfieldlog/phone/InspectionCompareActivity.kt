package org.rokidfieldlog.phone

import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputFilter
import android.widget.CheckBox
import android.widget.LinearLayout
import org.rokidfieldlog.core.*

class InspectionCompareActivity : InspectionScreen() {
    private var issueId = ""
    private var views = mutableListOf<RedactionView>()
    private var restored: Bundle? = null
    private var current: InspectionComparison? = null
    private lateinit var note: android.widget.EditText
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        issueId = intent.getStringExtra("issueId") ?: ""
        if (!CaptureFiles.validId(issueId)) { finish(); return }
        restored = state
        loadComparison()
    }
    private fun loadComparison() = task("正在校验原图并生成对照…", {
        val comparison = store.inspections.comparison(issueId)
        val baseline = store.uprightPreview(comparison.baselinePhoto)
        val latest = if (comparison.baseline.photoId == comparison.latest.photoId) baseline else store.uprightPreview(comparison.latestPhoto)
        Triple(comparison, baseline, latest)
    }) { (comparison, baseline, latest) -> render(comparison, baseline, latest) }
    private fun render(comparison: InspectionComparison, baseline: Bitmap, latest: Bitmap) {
        current = comparison
        views.forEach { it.release() }; views.clear(); content.removeAllViews()
        content.addView(OfflineUi.text(this, InspectionText.title(comparison.issue), 24f))
        status = OfflineUi.text(this, "照片对应各次观察的可见范围。请分别放大检查位置、遮挡和原问题。")
        content.addView(status)
        photoPanel("最初观察 · " + comparison.baseline.observedOn, baseline)
        photoPanel("最近观察 · " + comparison.latest.observedOn + " · " + InspectionText.finding(comparison.latest.finding), latest)
        if (comparison.baseline.eventId == comparison.latest.eventId) content.addView(OfflineUi.text(this, "目前只有一次观察，需要新的现场照片才能形成前后对照。"))
        content.addView(OfflineUi.text(this, "最近照片：" + InspectionText.visibility(comparison.latest.visibility)))
        content.addView(OfflineUi.text(this, "确认已处理需要：维修反馈之后的新观察、位置清楚可见、未见原问题，并由你核对拍摄先后。无法确认时保留待复查。"))
        note = OfflineUi.field(this, "复查依据或无法判断的原因（必填）", multiline = true).apply { filters = arrayOf(InputFilter.LengthFilter(500)) }
        restored?.takeIf { it.getLong("revision", -1) == comparison.issue.revision }?.let { note.setText(it.getString("note")) }
        restored = null
        content.addView(note)
        val reviewed = CheckBox(this).apply { text = "我已核对照片对应同一位置及所选结论" }
        val chronology = CheckBox(this).apply { text = "我已确认最近照片拍摄于最近维修反馈之后" }
        content.addView(reviewed); content.addView(chronology)
        fun record(decision: VerificationDecision) {
            if (!reviewed.isChecked || note.text.isBlank()) { status.text = "请填写复查依据并勾选照片核对确认"; return }
            val noteValue = note.text.toString(); val ordered = chronology.isChecked
            task("正在重新校验照片并保存结论…", {
                store.inspections.verify(issueId, comparison.issue.revision, comparison.latest.eventId, decision, ordered, noteValue)
            }) { finish() }
        }
        content.addView(OfflineUi.button(this, "确认已处理") { record(VerificationDecision.REPAIRED) })
        content.addView(OfflineUi.button(this, "确认问题仍存在") { record(VerificationDecision.STILL_PRESENT) })
        content.addView(OfflineUi.button(this, "无法确认，保留待复查") { record(VerificationDecision.INCONCLUSIVE) })
        content.addView(OfflineUi.button(this, "返回问题历史") { finish() })
    }
    private fun photoPanel(title: String, bitmap: Bitmap) {
        content.addView(OfflineUi.text(this, title, 18f))
        val image = RedactionView(this).apply { drawMode = false; setImage(bitmap); contentDescription = title + "，可缩放查看" }
        views += image
        val height = (260 * resources.displayMetrics.density).toInt()
        content.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
        val buttons = LinearLayout(this)
        listOf(OfflineUi.button(this, "放大") { image.changeZoom(2f) }, OfflineUi.button(this, "缩小") { image.changeZoom(0.5f) },
            OfflineUi.button(this, "适应") { image.fit() }).forEach { buttons.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        content.addView(buttons)
    }
    override fun onSaveInstanceState(out: Bundle) {
        current?.let { out.putLong("revision", it.issue.revision); if (::note.isInitialized) out.putString("note", note.text.toString()) }
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() { views.forEach { it.release() }; super.onDestroy() }
}
