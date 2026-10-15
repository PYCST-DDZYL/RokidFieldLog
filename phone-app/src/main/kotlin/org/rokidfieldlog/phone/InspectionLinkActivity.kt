package org.rokidfieldlog.phone

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.*
import org.rokidfieldlog.core.*
import java.time.LocalDate

class InspectionLinkActivity : InspectionScreen() {
    private var entries = emptyList<LibraryEntry>()
    private var targets = emptyList<TrackedIssue?>()
    private lateinit var photos: Spinner
    private lateinit var target: Spinner
    private lateinit var site: EditText
    private lateinit var category: Spinner
    private lateinit var visibility: Spinner
    private lateinit var finding: Spinner
    private lateinit var note: EditText
    private lateinit var confirmed: CheckBox
    private lateinit var date: Button
    private lateinit var newFields: LinearLayout
    private lateinit var observationFields: LinearLayout
    private lateinit var targetDescription: TextView
    private var observedOn = ""
    private var lastPhotoId = ""
    private var restored: Bundle? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); restored = state
        val fixedPhoto = intent.getStringExtra("photoId")
        val fixedIssue = intent.getStringExtra("issueId")
        if (listOfNotNull(fixedPhoto, fixedIssue).any { !CaptureFiles.validId(it) }) { finish(); return }
        task("正在查找可关联的照片…", {
            val listing = store.inspections.scan()
            check(listing.unreadableCount == 0) { "有问题历史无法读取，请保留数据并先核查" }
            val linked = fixedPhoto?.let { store.inspections.linkedIssue(it) }
            val selectedIssue = fixedIssue?.let { id -> listing.issues.single { it.issueId == id } }
            val used = listing.issues.flatMap { it.observations }.mapNotNull { it.photoId }.toSet()
            val available = store.library.list().filter { (fixedPhoto == null || it.draft.photoId == fixedPhoto) &&
                it.draft.photoId !in used && (selectedIssue == null || selectedIssue.isSynthetic == it.draft.isSynthetic) }
            LinkData(available, if (selectedIssue != null) listOf(selectedIssue) else listOf(null) + listing.issues, linked)
        }) { data ->
            if (data.linked != null) {
                startActivity(Intent(this, InspectionActivity::class.java).putExtra("issueId", data.linked.issueId)); finish()
            } else {
                entries = data.entries; targets = data.targets
                if (entries.isEmpty()) status.text = "没有可关联的新照片。请先导入另一张现场记录；同一照片只计为一次观察。"
                else render()
            }
        }
    }
    private data class LinkData(val entries: List<LibraryEntry>, val targets: List<TrackedIssue?>, val linked: TrackedIssue?)
    private fun spinner(labels: List<String>) = Spinner(this).apply { adapter = ArrayAdapter(this@InspectionLinkActivity,
        android.R.layout.simple_spinner_dropdown_item, labels) }
    private fun render() {
        content.removeAllViews(); content.addView(OfflineUi.text(this, "关联现场观察", 25f))
        status = OfflineUi.text(this, "请核对照片、位置和观察日期。关联由你确认，不会按 GPS 自动合并。")
        content.addView(status)
        val saved = restored
        val photoIndex = entries.indexOfFirst { it.draft.photoId == saved?.getString("photo") }.coerceAtLeast(0)
        photos = spinner(entries.map { (if (it.draft.isSynthetic) "[演示] " else "") + it.draft.address.ifBlank { "地址待确认" } + " · " + it.draft.photoId.take(8) })
        photos.setSelection(photoIndex); lastPhotoId = entries[photoIndex].draft.photoId
        content.addView(OfflineUi.text(this, "本次照片记录")); content.addView(photos)
        content.addView(OfflineUi.button(this, "先查看这张照片") {
            startActivity(Intent(this, IssueActivity::class.java).putExtra("photoId", entries[photos.selectedItemPosition].draft.photoId))
        })
        target = spinner(targets.map { it?.let(InspectionText::title) ?: "建立一个新问题" })
        target.setSelection(targets.indexOfFirst { it?.issueId == saved?.getString("target") }.coerceAtLeast(0))
        content.addView(OfflineUi.text(this, "关联到哪个问题")); content.addView(target)
        targetDescription = OfflineUi.text(this, ""); content.addView(targetDescription)
        newFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        site = OfflineUi.field(this, "能区分位置的道路、楼栋、地标", entries[photoIndex].draft.address.takeIf { entries[photoIndex].draft.addressConfirmed } ?: "")
            .apply { filters = arrayOf(InputFilter.LengthFilter(200)) }
        category = spinner(listOf("请选择类别") + IssueCategory.entries.map { MaterialExporter.categoryLabel(it) })
        category.setSelection(entries[photoIndex].draft.category?.ordinal?.plus(1) ?: 0)
        newFields.addView(OfflineUi.text(this, "问题位置名称")); newFields.addView(site); newFields.addView(category)
        newFields.addView(OfflineUi.text(this, "首张观察必须清楚显示此处问题。")); content.addView(newFields)
        observationFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        visibility = spinner(listOf("请选择可见情况") + InspectionVisibility.entries.map { InspectionText.visibility(it) })
        finding = spinner(listOf("请选择本次观察") + InspectionFinding.entries.map { InspectionText.finding(it) })
        observationFields.addView(visibility); observationFields.addView(finding); content.addView(observationFields)
        observedOn = saved?.getString("date") ?: entries[photoIndex].draft.eventDate
        date = OfflineUi.button(this, "观察日期：" + observedOn.ifBlank { "待确认" }) {
            val initial = runCatching { LocalDate.parse(observedOn) }.getOrElse { LocalDate.now() }
            DatePickerDialog(this, { _, year, month, day -> observedOn = LocalDate.of(year, month + 1, day).toString(); updateDate() },
                initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        }; content.addView(date)
        note = OfflineUi.field(this, "本次观察说明（最多 500 字）", multiline = true).apply { filters = arrayOf(InputFilter.LengthFilter(500)) }
        content.addView(note)
        confirmed = CheckBox(this).apply { text = "我已查看照片，核对问题位置、观察日期和所选情况" }; content.addView(confirmed)
        content.addView(OfflineUi.button(this, "保存到问题历史") { submit() })
        saved?.let {
            site.setText(it.getString("site", site.text.toString())); category.setSelection(it.getInt("category", category.selectedItemPosition))
            visibility.setSelection(it.getInt("visibility")); finding.setSelection(it.getInt("finding"))
            note.setText(it.getString("note")); confirmed.isChecked = it.getBoolean("confirmed")
        }
        restored = null; applyTarget()
        target.onItemSelectedListener = selection { applyTarget(); confirmed.isChecked = false }
        photos.onItemSelectedListener = selection {
            val entry = entries[photos.selectedItemPosition]
            if (lastPhotoId != entry.draft.photoId) {
                lastPhotoId = entry.draft.photoId; observedOn = entry.draft.eventDate; updateDate(); confirmed.isChecked = false
                visibility.setSelection(0); finding.setSelection(0)
                if (targets[target.selectedItemPosition] == null) {
                    site.setText(entry.draft.address.takeIf { entry.draft.addressConfirmed } ?: "")
                    category.setSelection(entry.draft.category?.ordinal?.plus(1) ?: 0)
                }
            }
        }
    }
    private fun selection(action: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { action() }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    private fun applyTarget() {
        val issue = targets[target.selectedItemPosition]
        newFields.visibility = if (issue == null) View.VISIBLE else View.GONE
        observationFields.visibility = if (issue == null) View.GONE else View.VISIBLE
        targetDescription.text = issue?.let { InspectionText.title(it) + " · " + InspectionText.state(it.state) } ?: "新的问题将获得独立编号。"
    }
    private fun updateDate() { date.text = "观察日期：" + observedOn.ifBlank { "待确认" } }
    private fun submit() {
        if (busy) return
        if (!confirmed.isChecked || runCatching { LocalDate.parse(observedOn) }.isFailure) { status.text = "请核对照片并确认观察日期"; return }
        val issue = targets[target.selectedItemPosition]; val entry = entries[photos.selectedItemPosition]
        val label = site.text.toString(); val chosenCategory = IssueCategory.entries.getOrNull(category.selectedItemPosition - 1)
        val chosenVisibility = InspectionVisibility.entries.getOrNull(visibility.selectedItemPosition - 1)
        val chosenFinding = InspectionFinding.entries.getOrNull(finding.selectedItemPosition - 1)
        if (issue == null && (label.isBlank() || chosenCategory == null)) { status.text = "请填写位置名称并选择类别"; return }
        if (issue != null && (chosenVisibility == null || chosenFinding == null)) { status.text = "请选择照片可见情况和观察结果"; return }
        val dateValue = observedOn; val noteValue = note.text.toString()
        task("正在校验照片并保存观察…", {
            if (issue == null) store.inspections.create(label, chosenCategory!!, entry.draft.photoId, dateValue, true, noteValue)
            else store.inspections.observe(issue.issueId, issue.revision, entry.draft.photoId, dateValue,
                chosenVisibility!!, chosenFinding!!, true, noteValue)
        }) { updated -> startActivity(Intent(this, InspectionActivity::class.java).putExtra("issueId", updated.issueId)); finish() }
    }
    override fun onSaveInstanceState(out: Bundle) {
        if (::photos.isInitialized) {
            out.putString("photo", entries[photos.selectedItemPosition].draft.photoId); out.putString("target", targets[target.selectedItemPosition]?.issueId)
            out.putString("site", site.text.toString()); out.putInt("category", category.selectedItemPosition)
            out.putInt("visibility", visibility.selectedItemPosition); out.putInt("finding", finding.selectedItemPosition)
            out.putString("date", observedOn); out.putString("note", note.text.toString()); out.putBoolean("confirmed", confirmed.isChecked)
        }; super.onSaveInstanceState(out)
    }
}
