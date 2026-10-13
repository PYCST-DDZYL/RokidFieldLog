package org.rokidfieldlog.glasses

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.UUID
import org.rokidfieldlog.core.IssueCategory

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val fileExecutor by lazy { (application as GlassesApplication).captureFileExecutor }
    private lateinit var store: PrivateCaptureStore
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var captureButton: Button
    private lateinit var categoryButton: Button
    private lateinit var deleteButton: Button
    private var selectedAction = 0
    private var selectedCategory: IssueCategory? = null
    private var previousKeyCode = -1
    private var previousKeyTime = 0L
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preparingCamera = false
    private var capturing = false
    private var recovering = true
    private var recoveryMessage = ""
    private var lastCaptureElapsed = 0L
    private val hidePanel = Runnable { panel.visibility = View.INVISIBLE }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) prepareCamera() else showStatus("摄像头权限未授予", 3_000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        store = PrivateCaptureStore(this)
        buildView()
        showStatus("正在检查本机照片…")
        fileExecutor.execute {
            val result = runCatching { store.recoverInterruptedCaptures() }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                recovering = false
                recoveryMessage = result.fold({ report ->
                    Log.i("FieldLog", "RECOVERY_CHECK restored=${report.recoveredRecords} incomplete=${report.incompleteCaptures} errors=${report.errors}")
                    when {
                        report.errors > 0 -> "恢复未完成 · 原文件保留"
                        report.incompleteCaptures > 0 -> "有 ${report.incompleteCaptures} 条照片需核对"
                        report.recoveredRecords > 0 -> "已恢复 ${report.recoveredRecords} 条照片记录"
                        else -> ""
                    }
                }, { "恢复检查失败 · 原文件保留" })
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) prepareCamera()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun buildView() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
        }
        status = greenText("正在准备摄像头…", 25f)
        count = greenText("仅保存到本机；手机连接未配置", 15f)
        panel.addView(greenText("FieldLog", 24f))
        panel.addView(status)
        panel.addView(count)
        captureButton = outlineButton("拍照 / 单指点击") { capture() }
        categoryButton = outlineButton("问题类型：待确认") { chooseCategory() }
        deleteButton = outlineButton("删除全部项目数据") { confirmDelete() }
        panel.addView(captureButton)
        panel.addView(categoryButton)
        panel.addView(deleteButton)
        val parameters = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        root.addView(panel, parameters)
        root.setOnClickListener { capture() }
        // View handles Enter as a click before Activity.onKeyUp unless intercepted.
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> onKeyDown(keyCode, event)
                    KeyEvent.ACTION_UP -> onKeyUp(keyCode, event)
                    else -> true
                }
            } else false
        }
        setContentView(root)
        root.requestFocus()
    }

    private fun greenText(textValue: String, size: Float) = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(Color.GREEN)
        gravity = Gravity.CENTER
    }

    private fun outlineButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 17f
        isFocusable = false // Enter must reach Activity.onKeyUp on the glasses.
        setTextColor(Color.GREEN)
        background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(1, Color.GREEN) }
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52).apply { topMargin = 10 }
    }

    private fun prepareCamera() {
        if (preparingCamera || recovering || isDestroyed) return
        preparingCamera = true
        showStatus("正在准备摄像头…")
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isDestroyed || isFinishing) {
                preparingCamera = false
                return@addListener
            }
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .build()
                imageCapture?.let { provider.unbind(it) }
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, capture)
                imageCapture = capture
                Log.i("FieldLog", "CAMERA_READY")
                showStatus(recoveryMessage.ifEmpty { "已就绪 · 单指点击拍照" }, 3_000)
            } catch (exception: Exception) {
                imageCapture = null
                Log.e("FieldLog", "CAMERA_INIT_FAILED", exception)
                showStatus("相机初始化失败：${exception.javaClass.simpleName}", 4_000)
            } finally {
                preparingCamera = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        if (recovering || capturing || SystemClock.elapsedRealtime() - lastCaptureElapsed < 500) return
        selectedAction = 0
        updateActionLabels(false)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        val camera = imageCapture
        if (camera == null) { prepareCamera(); return }
        val photoId = UUID.randomUUID().toString()
        val requestedAt = System.currentTimeMillis()
        val requestedElapsed = SystemClock.elapsedRealtimeNanos()
        val capturedCategory = selectedCategory
        val pending: File
        try { pending = store.pendingPhoto(photoId, requestedAt, requestedElapsed) }
        catch (exception: Exception) { showStatus("私有存储不可用", 3_000); return }
        capturing = true
        lastCaptureElapsed = SystemClock.elapsedRealtime()
        showStatus("正在拍摄…")
        try {
            camera.takePicture(ImageCapture.OutputFileOptions.Builder(pending).build(), fileExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val feedback = try {
                            store.finishPhoto(photoId, pending, requestedAt, requestedElapsed, capturedCategory)
                            Log.i("FieldLog", "CAPTURE_SAVED")
                            "本机已保存 · 位置待确认"
                        } catch (exception: Exception) {
                            Log.e("FieldLog", "CAPTURE_SAVE_FAILED", exception)
                            if (store.completedPhotoExists(photoId)) "照片已保存 · 重开恢复记录" else "保存未完成 · 原文件保留"
                        }
                        runOnUiThread {
                            capturing = false
                            if (!isDestroyed) showStatus(feedback, 1_500)
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e("FieldLog", "CAPTURE_FAILED", exception)
                        store.abandonCapture(photoId)
                        runOnUiThread {
                            capturing = false
                            if (!isDestroyed) showStatus("拍摄失败：${exception.imageCaptureError}", 3_000)
                        }
                    }
                })
        } catch (exception: Exception) {
            store.abandonCapture(photoId)
            capturing = false
            showStatus("拍摄请求失败", 3_000)
        }
    }

    private fun showStatus(message: String, hideAfter: Long? = null) {
        handler.removeCallbacks(hidePanel)
        panel.visibility = View.VISIBLE
        status.text = message
        count.text = if (recovering) "正在检查本机文件 · 未同步手机" else getString(R.string.saved_photo_count, store.savedPhotoCount())
        if (hideAfter != null) handler.postDelayed(hidePanel, hideAfter)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER) return true
        if (event.repeatCount == 0) {
            val recentPreviousKey = if (event.eventTime - previousKeyTime <= 500) previousKeyCode else -1
            previousKeyCode = event.keyCode
            previousKeyTime = event.eventTime
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true
            val action = when {
                recentPreviousKey == KeyEvent.KEYCODE_DPAD_RIGHT && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> (selectedAction + 1).coerceAtMost(2)
                recentPreviousKey == KeyEvent.KEYCODE_DPAD_LEFT && event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> (selectedAction - 1).coerceAtLeast(0)
                else -> null
            }
            if (action != null) {
                previousKeyCode = -1
                selectedAction = action
                Log.d("FieldLog", "MENU_SELECTED action=$action")
                updateActionLabels(true)
                showStatus("滑动选择 · 单指点击确认", 5_000)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.repeatCount == 0 && !event.isCanceled) {
                Log.d("FieldLog", "ENTER_ACTION selection=$selectedAction panelVisible=${panel.visibility == View.VISIBLE}")
                when (if (panel.visibility == View.VISIBLE) selectedAction else 0) {
                    1 -> chooseCategory()
                    2 -> confirmDelete()
                    else -> capture()
                }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun confirmDelete() {
        if (recovering || capturing) return
        handler.removeCallbacks(hidePanel)
        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(greenText("删除 FieldLog 本机数据？", 22f).apply { setPadding(20, 20, 20, 8) })
            .setMessage("仅删除此 App 的私有照片和记录。其他相册及 App 不受影响。此操作不可撤销。")
            .setNegativeButton("取消") { _, _ -> cancelDelete() }
            .setOnCancelListener { cancelDelete() }
            .setPositiveButton("删除") { _, _ ->
                selectedAction = 0
                selectedCategory = null
                updateActionLabels(false)
                try { store.deleteAllProjectData(); showStatus("项目数据已删除", 1_500) }
                catch (exception: Exception) { showStatus("删除未完成", 3_000) }
            }.create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(Color.GREEN)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            setTextColor(Color.GREEN)
            requestFocus()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.GREEN)
    }

    private fun cancelDelete() {
        selectedAction = 0
        updateActionLabels(false)
        showStatus("已取消", 1_500)
    }

    private fun categoryLabel(category: IssueCategory?): String = when (category) {
        IssueCategory.ROAD_DAMAGE -> "坑洞 / 路面损坏"
        IssueCategory.DAMAGED_FACILITY -> "设施损坏"
        IssueCategory.LITTER -> "垃圾堆积"
        IssueCategory.OTHER -> "其他问题"
        null -> "待确认"
    }

    private fun updateActionLabels(showMarker: Boolean) {
        fun marker(action: Int) = if (showMarker && selectedAction == action) "> " else ""
        captureButton.text = marker(0) + "拍照 / 单指点击"
        categoryButton.text = marker(1) + "问题类型：" + categoryLabel(selectedCategory)
        deleteButton.text = marker(2) + "删除全部项目数据"
    }

    private fun chooseCategory() {
        if (recovering || capturing) return
        handler.removeCallbacks(hidePanel)
        val categories = listOf(null) + IssueCategory.entries
        val labels = categories.map(::categoryLabel).toTypedArray()
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).apply {
                    (this as CheckedTextView).setTextColor(Color.GREEN)
                    checkMarkTintList = ColorStateList.valueOf(Color.GREEN)
                }
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(greenText("人工标签 · 后续仍需核对", 21f).apply { setPadding(16, 16, 16, 8) })
            .setSingleChoiceItems(adapter, categories.indexOf(selectedCategory)) { choiceDialog, which ->
                selectedCategory = categories[which]
                selectedAction = 0
                updateActionLabels(false)
                Log.d("FieldLog", "CATEGORY_SELECTED value=${selectedCategory?.name ?: "UNCONFIRMED"}")
                choiceDialog.dismiss()
                showStatus("已选：${categoryLabel(selectedCategory)}", 1_500)
            }
            .setNegativeButton("取消") { _, _ -> cancelDelete() }
            .setOnCancelListener { cancelDelete() }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.GREEN)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        imageCapture?.let { cameraProvider?.unbind(it) }
        super.onDestroy()
    }
}
