package org.rokidfieldlog.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PhoneIssueStore(this)
        content = OfflineUi.column(this)
        content.addView(OfflineUi.text(this, "FieldLog", 28f))
        content.addView(OfflineUi.text(this, "记录整理", 22f))
        content.addView(OfflineUi.text(this, "核对现场记录，处理照片隐私，再准备人工提交材料。"))
        status = OfflineUi.text(this, "正在读取记录…")
        content.addView(status)
        content.addView(OfflineUi.button(this, "导入采集包") {
            if (!busy) runCatching {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
                }, 201)
            }.onFailure { status.text = "此设备没有可用的文件选择器" }
        })
        content.addView(OfflineUi.button(this, "载入演示记录") {
            if (!busy) AlertDialog.Builder(this).setTitle("载入合成演示？")
                .setMessage("生成两张合成图片供练习整理、遮挡和导出。演示记录及材料会始终带演示标记。")
                .setNegativeButton("取消", null).setPositiveButton("载入") { _, _ ->
                    task("正在准备演示…", { SyntheticSamples.add(store) }) { report -> refresh("已载入 " + report.added + " 条演示记录") }
                }.show()
        })
        content.addView(OfflineUi.button(this, "定位检查") { if (!busy) startActivity(Intent(this, LocationActivity::class.java)) })
        search = OfflineUi.field(this, "搜索地址、说明或类别")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderEntries() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        content.addView(search)
        records = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(records)
        content.addView(OfflineUi.button(this, "清除演示记录") {
            if (!busy) AlertDialog.Builder(this).setTitle("删除合成演示记录？")
                .setMessage("仅删除带演示标记的记录及其照片。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    task("正在清除演示…", {
                        val ids = store.library.list().filter { it.draft.isSynthetic }.map { it.draft.photoId }
                        ids.forEach { store.library.delete(it) }; ids.size
                    }) { refresh("演示记录已清除") }
                }.show()
        })
        OfflineUi.install(this, content)
    }

    override fun onResume() { super.onResume(); refresh() }
    private fun refresh(message: String? = null) {
        if (busy) return
        task(message ?: "正在读取记录…", { store.library.scan() }) { loaded ->
            entries = loaded.entries
            status.text = (message?.let { it + "\n" } ?: "") + "本机共 " + entries.size + " 条记录；眼镜无线同步尚未接入。"
            if (loaded.unreadableCount > 0) status.append("\n有 " + loaded.unreadableCount + " 条记录无法读取，原文件已保留。")
            renderEntries()
        }
    }
    private fun renderEntries() {
        if (!::records.isInitialized) return
        records.removeAllViews()
        val query = search.text.toString().trim()
        val shown = entries.filter { query.isEmpty() || (it.draft.address + it.draft.description + MaterialExporter.categoryLabel(it.draft.category)).contains(query, ignoreCase = true) }
        if (shown.isEmpty()) records.addView(OfflineUi.text(this, if (entries.isEmpty()) "暂无记录。可导入自己的采集包，或先载入演示。" else "没有匹配的记录。"))
        for (entry in shown) {
            val draft = entry.draft
            val card = OfflineUi.card(this)
            card.addView(OfflineUi.text(this, (if (draft.isSynthetic) "[合成演示] " else "") + MaterialExporter.categoryLabel(draft.category), 20f))
            card.addView(OfflineUi.text(this, draft.address.ifBlank { "问题地址待确认" }))
            card.addView(OfflineUi.text(this, OfflineUi.stage(draft.stage) + " · " + if (draft.photoReview == null) "照片未审阅" else "照片已审阅"))
            card.addView(OfflineUi.button(this, "打开记录") {
                if (!busy) startActivity(Intent(this, IssueActivity::class.java).putExtra("photoId", draft.photoId))
            })
            records.addView(card)
        }
    }
    private fun <T> task(message: String, action: () -> T, done: (T) -> Unit) {
        busy = true; status.text = message; OfflineUi.enable(content, false)
        PrivateProbeStore.fileExecutor.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false; OfflineUi.enable(content, true)
                result.fold(done) { status.text = OfflineUi.error(it) }
            }
        }
    }
    @Deprecated("Uses the system document picker for Android 9 and newer")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 201 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            task("正在校验并导入…", { checkNotNull(contentResolver.openInputStream(uri)).use { store.library.importPackage(it) } }) { report ->
                refresh("导入 " + report.added + " 条，跳过 " + report.duplicates + " 条重复记录")
            }
        }
    }
}
