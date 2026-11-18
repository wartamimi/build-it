package dev.buildit.engine.layout

enum class ComponentCategory { Layouts, Text, Buttons, Inputs, Images, Lists, Containers, Navigation, Widgets, Custom }

enum class PropertyType { Id, Dimension, LayoutSize, Text, TextSize, Color, Drawable, Enum, Flags, Boolean, Float, Integer, Raw }

/** An editable attribute shown in the properties inspector. */
data class PropertyDef(
    val attr: String,
    val type: PropertyType,
    val group: PropertyGroup,
    val options: List<String> = emptyList(),
)

enum class PropertyGroup { Identity, Layout, Spacing, Appearance, Text, Behavior }

data class ComponentDef(
    /** XML tag: framework short name (e.g. `TextView`) or fully qualified class name. */
    val tag: String,
    val displayName: String,
    val category: ComponentCategory,
    val isContainer: Boolean = false,
    /** Maximum children (ScrollView = 1); null = unlimited. Ignored for non-containers. */
    val maxChildren: Int? = null,
    /** Attributes set when the component is dropped onto the canvas. */
    val defaults: Map<String, String> = emptyMap(),
    /** Extra properties beyond the common View set. */
    val properties: List<PropertyDef> = emptyList(),
    /** Library that must be a dependency of the project for this view to exist at runtime. */
    val requiresDependency: String? = null,
    /** Short prefix used for generated ids (e.g. "button" → @+id/button1). */
    val idPrefix: String = tag.substringAfterLast('.').replaceFirstChar { it.lowercase() },
)

object Attr {
    const val NS_ANDROID = "http://schemas.android.com/apk/res/android"
    const val NS_APP = "http://schemas.android.com/apk/res-auto"
    const val NS_TOOLS = "http://schemas.android.com/tools"
    const val ID = "android:id"
    const val WIDTH = "android:layout_width"
    const val HEIGHT = "android:layout_height"
}

object ComponentCatalog {
    private val gravityFlags = listOf("start", "end", "top", "bottom", "center", "center_horizontal", "center_vertical", "fill")

    /** Properties every View has. */
    val common: List<PropertyDef> = listOf(
        PropertyDef(Attr.ID, PropertyType.Id, PropertyGroup.Identity),
        PropertyDef(Attr.WIDTH, PropertyType.LayoutSize, PropertyGroup.Layout),
        PropertyDef(Attr.HEIGHT, PropertyType.LayoutSize, PropertyGroup.Layout),
        PropertyDef("android:layout_weight", PropertyType.Float, PropertyGroup.Layout),
        PropertyDef("android:layout_gravity", PropertyType.Flags, PropertyGroup.Layout, gravityFlags),
        PropertyDef("android:layout_margin", PropertyType.Dimension, PropertyGroup.Spacing),
        PropertyDef("android:layout_marginStart", PropertyType.Dimension, PropertyGroup.Spacing),
        PropertyDef("android:layout_marginTop", PropertyType.Dimension, PropertyGroup.Spacing),
        PropertyDef("android:layout_marginEnd", PropertyType.Dimension, PropertyGroup.Spacing),
        PropertyDef("android:layout_marginBottom", PropertyType.Dimension, PropertyGroup.Spacing),
        PropertyDef("android:padding", PropertyType.Dimension, PropertyGroup.Spacing),
        PropertyDef("android:paddingStart", PropertyType.Dimension, PropertyGroup.Spacing),
        PropertyDef("android:paddingTop", PropertyType.Dimension, PropertyGroup.Spacing),
        PropertyDef("android:paddingEnd", PropertyType.Dimension, PropertyGroup.Spacing),
        PropertyDef("android:paddingBottom", PropertyType.Dimension, PropertyGroup.Spacing),
        PropertyDef("android:background", PropertyType.Drawable, PropertyGroup.Appearance),
        PropertyDef("android:alpha", PropertyType.Float, PropertyGroup.Appearance),
        PropertyDef("android:elevation", PropertyType.Dimension, PropertyGroup.Appearance),
        PropertyDef("android:visibility", PropertyType.Enum, PropertyGroup.Behavior, listOf("visible", "invisible", "gone")),
        PropertyDef("android:enabled", PropertyType.Boolean, PropertyGroup.Behavior),
        PropertyDef("android:clickable", PropertyType.Boolean, PropertyGroup.Behavior),
        PropertyDef("android:contentDescription", PropertyType.Text, PropertyGroup.Behavior),
    )

