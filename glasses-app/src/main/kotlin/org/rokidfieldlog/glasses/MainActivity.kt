package org.rokidfieldlog.glasses

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import android.widget.ImageView
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
import java.util.UUID
import org.rokidfieldlog.core.IssueCategory
import org.rokidfieldlog.core.LowStorageException

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val fileExecutor by lazy { (application as GlassesApplication).captureFileExecutor }
    private lateinit var store: PrivateCaptureStore
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var captureButton: Button
    private lateinit var categoryButton: Button
    private lateinit var reviewButton: Button
    private var managing = false
    private var screenVisible = false
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
    private val hidePanel = Runnable {
        panel.visibility = View.INVISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private val idleCamera = object : Runnable {
        override fun run() {
            if (capturing) { handler.postDelayed(this, 60_000); return }
            imageCapture?.let { cameraProvider?.unbind(it) }
            imageCapture = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) prepareCamera() else showStatus("摄像头权限未授予", 3_000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        store = PrivateCaptureStore(this)
        buildView()
        selectedCategory = savedInstanceState?.getString("category")?.let { name -> IssueCategory.entries.find { it.name == name } }
        updateActionLabels(false)
        showStatus("正在准备 · 后台检查照片")
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) prepareCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
        (application as GlassesApplication).recoveryExecutor.execute {
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
                if (!capturing && screenVisible) showStatus(recoveryMessage.ifEmpty { "已就绪 · " + categoryLabel(selectedCategory) }, 3_000)
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
        reviewButton = outlineButton("回看 / 撤销上一张") { reviewLatest() }
        panel.addView(captureButton)
        panel.addView(categoryButton)
        panel.addView(reviewButton)
        val parameters = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        root.addView(panel, parameters)
        root.setOnClickListener { showStatus("请选择拍照 · " + categoryLabel(selectedCategory), 5_000) }
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
        if (preparingCamera || isDestroyed || isFinishing) return
        preparingCamera = true
        showStatus("正在准备摄像头…")
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isDestroyed || isFinishing || !screenVisible) {
                preparingCamera = false
                return@addListener
            }
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(93)
                    .build()
                imageCapture?.let { provider.unbind(it) }
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, capture)
                imageCapture = capture
                Log.i("FieldLog", "CAMERA_READY")
                showStatus(recoveryMessage.ifEmpty { "已就绪 · " + categoryLabel(selectedCategory) }, 3_000)
                scheduleCameraIdle()
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
        if (capturing || managing || SystemClock.elapsedRealtime() - lastCaptureElapsed < 500) return
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
        capturing = true
        lastCaptureElapsed = SystemClock.elapsedRealtime()
        handler.removeCallbacks(idleCamera)
        showStatus("正在拍摄 · " + categoryLabel(capturedCategory))
        // Journal creation, space checks and fsync must never run on the UI thread.
        fileExecutor.execute {
            val prepared = runCatching { store.pendingPhoto(photoId, requestedAt, requestedElapsed) }
            runOnUiThread {
                if (prepared.isFailure) {
                    capturing = false
                    if (!isDestroyed) showStatus(if (prepared.exceptionOrNull() is LowStorageException) "空间不足 · 请先清理项目照片" else "私有存储不可用", 3_000)
                    scheduleCameraIdle()
                    return@runOnUiThread
                }
                if (isDestroyed || isFinishing || !screenVisible) {
                    fileExecutor.execute { store.abandonCapture(photoId) }
                    capturing = false
                    return@runOnUiThread
                }
                val pending = prepared.getOrThrow()
                try {
                    camera.takePicture(ImageCapture.OutputFileOptions.Builder(pending).build(), fileExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                val feedback = try {
                                    store.finishPhoto(photoId, pending, requestedAt, requestedElapsed, capturedCategory)
                                    Log.i("FieldLog", "CAPTURE_SAVED")
                                    "已保存 · " + categoryLabel(capturedCategory)
                                } catch (exception: Exception) {
                                    Log.e("FieldLog", "CAPTURE_SAVE_FAILED", exception)
                                    if (store.completedPhotoExists(photoId)) "照片已保存 · 重开恢复记录" else "保存未完成 · 原文件保留"
                                }
                                runOnUiThread {
                                    capturing = false
                                    if (!isDestroyed && screenVisible) { showStatus(feedback, 1_500); scheduleCameraIdle() }
                                }
                            }
                            override fun onError(exception: ImageCaptureException) {
                                store.abandonCapture(photoId)
                                runOnUiThread {
                                    capturing = false
                                    if (!isDestroyed && screenVisible) { showStatus("拍摄失败", 3_000); scheduleCameraIdle() }
                                }
                            }
                        })
                } catch (_: Exception) {
                    fileExecutor.execute { store.abandonCapture(photoId) }
                    capturing = false
                    showStatus("拍摄请求失败", 3_000)
                    scheduleCameraIdle()
                }
            }
        }
    }

    private fun scheduleCameraIdle() {
        handler.removeCallbacks(idleCamera)
        if (!isDestroyed && screenVisible) handler.postDelayed(idleCamera, 60_000)
    }

    private fun reviewLatest() {
        if (capturing || managing) return
        managing = true
        handler.removeCallbacks(hidePanel)
        showStatus("正在读取上一张…")
        fileExecutor.execute {
            val loaded = runCatching {
                val capture = store.latestSaved()
                capture to capture?.let { BitmapFactory.decodeFile(it.file.path, BitmapFactory.Options().apply { inSampleSize = 8 }) }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || !screenVisible) { loaded.getOrNull()?.second?.recycle(); managing = false; return@runOnUiThread }
                if (loaded.isFailure) { managing = false; showStatus("上一张读取失败", 3_000); return@runOnUiThread }
                val (capture, bitmap) = loaded.getOrThrow()
                val dialog = AlertDialog.Builder(this)
                    .setTitle(if (capture == null) "暂无已验证照片" else "上一张 · " + categoryLabel(capture.category))
                    .setNegativeButton("返回", null)
                    .setNeutralButton("数据管理") { _, _ -> showDataManagement() }
                if (bitmap != null) dialog.setView(ImageView(this).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    setPadding(12, 12, 12, 12)
                })
                if (capture != null) dialog.setPositiveButton("撤销这张") { _, _ ->
                    AlertDialog.Builder(this)
                        .setTitle("撤销刚才回看的照片？")
                        .setMessage("删除这张照片及其记录。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("撤销") { _, _ -> deleteOne(capture.photoId) }
                        .show()
                }
                dialog.create().apply {
                    setOnDismissListener {
                        bitmap?.recycle()
                        managing = false
                        if (!isDestroyed && screenVisible) showStatus("已就绪 · " + categoryLabel(selectedCategory), 3_000)
                    }
                    show()
                }
            }
        }
    }

    private fun deleteOne(id: String) {
        if (capturing || managing) return
        managing = true
        showStatus("正在撤销…")
        fileExecutor.execute {
            val result = runCatching { store.deleteCapture(id) }
            runOnUiThread {
                managing = false
                if (!isDestroyed && screenVisible) showStatus(if (result.isSuccess) "已撤销上一张" else "撤销未完成 · 请重开检查", 3_000)
            }
        }
    }

    private fun showDataManagement() {
        AlertDialog.Builder(this).setTitle("项目数据管理")
            .setItems(arrayOf("删除全部项目数据")) { _, _ -> confirmDelete() }
            .setNegativeButton("返回", null).show()
    }

    private fun showStatus(message: String, hideAfter: Long? = null) {
        handler.removeCallbacks(hidePanel)
        panel.visibility = View.VISIBLE
        if (screenVisible) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = message
        count.text = getString(R.string.saved_photo_count, store.savedPhotoCount()) + if (recovering) " · 后台检查中" else ""
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
                    2 -> reviewLatest()
                    else -> capture()
                }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun confirmDelete() {
        if (capturing || managing) return
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
                managing = true
                showStatus("正在删除…")
                fileExecutor.execute {
                    val result = runCatching { store.deleteAllProjectData() }
                    runOnUiThread {
                        managing = false
                        if (!isDestroyed && screenVisible) showStatus(if (result.isSuccess) "项目数据已删除" else "删除未完成", 3_000)
                    }
                }
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
        reviewButton.text = marker(2) + "回看 / 撤销上一张"
    }

    private fun chooseCategory() {
        if (capturing || managing) return
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("category", selectedCategory?.name)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        screenVisible = true
        if (panel.visibility == View.VISIBLE) showStatus("已就绪 · " + categoryLabel(selectedCategory), 3_000)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && imageCapture == null) prepareCamera()
    }

    override fun onStop() {
        screenVisible = false
        handler.removeCallbacks(idleCamera)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        imageCapture?.let { cameraProvider?.unbind(it) }
        imageCapture = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        imageCapture?.let { cameraProvider?.unbind(it) }
        super.onDestroy()
    }
}
