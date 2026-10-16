package org.rokidfieldlog.phone

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.widget.*
import org.rokidfieldlog.core.*
import java.time.LocalDate

/** Manual attempt notes stay with this issue's material, outside all outgoing exports. */
class ReportHandoffActivity : InspectionScreen() {
    private lateinit var reportId: String
    private var draft: ReportDraft? = null
    private var restored: ReportDraft? = null
    private lateinit var receipt: EditText
    private lateinit var extra: EditText
    private lateinit var rejected: EditText
    private lateinit var minutes: EditText
    private lateinit var album: Spinner
    private lateinit var date: Button
    private var attemptedOn = ""
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        reportId = intent.getStringExtra("reportId") ?: ""
        if (!CaptureFiles.validId(reportId)) { finish(); return }
        restored = state?.getString("editing")?.let { runCatching { ReportCodec.decode(it, reportId) }.getOrNull() }
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { leave() }
    }
    override fun onResume() {
        super.onResume()
        val editing = restored ?: draft?.let { snapshot() }; restored = null
        task("正在读取这条问题的回执…", { store.reports.load(reportId) }) { saved ->
            render(editing?.takeIf { it.revision == saved.revision } ?: saved)
        }
    }
    private fun field(label: String, value: String, limit: Int, multiline: Boolean = true): EditText {
        content.addView(OfflineUi.text(this, label))
        return OfflineUi.field(this, label, value, multiline).apply { filters = arrayOf(InputFilter.LengthFilter(limit)); content.addView(this) }
    }
    private fun render(value: ReportDraft) {
        draft = value; val note = value.handoff
        content.removeAllViews(); content.addView(OfflineUi.text(this, "回执与试交记录", 25f))
        status.text = "问题 #" + reportId.take(8) + " · " + value.address
        content.addView(status)
        content.addView(OfflineUi.text(this, "只记录实际发生的人工提交。分享、打开网页和保存ZIP都不会自动填回执，也不代表受理或修复。"))
        receipt = field("回执号 / 对方明确回复（没有就留空）", note.receipt, 200)
        attemptedOn = note.attemptedOn
        date = OfflineUi.button(this, "本次实际试交日期：" + attemptedOn.ifBlank { "尚未登记" }) {
            val initial = runCatching { LocalDate.parse(attemptedOn) }.getOrElse { LocalDate.now() }
            DatePickerDialog(this, { _, year, month, day ->
                attemptedOn = LocalDate.of(year, month + 1, day).toString(); date.text = "本次实际试交日期：" + attemptedOn
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        }; content.addView(date)
        extra = field("对方额外要求了什么（最多1000字）", note.extraRequirements, 1000)
        content.addView(OfflineUi.text(this, value.photos.mapIndexed { index, photo -> "照片" + (index + 1) + " = #" + photo.photoId.take(8) + "（" + ReportReadiness.roleLabel(photo.role) + "）" }.joinToString("\n")))
        rejected = field("哪张照片被退，原因是什么（写照片编号，最多1000字）", note.rejectedPhotos, 1000)
        minutes = field("本次人工整理与提交一共花了多少分钟（实测）", note.elapsedMinutes, 5, false).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        content.addView(OfflineUi.text(this, "相册 / 打码照片：这一次得到什么结果？"))
        album = Spinner(this).apply {
            adapter = ArrayAdapter(this@ReportHandoffActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("尚未验证", "本次接受了打码相册照片", "本次拒绝了打码相册照片", "本批无需打码，不能据此判断打码接收"))
            setSelection(listOf("UNKNOWN", "ACCEPTED", "REJECTED", "NOT_NEEDED").indexOf(note.albumResult).coerceAtLeast(0))
        }; content.addView(album)
        content.addView(OfflineUi.button(this, "保存这条问题的记录") { save { render(it); status.append("\n已保存。") } })
        content.addView(OfflineUi.button(this, "有回执后：关联现有问题历史 / 继续复查") {
            save { saved ->
                if (saved.handoff.receipt.isBlank()) { status.text = "先记下真实回执；尚未取得回执可以保存试交记录。"; return@save }
                task("正在查找已有现场观察…", {
                    saved.handoff.inspectionIssueId?.let { runCatching { store.inspections.load(it) }.getOrNull() }
                        ?: saved.photos.mapNotNull { store.inspections.linkedIssue(it.photoId) }.distinctBy { it.issueId }.also {
                            require(it.size <= 1) { "材料照片分属多个问题，请先核对关联" }
                        }.singleOrNull()
                }) { linked ->
                    if (linked != null) startActivity(Intent(this, InspectionActivity::class.java).putExtra("issueId", linked.issueId))
                    else if (saved.photos.isEmpty()) status.text = "请先把这次现场照片加入材料，再建立观察关联。"
                    else startActivity(Intent(this, InspectionLinkActivity::class.java).putExtra("reportId", saved.reportId))
                }
            }
        })
        content.addView(OfflineUi.text(this, "沿用现有的观察关联和前后对照。回执不会自动变成维修完成，也不会替人判断修没修。"))
        content.addView(OfflineUi.button(this, "保存并返回材料") { save { finish() } })
    }
    private fun snapshot() = checkNotNull(draft).copy(handoff = draft!!.handoff.copy(
        receipt = receipt.text.toString().trim(), attemptedOn = attemptedOn,
        extraRequirements = extra.text.toString().trim(), rejectedPhotos = rejected.text.toString().trim(),
        elapsedMinutes = minutes.text.toString().trim(), albumResult = listOf("UNKNOWN", "ACCEPTED", "REJECTED", "NOT_NEEDED")[album.selectedItemPosition]))
    private fun save(done: (ReportDraft) -> Unit) {
        if (busy || draft == null) return
        val current = snapshot()
        task("正在保存回执与试交记录…", { store.reports.save(current, current.revision) }) { draft = it; done(it) }
    }
    private fun leave() { if (draft == null) finish() else save { finish() } }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Android 9–12 callback")
    override fun onBackPressed() { if (android.os.Build.VERSION.SDK_INT < 33) leave() else super.onBackPressed() }
    override fun onSaveInstanceState(out: Bundle) {
        if (draft != null && ::receipt.isInitialized) out.putString("editing", ReportCodec.encode(snapshot()))
        super.onSaveInstanceState(out)
    }
}
