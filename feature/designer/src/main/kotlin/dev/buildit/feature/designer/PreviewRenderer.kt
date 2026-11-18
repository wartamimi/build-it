package dev.buildit.feature.designer

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import dev.buildit.engine.layout.Attr
import dev.buildit.engine.layout.LayoutEditor
import dev.buildit.engine.layout.ResourceValues
import dev.buildit.engine.layout.Values
import dev.buildit.engine.layout.XmlElement
import java.io.File

/** Something the preview could not show exactly as the app will. Never hidden from the user. */
data class PreviewIssue(val uid: Long, val code: String, val detail: String)

class RenderResult(val root: View, val views: Map<Long, View>, val issues: List<PreviewIssue>)

/**
 * Builds real Android Views from the layout document (no XML inflation: LayoutInflater only reads
 * compiled XML). Supported attributes are applied programmatically; anything that cannot be shown
 * is reported as a [PreviewIssue] instead of being faked.
 */
class PreviewRenderer(
    base: Context,
    private val config: PreviewConfig,
    private val values: ResourceValues,
    private val resDir: File?,
) {
    val context: Context = themed(base, config)
    private val dm = context.resources.displayMetrics
    private val views = LinkedHashMap<Long, View>()
    private val issues = mutableListOf<PreviewIssue>()
    private val ids = HashMap<String, Int>()
    private var count = 0

    fun render(root: XmlElement): RenderResult {
        val view = build(root, null)
        view.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return RenderResult(view, views, issues)
    }

    private fun build(e: XmlElement, parent: ViewGroup?): View {
        if (++count > MAX_VIEWS) {
            issue(e, "too_many_views", "$MAX_VIEWS")
            return placeholder(e, e.name)
        }
        val view = try {
            create(e) ?: placeholder(e, e.name).also { issue(e, "not_previewable", e.name) }
        } catch (t: Throwable) {
            issue(e, "create_failed", t.message ?: t.javaClass.simpleName)
            placeholder(e, e.name)
        }
        views[e.uid] = view
        e[Attr.ID]?.let { view.id = idFor(LayoutEditor.idName(it)) }
        try {
            applyAttributes(e, view)
        } catch (t: Throwable) {
            issue(e, "attribute_failed", t.message ?: t.javaClass.simpleName)
        }
        if (view is ViewGroup && view !is RecyclerView) {
            e.elements.forEach { child ->
                val cv = build(child, view)
                try {
                    view.addView(cv, layoutParams(child, view))
                } catch (t: Throwable) {
                    issue(child, "attach_failed", t.message ?: t.javaClass.simpleName)
                }
            }
        } else if (e.elements.isNotEmpty()) {
            issue(e, "children_ignored", e.name)
        }
        return view
    }

    private fun create(e: XmlElement): View? {
        val c = context
        return when (e.name) {
            "LinearLayout" -> LinearLayout(c)
            "FrameLayout" -> FrameLayout(c)
            "RelativeLayout" -> RelativeLayout(c)
            "ScrollView" -> ScrollView(c)
            "HorizontalScrollView" -> HorizontalScrollView(c)
            "TextView" -> TextView(c)
            "Button" -> Button(c)
            "EditText" -> EditText(c)
            "ImageView" -> ImageView(c)
            "ImageButton" -> ImageButton(c)
            "CheckBox" -> CheckBox(c)
            "RadioButton" -> RadioButton(c)
            "Switch" -> android.widget.Switch(c)
            "ProgressBar" -> ProgressBar(c)
            "View" -> View(c)
            "WebView" -> placeholder(e, "WebView").also { issue(e, "runtime_content", "WebView") }
            "androidx.constraintlayout.widget.ConstraintLayout" -> ConstraintLayout(c)
            "androidx.cardview.widget.CardView" -> CardView(c)
            "com.google.android.material.card.MaterialCardView" -> MaterialCardView(c)
            "androidx.recyclerview.widget.RecyclerView" -> RecyclerView(c).also { issue(e, "runtime_content", "RecyclerView") }
            "androidx.appcompat.widget.Toolbar" -> Toolbar(c)
            "com.google.android.material.appbar.AppBarLayout" -> AppBarLayout(c)
            "com.google.android.material.bottomnavigation.BottomNavigationView" -> BottomNavigationView(c)
            "androidx.drawerlayout.widget.DrawerLayout" -> DrawerLayout(c)
            else -> null
        }
    }

    // ---------------- attributes ----------------

    private fun applyAttributes(e: XmlElement, v: View) {
        val handled = HashSet<String>()
        fun attr(name: String): String? = e[name]?.also { handled += name }

        attr("android:padding")?.let { px(it, e) }?.let { p -> v.setPaddingRelative(p, p, p, p) }
        val ps = attr("android:paddingStart")?.let { px(it, e) }
        val pt = attr("android:paddingTop")?.let { px(it, e) }
        val pe = attr("android:paddingEnd")?.let { px(it, e) }
        val pb = attr("android:paddingBottom")?.let { px(it, e) }
        if (ps != null || pt != null || pe != null || pb != null) {
            v.setPaddingRelative(ps ?: v.paddingStart, pt ?: v.paddingTop, pe ?: v.paddingEnd, pb ?: v.paddingBottom)
        }
        attr("android:background")?.let { drawable(it, e) }?.let { v.background = it }
        attr("android:alpha")?.toFloatOrNull()?.let { v.alpha = it.coerceIn(0f, 1f) }
        attr("android:elevation")?.let { px(it, e) }?.let { v.elevation = it.toFloat() }
        attr("android:visibility")?.let { v.visibility = when (it) { "invisible" -> View.INVISIBLE; "gone" -> View.GONE; else -> View.VISIBLE } }
        attr("android:enabled")?.let { v.isEnabled = it != "false" }
        attr("android:clickable")?.let { v.isClickable = it == "true" }
        attr("android:contentDescription")?.let { v.contentDescription = text(it) }

        if (v is LinearLayout) {
            attr("android:orientation")?.let { v.orientation = if (it == "horizontal") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
            attr("android:gravity")?.let { v.gravity = gravity(it) }
        }
        if (v is RelativeLayout) attr("android:gravity")?.let { v.gravity = gravity(it) }
        if (v is TextView) {
            attr("android:text")?.let { v.text = text(it) }
            attr("android:hint")?.let { v.hint = text(it) }
            attr("android:textSize")?.let { dimension(it) }?.let { (value, unit) -> v.setTextSize(unit, value) }
            attr("android:textColor")?.let { color(it, e) }?.let { v.setTextColor(it) }
            val family = attr("android:fontFamily")
            val style = attr("android:textStyle")
            if (family != null || style != null) {
                val s = (style ?: "").split('|').fold(Typeface.NORMAL) { acc, f ->
                    acc or when (f.trim()) { "bold" -> Typeface.BOLD; "italic" -> Typeface.ITALIC; else -> 0 }
                }
                v.setTypeface(if (family != null) Typeface.create(family, s) else Typeface.defaultFromStyle(s))
            }
            attr("android:gravity")?.let { v.gravity = gravity(it) }
            attr("android:maxLines")?.toIntOrNull()?.let { v.maxLines = it }
            attr("android:inputType")?.let { v.inputType = inputType(it) }
        }
        if (v is CompoundButton) attr("android:checked")?.let { v.isChecked = it == "true" }
        if (v is ImageView) {
            attr("android:src")?.let { drawable(it, e) }?.let { v.setImageDrawable(it) }
            attr("android:scaleType")?.let { st ->
                v.scaleType = when (st) {
                    "centerCrop" -> ImageView.ScaleType.CENTER_CROP; "centerInside" -> ImageView.ScaleType.CENTER_INSIDE
                    "fitXY" -> ImageView.ScaleType.FIT_XY; "center" -> ImageView.ScaleType.CENTER; else -> ImageView.ScaleType.FIT_CENTER
                }
            }
        }
        if (v is CardView) {
            attr("app:cardCornerRadius")?.let { px(it, e) }?.let { v.radius = it.toFloat() }
            attr("app:cardElevation")?.let { px(it, e) }?.let { v.cardElevation = it.toFloat() }
            attr("app:cardBackgroundColor")?.let { color(it, e) }?.let { v.setCardBackgroundColor(it) }
        }
        if (v is MaterialCardView) {
            attr("app:strokeWidth")?.let { px(it, e) }?.let { v.strokeWidth = it }
            attr("app:strokeColor")?.let { color(it, e) }?.let { v.strokeColor = it }
        }
        if (v is Toolbar) attr("app:title")?.let { v.title = text(it) }
        if (v is BottomNavigationView && e["app:menu"] != null) {
            handled += "app:menu"
            issue(e, "menu_not_previewed", e["app:menu"]!!)
        }

        // Layout attributes are applied by the parent through layoutParams().
        e.attributes.keys.filterNot { k ->
            k in handled || k.startsWith("xmlns:") || k.startsWith("tools:") || k == Attr.ID ||
                k.substringAfter(':').startsWith("layout_")
        }.forEach { issue(e, "attribute_not_previewed", it) }
    }

    private fun layoutParams(e: XmlElement, parent: ViewGroup): ViewGroup.LayoutParams {
        val w = size(e[Attr.WIDTH], e)
        val h = size(e[Attr.HEIGHT], e)
        val lp: ViewGroup.MarginLayoutParams = when (parent) {
            is LinearLayout -> LinearLayout.LayoutParams(w, h).apply {
                e["android:layout_weight"]?.toFloatOrNull()?.let { weight = it }
                e["android:layout_gravity"]?.let { gravity = gravity(it) }
            }
            is ConstraintLayout -> ConstraintLayout.LayoutParams(w, h).apply { constraints(e, this) }
            is RelativeLayout -> RelativeLayout.LayoutParams(w, h).apply { relative(e, this) }
            is DrawerLayout -> DrawerLayout.LayoutParams(w, h).apply { e["android:layout_gravity"]?.let { gravity = gravity(it) } }
            is CardView, is ScrollView, is HorizontalScrollView, is FrameLayout -> FrameLayout.LayoutParams(w, h).apply {
                e["android:layout_gravity"]?.let { gravity = gravity(it) }
            }
            is AppBarLayout -> AppBarLayout.LayoutParams(w, h)
            else -> ViewGroup.MarginLayoutParams(w, h)
        }
        e["android:layout_margin"]?.let { px(it, e) }?.let { m -> lp.setMargins(m, m, m, m); lp.marginStart = m; lp.marginEnd = m }
        e["android:layout_marginStart"]?.let { px(it, e) }?.let { lp.marginStart = it }
        e["android:layout_marginEnd"]?.let { px(it, e) }?.let { lp.marginEnd = it }
        e["android:layout_marginTop"]?.let { px(it, e) }?.let { lp.topMargin = it }
        e["android:layout_marginBottom"]?.let { px(it, e) }?.let { lp.bottomMargin = it }
        return lp
    }

    private fun constraints(e: XmlElement, lp: ConstraintLayout.LayoutParams) {
        fun target(attr: String): Int? = e[attr]?.let { if (it == "parent") ConstraintLayout.LayoutParams.PARENT_ID else idFor(LayoutEditor.idName(it)) }
        target("app:layout_constraintTop_toTopOf")?.let { lp.topToTop = it }
        target("app:layout_constraintTop_toBottomOf")?.let { lp.topToBottom = it }
        target("app:layout_constraintBottom_toBottomOf")?.let { lp.bottomToBottom = it }
        target("app:layout_constraintBottom_toTopOf")?.let { lp.bottomToTop = it }
        target("app:layout_constraintStart_toStartOf")?.let { lp.startToStart = it }
        target("app:layout_constraintStart_toEndOf")?.let { lp.startToEnd = it }
        target("app:layout_constraintEnd_toEndOf")?.let { lp.endToEnd = it }
        target("app:layout_constraintEnd_toStartOf")?.let { lp.endToStart = it }
        e["app:layout_constraintHorizontal_bias"]?.toFloatOrNull()?.let { lp.horizontalBias = it }
        e["app:layout_constraintVertical_bias"]?.toFloatOrNull()?.let { lp.verticalBias = it }
    }

    private fun relative(e: XmlElement, lp: RelativeLayout.LayoutParams) {
        val flags = mapOf(
            "android:layout_alignParentTop" to RelativeLayout.ALIGN_PARENT_TOP, "android:layout_alignParentBottom" to RelativeLayout.ALIGN_PARENT_BOTTOM,
            "android:layout_alignParentStart" to RelativeLayout.ALIGN_PARENT_START, "android:layout_alignParentEnd" to RelativeLayout.ALIGN_PARENT_END,
            "android:layout_centerInParent" to RelativeLayout.CENTER_IN_PARENT, "android:layout_centerHorizontal" to RelativeLayout.CENTER_HORIZONTAL,
            "android:layout_centerVertical" to RelativeLayout.CENTER_VERTICAL,
        )
        flags.forEach { (a, rule) -> if (e[a] == "true") lp.addRule(rule) }
        val anchors = mapOf(
            "android:layout_below" to RelativeLayout.BELOW, "android:layout_above" to RelativeLayout.ABOVE,
            "android:layout_toStartOf" to RelativeLayout.START_OF, "android:layout_toEndOf" to RelativeLayout.END_OF,
        )
        anchors.forEach { (a, rule) -> e[a]?.let { lp.addRule(rule, idFor(LayoutEditor.idName(it))) } }
    }

    // ---------------- value resolution ----------------

    private fun resolved(raw: String): String? = values.resolve(raw, config.night)

    private fun text(raw: String): String = when {
        raw.startsWith("@string/") -> resolved(raw) ?: raw
        raw.startsWith("@android:string/") -> frameworkString(raw.substringAfter('/')) ?: raw
        else -> raw
    }

    private fun size(raw: String?, e: XmlElement): Int = when (raw) {
        null, "wrap_content" -> ViewGroup.LayoutParams.WRAP_CONTENT
        "match_parent", "fill_parent" -> ViewGroup.LayoutParams.MATCH_PARENT
        else -> px(raw, e) ?: ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private fun dimension(raw: String): Pair<Float, Int>? {
        val v = if (raw.startsWith("@dimen/")) resolved(raw) ?: return null else raw
        val d = Values.parseDimension(v) ?: return null
        val unit = when (d.unit) {
            Values.Unit.Dp -> TypedValue.COMPLEX_UNIT_DIP; Values.Unit.Sp -> TypedValue.COMPLEX_UNIT_SP
            Values.Unit.Px -> TypedValue.COMPLEX_UNIT_PX; Values.Unit.Pt -> TypedValue.COMPLEX_UNIT_PT
            Values.Unit.In -> TypedValue.COMPLEX_UNIT_IN; Values.Unit.Mm -> TypedValue.COMPLEX_UNIT_MM
        }
        return d.value to unit
    }

    private fun px(raw: String, e: XmlElement): Int? {
        if (raw.startsWith("?")) return themeDimension(raw) ?: null.also { issue(e, "unresolved", raw) }
        val (value, unit) = dimension(raw) ?: return null.also { issue(e, "unresolved", raw) }
        return TypedValue.applyDimension(unit, value, dm).toInt()
    }

    private fun color(raw: String, e: XmlElement): Int? {
        Values.parseColor(raw)?.let { return it }
        return when {
            raw.startsWith("@color/") -> resolved(raw)?.let { Values.parseColor(it) }
            raw.startsWith("@android:color/") -> frameworkColor(raw.substringAfter('/'))
            raw.startsWith("?") -> themeColor(raw)
            else -> null
        } ?: null.also { issue(e, "unresolved", raw) }
    }

    private fun drawable(raw: String, e: XmlElement): Drawable? {
        if (raw.startsWith("#") || raw.startsWith("@color/") || raw.startsWith("@android:color/") || raw.startsWith("?")) {
            return color(raw, e)?.let { ColorDrawable(it) }
        }
        if (raw.startsWith("@android:drawable/")) {
            val id = context.resources.getIdentifier(raw.substringAfter('/'), "drawable", "android")
            if (id != 0) return context.getDrawable(id)
        }
        if (raw.startsWith("@drawable/") || raw.startsWith("@mipmap/")) {
            val type = raw.substringAfter('@').substringBefore('/')
            val name = raw.substringAfter('/')
            val files = resDir?.listFiles { f -> f.isDirectory && f.name.startsWith(type) }.orEmpty()
                .sortedBy { it.name.length }.flatMap { dir -> dir.listFiles { f -> f.nameWithoutExtension == name }.orEmpty().toList() }
            val bitmapFile = files.firstOrNull { it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") }
            if (bitmapFile != null) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                BitmapFactory.decodeFile(bitmapFile.path, opts)?.let { return BitmapDrawable(context.resources, it) }
            }
            if (files.any { it.extension == "xml" }) issue(e, "xml_drawable_not_previewed", raw) else issue(e, "unresolved", raw)
            return null
        }
        issue(e, "unresolved", raw)
        return null
    }

    private fun themeAttr(raw: String): TypedValue? {
        val name = raw.removePrefix("?").removePrefix("attr/").removePrefix("android:attr/").removePrefix("android:")
        val pkg = if (raw.contains("android:")) "android" else context.packageName
        val id = context.resources.getIdentifier(name, "attr", pkg).takeIf { it != 0 }
            ?: context.resources.getIdentifier(name, "attr", "android").takeIf { it != 0 } ?: return null
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(id, tv, true)) tv else null
    }

    private fun themeDimension(raw: String): Int? = themeAttr(raw)?.let { tv ->
        if (tv.type == TypedValue.TYPE_DIMENSION) TypedValue.complexToDimensionPixelSize(tv.data, dm) else null
    }

    private fun themeColor(raw: String): Int? = themeAttr(raw)?.let { tv ->
        if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) tv.data
        else if (tv.resourceId != 0) runCatching { context.getColor(tv.resourceId) }.getOrNull() else null
    }

    private fun frameworkColor(name: String): Int? =
        context.resources.getIdentifier(name, "color", "android").takeIf { it != 0 }?.let { runCatching { context.getColor(it) }.getOrNull() }

    private fun frameworkString(name: String): String? =
        context.resources.getIdentifier(name, "string", "android").takeIf { it != 0 }?.let { context.getString(it) }

    private fun gravity(raw: String): Int = raw.split('|').fold(0) { acc, g ->
        acc or when (g.trim()) {
            "start" -> Gravity.START; "end" -> Gravity.END; "left" -> Gravity.LEFT; "right" -> Gravity.RIGHT
            "top" -> Gravity.TOP; "bottom" -> Gravity.BOTTOM; "center" -> Gravity.CENTER
            "center_horizontal" -> Gravity.CENTER_HORIZONTAL; "center_vertical" -> Gravity.CENTER_VERTICAL
            "fill" -> Gravity.FILL; "fill_horizontal" -> Gravity.FILL_HORIZONTAL; "fill_vertical" -> Gravity.FILL_VERTICAL
            else -> 0
        }
    }

    private fun inputType(raw: String): Int = raw.split('|').fold(0) { acc, t ->
        acc or when (t.trim()) {
            "text" -> InputType.TYPE_CLASS_TEXT
            "textPassword" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            "textEmailAddress" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            "textMultiLine" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            "number" -> InputType.TYPE_CLASS_NUMBER
            "numberDecimal" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            "phone" -> InputType.TYPE_CLASS_PHONE
            else -> 0
        }
    }

    private fun idFor(name: String): Int = ids.getOrPut(name) { View.generateViewId() }

    private fun placeholder(e: XmlElement, label: String): View = TextView(context).apply {
        text = label.substringAfterLast('.')
        gravity = Gravity.CENTER
        setTextColor(Color.GRAY)
        minHeight = (48 * dm.density).toInt()
        background = GradientDrawable().apply {
            setStroke((1 * dm.density).toInt().coerceAtLeast(1), Color.GRAY, 6 * dm.density, 4 * dm.density)
            setColor(Color.argb(20, 128, 128, 128))
        }
    }

    private fun issue(e: XmlElement, code: String, detail: String) {
        issues += PreviewIssue(e.uid, code, detail)
    }

    companion object {
        const val MAX_VIEWS = 1500

        fun themed(base: Context, config: PreviewConfig): Context {
            val (w, h) = config.device.size(config.landscape)
            val conf = Configuration(base.resources.configuration).apply {
                densityDpi = config.device.densityDpi
                screenWidthDp = w
                screenHeightDp = h
                smallestScreenWidthDp = minOf(w, h)
                orientation = if (config.landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (config.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                fontScale = 1f
            }
            val configured = base.createConfigurationContext(conf)
            return ContextThemeWrapper(configured, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
        }
    }
}
