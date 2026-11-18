package dev.buildit.feature.designer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.layout.LayoutEditor
import dev.buildit.engine.layout.PropertyDef
import dev.buildit.engine.layout.PropertyType
import dev.buildit.engine.layout.Values
import java.io.File

/*
 * Sketchware-style property editing: the phone property bar shows one card per property with its
 * current value, and tapping a card opens a small editor made for that kind of value (sizes with
 * presets, a colour palette, choices, flags, numbers with their unit).
 */

enum class PropertyTab(val label: Int) { Basic(R.string.ds_props_basic), Recent(R.string.ds_props_recent), All(R.string.ds_props_all) }

/** The properties most views need, shown first (Sketchware's "Basic"). */
internal val basicAttributes = listOf(
    "android:id", "android:layout_width", "android:layout_height", "android:text", "android:textSize", "android:textColor",
    "android:hint", "android:background", "android:src", "android:padding", "android:layout_margin", "android:gravity",
    "android:layout_gravity", "android:orientation", "android:layout_weight", "android:visibility", "android:textStyle",
)

/** Properties of the selected view shown under [tab]; Recent keeps the order they were edited in. */
internal fun propertiesForTab(all: List<PropertyDef>, tab: PropertyTab, recent: List<String>): List<PropertyDef> = when (tab) {
    PropertyTab.All -> all
    PropertyTab.Basic -> basicAttributes.mapNotNull { a -> all.firstOrNull { it.attr == a } }
    PropertyTab.Recent -> recent.mapNotNull { a -> all.firstOrNull { it.attr == a } }
}

@Composable
internal fun PropertyTabs(tab: PropertyTab, onTab: (PropertyTab) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PropertyTab.entries.forEach { t ->
            val on = t == tab
            Surface(
                onClick = { onTab(t) }, shape = RoundedCornerShape(6.dp),
                color = if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 1.dp)) }
        }
    }
}

/** Short form of a value for a property card. */
internal fun cardValue(def: PropertyDef, value: String?): String = when {
    value == null -> "—"
    def.type == PropertyType.Id -> LayoutEditor.idName(value) ?: value
    value == "match_parent" || value == "fill_parent" -> "match"
    value == "wrap_content" -> "wrap"
    else -> value
}

