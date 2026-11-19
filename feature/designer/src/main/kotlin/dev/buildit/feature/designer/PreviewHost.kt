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

    /** Status bar and action bar drawn above the layout, like the device shows them. */
    data class Chrome(val statusPx: Int = 0, val barPx: Int = 0, val color: Int = 0xFF263238.toInt(), val darkColor: Int = 0xFF21272B.toInt(), val title: String = "", val density: Float = 1f)

    var chrome = Chrome()
        private set
    /** Device pixels above the layout content. */
    val topPx get() = chrome.statusPx + chrome.barPx

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
    /** The selected element's bounds in this view's pixels after each draw (null: none); called only on change. */
    var onSelectionBounds: ((android.graphics.RectF?) -> Unit)? = null
    private var lastSelection: android.graphics.RectF? = null

    private var dragging = false
    private val density = resources.displayMetrics.density
    /** The selected view is filled with a see-through tint (like Sketchware), not outlined. */
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x552D7CF0 }
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density; color = 0xFFA63D0B.toInt()
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 5 * density), 0f)
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1 * density; color = 0x33000000 }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap?.invoke(e.x / scale, e.y / scale - topPx); return true
        }
        override fun onLongPress(e: MotionEvent) {
            dragging = onDragStart?.invoke(e.x / scale, e.y / scale - topPx) == true
            if (dragging) parent?.requestDisallowInterceptTouchEvent(true)
        }
    })

    init {
        setWillNotDraw(false)
        isLongClickable = true
    }

    fun setContent(root: View, viewMap: Map<Long, View>, widthPx: Int, heightPx: Int, chrome: Chrome = Chrome()) {
        content?.let { removeView(it) }
        this.chrome = chrome
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
            MeasureSpec.makeMeasureSpec((deviceHeightPx - topPx).coerceAtLeast(1), MeasureSpec.EXACTLY),
        )
        setMeasuredDimension((deviceWidthPx * scale).toInt(), (deviceHeightPx * scale).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content?.let {
            it.layout(0, 0, deviceWidthPx, (deviceHeightPx - topPx).coerceAtLeast(1))
            it.pivotX = 0f; it.pivotY = 0f
            it.scaleX = scale; it.scaleY = scale
            it.translationY = topPx * scale
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dragging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> onDragMove?.invoke(event.x / scale, event.y / scale - topPx)
                MotionEvent.ACTION_UP -> { dragging = false; onDragEnd?.invoke(event.x / scale, event.y / scale - topPx, false) }
                MotionEvent.ACTION_CANCEL -> { dragging = false; onDragEnd?.invoke(event.x / scale, event.y / scale - topPx, true) }
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

    private val chromePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawChrome(c: Canvas) {
        val ch = chrome
        if (topPx == 0) return
        val w = width.toFloat()
        val s = scale
        val dp = s * ch.density // one device dp in canvas pixels
        if (ch.statusPx > 0) {
            val h = ch.statusPx * s
            chromePaint.color = ch.darkColor; chromePaint.style = Paint.Style.FILL
            c.drawRect(0f, 0f, w, h, chromePaint)
            chromePaint.color = Color.WHITE; chromePaint.textSize = h * 0.55f
            chromePaint.textAlign = Paint.Align.LEFT
            c.drawText("12:00", 12 * dp, h * 0.72f, chromePaint)
            // Battery and signal, simple shapes
            val bh = h * 0.4f; val top = (h - bh) / 2
            c.drawRect(w - 12 * dp - bh * 0.6f, top, w - 12 * dp, top + bh, chromePaint)
            val path = android.graphics.Path().apply { moveTo(w - 20 * dp - bh, top + bh); lineTo(w - 20 * dp, top + bh); lineTo(w - 20 * dp, top); close() }
            c.drawPath(path, chromePaint)
        }
        if (ch.barPx > 0) {
            val t = ch.statusPx * s
            val h = ch.barPx * s
            chromePaint.color = ch.color; chromePaint.style = Paint.Style.FILL
            c.drawRect(0f, t, w, t + h, chromePaint)
            chromePaint.color = Color.WHITE; chromePaint.textSize = h * 0.36f
            chromePaint.isFakeBoldText = true
            c.drawText(ch.title, 16 * dp, t + h * 0.62f, chromePaint)
            chromePaint.isFakeBoldText = false
            chromePaint.color = 0x22000000
            c.drawRect(0f, t + h, w, t + h + 2 * dp, chromePaint)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawChrome(canvas)
        val boxes = if (selectedUid != null || highlightUid != null) boxes() else emptyMap()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), framePaint)
        highlightUid?.let { boxes[it] }?.let { drawBox(canvas, it, hiPaint) }
        val sel = selectedUid?.let { boxes[it] }
        sel?.let { drawBox(canvas, it, selPaint) }
        val r = sel?.let { b -> val dy = topPx * scale; android.graphics.RectF(b.left * scale, b.top * scale + dy, b.right * scale, b.bottom * scale + dy) }
        if (r != lastSelection) { lastSelection = r; onSelectionBounds?.invoke(r) }
    }

    private fun drawBox(c: Canvas, b: Box, p: Paint) {
        val inset = p.strokeWidth / 2
        val dy = topPx * scale
        c.drawRect(b.left * scale + inset, b.top * scale + inset + dy, b.right * scale - inset, b.bottom * scale - inset + dy, p)
    }
}
