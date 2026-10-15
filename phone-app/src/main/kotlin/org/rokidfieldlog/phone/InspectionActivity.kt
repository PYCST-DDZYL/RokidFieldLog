package org.rokidfieldlog.phone

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.LinearLayout
import org.rokidfieldlog.core.*

class InspectionActivity : InspectionScreen() {
    private var issueId: String? = null
    private var issues = emptyList<TrackedIssue>()
    private var onlyPending = false
    private var query = ""
    private var listPage = 0
    private var historyPage = 0
    private lateinit var rows: LinearLayout
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        issueId = intent.getStringExtra("issueId")
        if (issueId != null && !CaptureFiles.validId(issueId!!)) { finish(); return }
        onlyPending = state?.getBoolean("pending") ?: false; query = state?.getString("query") ?: ""
        if (issueId == null) buildList()
    }
    override fun onResume() {
        super.onResume()
        if (isFinishing || busy) return
        val id = issueId
        if (id == null) task("正在读取问题历史…", { store.inspections.scan() }) { listing ->
            issues = listing.issues
            status.text = "共 " + issues.size + " 个问题，" + issues.count { it.needsRecheck } + " 个待复查。"
            if (listing.unreadableCount > 0) status.append("\n有历史无法读取，原文件已保留。")
            renderRows()
        } else task("正在读取问题历史…", { store.inspections.load(id) }) { renderIssue(it) }
    }
    private fun buildList() {
        content.removeAllViews()
        content.addView(OfflineUi.text(this, "问题历史与复查", 25f)); content.addView(status)
        content.addView(OfflineUi.text(this, "一处问题接续多次观察。维修反馈、现场照片和人工结论分别记录。"))
        content.addView(OfflineUi.button(this, "添加问题 / 关联观察照片") { startActivity(Intent(this, InspectionLinkActivity::class.java)) })
        val pending = CheckBox(this).apply { text = "只看待复查"; isChecked = onlyPending }
        pending.setOnCheckedChangeListener { _, checked -> onlyPending = checked; listPage = 0; renderRows() }
        content.addView(pending)
        val search = OfflineUi.field(this, "搜索位置或问题编号", query)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); listPage = 0; renderRows() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        content.addView(search)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; content.addView(rows)
    }
    private fun renderRows() {
        if (!::rows.isInitialized) return
        rows.removeAllViews()
        val shown = issues.filter { (!onlyPending || it.needsRecheck) &&
            (query.isBlank() || (it.siteLabel + it.issueId).contains(query.trim(), ignoreCase = true)) }
        if (shown.isEmpty()) rows.addView(OfflineUi.text(this, "暂无符合条件的问题。可从已有照片建立问题或添加观察。"))
        val pages = ((shown.size - 1).coerceAtLeast(0) / 30) + 1
        listPage = listPage.coerceIn(0, pages - 1)
        if (shown.isNotEmpty()) rows.addView(OfflineUi.text(this, "第 " + (listPage + 1) + " / " + pages + " 页，共 " + shown.size + " 个问题"))
        for (issue in shown.drop(listPage * 30).take(30)) {
            val card = OfflineUi.card(this)
            card.addView(OfflineUi.text(this, InspectionText.title(issue), 20f))
            card.addView(OfflineUi.text(this, "问题 #" + issue.issueId.take(8) + " · " + InspectionText.state(issue.state)))
            card.addView(OfflineUi.text(this, issue.recheckReason))
            card.addView(OfflineUi.text(this, "最近观察：" + issue.observations.last().observedOn + " · 共 " + issue.observations.size + " 次"))
            card.addView(OfflineUi.button(this, "查看历史与前后对照") {
                startActivity(Intent(this, InspectionActivity::class.java).putExtra("issueId", issue.issueId))
            }); rows.addView(card)
        }
        if (listPage > 0) rows.addView(OfflineUi.button(this, "上一页问题") { listPage--; renderRows() })
        if (listPage + 1 < pages) rows.addView(OfflineUi.button(this, "下一页问题") { listPage++; renderRows() })
    }
    private fun renderIssue(issue: TrackedIssue) {
        content.removeAllViews()
        content.addView(OfflineUi.text(this, InspectionText.title(issue), 25f))
        content.addView(OfflineUi.text(this, "问题 #" + issue.issueId.take(8) + " · " + MaterialExporter.categoryLabel(issue.category)))
        status = OfflineUi.text(this, InspectionText.state(issue.state) + "\n" + issue.recheckReason); content.addView(status)
        content.addView(OfflineUi.button(this, "前后照片对照 / 记录复查结论") {
            startActivity(Intent(this, InspectionCompareActivity::class.java).putExtra("issueId", issue.issueId))
        })
        content.addView(OfflineUi.button(this, "关联一次新的现场观察") {
            startActivity(Intent(this, InspectionLinkActivity::class.java).putExtra("issueId", issue.issueId))
        })
        content.addView(OfflineUi.button(this, "记录维修反馈") {
            val note = OfflineUi.field(this, "维修方的反馈、回执或说明", multiline = true).apply { filters = arrayOf(InputFilter.LengthFilter(500)) }
            AlertDialog.Builder(this).setTitle("记录维修反馈").setMessage("保存后进入待复查，现场结果需要另行核对。")
                .setView(note).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                    val value = note.text.toString()
                    task("正在保存维修反馈…", { store.inspections.repairClaim(issue.issueId, issue.revision, value) }) { historyPage = 0; renderIssue(it) }
                }.show()
        })
        val historyPages = (issue.events.size - 1) / 20 + 1
        historyPage = historyPage.coerceIn(0, historyPages - 1)
        content.addView(OfflineUi.text(this, "连续历史（最近登记在前）", 21f))
        content.addView(OfflineUi.text(this, "第 " + (historyPage + 1) + " / " + historyPages + " 页，共 " + issue.events.size + " 条"))
        for (event in issue.events.asReversed().drop(historyPage * 20).take(20)) {
            val card = OfflineUi.card(this)
            val heading = when (event.kind) {
                InspectionEventKind.OBSERVATION -> "现场观察 · " + event.observedOn
                InspectionEventKind.REPAIR_CLAIM -> "维修反馈 · 等待现场核对"
                InspectionEventKind.VERIFICATION -> InspectionText.decision(event.decision)
            }
            card.addView(OfflineUi.text(this, (event.sequence + 1).toString() + ". " + heading, 18f))
            if (event.kind == InspectionEventKind.OBSERVATION) {
                card.addView(OfflineUi.text(this, InspectionText.visibility(event.visibility) + " · " + InspectionText.finding(event.finding)))
                card.addView(OfflineUi.button(this, "查看这次观察的照片记录") {
                    startActivity(Intent(this, IssueActivity::class.java).putExtra("photoId", event.photoId))
                })
            }
            if (event.note.isNotBlank()) card.addView(OfflineUi.text(this, event.note))
            if (event.chronologyConfirmed) card.addView(OfflineUi.text(this, "已由人核对与维修反馈的拍摄先后顺序"))
            content.addView(card)
        }
        if (historyPage > 0) content.addView(OfflineUi.button(this, "查看较新的历史") { historyPage--; renderIssue(issue) })
        if (historyPage + 1 < historyPages) content.addView(OfflineUi.button(this, "查看更早的历史") { historyPage++; renderIssue(issue) })
        content.addView(OfflineUi.button(this, "删除此问题的跟踪历史") {
            AlertDialog.Builder(this).setTitle("删除这份问题历史？")
                .setMessage("删除观察关联、维修反馈和复查结论。各张原始照片记录仍保留。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    task("正在删除问题历史…", { store.inspections.deleteTracking(issue.issueId, issue.revision) }) { finish() }
                }.show()
        })
    }
    override fun onSaveInstanceState(out: Bundle) { out.putBoolean("pending", onlyPending); out.putString("query", query); super.onSaveInstanceState(out) }
}