    private val textProps = listOf(
        PropertyDef("android:text", PropertyType.Text, PropertyGroup.Text),
        PropertyDef("android:textSize", PropertyType.TextSize, PropertyGroup.Text),
        PropertyDef("android:textColor", PropertyType.Color, PropertyGroup.Text),
        PropertyDef("android:textStyle", PropertyType.Flags, PropertyGroup.Text, listOf("normal", "bold", "italic")),
        PropertyDef("android:fontFamily", PropertyType.Enum, PropertyGroup.Text, listOf("sans-serif", "sans-serif-medium", "sans-serif-light", "serif", "monospace")),
        PropertyDef("android:gravity", PropertyType.Flags, PropertyGroup.Text, gravityFlags),
        PropertyDef("android:maxLines", PropertyType.Integer, PropertyGroup.Text),
    )
    private val gravity = PropertyDef("android:gravity", PropertyType.Flags, PropertyGroup.Layout, gravityFlags)
    private val orientation = PropertyDef("android:orientation", PropertyType.Enum, PropertyGroup.Layout, listOf("vertical", "horizontal"))
    private val wrap = mapOf(Attr.WIDTH to "wrap_content", Attr.HEIGHT to "wrap_content")
    private val matchWidth = mapOf(Attr.WIDTH to "match_parent", Attr.HEIGHT to "wrap_content")
    private val matchBoth = mapOf(Attr.WIDTH to "match_parent", Attr.HEIGHT to "match_parent")
    private const val CARDVIEW = "androidx.cardview:cardview:1.0.0"
    private const val MATERIAL = "com.google.android.material:material:1.14.0"
    private const val RECYCLER = "androidx.recyclerview:recyclerview:1.4.0"
    private const val CONSTRAINT = "androidx.constraintlayout:constraintlayout:2.2.2"
    private const val APPCOMPAT = "androidx.appcompat:appcompat:1.8.0"
    private const val DRAWER = "androidx.drawerlayout:drawerlayout:1.2.0"

