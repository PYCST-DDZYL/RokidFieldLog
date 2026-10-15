package org.rokidfieldlog.phone

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import org.rokidfieldlog.core.*

abstract class InspectionScreen : Activity() {
    protected lateinit var store: PhoneIssueStore
    protected lateinit var content: LinearLayout
    protected lateinit var status: TextView
    protected var busy = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = PhoneIssueStore(this); content = OfflineUi.column(this)
        status = OfflineUi.text(this, "正在读取…"); content.addView(status)
        OfflineUi.install(this, content)
    }
    protected fun <T> task(message: String, action: () -> T, done: (T) -> Unit) {
        if (busy) return
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
}
object InspectionText {
    fun state(value: InspectionState): String = when (value) {
        InspectionState.OPEN -> "仍待处理"
        InspectionState.NEEDS_RECHECK -> "需要再次观察"
        InspectionState.AWAITING_VERIFICATION -> "照片待人工复查"
        InspectionState.VERIFIED_REPAIRED -> "已有复查确认记录"
    }
    fun visibility(value: InspectionVisibility?): String = when (value) {
        InspectionVisibility.CLEAR -> "位置清楚可见"
        InspectionVisibility.OBSTRUCTED -> "目标被遮挡"
        InspectionVisibility.WRONG_VIEW -> "未拍到所需视角"
        InspectionVisibility.UNUSABLE -> "照片无法判断"
        null -> ""
    }
    fun finding(value: InspectionFinding?): String = when (value) {
        InspectionFinding.PROBLEM_PRESENT -> "仍可见问题"
        InspectionFinding.NO_VISIBLE_PROBLEM -> "可见范围内未见原问题"
        InspectionFinding.UNABLE_TO_ASSESS -> "无法判断"
        null -> ""
    }
    fun decision(value: VerificationDecision?): String = when (value) {
        VerificationDecision.REPAIRED -> "人工复查确认已处理"
        VerificationDecision.STILL_PRESENT -> "人工复查确认仍存在"
        else -> "人工复查无法确认"
    }
    fun title(issue: TrackedIssue) = (if (issue.isSynthetic) "[合成演示] " else "") + issue.siteLabel
}
