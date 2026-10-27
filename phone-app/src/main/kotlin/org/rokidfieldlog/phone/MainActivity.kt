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
    private lateinit var siteDrafts: LinearLayout
    private lateinit var batchActions: LinearLayout
    private var batchIds: List<String> = emptyList()
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
        content.addView(OfflineUi.text(this, "1. 人还在现场：先把地址定下来", 19f))
        content.addView(OfflineUi.button(this, "现场定地址 · 我在这里") {
            if (!busy) startActivity(Intent(this, OnSiteActivity::class.java))
        })
        siteDrafts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; content.addView(siteDrafts)
        content.addView(OfflineUi.text(this, "眼镜只拍同一处的环境照和细节照。地址确认后，照片可回去再拷。"))
        content.addView(OfflineUi.text(this, "2. 回来后：导入照片，加入已有材料", 19f))
        content.addView(OfflineUi.button(this, "选择手机照片（JPEG）") { if (!busy) choosePhotos() })
        content.addView(OfflineUi.button(this, "导入采集包") {
            if (!busy) runCatching {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
                }, 201)
            }.onFailure { status.text = "此设备没有可用的文件选择器" }
        })
        batchActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; content.addView(batchActions)
        batchIds = savedInstanceState?.getStringArrayList("batchIds") ?: emptyList()
        renderBatch()
        content.addView(OfflineUi.text(this, "3. 整理材料：核对字段、逐张审阅、人工提交", 19f))
        content.addView(OfflineUi.button(this, "报修材料（继续草稿 / 新建）") { if (!busy) startActivity(Intent(this, ReportActivity::class.java)) })
        content.addView(OfflineUi.text(this, "4. 记下回执，下次路过再复查", 19f))
        content.addView(OfflineUi.button(this, "问题历史与待复查清单") { if (!busy) startActivity(Intent(this, InspectionActivity::class.java)) })
        content.addView(OfflineUi.button(this, "更多：资料检查、演示与定位") { if (!busy) showTools() })
        draftsOnly = CheckBox(this).apply {
            text = "仅显示照片未审阅的记录"
            isChecked = savedInstanceState?.getBoolean("draftsOnly", false) ?: false
            setOnCheckedChangeListener { _, _ -> page = 0; renderEntries() }
        }
        content.addView(draftsOnly)
        search = OfflineUi.field(this, "搜索地址、说明、类别或记录编号")
        search.setText(savedInstanceState?.getString("query", "") ?: "")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { page = 0; renderEntries() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        content.addView(search)
        records = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(records)
        OfflineUi.install(this, content)
    }

    private fun showTools() {
        val box = OfflineUi.column(this)
        box.addView(OfflineUi.button(this, "检查本机资料完整性") { if (!busy) checkLibraryHealth() })
        box.addView(OfflineUi.button(this, "载入复查流程演示") {
            if (!busy) AlertDialog.Builder(this).setTitle("载入合成复查演示？")
                .setMessage("生成破损、遮挡、处理后共三张合成图片。问题先停在待复查；第三张供你练习关联和人工确认。")
                .setNegativeButton("取消", null).setPositiveButton("载入") { _, _ ->
                    task("正在准备合成复查…", { InspectionSamples.add(store) }) { issue ->
                        startActivity(Intent(this, InspectionActivity::class.java).putExtra("issueId", issue.issueId))
                    }
                }.show()
        })
        box.addView(OfflineUi.button(this, "载入演示记录") {
            if (!busy) AlertDialog.Builder(this).setTitle("载入合成演示？")
                .setMessage("生成两张合成图片供练习整理、遮挡和导出。演示记录及材料会始终带演示标记。")
                .setNegativeButton("取消", null).setPositiveButton("载入") { _, _ ->
                    task("正在准备演示…", { SyntheticSamples.add(store) }) { report -> refresh("已载入 " + report.added + " 条演示记录") }
                }.show()
        })
        box.addView(OfflineUi.button(this, "定位检查") { if (!busy) startActivity(Intent(this, LocationActivity::class.java)) })
        box.addView(OfflineUi.button(this, "清除演示记录") {
            if (!busy) AlertDialog.Builder(this).setTitle("删除合成演示记录？")
                .setMessage("删除带演示标记的问题历史、记录及其照片。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    task("正在清除演示…", {
                        store.clearSyntheticRecords()
                    }) { refresh("演示记录已清除") }
                }.show()
        })

        AlertDialog.Builder(this).setTitle("资料检查与工具").setView(ScrollView(this).apply { addView(box) }).setNegativeButton("关闭", null).show()
    }
    private fun checkLibraryHealth() {
        task("正在检查原图、审阅副本和记录；照片较多时请稍候…", {
            Triple(LibraryHealthCheck(store.library, store.inspector).check(), store.reports.scan(), store.inspections.scan())
        }) { (photos, materials, histories) ->
            val details = photos.issues.take(20).joinToString("\n") { "#" + it.photoId.take(8) + "：" + it.problem.label }
            val text = photos.summary() + "\n材料草稿：" + materials.reports.size + "，不可读：" + materials.unreadableCount +
                "\n问题历史：" + histories.issues.size + "，不可读：" + histories.unreadableCount +
                (if (details.isBlank()) "" else "\n\n" + details) +
                (if (photos.issues.size > 20) "\n另有更多异常，请保留原始资料后逐项核查。" else "") +
                "\n\n检查不会删除或修复原文件。可在首页搜索记录编号。文件一致性不证明照片内容、位置或政府受理。"
            AlertDialog.Builder(this).setTitle("本机资料检查结果").setMessage(text).setPositiveButton("知道了", null).show()
        }
    }
    private fun importComplete(report: ImportReport, message: String) {
        batchIds = report.photoIds; renderBatch(); refresh(message) { if (batchIds.isNotEmpty()) chooseTargetReport() }
    }
    private fun renderBatch() {
        batchActions.removeAllViews()
        if (batchIds.isEmpty()) return
        batchActions.addView(OfflineUi.text(this, "本批共 " + batchIds.size + " 张（含已存在的重复照片）。加入现场已写好地址的那份材料，再核对角色、同一问题和隐私。"))
        batchActions.addView(OfflineUi.button(this, "把这批照片加入已有材料") { if (!busy) chooseTargetReport() })
        batchActions.addView(OfflineUi.button(this, "其他事项：用这批照片另建材料") { if (!busy) chooseBatch() })
    }
    private fun chooseTargetReport() {
        if (busy) return
        task("正在查找现场材料…", { store.reports.scan() }) { listing ->
            val candidates = listing.reports.filter { it.locationConfirmed && it.address.isNotBlank() && it.landmark.isNotBlank() }
            if (candidates.isEmpty()) {
                AlertDialog.Builder(this).setTitle("还没有确认好地址的材料")
                    .setMessage("照片已导入并保留。请先补好现场地址和参照物，再把这批照片加入同一份材料。")
                    .setNegativeButton("稍后整理", null).setPositiveButton("查看已有材料") { _, _ ->
                        startActivity(Intent(this, ReportActivity::class.java))
                    }.show()
            } else AlertDialog.Builder(this).setTitle("加入哪份已确认地址的材料？")
                .setItems(candidates.map { (if (it.isSynthetic == true) "[演示] " else "") + it.address + " · " + it.photos.size + "/5张 · #" + it.reportId.take(8) }.toTypedArray()) { _, index ->
                    val target = candidates[index]
                    task("正在读取本批照片…", { batchIds.map { store.library.load(it) } }) { batch ->
                        val available = batch.filter { entry -> target.photos.none { it.photoId == entry.draft.photoId } }
                        if (available.isEmpty()) startActivity(Intent(this, ReportActivity::class.java).putExtra("reportId", target.reportId))
                        else pickBatchForReport(target, available)
                    }
                }.setNegativeButton("暂不加入", null).show()
        }
    }
    private fun pickBatchForReport(target: ReportDraft, candidates: List<LibraryEntry>) {
        val slots = ReportCodec.MAX_PHOTOS - target.photos.size
        if (slots <= 0) { status.text = "这份材料已有5张照片，请先进入材料移除不需要的照片。"; return }
        val selected = linkedSetOf<String>()
        val rows = OfflineUi.column(this)
        rows.addView(OfflineUi.text(this, "地址：" + target.address + "\n参照物：" + target.landmark + "\n原地址和确认会保留；本次只加照片，不自动确认角色或同一问题。"))
        val dialog = AlertDialog.Builder(this).setTitle("选入本批照片（还可加" + slots + "张）")
            .setView(ScrollView(this).apply { addView(rows) }).setNegativeButton("取消", null)
            .setPositiveButton("加入这份材料") { _, _ ->
                task("正在保留地址并加入照片…", { store.reports.attachBatch(target.reportId, target.revision, candidates.map { it.draft.photoId }.filter { it in selected }) }) { updated ->
                    startActivity(Intent(this, ReportActivity::class.java).putExtra("reportId", updated.reportId))
                }
            }.create()
        val previews = mutableListOf<Pair<java.io.File, ImageView>>()
        for ((index, entry) in candidates.withIndex()) {
            val card = OfflineUi.card(this)
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "本批照片 " + (index + 1) }
            card.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (120 * resources.displayMetrics.density).toInt()))
            previews += (entry.draft.photoReview?.let { entry.reviewFile(it.fileName) } ?: entry.original) to image
            val check = CheckBox(this).apply {
                text = "照片 " + (index + 1) + " · #" + entry.draft.photoId.take(8) + if (entry.draft.isSynthetic) " [演示]" else ""
                isEnabled = target.isSynthetic == null || target.isSynthetic == entry.draft.isSynthetic
            }
            check.setOnCheckedChangeListener { _, checked ->
                if (checked && selected.size >= slots) { check.isChecked = false; Toast.makeText(this, "最多再加" + slots + "张", Toast.LENGTH_SHORT).show() }
                else if (checked) selected += entry.draft.photoId else selected -= entry.draft.photoId
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = selected.isNotEmpty()
            }
            card.addView(check); rows.addView(card)
            if (candidates.size <= slots && check.isEnabled) { selected += entry.draft.photoId; check.isChecked = true }
        }
        dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = selected.isNotEmpty()
        PrivateProbeStore.fileExecutor.execute {
            for ((file, image) in previews) {
                val bitmap = runCatching { store.uprightPreview(file, 192) }.getOrNull() ?: continue
                runOnUiThread { if (isDestroyed || !dialog.isShowing) bitmap.recycle() else image.setImageBitmap(bitmap) }
            }
        }
    }
    private fun chooseBatch() {
        fun create(ids: List<String>) { startActivity(Intent(this, ReportActivity::class.java).putExtra("create", true).putStringArrayListExtra("photoIds", ArrayList(ids))) }
        if (batchIds.size <= ReportCodec.MAX_PHOTOS) { create(batchIds); return }
        task("正在读取本批照片…", { batchIds.map { store.library.load(it) } }) { candidates ->
            val selected = linkedSetOf<String>()
            val rows = OfflineUi.column(this)
            val dialog = AlertDialog.Builder(this).setTitle("本批超过5张，请选入1–5张")
                .setView(ScrollView(this).apply { addView(rows) }).setNegativeButton("取消", null)
                .setPositiveButton("新建材料") { _, _ -> create(batchIds.filter { it in selected }) }.create()
            val previews = mutableListOf<Pair<java.io.File, ImageView>>()
            for ((index, entry) in candidates.withIndex()) {
                val card = OfflineUi.card(this)
                val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "本批照片 " + (index + 1) }
                card.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (120 * resources.displayMetrics.density).toInt()))
                previews += (entry.draft.photoReview?.let { entry.reviewFile(it.fileName) } ?: entry.original) to image
                val check = CheckBox(this).apply { text = "照片 " + (index + 1) + " · #" + entry.draft.photoId.take(8) }
                check.setOnCheckedChangeListener { _, checked ->
                    if (checked && selected.size >= ReportCodec.MAX_PHOTOS) { check.isChecked = false; Toast.makeText(this, "每份材料最多5张，其他照片保留在列表", Toast.LENGTH_SHORT).show() }
                    else if (checked) selected += entry.draft.photoId else selected -= entry.draft.photoId
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = selected.isNotEmpty()
                }
                card.addView(check); rows.addView(card)
            }
            dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            PrivateProbeStore.fileExecutor.execute {
                for ((file, image) in previews) {
                    val bitmap = runCatching { store.uprightPreview(file, 192) }.getOrNull() ?: continue
                    runOnUiThread { if (isDestroyed || !dialog.isShowing) bitmap.recycle() else image.setImageBitmap(bitmap) }
                }
            }
        }
    }
    override fun onResume() { super.onResume(); refresh() }
    private fun refresh(message: String? = null, after: (() -> Unit)? = null) {
        if (busy) return
        task(message ?: "正在读取记录…", { runCatching { store.shares.pruneExpired() }; store.library.scan() to store.reports.scan() }) { (loaded, materials) ->
            entries = loaded.entries
            siteDrafts.removeAllViews()
            for (item in materials.reports.filter { it.isSynthetic != true }.takeLast(8)) {
                siteDrafts.addView(OfflineUi.button(this, (if (item.locationConfirmed) "地址已确认 · " else "继续定地址 · ") + item.address.ifBlank { "未填写" } + " · #" + item.reportId.take(8)) {
                    if (!busy) startActivity(Intent(this, OnSiteActivity::class.java).putExtra("reportId", item.reportId))
                })
            }
            status.text = (message?.let { it + "\n" } ?: "") + "本机共 " + entries.size + " 条记录。可选择手机 JPEG 或导入采集包。"
            if (loaded.unreadableCount > 0) status.append("\n有 " + loaded.unreadableCount + " 条记录无法读取，原文件已保留。")
            if (materials.unreadableCount > 0) status.append("\n有 " + materials.unreadableCount + " 份材料无法读取，原文件已保留。")
            renderEntries(); after?.invoke()
        }
    }
    private fun renderEntries() {
        if (!::records.isInitialized) return
        records.removeAllViews()
        val query = search.text.toString().trim()
        val shown = entries.filter { (!draftsOnly.isChecked || it.draft.photoReview == null) &&
            (query.isEmpty() || (it.draft.photoId + " " + it.draft.address + it.draft.description + MaterialExporter.categoryLabel(it.draft.category)).contains(query, ignoreCase = true)) }
        val pages = ((shown.size + 19) / 20).coerceAtLeast(1)
        page = page.coerceIn(0, pages - 1)
        records.addView(OfflineUi.text(this, "匹配 " + shown.size + " 条 · 第 " + (page + 1) + " / " + pages + " 页"))
        if (shown.isEmpty()) records.addView(OfflineUi.text(this, if (entries.isEmpty()) "暂无记录。先选择手机照片，或载入演示练习。" else "没有匹配的记录。"))
        for (entry in shown.drop(page * 20).take(20)) {
            val draft = entry.draft
            val card = OfflineUi.card(this)
            card.addView(OfflineUi.text(this, (if (draft.isSynthetic) "[合成演示] " else "") + MaterialExporter.categoryLabel(draft.category), 20f))
            card.addView(OfflineUi.text(this, "记录 #" + draft.photoId.take(8)))
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
                importComplete(report, "导入 " + report.added + " 条，跳过 " + report.duplicates + " 条重复记录")
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
            importComplete(report, "已导入 " + report.added + " 张，跳过 " + report.duplicates + " 张重复照片")
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putStringArrayList("batchIds", ArrayList(batchIds))
        out.putInt("page", page)
        out.putBoolean("draftsOnly", draftsOnly.isChecked)
        out.putString("query", search.text.toString())
        super.onSaveInstanceState(out)
    }
}