    val all: List<ComponentDef> = listOf(
        ComponentDef("LinearLayout", "LinearLayout", ComponentCategory.Layouts, isContainer = true,
            defaults = matchWidth + ("android:orientation" to "vertical"), properties = listOf(orientation, gravity), idPrefix = "linear"),
        ComponentDef("FrameLayout", "FrameLayout", ComponentCategory.Layouts, isContainer = true, defaults = matchWidth, idPrefix = "frame"),
        ComponentDef("RelativeLayout", "RelativeLayout", ComponentCategory.Layouts, isContainer = true, defaults = matchWidth, properties = listOf(gravity), idPrefix = "relative"),
        ComponentDef("androidx.constraintlayout.widget.ConstraintLayout", "ConstraintLayout", ComponentCategory.Layouts, isContainer = true,
            defaults = matchBoth, requiresDependency = CONSTRAINT, idPrefix = "constraint"),
        ComponentDef("ScrollView", "ScrollView", ComponentCategory.Containers, isContainer = true, maxChildren = 1, defaults = matchBoth, idPrefix = "scroll"),
        ComponentDef("HorizontalScrollView", "HorizontalScrollView", ComponentCategory.Containers, isContainer = true, maxChildren = 1, defaults = matchWidth, idPrefix = "hscroll"),
        ComponentDef("androidx.cardview.widget.CardView", "CardView", ComponentCategory.Containers, isContainer = true, defaults = matchWidth, requiresDependency = CARDVIEW, idPrefix = "card",
            properties = listOf(PropertyDef("app:cardCornerRadius", PropertyType.Dimension, PropertyGroup.Appearance), PropertyDef("app:cardElevation", PropertyType.Dimension, PropertyGroup.Appearance), PropertyDef("app:cardBackgroundColor", PropertyType.Color, PropertyGroup.Appearance))),
        ComponentDef("com.google.android.material.card.MaterialCardView", "MaterialCardView", ComponentCategory.Containers, isContainer = true, defaults = matchWidth, requiresDependency = MATERIAL, idPrefix = "materialCard",
            properties = listOf(PropertyDef("app:cardCornerRadius", PropertyType.Dimension, PropertyGroup.Appearance), PropertyDef("app:strokeWidth", PropertyType.Dimension, PropertyGroup.Appearance), PropertyDef("app:strokeColor", PropertyType.Color, PropertyGroup.Appearance))),
        ComponentDef("TextView", "TextView", ComponentCategory.Text, defaults = wrap + ("android:text" to "TextView"), properties = textProps, idPrefix = "text"),
        ComponentDef("Button", "Button", ComponentCategory.Buttons, defaults = wrap + ("android:text" to "Button"), properties = textProps, idPrefix = "button"),
        ComponentDef("ImageButton", "ImageButton", ComponentCategory.Buttons, defaults = wrap, idPrefix = "imageButton",
            properties = listOf(PropertyDef("android:src", PropertyType.Drawable, PropertyGroup.Appearance))),
        ComponentDef("EditText", "EditText", ComponentCategory.Inputs, defaults = matchWidth + ("android:hint" to "Enter text"), idPrefix = "edit",
            properties = textProps + listOf(PropertyDef("android:hint", PropertyType.Text, PropertyGroup.Text), PropertyDef("android:inputType", PropertyType.Flags, PropertyGroup.Behavior, listOf("text", "textPassword", "textEmailAddress", "number", "numberDecimal", "phone", "textMultiLine")))),
        ComponentDef("CheckBox", "CheckBox", ComponentCategory.Inputs, defaults = wrap + ("android:text" to "CheckBox"), idPrefix = "check",
            properties = textProps + PropertyDef("android:checked", PropertyType.Boolean, PropertyGroup.Behavior)),
        ComponentDef("RadioButton", "RadioButton", ComponentCategory.Inputs, defaults = wrap + ("android:text" to "RadioButton"), idPrefix = "radio",
            properties = textProps + PropertyDef("android:checked", PropertyType.Boolean, PropertyGroup.Behavior)),
        ComponentDef("Switch", "Switch", ComponentCategory.Inputs, defaults = wrap + ("android:text" to "Switch"), idPrefix = "switch",
            properties = textProps + PropertyDef("android:checked", PropertyType.Boolean, PropertyGroup.Behavior)),
        ComponentDef("ImageView", "ImageView", ComponentCategory.Images, defaults = mapOf(Attr.WIDTH to "96dp", Attr.HEIGHT to "96dp"), idPrefix = "image",
            properties = listOf(PropertyDef("android:src", PropertyType.Drawable, PropertyGroup.Appearance), PropertyDef("android:scaleType", PropertyType.Enum, PropertyGroup.Appearance, listOf("fitCenter", "centerCrop", "centerInside", "fitXY", "center")))),
        ComponentDef("ProgressBar", "ProgressBar", ComponentCategory.Widgets, defaults = wrap, idPrefix = "progress"),
        ComponentDef("WebView", "WebView", ComponentCategory.Widgets, defaults = matchBoth, idPrefix = "web"),
        ComponentDef("androidx.recyclerview.widget.RecyclerView", "RecyclerView", ComponentCategory.Lists, defaults = matchBoth, requiresDependency = RECYCLER, idPrefix = "list"),
        ComponentDef("androidx.appcompat.widget.Toolbar", "Toolbar", ComponentCategory.Navigation, defaults = mapOf(Attr.WIDTH to "match_parent", Attr.HEIGHT to "?attr/actionBarSize"), requiresDependency = APPCOMPAT, idPrefix = "toolbar",
            properties = listOf(PropertyDef("app:title", PropertyType.Text, PropertyGroup.Text))),
        ComponentDef("com.google.android.material.appbar.AppBarLayout", "AppBarLayout", ComponentCategory.Navigation, isContainer = true, defaults = matchWidth, requiresDependency = MATERIAL, idPrefix = "appBar"),
        ComponentDef("com.google.android.material.bottomnavigation.BottomNavigationView", "BottomNavigationView", ComponentCategory.Navigation, defaults = matchWidth, requiresDependency = MATERIAL, idPrefix = "bottomNav",
            properties = listOf(PropertyDef("app:menu", PropertyType.Raw, PropertyGroup.Behavior))),
        ComponentDef("androidx.drawerlayout.widget.DrawerLayout", "DrawerLayout", ComponentCategory.Navigation, isContainer = true, defaults = matchBoth, requiresDependency = DRAWER, idPrefix = "drawer"),
        ComponentDef("View", "View (custom)", ComponentCategory.Custom, defaults = mapOf(Attr.WIDTH to "match_parent", Attr.HEIGHT to "1dp"), idPrefix = "view"),
    )

    private val byTag = all.associateBy { it.tag }

    /** Looks up a component by XML tag; unknown tags (custom views) return null. */
    fun find(tag: String): ComponentDef? = byTag[tag]

    fun propertiesFor(tag: String): List<PropertyDef> {
        val extra = find(tag)?.properties.orEmpty()
        return (common + extra).distinctBy { it.attr }
    }

    /** True if an element with [tag] may contain children. Unknown tags are treated as containers only if they already have children. */
    fun isContainer(tag: String): Boolean = find(tag)?.isContainer ?: false
}