@Composable
internal fun PropertyCard(def: PropertyDef, value: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (value != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(min = 72.dp, max = 140.dp),
    ) {
        Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
            Text(def.attr.substringAfter(':'), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (def.type == PropertyType.Color) value?.let(Values::parseColor)?.let { c ->
                    Box(Modifier.padding(end = Spacing.xs).size(12.dp).background(Color(c), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                }
                Text(cardValue(def, value), style = CodeTypography.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Material palette offered by the colour popup (500 shades, then light and dark neutrals). */
internal val paletteColors = listOf(
    "#F44336", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5", "#2196F3", "#03A9F4", "#00BCD4",
    "#009688", "#4CAF50", "#8BC34A", "#CDDC39", "#FFEB3B", "#FFC107", "#FF9800", "#FF5722",
    "#795548", "#9E9E9E", "#607D8B", "#000000", "#FFFFFF", "#F5F5F5", "#212121", "#00000000",
)

/** Splits `16dp` into 16 and dp; values like `@dimen/x` give null. */
internal fun splitDimension(v: String?): Pair<String, String>? {
    val m = Regex("""^(-?\d+(?:\.\d+)?)(dp|sp|px|pt|mm|in|dip)?$""").find(v?.trim() ?: return null) ?: return null
    return m.groupValues[1] to m.groupValues[2].ifEmpty { "dp" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PropertyPopup(def: PropertyDef, value: String?, resDir: File?, onSet: (String?) -> Unit, onDismiss: () -> Unit) {
    val label = def.attr.substringAfter(':')
    // The draft the dialog edits; null means "remove the attribute".
    var draft by remember(def.attr, value) { mutableStateOf(value) }
    var error by remember { mutableStateOf(false) }

    @Composable
    fun choice(text: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(text, modifier = Modifier.padding(start = Spacing.sm))
        }
    }

    @Composable
    fun numberField(number: String, unit: String?, onChange: (String) -> Unit) {
        OutlinedTextField(
            number, onChange, singleLine = true, isError = error,
            label = { Text(unit?.let { stringResource(R.string.ds_popup_value_in, it) } ?: stringResource(R.string.ds_popup_value)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = CodeTypography.body, modifier = Modifier.fillMaxWidth(),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (def.type) {
                    PropertyType.LayoutSize -> {
                        val fixed = draft !in listOf("match_parent", "fill_parent", "wrap_content", null)
                        choice("match_parent", draft == "match_parent" || draft == "fill_parent") { draft = "match_parent" }
                        choice("wrap_content", draft == "wrap_content") { draft = "wrap_content" }
                        choice(stringResource(R.string.ds_popup_fixed), fixed) { if (!fixed) draft = "100dp" }
                        if (fixed) {
                            val (n, u) = splitDimension(draft) ?: ((draft ?: "") to "dp")
                            numberField(n, u) { t -> draft = t.trim() + u; error = splitDimension(draft) == null }
                        }
                    }
                    PropertyType.Dimension, PropertyType.TextSize -> {
                        val unit = if (def.type == PropertyType.TextSize) "sp" else "dp"
                        val (n, u) = splitDimension(draft) ?: ((draft ?: "") to unit)
                        numberField(n, u) { t -> draft = t.trim().ifEmpty { null }?.let { it + u }; error = draft != null && splitDimension(draft) == null }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(top = Spacing.sm)) {
                            (if (def.type == PropertyType.TextSize) listOf(12, 14, 16, 18, 20, 24, 32) else listOf(0, 4, 8, 12, 16, 24, 32)).forEach { p ->
                                Surface(onClick = { draft = "$p$unit"; error = false }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Text("$p", Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs))
                                }
                            }
                        }
                    }
                    PropertyType.Integer, PropertyType.Float -> {
                        numberField(draft.orEmpty(), null) { t ->
                            draft = t.trim().ifEmpty { null }
                            error = draft?.let { if (def.type == PropertyType.Integer) it.toIntOrNull() == null else it.toFloatOrNull() == null } ?: false
                        }
                    }
                    PropertyType.Color -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            paletteColors.forEach { hex ->
                                val c = Color(Values.parseColor(hex)!!)
                                val selected = draft.equals(hex, ignoreCase = true)
                                Box(
                                    Modifier.size(36.dp).background(c, CircleShape)
                                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                                        .clickable { draft = hex; error = false }
                                        .semantics { contentDescription = hex },
                                )
                            }
                        }
                        OutlinedTextField(
                            draft.orEmpty(), { t -> draft = t.trim().ifEmpty { null }; error = draft?.let { it.startsWith("#") && Values.parseColor(it) == null } ?: false },
                            label = { Text(stringResource(R.string.ds_popup_color_hint)) }, singleLine = true, isError = error,
                            textStyle = CodeTypography.body, modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
                        )
                    }
                    PropertyType.Enum, PropertyType.Boolean -> {
                        val options = if (def.type == PropertyType.Boolean) listOf("true", "false") else def.options
                        options.forEach { opt -> choice(opt, draft == opt) { draft = opt } }
                    }
                    PropertyType.Flags -> {
                        val selected = draft?.split('|')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
                        def.options.forEach { opt ->
                            val on = opt in selected
                            Row(
                                Modifier.fillMaxWidth().toggleable(on, role = Role.Checkbox) {
                                    draft = (if (on) selected - opt else selected + opt).joinToString("|").ifEmpty { null }
                                }.padding(vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = null)
                                Text(opt, modifier = Modifier.padding(start = Spacing.sm))
                            }
                        }
                    }
                    PropertyType.Drawable -> {
                        val drawables = remember(resDir) {
                            resDir?.listFiles { f -> f.isDirectory && (f.name == "drawable" || f.name.startsWith("drawable-") || f.name.startsWith("mipmap")) }
                                .orEmpty().flatMap { d -> d.listFiles().orEmpty().map { "@${d.name.substringBefore('-')}/${it.nameWithoutExtension.removeSuffix(".9")}" } }
                                .distinct().sorted()
                        }
                        drawables.forEach { d -> choice(d, draft == d) { draft = d } }
                        OutlinedTextField(draft.orEmpty(), { draft = it.trim().ifEmpty { null } }, singleLine = true, textStyle = CodeTypography.body, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm))
                    }
                    PropertyType.Id -> OutlinedTextField(
                        LayoutEditor.idName(draft.orEmpty()) ?: draft.orEmpty(),
                        { t -> val n = t.trim(); draft = n.ifEmpty { null }?.let { "@+id/$it" }; error = n.isNotEmpty() && !Regex("[A-Za-z_][A-Za-z0-9_]*").matches(n) },
                        singleLine = true, isError = error, textStyle = CodeTypography.body, modifier = Modifier.fillMaxWidth(),
                    )
                    PropertyType.Text -> OutlinedTextField(draft.orEmpty(), { draft = it.ifEmpty { null } }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    PropertyType.Raw -> OutlinedTextField(draft.orEmpty(), { draft = it.ifEmpty { null } }, singleLine = true, textStyle = CodeTypography.body, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(enabled = !error, onClick = { onSet(draft); onDismiss() }) { Text(stringResource(R.string.ds_popup_apply)) } },
        dismissButton = {
            Row {
                if (value != null) TextButton(onClick = { onSet(null); onDismiss() }) { Text(stringResource(R.string.ds_popup_clear)) }
                Box(Modifier.width(Spacing.xs))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ds_cancel)) }
            }
        },
    )
}
