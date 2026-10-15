package org.rokidfieldlog.phone

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.rokidfieldlog.core.SubmissionStage

object OfflineUi {
    val ink = Color.rgb(29, 48, 41)
    val accent = Color.rgb(21, 103, 76)
    fun column(activity: Activity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (18 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        setBackgroundColor(Color.rgb(246, 248, 245))
    }
    fun text(activity: Activity, value: String, size: Float = 16f) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(ink); setPadding(0, 8, 0, 10)
    }
    fun button(activity: Activity, label: String, action: () -> Unit) = Button(activity).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }
    fun field(activity: Activity, hintText: String, value: String = "", multiline: Boolean = false) = EditText(activity).apply {
        hint = hintText; setText(value); setTextColor(ink); textSize = 16f
        inputType = android.text.InputType.TYPE_CLASS_TEXT or if (multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        minLines = if (multiline) 3 else 1
        maxLines = if (multiline) 6 else 2
    }
    fun card(activity: Activity) = column(activity).apply {
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16f; setStroke(1, Color.rgb(212, 223, 215)) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
    }
    fun install(activity: Activity, content: View, scroll: Boolean = true) {
        val root = if (scroll) ScrollView(activity).apply { addView(content) } else content
        val left = root.paddingLeft; val top = root.paddingTop
        val right = root.paddingRight; val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom); insets
        }
        activity.setContentView(root)
    }
    fun enable(view: View, enabled: Boolean) {
        if (view is Button || view is EditText || view is Spinner || view is CheckBox) view.isEnabled = enabled
        if (view is ViewGroup) for (index in 0 until view.childCount) enable(view.getChildAt(index), enabled)
    }
    fun stage(stage: SubmissionStage): String = when (stage) {
        SubmissionStage.DRAFT -> "待整理"
        SubmissionStage.EXPORTED -> "材料已保存"
        SubmissionStage.SUBMITTED -> "已人工提交"
        SubmissionStage.RESOLVED -> "已记录处理结果"
    }
    fun error(error: Throwable): String = when (error) {
        is SecurityException -> "未获得所选文件的访问权限，请重新选择"
        is IllegalArgumentException, is IllegalStateException -> error.message?.take(160) ?: "数据需要核对"
        else -> "文件处理未完成，请检查文件和可用空间后重试"
    }
}
