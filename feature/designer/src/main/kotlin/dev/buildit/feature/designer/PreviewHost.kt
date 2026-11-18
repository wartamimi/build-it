package dev.buildit.feature.designer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.buildit.engine.layout.Box
import kotlin.math.min

/**
 * Hosts the rendered preview at the simulated device's exact pixel size and scales it to fit.
 * Touches never reach the preview widgets: taps select, long-press + drag moves an element.
 * Selection and drop targets are drawn as an overlay.
 */
@SuppressLint("ViewConstructor")
class PreviewHost(context: Context) : ViewGroup(context) {
    var deviceWidthPx = 1
    var deviceHeightPx = 1
    var zoom = 1f
        set(value) { field = value; requestLayout() }
    var surfaceColor = Color.WHITE
        set(value) { field = value; invalidate() }

    private var content: View? = null
    private var views: Map<Long, View> = emptyMap()
    var scale = 1f
        private set

    var selectedUid: Long? = null
        set(value) { field = value; invalidate() }
    var highlightUid: Long? = null
        set(value) { field = value; invalidate() }

    /** Called with preview-pixel coordinates. */
    var onTap: ((Float, Float) -> Unit)? = null
    var onDragStart: ((Float, Float) -> Boolean)? = null
    var onDragMove: ((Float, Float) -> Unit)? = null
    var onDragEnd: ((Float, Float, Boolean) -> Unit)? = null

    private var dragging = false
    private val density = resources.displayMetrics.density
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density; color = 0xFF2D57C0.toInt() }
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density; color = 0xFFA63D0B.toInt()
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 5 * density), 0f)
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1 * density; color = 0x33000000 }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap?.invoke(e.x / scale, e.y / scale); return true
        }
        override fun onLongPress(e: MotionEvent) {
            dragging = onDragStart?.invoke(e.x / scale, e.y / scale) == true
            if (dragging) parent?.requestDisallowInterceptTouchEvent(true)
        }
    })

    init {
        setWillNotDraw(false)
        isLongClickable = true
    }

    fun setContent(root: View, viewMap: Map<Long, View>, widthPx: Int, heightPx: Int) {
        content?.let { removeView(it) }
        deviceWidthPx = widthPx
        deviceHeightPx = heightPx
        views = viewMap
        val frame = FrameLayout(root.context).apply { setBackgroundColor(surfaceColor); addView(root) }
        content = frame
        addView(frame)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val availH = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        val fit = min(availW.toFloat() / deviceWidthPx, availH.toFloat() / deviceHeightPx)
        scale = (fit * zoom).coerceIn(0.05f, 4f)
        content?.measure(
            MeasureSpec.makeMeasureSpec(deviceWidthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(deviceHeightPx, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension((deviceWidthPx * scale).toInt(), (deviceHeightPx * scale).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content?.let {
            it.layout(0, 0, deviceWidthPx, deviceHeightPx)
            it.pivotX = 0f; it.pivotY = 0f
            it.scaleX = scale; it.scaleY = scale
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dragging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> onDragMove?.invoke(event.x / scale, event.y / scale)
                MotionEvent.ACTION_UP -> { dragging = false; onDragEnd?.invoke(event.x / scale, event.y / scale, false) }
                MotionEvent.ACTION_CANCEL -> { dragging = false; onDragEnd?.invoke(event.x / scale, event.y / scale, true) }
            }
            return true
        }
        return gestures.onTouchEvent(event)
    }

    /** Bounds of every rendered element in preview pixels, relative to the device screen. */
    fun boxes(): Map<Long, Box> {
        val root = content ?: return emptyMap()
        return views.mapNotNull { (uid, v) -> boundsOf(v, root)?.let { uid to it } }.toMap()
    }

    private fun boundsOf(v: View, root: View): Box? {
        if (!v.isAttachedToWindow && v.parent == null) return null
        var x = 0f
        var y = 0f
        var cur: View? = v
        while (cur != null && cur !== root) {
            x += cur.left - (cur.parent as? View)?.scrollX.let { it ?: 0 }
            y += cur.top - (cur.parent as? View)?.scrollY.let { it ?: 0 }
            cur = cur.parent as? View
        }
        if (cur !== root) return null
        if (v.visibility == View.GONE) return null
        return Box(x, y, x + v.width, y + v.height)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val boxes = if (selectedUid != null || highlightUid != null) boxes() else emptyMap()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), framePaint)
        highlightUid?.let { boxes[it] }?.let { drawBox(canvas, it, hiPaint) }
        selectedUid?.let { boxes[it] }?.let { drawBox(canvas, it, selPaint) }
    }

    private fun drawBox(c: Canvas, b: Box, p: Paint) {
        val inset = p.strokeWidth / 2
        c.drawRect(b.left * scale + inset, b.top * scale + inset, b.right * scale - inset, b.bottom * scale - inset, p)
    }
}
