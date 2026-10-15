package org.rokidfieldlog.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import org.rokidfieldlog.core.*

class MainActivity : Activity() {
    private lateinit var store: PhoneIssueStore
    private lateinit var content: LinearLayout
    private lateinit var records: LinearLayout
    private lateinit var status: TextView
    private lateinit var search: EditText
    private var entries = emptyList<LibraryEntry>()
    private var busy = false
    private var pendingTasks = 0
    private var page = 0
    private lateinit var draftsOnly: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PhoneIssueStore(this)
        page = savedInstanceState?.getInt("page", 0) ?: 0
        content = OfflineUi.column(this)
        content.addView(OfflineUi.text(this, "FieldLog", 28f))
        content.addView(OfflineUi.text(this, "现场报修与复查", 22f))
        content.addView(OfflineUi.text(this, "整理能找到现场、能看清问题的报修材料，并核对后续处理结果。"))
        status = OfflineUi.text(this, "正在读取记录…")
        content.addView(status)
        content.addView(OfflineUi.button(this, "选择手机照片（JPEG）") { if (!busy) choosePhotos() })
        content.addView(OfflineUi.button(this, "报修材料（环境照 + 细节照）") { if (!busy) startActivity(Intent(this, ReportActivity::class.java)) })
        content.addView(OfflineUi.button(this, "问题历史与待复查清单") { if (!busy) startActivity(Intent(this, InspectionActivity::class.java)) })
        content.addView(OfflineUi.button(this, "导入采集包") {
            if (!busy) runCatching {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
                }, 201)
            }.onFailure { status.text = "此设备没有可用的文件选择器" }
        })
        content.addView(OfflineUi.button(this, "载入复查流程演示") {
            if (!busy) AlertDialog.Builder(this).setTitle("载入合成复查演示？")
                .setMessage("生成破损、遮挡、处理后共三张合成图片。问题先停在待复查；第三张供你练习关联和人工确认。")
                .setNegativeButton("取消", null).setPositiveButton("载入") { _, _ ->
                    task("正在准备合成复查…", { InspectionSamples.add(store) }) { issue ->
                        startActivity(Intent(this, InspectionActivity::class.java).putExtra("issueId", issue.issueId))
                    }
                }.show()
        })
        content.addView(OfflineUi.button(this, "载入演示记录") {
            if (!busy) AlertDialog.Builder(this).setTitle("载入合成演示？")
                .setMessage("生成两张合成图片供练习整理、遮挡和导出。演示记录及材料会始终带演示标记。")
                .setNegativeButton("取消", null).setPositiveButton("载入") { _, _ ->
                    task("正在准备演示…", { SyntheticSamples.add(store) }) { report -> refresh("已载入 " + report.added + " 条演示记录") }
                }.show()
        })
        content.addView(OfflineUi.button(this, "定位检查") { if (!busy) startActivity(Intent(this, LocationActivity::class.java)) })
        draftsOnly = CheckBox(this).apply {
            text = "仅显示照片未审阅的记录"
            isChecked = savedInstanceState?.getBoolean("draftsOnly", false) ?: false
            setOnCheckedChangeListener { _, _ -> page = 0; renderEntries() }
        }
        content.addView(draftsOnly)
        search = OfflineUi.field(this, "搜索地址、说明或类别")
        search.setText(savedInstanceState?.getString("query", "") ?: "")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { page = 0; renderEntries() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        content.addView(search)
        records = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(records)
        content.addView(OfflineUi.button(this, "清除演示记录") {
            if (!busy) AlertDialog.Builder(this).setTitle("删除合成演示记录？")
                .setMessage("删除带演示标记的问题历史、记录及其照片。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    task("正在清除演示…", {
                        store.clearSyntheticRecords()
                    }) { refresh("演示记录已清除") }
                }.show()
        })
        OfflineUi.install(this, content)
    }

    override fun onResume() { super.onResume(); refresh() }
    private fun refresh(message: String? = null) {
        if (busy) return
        task(message ?: "正在读取记录…", { runCatching { store.shares.pruneExpired() }; store.library.scan() }) { loaded ->
            entries = loaded.entries
            status.text = (message?.let { it + "\n" } ?: "") + "本机共 " + entries.size + " 条记录。可选择手机 JPEG 或导入采集包。"
            if (loaded.unreadableCount > 0) status.append("\n有 " + loaded.unreadableCount + " 条记录无法读取，原文件已保留。")
            renderEntries()
        }
    }
    private fun renderEntries() {
        if (!::records.isInitialized) return
        records.removeAllViews()
        val query = search.text.toString().trim()
        val shown = entries.filter { (!draftsOnly.isChecked || it.draft.photoReview == null) &&
            (query.isEmpty() || (it.draft.address + it.draft.description + MaterialExporter.categoryLabel(it.draft.category)).contains(query, ignoreCase = true)) }
        val pages = ((shown.size + 19) / 20).coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        records.addView(OfflineUi.text(this, "匹配 " + shown.size + " 条 · 第 " + (page + 1) + " / " + pages + " 页"))
        if (shown.isEmpty()) records.addView(OfflineUi.text(this, if (entries.isEmpty()) "暂无记录。先选择手机照片，或载入演示练习。" else "没有匹配的记录。"))
        for (entry in shown.drop(page * 20).take(20)) {
            val draft = entry.draft
            val card = OfflineUi.card(this)
            card.addView(OfflineUi.text(this, (if (draft.isSynthetic) "[合成演示] " else "") + MaterialExporter.categoryLabel(draft.category), 20f))
            card.addView(OfflineUi.text(this, draft.address.ifBlank { "问题地址待确认" }))
            if (entry.source.record.captureTimeSource == "UNKNOWN_SELECTED_PHOTO") card.addView(OfflineUi.text(this, "手机选入 · 原始拍摄时间和位置未知"))
            card.addView(OfflineUi.text(this, OfflineUi.stage(draft.stage) + " · " + if (draft.photoReview == null) "照片未审阅" else "照片已审阅"))
            card.addView(OfflineUi.button(this, "打开记录") {
                if (!busy) startActivity(Intent(this, IssueActivity::class.java).putExtra("photoId", draft.photoId))
            })
            records.addView(card)
        }
        if (page > 0) records.addView(OfflineUi.button(this, "上一页记录") { if (!busy) { page--; renderEntries() } })
        if (page + 1 < pages) records.addView(OfflineUi.button(this, "下一页记录") { if (!busy) { page++; renderEntries() } })
    }
    private fun <T> task(message: String, action: () -> T, done: (T) -> Unit) {
        pendingTasks++; busy = true; status.text = message; OfflineUi.enable(content, false)
        PrivateProbeStore.fileExecutor.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                pendingTasks--; busy = pendingTasks > 0; OfflineUi.enable(content, !busy)
                result.fold(done) { status.text = OfflineUi.error(it) }
            }
        }
    }
    @Deprecated("Uses the system document picker for Android 9 and newer")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 204 && resultCode == RESULT_OK) {
            runCatching {
                val clip = data?.clipData
                val uris = if (clip != null) {
                    require(clip.itemCount in 1..OfflineLibrary.MAX_SELECTED_PHOTOS) { "每次请选择 1–20 张照片" }
                    (0 until clip.itemCount).map { clip.getItemAt(it).uri }
                } else listOfNotNull(data?.data)
                require(uris.isNotEmpty() && uris.all { it?.scheme == "content" }) { "请选择系统提供的照片文件" }
                importPhotos(uris.filterNotNull().distinct())
            }.onFailure { status.text = OfflineUi.error(it) }
            return
        }
        if (requestCode == 201 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            task("正在校验并导入…", { checkNotNull(contentResolver.openInputStream(uri)).use { store.library.importPackage(it) } }) { report ->
                refresh("导入 " + report.added + " 条，跳过 " + report.duplicates + " 条重复记录")
            }
        }
    }
    private fun choosePhotos() {
        fun documents() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/jpeg"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val picker = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            type = "image/jpeg"
            putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(OfflineLibrary.MAX_SELECTED_PHOTOS, MediaStore.getPickImagesMaxLimit()))
        } else documents()
        runCatching { startActivityForResult(picker, 204) }
            .recoverCatching { startActivityForResult(documents(), 204) }
            .onFailure { status.text = "此设备没有可用的照片选择器" }
    }
    private fun importPhotos(uris: List<Uri>) {
        task("正在复制并校验照片；请稍候…", {
            store.library.importPhotos(uris.map { uri -> { checkNotNull(contentResolver.openInputStream(uri)) { "照片暂时无法读取，请重新选择" } } })
        }) { report ->
            page = 0
            refresh("已导入 " + report.added + " 张，跳过 " + report.duplicates + " 张重复照片。下一步：打开记录审阅照片，或建立报修材料。")
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("page", page)
        out.putBoolean("draftsOnly", draftsOnly.isChecked)
        out.putString("query", search.text.toString())
        super.onSaveInstanceState(out)
    }
}
