package org.rokidfieldlog.phone

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import org.rokidfieldlog.core.*
import java.io.File
import java.time.LocalDate

class IssueActivity : Activity() {
    private lateinit var store: PhoneIssueStore
    private lateinit var id: String
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var category: Spinner
    private lateinit var address: EditText
    private lateinit var confirmed: CheckBox
    private lateinit var description: EditText
    private lateinit var date: Button
    private lateinit var latitude: EditText
    private lateinit var longitude: EditText
    private lateinit var crs: Spinner
    private var photographerReference: PhotographerReference? = null
    private var eventDate = ""
    private var entry: LibraryEntry? = null
    private var busy = false
    private var savedForm: Bundle? = null
    private var pending: PreparedMaterial? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("photoId") ?: ""
        if (!CaptureFiles.validId(id)) { finish(); return }
        store = PhoneIssueStore(this)
        savedForm = savedInstanceState
        savedInstanceState?.getString("exportName")?.let { name ->
            if (name.matches(Regex("[0-9a-f-]{36}\\.zip"))) pending = PreparedMaterial(id,
                savedInstanceState.getLong("exportRevision"), File(store.root, "exports/$name"), savedInstanceState.getString("exportHash") ?: "")
        }
        content = OfflineUi.column(this)
        status = OfflineUi.text(this, "正在读取记录…")
        content.addView(status)
        OfflineUi.install(this, content)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { leave() }
        }
    }
    override fun onResume() { super.onResume(); if (!busy && ::store.isInitialized && !isFinishing) reload() }
    private fun reload() {
        if (entry != null && ::category.isInitialized) savedForm = Bundle().also(::captureFormState)
        task("正在读取记录…", { store.library.load(id) }) { loaded -> entry = loaded; render(loaded) }
    }
    private fun render(loaded: LibraryEntry) {
        content.removeAllViews()
        val draft = loaded.draft
        val currentForm = savedForm?.takeIf { it.getLong("formRevision", -1) == draft.revision }
        content.addView(OfflineUi.text(this, if (draft.isSynthetic) "合成演示记录" else "核对记录", 26f))
        content.addView(OfflineUi.text(this, "原图保留在本机；先保存编辑，再检查照片隐私。"))
        status = OfflineUi.text(this, OfflineUi.stage(draft.stage))
        content.addView(status)
        category = Spinner(this).apply {
            adapter = ArrayAdapter(this@IssueActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("请选择问题类别") + IssueCategory.entries.map { MaterialExporter.categoryLabel(it) })
            setSelection(draft.category?.ordinal?.plus(1) ?: 0)
        }
        content.addView(OfflineUi.text(this, "问题类别")); content.addView(category)
        address = OfflineUi.field(this, "道路、门牌或附近明显地标", draft.address).apply { filters = arrayOf(InputFilter.LengthFilter(200)) }
        content.addView(OfflineUi.text(this, "问题文字地址")); content.addView(address)
        confirmed = CheckBox(this).apply { text = "我已核对问题地址"; isChecked = draft.addressConfirmed }
        content.addView(confirmed)
        description = OfflineUi.field(this, "说明现场现象和希望处理的事项", draft.description, true).apply { filters = arrayOf(InputFilter.LengthFilter(2000)) }
        content.addView(OfflineUi.text(this, "情况说明")); content.addView(description)
        eventDate = draft.eventDate
        date = OfflineUi.button(this, "事发日期：" + eventDate.ifBlank { "待人工确认" }) {
            val initial = runCatching { LocalDate.parse(eventDate) }.getOrElse { LocalDate.now() }
            DatePickerDialog(this, { _, year, month, day ->
                eventDate = LocalDate.of(year, month + 1, day).toString(); date.text = "事发日期：" + eventDate
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        }
        content.addView(date)
        content.addView(OfflineUi.text(this, "拍摄时钟可能不准确，事发日期需要你确认。"))
        content.addView(OfflineUi.text(this, "问题坐标（可选）", 19f))
        content.addView(OfflineUi.text(this, "没有确认坐标时可以留空，使用文字地址。"))
        latitude = coordinateField("纬度", draft.issuePosition?.latitude?.toString() ?: "")
        longitude = coordinateField("经度", draft.issuePosition?.longitude?.toString() ?: "")
        content.addView(latitude); content.addView(longitude)
        crs = Spinner(this).apply {
            adapter = ArrayAdapter(this@IssueActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("请选择坐标系", "WGS84", "GCJ02", "BD09"))
            setSelection(draft.issuePosition?.coordinateSystem?.ordinal?.plus(1) ?: 0)
        }
        content.addView(crs)
        loaded.source.record.phoneLocation?.let { observation ->
            content.addView(OfflineUi.text(this, "原始拍摄者位置：" + observation.latitude + ", " + observation.longitude +
                "（" + observation.coordinateSystem + "）。问题实际位置需要独立确认。"))
        }
        photographerReference = currentForm?.getString("referenceDraft")?.let { runCatching { DraftCodec.decode(it, id).photographerReference }.getOrNull() } ?: draft.photographerReference
        if (currentForm?.getBoolean("referenceRemoved", false) == true) photographerReference = null
        ReferenceLocationPicker.add(this, content, photographerReference) { photographerReference = it }
        content.addView(OfflineUi.text(this, "照片：" + if (draft.photoReview == null) "尚未完成隐私审阅" else "已审阅导出副本"))
        content.addView(OfflineUi.button(this, "关联问题 / 添加本次观察") {
            saveForm { startActivity(Intent(this, InspectionLinkActivity::class.java).putExtra("photoId", id)) }
        })
        content.addView(OfflineUi.button(this, "保存编辑") { saveForm { status.text = "编辑已保存" } })
        content.addView(OfflineUi.button(this, "查看照片 / 隐私遮挡") {
            saveForm { startActivity(Intent(this, PrivacyActivity::class.java).putExtra("photoId", id)) }
        })
        content.addView(OfflineUi.text(this, "导出采用通用材料格式，一条记录一张 JPG、说明文本和归档清单；请再核对当地官方表单要求。"))
        content.addView(OfflineUi.button(this, "用这张照片新建多图报修材料") {
            saveForm { startActivity(Intent(this, ReportActivity::class.java).putExtra("create", true).putExtra("photoId", id)) }
        })
        content.addView(OfflineUi.button(this, "保存单图人工提交材料") {
            saveForm {
                task("正在校验照片和生成材料…", { store.library.prepareMaterial(id) },
                    onAbandoned = { store.library.discardMaterial(it) }) { prepared ->
                    pending = prepared
                    runCatching {
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                            putExtra(Intent.EXTRA_TITLE, "FieldLog_" + (if (entry!!.draft.isSynthetic) "演示_" else "") + entry!!.draft.eventDate + ".zip")
                        }, 202)
                    }.onFailure { discardPending(); status.text = "此设备没有可用的保存位置选择器" }
                }
            }
        })
        if (draft.receipt.isNotBlank()) content.addView(OfflineUi.text(this, "保留的回执 / 处理记录：\n" + draft.receipt))
        content.addView(OfflineUi.button(this, "记录官方回执 / 处理结果") { receiptDialog() })
        content.addView(OfflineUi.button(this, "删除这条本机记录") {
            if (!busy) AlertDialog.Builder(this).setTitle("删除这条记录及其照片？")
                .setMessage("已保存到自选位置的材料文件由你管理。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    task("正在删除…", { store.library.delete(id) }) { finish() }
                }.show()
        })
        content.addView(OfflineUi.button(this, "保存并返回列表") { leave() })
        currentForm?.let { state ->
            category.setSelection(state.getInt("category")); address.setText(state.getString("address"))
            confirmed.isChecked = state.getBoolean("confirmed"); description.setText(state.getString("description"))
            eventDate = state.getString("date") ?: ""; date.text = "事发日期：" + eventDate.ifBlank { "待人工确认" }
            latitude.setText(state.getString("latitude")); longitude.setText(state.getString("longitude")); crs.setSelection(state.getInt("crs"))
        }
        savedForm = null
        address.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { confirmed.isChecked = false }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private fun coordinateField(hint: String, value: String) = OfflineUi.field(this, hint, value).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    }
    private fun snapshot(): IssueDraft {
        val current = checkNotNull(entry).draft
        val lat = latitude.text.toString().trim(); val lon = longitude.text.toString().trim()
        val point = if (lat.isEmpty() && lon.isEmpty()) null else {
            require(lat.isNotEmpty() && lon.isNotEmpty() && crs.selectedItemPosition > 0) { "请填写完整坐标并选择坐标系，或将坐标留空" }
            GeoPoint(lat.toDoubleOrNull() ?: error("纬度格式有误"), lon.toDoubleOrNull() ?: error("经度格式有误"), CoordinateSystem.entries[crs.selectedItemPosition - 1])
        }
        return current.copy(category = IssueCategory.entries.getOrNull(category.selectedItemPosition - 1),
            address = address.text.toString().trim(), addressConfirmed = confirmed.isChecked,
            description = description.text.toString().trim(), eventDate = eventDate, issuePosition = point, photographerReference = photographerReference)
    }
    private fun saveForm(done: () -> Unit) {
        if (busy || entry == null) return
        val snapshot = runCatching { snapshot() }.getOrElse { status.text = OfflineUi.error(it); return }
        task("正在保存编辑…", { store.library.save(snapshot, snapshot.revision) }) { updated -> entry = updated; done() }
    }
    private fun leave() {
        if (busy) return
        val current = entry ?: run { finish(); return }
        val edited = runCatching { snapshot() }.getOrElse { error ->
            AlertDialog.Builder(this).setTitle("编辑尚未保存").setMessage(OfflineUi.error(error))
                .setPositiveButton("继续编辑", null)
                .setNegativeButton("放弃编辑并返回") { _, _ -> finish() }.show()
            return
        }
        if (edited == current.draft) finish() else saveForm { finish() }
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Back callback for Android 9–12")
    override fun onBackPressed() { if (android.os.Build.VERSION.SDK_INT < 33) leave() else super.onBackPressed() }
    private fun receiptDialog() {
        saveForm {
            val box = OfflineUi.column(this)
            val stages = Spinner(this).apply { adapter = ArrayAdapter(this@IssueActivity, android.R.layout.simple_spinner_dropdown_item, listOf("已在官方渠道提交", "已收到处理结果")) }
            val receipt = OfflineUi.field(this, "回执编号或处理说明", entry!!.draft.receipt, true)
            box.addView(OfflineUi.text(this, "请在你自行完成提交或核对结果后记录。材料导出不会自动提交。"))
            box.addView(stages); box.addView(receipt)
            AlertDialog.Builder(this).setTitle("记录回执").setView(box).setNegativeButton("取消", null)
                .setPositiveButton("保存") { _, _ ->
                    val base = entry!!.draft
                    val stage = if (stages.selectedItemPosition == 0) SubmissionStage.SUBMITTED else SubmissionStage.RESOLVED
                    val value = receipt.text.toString()
                    task("正在保存回执…", { store.library.updateReceipt(id, base.revision, stage, value) }) { updated -> entry = updated; render(updated) }
                }.show()
        }
    }
    private fun <T> task(message: String, action: () -> T, onAbandoned: ((T) -> Unit)? = null, done: (T) -> Unit) {
        if (busy) return
        busy = true; status.text = message; OfflineUi.enable(content, false)
        PrivateProbeStore.fileExecutor.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    result.onSuccess { value -> onAbandoned?.let { cleanup ->
                        PrivateProbeStore.fileExecutor.execute { runCatching { cleanup(value) } }
                    } }
                    return@runOnUiThread
                }
                busy = false; OfflineUi.enable(content, true)
                result.fold(done) { status.text = OfflineUi.error(it) }
            }
        }
    }
    private fun discardPending() {
        val prepared = pending ?: return
        pending = null
        PrivateProbeStore.fileExecutor.execute { runCatching { store.library.discardMaterial(prepared) } }
    }
    @Deprecated("System document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 202) return
        val prepared = pending ?: return
        val uri = data?.data
        pending = null
        if (resultCode != RESULT_OK || uri == null) {
            PrivateProbeStore.fileExecutor.execute { runCatching { store.library.discardMaterial(prepared) } }
            status.text = "已取消材料保存"; return
        }
        task("正在写入你选择的位置…", {
            try {
                MaterialDocumentCopy.copy(
                    validate = { store.library.validatePrepared(prepared) },
                    openInput = { prepared.file.inputStream() },
                    openOutput = { checkNotNull(contentResolver.openOutputStream(uri, "w")) },
                    deletePartial = { DocumentsContract.deleteDocument(contentResolver, uri) })
                store.library.markExported(id, prepared.revision)
                store.library.load(id)
            } finally { store.library.discardMaterial(prepared) }
        }) { updated -> entry = updated; render(updated); status.text = "材料已保存，请到官方渠道人工提交。" }
    }
    private fun captureFormState(out: Bundle) {
        entry?.let {
            if (::category.isInitialized) {
                out.putString("referenceDraft", DraftCodec.encode(it.draft.copy(photographerReference = photographerReference)))
                out.putBoolean("referenceRemoved", photographerReference == null)
                out.putLong("formRevision", it.draft.revision); out.putInt("category", category.selectedItemPosition)
                out.putString("address", address.text.toString()); out.putBoolean("confirmed", confirmed.isChecked)
                out.putString("description", description.text.toString()); out.putString("date", eventDate)
                out.putString("latitude", latitude.text.toString()); out.putString("longitude", longitude.text.toString()); out.putInt("crs", crs.selectedItemPosition)
            }
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        captureFormState(out)
        pending?.let { out.putString("exportName", it.file.name); out.putLong("exportRevision", it.revision); out.putString("exportHash", it.sha256) }
        super.onSaveInstanceState(out)
    }
}
