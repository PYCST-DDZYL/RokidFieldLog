package org.rokidfieldlog.phone

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.*
import org.rokidfieldlog.core.*

class PrivacyActivity : Activity() {
    private lateinit var store: PhoneIssueStore
    private lateinit var id: String
    private lateinit var content: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var status: TextView
    private lateinit var image: RedactionView
    private var entry: LibraryEntry? = null
    private var candidate: PhotoReview? = null
    private var editingMasks = emptyList<MaskRect>()
    private var busy = false
    private var restored: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("photoId") ?: ""
        if (!CaptureFiles.validId(id)) { finish(); return }
        restored = savedInstanceState
        store = PhoneIssueStore(this)
        content = OfflineUi.column(this)
        content.addView(OfflineUi.text(this, "照片隐私审阅", 23f))
        status = OfflineUi.text(this, "正在读取照片…")
        content.addView(status)
        image = RedactionView(this)
        content.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(controls)
        OfflineUi.install(this, content, scroll = false)
        task("正在读取照片…", {
            val loaded = store.library.load(id)
            Triple(loaded, store.uprightPreview(loaded.original), PhotoDigest.sha256(loaded.original))
        }) { (loaded, bitmap, sourceHash) ->
            entry = loaded
            editingMasks = loaded.draft.photoReview?.takeIf { it.originalSha256 == sourceHash }?.masks ?: emptyList()
            restored?.takeIf { it.getLong("revision", -1) == loaded.draft.revision }?.getFloatArray("masks")?.let { values ->
                if (values.size % 4 == 0 && values.size <= 400) editingMasks = values.toList().chunked(4).map { MaskRect(it[0], it[1], it[2], it[3]).apply { validate() } }
            }
            restored = null
            image.setImage(bitmap, editingMasks); editingControls()
        }
    }
    private fun editingControls() {
        candidate = null; controls.removeAllViews(); image.drawMode = true
        status.text = (if (entry!!.draft.isSynthetic) "合成演示 · " else "") + "拖动画框遮挡人脸、车牌等信息；可放大查看。"
        val mode = OfflineUi.button(this, "切换为查看 / 拖动") {
            image.drawMode = !image.drawMode
            (controls.getChildAt(0) as LinearLayout).getChildAt(0).let { (it as Button).text = if (image.drawMode) "切换为查看 / 拖动" else "切换为画框遮挡" }
        }
        row(mode, OfflineUi.button(this, "撤销框") { image.undo() }, OfflineUi.button(this, "清空框") { image.clearMasks() })
        zoomControls()
        controls.addView(OfflineUi.button(this, "生成导出照片预览") {
            if (busy) return@button
            editingMasks = image.masks
            val loaded = entry ?: return@button
            task("正在生成不透明遮挡副本…", {
                val review = store.renderReview(loaded, editingMasks)
                val bitmap = checkNotNull(BitmapFactory.decodeFile(loaded.reviewFile(review.fileName).path))
                review to bitmap
            }) { (review, bitmap) -> candidate = review; image.setImage(bitmap); previewControls() }
        })
        controls.addView(OfflineUi.button(this, "返回记录") { finish() })
    }
    private fun previewControls() {
        controls.removeAllViews(); image.drawMode = false
        val review = checkNotNull(candidate)
        status.text = "这是实际导出副本：" + review.width + " × " + review.height + "，" + (review.byteCount / 1024) + " KB。请放大检查。"
        zoomControls()
        val clear = CheckBox(this).apply { text = "照片中没有需要遮挡的第三方信息"; isChecked = false }
        if (review.masks.isEmpty()) controls.addView(clear)
        val checked = CheckBox(this).apply { text = "我已检查遮挡完整，现场问题仍清楚可辨"; isChecked = false }
        controls.addView(checked)
        controls.addView(OfflineUi.button(this, "确认这张照片已审阅") {
            if (!checked.isChecked || review.masks.isEmpty() && !clear.isChecked) {
                status.text = "请先完成照片检查并勾选确认"; return@button
            }
            val loaded = entry ?: return@button
            val approved = review.copy(noThirdPartyInformation = clear.isChecked)
            task("正在保存审阅结果…", { store.library.approvePhoto(id, loaded.draft.revision, approved) }) { finish() }
        })
        controls.addView(OfflineUi.button(this, "返回调整遮挡") {
            val loaded = entry ?: return@button
            task("正在读取原图…", { store.uprightPreview(loaded.original) }) { bitmap -> image.setImage(bitmap, editingMasks); editingControls() }
        })
    }
    private fun zoomControls() = row(OfflineUi.button(this, "放大") { image.changeZoom(2f) },
        OfflineUi.button(this, "缩小") { image.changeZoom(0.5f) }, OfflineUi.button(this, "适应") { image.fit() })
    private fun row(vararg buttons: Button) {
        val row = LinearLayout(this)
        buttons.forEach { row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        controls.addView(row)
    }
    private fun <T> task(message: String, action: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true; status.text = message; OfflineUi.enable(content, false); image.isEnabled = false
        PrivateProbeStore.fileExecutor.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false; OfflineUi.enable(content, true); image.isEnabled = true
                result.fold(done) { status.text = OfflineUi.error(it) }
            }
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        entry?.let {
            out.putLong("revision", it.draft.revision)
            val masks = if (candidate == null) image.masks else editingMasks
            out.putFloatArray("masks", masks.flatMap { box -> listOf(box.left, box.top, box.right, box.bottom) }.toFloatArray())
        }
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() { if (::image.isInitialized) image.release(); super.onDestroy() }
}
