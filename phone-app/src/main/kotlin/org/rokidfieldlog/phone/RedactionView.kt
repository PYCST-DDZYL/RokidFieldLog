package org.rokidfieldlog.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.rokidfieldlog.core.MaskRect

class RedactionView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val boxes = mutableListOf<MaskRect>()
    private val displayRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var start: Pair<Float, Float>? = null
    private var pending: MaskRect? = null
    var drawMode = true
    var onChanged: (() -> Unit)? = null
    val masks: List<MaskRect> get() = boxes.toList()
    private val scaling = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 8f)
            start = null; pending = null; clampPan(); invalidate(); return true
        }
    })
    init {
        contentDescription = "照片审阅。画框模式下拖动添加不透明遮挡；查看模式下可以拖动放大的照片。"
        isFocusable = true
    }
    fun setImage(image: Bitmap, masks: List<MaskRect> = emptyList()) {
        bitmap = image; boxes.clear(); boxes.addAll(masks)
        start = null; pending = null; zoom = 1f; panX = 0f; panY = 0f; invalidate()
    }
    fun undo() { if (boxes.isNotEmpty()) { boxes.removeAt(boxes.lastIndex); onChanged?.invoke(); invalidate() } }
    fun clearMasks() { boxes.clear(); onChanged?.invoke(); invalidate() }
    fun changeZoom(factor: Float) { zoom = (zoom * factor).coerceIn(1f, 8f); clampPan(); invalidate() }
    fun fit() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }
    private fun imageRect(): RectF {
        val image = bitmap ?: return displayRect.apply { setEmpty() }
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height) * zoom
        val w = image.width * scale; val h = image.height * scale
        displayRect.set((width - w) / 2 + panX, (height - h) / 2 + panY, (width + w) / 2 + panX, (height + h) / 2 + panY)
        return displayRect
    }
    private fun clampPan() {
        val image = bitmap ?: return
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height) * zoom
        val xLimit = ((image.width * scale - width) / 2).coerceAtLeast(0f)
        val yLimit = ((image.height * scale - height) / 2).coerceAtLeast(0f)
        panX = panX.coerceIn(-xLimit, xLimit); panY = panY.coerceIn(-yLimit, yLimit)
    }
    private fun point(x: Float, y: Float): Pair<Float, Float>? {
        val rect = imageRect()
        if (rect.width() <= 0 || rect.height() <= 0) return null
        return ((x - rect.left) / rect.width()).coerceIn(0f, 1f) to ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(21, 27, 24))
        val image = bitmap ?: return
        val rect = imageRect()
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        canvas.drawBitmap(image, null, rect, paint)
        for (mask in boxes) drawMask(canvas, rect, mask)
        pending?.let { drawMask(canvas, rect, it) }
        paint.style = Paint.Style.FILL
    }
    private fun drawMask(canvas: Canvas, image: RectF, mask: MaskRect) {
        val left = image.left + mask.left * image.width(); val top = image.top + mask.top * image.height()
        val right = image.left + mask.right * image.width(); val bottom = image.top + mask.bottom * image.height()
        paint.style = Paint.Style.FILL; paint.color = Color.BLACK
        canvas.drawRect(left, top, right, bottom, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.rgb(74, 222, 142)
        canvas.drawRect(left, top, right, bottom, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || bitmap == null) return false
        scaling.onTouchEvent(event)
        if (event.pointerCount > 1 || scaling.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
            start = null; pending = null; return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                start = if (drawMode && imageRect().contains(event.x, event.y)) point(event.x, event.y) else null
                // At fit zoom, a read-only photo lets its surrounding page scroll.
                parent?.requestDisallowInterceptTouchEvent(drawMode || zoom > 1f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawMode) {
                    val first = start; val end = point(event.x, event.y)
                    if (first != null && end != null) pending = MaskRect(minOf(first.first, end.first), minOf(first.second, end.second),
                        maxOf(first.first, end.first), maxOf(first.second, end.second))
                } else { panX += event.x - lastX; panY += event.y - lastY; clampPan() }
                lastX = event.x; lastY = event.y; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                pending?.takeIf { it.right - it.left > 0.002f && it.bottom - it.top > 0.002f && boxes.size < 100 }?.let {
                    boxes.add(it); onChanged?.invoke()
                }
                start = null; pending = null; parent?.requestDisallowInterceptTouchEvent(false); performClick(); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { start = null; pending = null; parent?.requestDisallowInterceptTouchEvent(false); invalidate() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun release() { bitmap = null; boxes.clear(); invalidate() }
}
