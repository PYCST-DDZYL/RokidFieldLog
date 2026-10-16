package org.rokidfieldlog.phone

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.*
import org.rokidfieldlog.core.*
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class ReportActivity : InspectionScreen() {
    private var reportId: String? = null
    private var draft: ReportDraft? = null
    private var pending: PreparedReport? = null
    private var restored: ReportDraft? = null
    private var entries = emptyMap<String, LibraryEntry>()
    private var listPage = 0
    private lateinit var channel: Spinner
    private lateinit var province: EditText
    private lateinit var city: EditText
    private lateinit var district: EditText
    private lateinit var purpose: EditText
    private lateinit var contactName: EditText
    private lateinit var contactPhone: EditText
    private lateinit var mediaInterview: Spinner
    private lateinit var noticeAccepted: CheckBox
    private var photographerReference: PhotographerReference? = null
    private var checklist: TextView? = null
    private lateinit var recipient: EditText
    private lateinit var category: EditText
    private lateinit var address: EditText
    private lateinit var landmark: EditText
    private lateinit var description: EditText
    private lateinit var locationConfirmed: CheckBox
    private lateinit var sameVisit: CheckBox
    private lateinit var date: Button
    private var eventDate = ""
    private val roles = linkedMapOf<String, Spinner>()
    private val thumbnails = AtomicInteger()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        reportId = state?.getString("reportId") ?: intent.getStringExtra("reportId")
        if (reportId != null && !CaptureFiles.validId(reportId!!)) { finish(); return }
        restored = state?.getString("editing")?.let { value -> runCatching { ReportCodec.decode(value, reportId ?: "") }.getOrNull() }
        if (state != null && reportId != null) {
            val name = state.getString("exportName")
            val hash = state.getString("exportHash")
            val ids = state.getStringArray("exportPhotoIds")
            val revisions = state.getLongArray("exportPhotoRevisions")
            if (name != null && name.matches(Regex("[0-9a-f-]{36}\\.zip")) && hash != null && hash.matches(Regex("[0-9a-f]{64}")) &&
                ids != null && revisions != null && ids.size == revisions.size && ids.all(CaptureFiles::validId)) {
                pending = PreparedReport(reportId!!, state.getLong("exportRevision"), ids.zip(revisions.toList()).toMap(),
                    File(store.root, "report-exports/" + name), hash)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { leave() }
        }
        if (reportId == null && state == null && intent.getBooleanExtra("create", false)) {
            val batch = intent.getStringArrayListExtra("photoIds")
            if (batch != null) createBatch(batch) else create(intent.getStringExtra("photoId"))
        }
    }
    override fun onResume() {
        super.onResume()
        if (isFinishing || busy) return
        if (reportId == null) refreshList() else {
            val editing = restored ?: draft?.let { runCatching { snapshot() }.getOrNull() }
            restored = null
            task("正在读取报修材料…", {
                val saved = store.reports.load(reportId!!)
                saved to store.reports.selected(saved)
            }) { (saved, selected) ->
                val photosChanged = entries.isNotEmpty() && saved.photos.any { photo ->
                    entries[photo.photoId]?.draft?.photoReview != selected[photo.photoId]?.draft?.photoReview
                }
                entries = selected
                val shown = editing?.takeIf { it.revision == saved.revision } ?: saved
                render(if (photosChanged) shown.copy(sameIssueVisitConfirmed = false) else shown)
            }
        }
    }
    private fun refreshList(): Unit = task("正在读取报修材料…", { store.reports.scan() }) { listing ->
        content.removeAllViews(); roles.clear(); draft = null
        content.addView(OfflineUi.text(this, "多图报修材料", 25f))
        status.text = "先在现场写好并确认地址，回来后将照片加进同一份材料；人工提交后再记录回执。"
        if (listing.unreadableCount > 0) status.append("\n有材料无法读取，原文件已保留。")
        content.addView(status)
        content.addView(OfflineUi.button(this, "新建报修材料") { create(null) })
        val pages = (listing.reports.size - 1).coerceAtLeast(0) / 30 + 1
        listPage = listPage.coerceIn(0, pages - 1)
        if (listing.reports.isEmpty()) content.addView(OfflineUi.text(this, "暂无材料。可先在首页定地址，不需要等照片导入。"))
        for (item in listing.reports.drop(listPage * 30).take(30)) {
            val card = OfflineUi.card(this)
            card.addView(OfflineUi.text(this, (if (item.isSynthetic == true) "[合成演示] " else "") + item.address.ifBlank { "地址待填写" }, 20f))
            card.addView(OfflineUi.text(this, item.recipient.ifBlank { "接收方待填写" } + " · " + item.photos.size + " 张照片"))
            card.addView(OfflineUi.button(this, "继续整理") {
                startActivity(Intent(this, ReportActivity::class.java).putExtra("reportId", item.reportId))
            }); content.addView(card)
        }
        if (listPage > 0) content.addView(OfflineUi.button(this, "上一页") { listPage--; refreshList() })
        if (listPage + 1 < pages) content.addView(OfflineUi.button(this, "下一页") { listPage++; refreshList() })
        content.addView(OfflineUi.button(this, "返回首页") { finish() })
    }
    private fun create(photoId: String?) = task("正在新建材料…", {
        store.reports.create(photoId)
    }) { created ->
        reportId = created.reportId; entries = emptyMap(); render(created)
        if (photoId != null) task("正在读取所选照片…", { store.reports.selected(created) }) { entries = it; render(created) }
    }
    private fun createBatch(ids: List<String>) = task("正在用本批照片新建材料…", {
        val created = store.reports.createBatch(ids)
        created to store.reports.selected(created)
    }) { (created, selected) -> reportId = created.reportId; entries = selected; render(created) }
    private fun field(hint: String, value: String, limit: Int, multiline: Boolean = false): EditText {
        val counter = OfflineUi.text(this, hint + " · " + value.length + "/" + limit)
        content.addView(counter)
        return OfflineUi.field(this, hint, value, multiline).apply {
            filters = arrayOf(InputFilter.LengthFilter(limit)); content.addView(this)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { counter.text = hint + " · " + (s?.length ?: 0) + "/" + limit; updateChecklist() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
    }
    private fun updateChecklist() {
        val target = checklist ?: return
        val reasons = ReportReadiness.reasons(snapshot(), entries)
        target.text = if (reasons.isEmpty()) "填写检查通过；交接前还会校验照片文件。" else "还需要：\n" + reasons.joinToString("\n") { "• " + it }
    }
    private fun render(value: ReportDraft) {
        checklist = null
        draft = value; roles.clear(); content.removeAllViews()
        val thumbnailToken = thumbnails.incrementAndGet()
        val previews = mutableListOf<Pair<File, ImageView>>()
        content.addView(OfflineUi.text(this, (if (value.isSynthetic == true) "[合成演示] " else "") + "多图报修材料", 25f))
        status.text = "地址先在现场确认，照片回来再补。角色、同一问题和每张照片隐私都由人核对。"
        content.addView(status)
        content.addView(OfflineUi.text(this, "材料入口"))
        channel = Spinner(this).apply {
            adapter = ArrayAdapter(this@ReportActivity, android.R.layout.simple_spinner_dropdown_item, listOf("通用人工交接（不声明渠道兼容）", "江苏12345网页（已核对公开字段）"))
            setSelection(if (value.channelId == Jiangsu12345.ID) 1 else 0)
        }; content.addView(channel)
        content.addView(OfflineUi.text(this, "江苏12345公开字段核对：" + Jiangsu12345.CHECKED_ON + "。登录后个人信息和打码相册接收尚未实测；其他政务渠道未适配。"))
        content.addView(OfflineUi.button(this, "打开江苏12345填写页") { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Jiangsu12345.URL))) }.onFailure { status.text = "没有可用浏览器" } })
        province = field("省（江苏入口填写江苏省）", value.province, 100)
        city = field("市（按官方行政区划选项）", value.city, 100)
        district = field("区 / 县（按官方行政区划选项）", value.district, 100)
        contactName = field("联系人（交接用，官方个人信息另核对）", value.contactName, 80)
        contactPhone = field("联系电话（会随材料导出）", value.contactPhone, 32).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
        content.addView(OfflineUi.text(this, "是否接受媒体采访（江苏必选）"))
        mediaInterview = Spinner(this).apply {
            adapter = ArrayAdapter(this@ReportActivity, android.R.layout.simple_spinner_dropdown_item, listOf("请选择", "是", "否"))
            setSelection(when (value.mediaInterview) { true -> 1; false -> 2; null -> 0 })
        }; content.addView(mediaInterview)
        content.addView(OfflineUi.text(this, Jiangsu12345.NOTICE))
        content.addView(OfflineUi.button(this, "阅读江苏12345官方填报须知") { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Jiangsu12345.NOTICE_URL))) }.onFailure { status.text = "没有可用浏览器" } })
        noticeAccepted = CheckBox(this).apply { text = "我已阅读官方须知及上述联系方式与个人信息告知"; isChecked = value.noticeAccepted }; content.addView(noticeAccepted)
        content.addView(OfflineUi.text(this, "不预先承诺接收方接受打码相册照片；首次实际提交后，在回执与试交记录里记下结果。"))
        photographerReference = value.photographerReference
        ReferenceLocationPicker.add(this, content, photographerReference) { photographerReference = it; updateChecklist() }
        recipient = field("实际接收人或渠道，如园区维修负责人", value.recipient, 120)
        category = field("接收方的问题分类，请按其实际选项填写", value.category, 120)
        purpose = field("诉求目的", value.purpose, 100, true)
        address = field("问题详细地址", value.address, 200)
        landmark = field("附近参照物或设施编号，如东门内第二盏路灯旁", value.landmark, 200)
        locationConfirmed = CheckBox(this).apply { text = "我已确认接收方能据此找到问题位置"; isChecked = value.locationConfirmed }
        content.addView(locationConfirmed)
        eventDate = value.eventDate
        date = OfflineUi.button(this, "本次现场日期：" + eventDate.ifBlank { "待人工确认" }) {
            val initial = runCatching { LocalDate.parse(eventDate) }.getOrElse { LocalDate.now() }
            DatePickerDialog(this, { _, year, month, day ->
                eventDate = LocalDate.of(year, month + 1, day).toString(); date.text = "本次现场日期：" + eventDate; sameVisit.isChecked = false; updateChecklist()
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        }; content.addView(date)
        description = field("正文：具体哪里损坏、影响什么、希望如何处理", value.description, 2000, true)
        sameVisit = CheckBox(this).apply {
            text = "我已逐张核对：属于同一问题、同一次现场观察，且视角清晰可用"
            isChecked = value.sameIssueVisitConfirmed
        }
        content.addView(OfflineUi.text(this, "照片：应用每张导出JPG最多5 MB、长边2048像素；江苏网页单个附件10 MB、最多5个。ZIP须先解压。", 19f))
        for ((index, photo) in value.photos.withIndex()) {
            val entry = entries[photo.photoId]
            val card = OfflineUi.card(this)
            card.addView(OfflineUi.text(this, "照片 " + (index + 1) + " · #" + photo.photoId.take(8), 19f))
            card.addView(OfflineUi.text(this, if (entry == null) "记录暂未读取或已不可用" else
                (if (entry.draft.isSynthetic) "[合成演示] " else "") + if (entry.draft.photoReview == null) "请先完成隐私审阅" else "已有隐私审阅，生成材料时将再次校验"))
            if (entry != null) {
                val preview = thumbnail(card)
                val file = entry.draft.photoReview?.let { entry.reviewFile(it.fileName) } ?: entry.original
                previews += file to preview
            }
            val role = Spinner(this).apply {
                adapter = ArrayAdapter(this@ReportActivity, android.R.layout.simple_spinner_dropdown_item, ReportPhotoRole.entries.map(ReportReadiness::roleLabel))
                setSelection(photo.role.ordinal)
            }
            roles[photo.photoId] = role; card.addView(role)
            role.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (position != photo.role.ordinal) sameVisit.isChecked = false
                    updateChecklist()
                }
            }
            card.addView(OfflineUi.button(this, "查看照片 / 隐私审阅") {
                save { startActivity(Intent(this, PrivacyActivity::class.java).putExtra("photoId", photo.photoId)) }
            })
            card.addView(OfflineUi.button(this, "查看或更正这张记录的日期") {
                save { startActivity(Intent(this, IssueActivity::class.java).putExtra("photoId", photo.photoId)) }
            })
            card.addView(OfflineUi.button(this, "从材料移除这张照片") {
                val changed = snapshot().copy(photos = snapshot().photos.filterNot { it.photoId == photo.photoId }, sameIssueVisitConfirmed = false)
                save(changed) { render(it) }
            }); content.addView(card)
        }
        if (value.photos.size < ReportCodec.MAX_PHOTOS) content.addView(OfflineUi.button(this, "选择环境照 / 细节照 / 补充照") { choosePhoto() })
        content.addView(sameVisit)
        for (input in listOf(address, landmark)) input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { locationConfirmed.isChecked = false; sameVisit.isChecked = false }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        checklist = OfflineUi.text(this, "").also { content.addView(it) }
        for (check in listOf(locationConfirmed, sameVisit, noticeAccepted)) check.setOnCheckedChangeListener { _, _ -> updateChecklist() }
        for (spinner in listOf(channel, mediaInterview)) spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { updateChecklist() }
        }
        updateChecklist()
        content.addView(OfflineUi.button(this, "保存草稿并检查缺项") { save { render(it) } })
        content.addView(OfflineUi.button(this, "返回现场地址核对") {
            save { saved -> startActivity(Intent(this, OnSiteActivity::class.java).putExtra("reportId", saved.reportId)) }
        })
        content.addView(OfflineUi.text(this, "按填写顺序逐段复制", 20f))
        for ((label, part) in listOf("1. 复制诉求目的" to ReportTextPart.PURPOSE, "2. 复制详细地址（含参照物）" to ReportTextPart.ADDRESS, "3. 复制正文" to ReportTextPart.BODY)) {
            content.addView(OfflineUi.button(this, label) {
                save { saved -> task("正在核对材料…", { store.reports.submissionPart(saved.reportId, part) }) { text ->
                    MaterialSharing.copyText(this, text); status.text = "已复制。请在实际渠道粘贴对应字段。"
                } }
            })
        }
        content.addView(OfflineUi.button(this, "分享已审阅照片和说明") {
            save { saved ->
                task("正在校验并准备分享照片…", { store.reports.prepareShare(saved.reportId, store.shares) },
                    onAbandoned = { store.shares.discard(it) }) { material ->
                    runCatching { MaterialSharing.launch(this, material) }
                        .onSuccess { status.text = "已打开系统分享。请核对接收方及照片；部分应用不接收附带文字，可另用复制说明。分享不表示已提交或受理。" }
                        .onFailure {
                            PrivateProbeStore.fileExecutor.execute { runCatching { store.shares.discard(material) } }
                            status.text = "无法打开系统分享，请使用保存 ZIP 或复制说明。"
                        }
                }
            }
        })
        content.addView(OfflineUi.button(this, "复制报修说明") {
            save { saved ->
                task("正在核对报修说明…", { store.reports.submissionText(saved.reportId) }) { text ->
                    MaterialSharing.copyText(this, text)
                    status.text = "已复制说明。请粘贴到接收方渠道，并另行附上已审阅照片。"
                }
            }
        })
        content.addView(OfflineUi.text(this, "分享后请自行确认接收方及受理情况。部分应用只接收照片，可以另用复制说明。"))
        content.addView(OfflineUi.button(this, "生成并保存报修材料") {
            save { saved ->
                prepare(saved)
            }
        })
        content.addView(OfflineUi.text(this, "保存后解压，将文字和 JPG 填入接收方现有渠道。请核对当地分类、附件和联系信息要求。"))
        content.addView(OfflineUi.button(this, "回执与试交记录（补充要求 / 退图 / 耗时）") {
            save { saved -> startActivity(Intent(this, ReportHandoffActivity::class.java).putExtra("reportId", saved.reportId)) }
        })
        content.addView(OfflineUi.button(this, "删除这份材料草稿") {
            AlertDialog.Builder(this).setTitle("删除这份材料及私人回执记录？").setMessage("这份材料里的地址、回执、退图及耗时记录会一并删除。照片及独立的问题历史继续保留，已保存到自选位置的文件由你管理。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    val current = draft ?: return@setPositiveButton
                    task("正在删除材料草稿…", { store.reports.delete(current.reportId, current.revision) }) { finish() }
                }.show()
        })
        content.addView(OfflineUi.button(this, "保存草稿并返回") { save { finish() } })
        loadThumbnails(previews, thumbnails, thumbnailToken)
    }
    private fun snapshot(): ReportDraft = checkNotNull(draft).copy(
        channelId = if (channel.selectedItemPosition == 1) Jiangsu12345.ID else "GENERIC_MANUAL",
        province = province.text.toString().trim(), city = city.text.toString().trim(), district = district.text.toString().trim(),
        purpose = purpose.text.toString().trim(), contactName = contactName.text.toString().trim(), contactPhone = contactPhone.text.toString().trim(),
        mediaInterview = when (mediaInterview.selectedItemPosition) { 1 -> true; 2 -> false; else -> null },
        noticeAccepted = noticeAccepted.isChecked, albumAcceptanceConfirmed = false, photographerReference = photographerReference,
        recipient = recipient.text.toString().trim(), category = category.text.toString().trim(), address = address.text.toString().trim(),
        landmark = landmark.text.toString().trim(), description = description.text.toString().trim(), eventDate = eventDate,
        locationConfirmed = locationConfirmed.isChecked, sameIssueVisitConfirmed = sameVisit.isChecked,
        photos = draft!!.photos.map { it.copy(role = ReportPhotoRole.entries[roles.getValue(it.photoId).selectedItemPosition]) })
    private fun save(value: ReportDraft? = null, done: (ReportDraft) -> Unit) {
        if (busy || draft == null) return
        val snapshot = value ?: snapshot()
        task("正在保存材料草稿…", { store.reports.save(snapshot, snapshot.revision) }) { saved -> draft = saved; done(saved) }
    }
    private fun choosePhoto() {
        save { saved ->
            task("正在读取可选照片…", { store.library.scan() }) { listing ->
                val candidates = listing.entries.filter { entry -> saved.photos.none { it.photoId == entry.draft.photoId } &&
                    (saved.isSynthetic == null || saved.isSynthetic == entry.draft.isSynthetic) }
                val box = OfflineUi.column(this)
                val search = OfflineUi.field(this, "搜索照片地址、说明或编号"); box.addView(search)
                val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; box.addView(rows)
                var page = 0
                val pickerThumbnails = AtomicInteger()
                val dialog = AlertDialog.Builder(this).setTitle("选择同一次现场的另一张照片")
                    .setView(ScrollView(this).apply { addView(box) }).setNegativeButton("取消", null).create()
                dialog.setOnDismissListener { pickerThumbnails.incrementAndGet() }
                fun showRows() {
                    rows.removeAllViews()
                    val token = pickerThumbnails.incrementAndGet()
                    val previews = mutableListOf<Pair<File, ImageView>>()
                    val query = search.text.toString().trim()
                    val shown = candidates.filter { query.isBlank() || (it.draft.address + it.draft.description + it.draft.photoId).contains(query, true) }
                    val pages = (shown.size - 1).coerceAtLeast(0) / 10 + 1; page = page.coerceIn(0, pages - 1)
                    if (shown.isEmpty()) rows.addView(OfflineUi.text(this, "没有可选照片。请先导入同一次现场的其他视角。演示与真实照片分别整理。"))
                    for (entry in shown.drop(page * 10).take(10)) {
                        val card = OfflineUi.card(this)
                        val preview = thumbnail(card)
                        val file = entry.draft.photoReview?.let { entry.reviewFile(it.fileName) } ?: entry.original
                        previews += file to preview
                        card.addView(OfflineUi.button(this,
                            (if (entry.draft.isSynthetic) "[演示] " else "") + entry.draft.address.ifBlank { "地址未填" } + " · #" + entry.draft.photoId.take(8) + " · 选入") {
                            dialog.dismiss()
                            save(saved.copy(photos = saved.photos + ReportPhoto(entry.draft.photoId), sameIssueVisitConfirmed = false)) { updated ->
                                entries = entries + (entry.draft.photoId to entry); render(updated)
                            }
                        }); rows.addView(card)
                    }
                    loadThumbnails(previews, pickerThumbnails, token)
                    if (page > 0) rows.addView(OfflineUi.button(this, "上一页照片") { page--; showRows() })
                    if (page + 1 < pages) rows.addView(OfflineUi.button(this, "下一页照片") { page++; showRows() })
                }
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { page = 0; showRows() }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
                showRows(); dialog.show()
            }
        }
    }
    private fun prepare(saved: ReportDraft) {
        if (busy) return
        busy = true; status.text = "正在校验每张照片并生成材料…"; OfflineUi.enable(content, false)
        PrivateProbeStore.fileExecutor.execute {
            val result = runCatching { store.reports.prepare(saved.reportId) }
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    result.getOrNull()?.let { prepared -> PrivateProbeStore.fileExecutor.execute { store.reports.discard(prepared) } }
                    return@runOnUiThread
                }
                busy = false; OfflineUi.enable(content, true)
                result.fold({ prepared ->
                    pending = prepared
                    runCatching {
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                            putExtra(Intent.EXTRA_TITLE, "FieldLog_报修_" + (if (saved.isSynthetic == true) "演示_" else "") + saved.eventDate + ".zip")
                        }, 203)
                    }.onFailure { discardPending(); status.text = "此设备没有可用的保存位置选择器" }
                }) { status.text = OfflineUi.error(it) }
            }
        }
    }
    private fun thumbnail(parent: LinearLayout): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "照片缩略图，完整检查请进入隐私审阅"
        parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (150 * resources.displayMetrics.density).toInt()))
    }
    private fun loadThumbnails(requests: List<Pair<File, ImageView>>, generation: AtomicInteger, token: Int) {
        if (requests.isEmpty()) return
        PrivateProbeStore.fileExecutor.execute {
            for ((file, image) in requests) {
                if (generation.get() != token) break
                val bitmap = runCatching { store.uprightPreview(file, 256) }.getOrNull() ?: continue
                runOnUiThread {
                    if (isDestroyed || isFinishing || generation.get() != token) bitmap.recycle()
                    else image.setImageBitmap(bitmap)
                }
            }
        }
    }
    override fun onDestroy() { thumbnails.incrementAndGet(); super.onDestroy() }
    private fun leave() {
        if (busy) return
        if (draft == null) finish() else save { finish() }
    }
    // API 33+ uses the registered OnBackInvokedCallback above; only older Android reaches this fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Back callback for Android 9–12")
    override fun onBackPressed() { if (android.os.Build.VERSION.SDK_INT < 33) leave() else super.onBackPressed() }
    private fun discardPending() {
        val prepared = pending ?: return; pending = null
        PrivateProbeStore.fileExecutor.execute { store.reports.discard(prepared) }
    }
    @Deprecated("System document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 203) return
        val prepared = pending ?: return
        pending = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { PrivateProbeStore.fileExecutor.execute { store.reports.discard(prepared) }; status.text = "已取消材料保存"; return }
        task("正在保存到你选择的位置…", {
            try {
                MaterialDocumentCopy.copy(
                    validate = { store.reports.validatePrepared(prepared) },
                    openInput = { prepared.file.inputStream() },
                    openOutput = { checkNotNull(contentResolver.openOutputStream(uri, "w")) },
                    deletePartial = { DocumentsContract.deleteDocument(contentResolver, uri) })
            } finally { store.reports.discard(prepared) }
        }) { status.text = "材料已保存。解压后将文字和照片填入接收方渠道；请自行确认受理情况。" }
    }
    override fun onSaveInstanceState(out: Bundle) {
        reportId?.let { out.putString("reportId", it) }
        if (draft != null && ::recipient.isInitialized) runCatching { ReportCodec.encode(snapshot()) }.getOrNull()?.let { out.putString("editing", it) }
        pending?.let { prepared ->
            out.putString("exportName", prepared.file.name); out.putString("exportHash", prepared.sha256); out.putLong("exportRevision", prepared.revision)
            out.putStringArray("exportPhotoIds", prepared.photoRevisions.keys.toTypedArray()); out.putLongArray("exportPhotoRevisions", prepared.photoRevisions.values.toLongArray())
        }
        super.onSaveInstanceState(out)
    }
}
