@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.buildit.feature.designer

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.buildit.core.designsystem.component.BiEmptyState
import dev.buildit.core.designsystem.component.BiIconButton
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.layout.Attr
import dev.buildit.engine.layout.ComponentCatalog
import dev.buildit.engine.layout.ComponentDef
import dev.buildit.engine.layout.LayoutCommand
import dev.buildit.engine.layout.LayoutDiagnostic
import dev.buildit.engine.layout.LayoutEditor
import dev.buildit.engine.layout.PropertyDef
import dev.buildit.engine.layout.PropertyGroup
import dev.buildit.engine.layout.PropertyType
import dev.buildit.engine.layout.Values
import dev.buildit.engine.layout.XmlElement

/** A component being dragged from the library; position in window coordinates. */
@Stable
class LibraryDrag {
    var tag by mutableStateOf<String?>(null)
    var position by mutableStateOf(Offset.Zero)
}

// ---------------------------------------------------------------- Library

/** Test tag of the component library list. */
const val LIBRARY_TAG = "designer-library"

@Composable
fun ComponentLibrary(
    onAdd: (String) -> Unit, drag: LibraryDrag, onDragMove: () -> Unit, onDrop: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false,
    collection: WidgetCollection? = null,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        ComponentCatalog.all.filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) || it.tag.contains(query, ignoreCase = true) }
            .groupBy { it.category }
    }
    Column(modifier) {
        if (!compact) OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.ds_search_components)) },
            leadingIcon = { Icon(BiIcons.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
        )
        if (!compact) Text(
            stringResource(R.string.ds_library_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
        LazyColumn(Modifier.fillMaxSize().testTag(LIBRARY_TAG)) {
            val collected = collection?.names.orEmpty().filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            if (collected.isNotEmpty()) {
                item(key = "h-collection") {
                    Text(
                        stringResource(R.string.ds_collection),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = if (compact) Spacing.sm else Spacing.md, top = Spacing.md, bottom = Spacing.xs),
                    )
                }
                items(collected, key = { "col-$it" }) { name -> CollectedItem(name, collection!!, compact) }
            }
            filtered.forEach { (category, defs) ->
                item(key = "h-$category") {
                    Text(
                        category.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = if (compact) Spacing.sm else Spacing.md, top = Spacing.md, bottom = Spacing.xs),
                    )
                }
                items(defs, key = { it.tag }) { def -> LibraryItem(def, onAdd, drag, onDragMove, onDrop, compact) }
            }
        }
    }
}

/** Widgets from the collection shown at the top of the library. */
class WidgetCollection(val names: List<String>, val onAdd: (String) -> Unit, val onDelete: (String) -> Unit)

@Composable
private fun CollectedItem(name: String, c: WidgetCollection, compact: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { c.onAdd(name) }, onLongClick = { menu = true })
                .padding(horizontal = if (compact) Spacing.sm else Spacing.md, vertical = if (compact) Spacing.xs else Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(if (compact) 24.dp else 28.dp).background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(BiIcons.Library, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer) }
            Text(
                name, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (compact) Spacing.sm else Spacing.md).weight(1f),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.ds_collection_remove)) }, leadingIcon = { Icon(BiIcons.Delete, null) }, onClick = { menu = false; c.onDelete(name) })
        }
    }
}

@Composable
private fun LibraryItem(def: ComponentDef, onAdd: (String) -> Unit, drag: LibraryDrag, onDragMove: () -> Unit, onDrop: () -> Unit, compact: Boolean) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(def.tag) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o -> drag.tag = def.tag; drag.position = origin + o },
                    onDrag = { change, amount -> change.consume(); drag.position += amount; onDragMove() },
                    onDragEnd = { onDrop(); drag.tag = null },
                    onDragCancel = { drag.tag = null; onDragMove() },
                )
            }
            .clickable { onAdd(def.tag) }
            .padding(horizontal = if (compact) Spacing.sm else Spacing.md, vertical = if (compact) Spacing.xs else Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (compact) 24.dp else 28.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(def.displayName.take(2), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.padding(start = if (compact) Spacing.sm else Spacing.md).weight(1f)) {
            Text(def.displayName, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium, maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            if (!compact) def.requiresDependency?.let {
                Text(stringResource(R.string.ds_needs_dependency, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Floating label that follows the pointer while dragging from the library. */
@Composable
fun DragGhost(drag: LibraryDrag, rootOffset: Offset) {
    val tag = drag.tag ?: return
    val p = drag.position - rootOffset
    Box(
        Modifier
            .offset { IntOffset(p.x.toInt() + 24, p.y.toInt() - 48) }
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    ) {
        Text(ComponentCatalog.find(tag)?.displayName ?: tag, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------- Tree

private data class TreeRow(val element: XmlElement, val depth: Int)

private fun flatten(root: XmlElement): List<TreeRow> = buildList {
    fun visit(e: XmlElement, d: Int) { add(TreeRow(e, d)); e.elements.forEach { visit(it, d + 1) } }
    visit(root, 0)
}

fun XmlElement.label(): String {
    val id = this[Attr.ID]?.let(LayoutEditor::idName)
    val type = ComponentCatalog.find(name)?.displayName ?: name.substringAfterLast('.')
    return if (id != null) "$id · $type" else type
}

@Composable
fun ComponentTree(state: DesignerUiState, vm: DesignerViewModel, modifier: Modifier = Modifier) {
    val root = state.root ?: return
    val rows = remember(root) { flatten(root) }
    val errorUids = remember(state.diagnostics) { state.diagnostics.filter { it.severity == LayoutDiagnostic.Severity.Error }.map { it.uid }.toSet() }
    val warnUids = remember(state.diagnostics) { state.diagnostics.map { it.uid }.toSet() }
    LazyColumn(modifier.fillMaxSize()) {
        items(rows, key = { it.element.uid }) { row ->
            val selected = row.element.uid == state.selectedUid
            val isRoot = row.element.uid == root.uid
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { vm.select(row.element.uid) }
                    .padding(start = Spacing.sm + (row.depth * 16).dp, end = Spacing.xs)
                    .heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dot = when (row.element.uid) {
                    in errorUids -> MaterialTheme.colorScheme.error
                    in warnUids -> MaterialTheme.colorScheme.tertiary
                    else -> null
                }
                Box(Modifier.size(8.dp).background(dot ?: Color.Transparent, CircleShape))
                Text(
                    row.element.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Spacing.sm).weight(1f),
                )
                if (selected && !isRoot) {
                    BiIconButton(BiIcons.MoveUp, stringResource(R.string.ds_move_up), { vm.shift(row.element.uid, -1) })
                    BiIconButton(BiIcons.MoveDown, stringResource(R.string.ds_move_down), { vm.shift(row.element.uid, 1) })
                    BiIconButton(BiIcons.Duplicate, stringResource(R.string.ds_duplicate), { vm.apply(LayoutCommand.Duplicate(row.element.uid)) })
                    BiIconButton(BiIcons.Delete, stringResource(R.string.ds_delete), { vm.apply(LayoutCommand.Remove(row.element.uid)) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Inspector

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PropertiesInspector(state: DesignerUiState, vm: DesignerViewModel, modifier: Modifier = Modifier) {
    val el = state.selectedUid?.let { state.root?.find(it) }
    if (el == null) {
        BiEmptyState(BiIcons.Tune, stringResource(R.string.ds_no_selection_title), stringResource(R.string.ds_no_selection_body), modifier)
        return
    }
    val defs = remember(el.name) { ComponentCatalog.propertiesFor(el.name) }
    val known = remember(defs) { defs.map { it.attr }.toSet() }
    val set: (String, String?) -> Unit = { name, value -> vm.apply(LayoutCommand.SetAttribute(el.uid, name, value)) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md)) {
        item {
            Text(el.label(), style = MaterialTheme.typography.titleMedium)
            Text(el.name, style = CodeTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ComponentCatalog.find(el.name)?.requiresDependency?.let {
                Text(stringResource(R.string.ds_needs_dependency, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        PropertyGroup.entries.forEach { group ->
            val inGroup = defs.filter { it.group == group }
            if (inGroup.isNotEmpty()) {
                item(key = "g-$group") {
                    Text(group.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs))
                }
                items(inGroup, key = { "${el.uid}-${it.attr}" }) { def -> PropertyEditor(def, el[def.attr], set) }
            }
        }
        val custom = el.attributes.filterKeys { it !in known && !it.startsWith("xmlns:") }
        item(key = "custom") {
            Text(stringResource(R.string.ds_custom_attributes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs))
        }
        items(custom.keys.toList(), key = { "${el.uid}-c-$it" }) { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CommitTextField(name, custom[name].orEmpty(), { set(name, it) }, Modifier.weight(1f))
                BiIconButton(BiIcons.Close, stringResource(R.string.ds_delete), { set(name, null) })
            }
        }
        item(key = "add-${el.uid}") { AddAttributeRow(onAdd = set) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PropertyEditor(def: PropertyDef, value: String?, set: (String, String?) -> Unit) {
    val label = def.attr.substringAfter(':')
    when (def.type) {
        PropertyType.Id -> CommitTextField(label, value?.let(LayoutEditor::idName).orEmpty(), { set(def.attr, it.trim().takeIf(String::isNotEmpty)?.let { n -> "@+id/$n" }) })
        PropertyType.LayoutSize -> Column(Modifier.padding(vertical = Spacing.xs)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                listOf("match_parent", "wrap_content", "0dp").forEach { opt ->
                    FilterChip(selected = value == opt, onClick = { set(def.attr, opt) }, label = { Text(opt) })
                }
            }
            CommitTextField(stringResource(R.string.ds_custom_size), value.takeIf { it !in listOf("match_parent", "wrap_content", "0dp") }.orEmpty(), { if (it.isNotBlank()) set(def.attr, it.trim()) })
        }
        PropertyType.Enum -> ChoiceField(label, value, listOf<String?>(null) + def.options) { set(def.attr, it) }
        PropertyType.Boolean -> ChoiceField(label, value, listOf(null, "true", "false")) { set(def.attr, it) }
        PropertyType.Flags -> Column(Modifier.padding(vertical = Spacing.xs)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            val selected = value?.split('|')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                def.options.forEach { opt ->
                    val on = opt in selected
                    FilterChip(selected = on, onClick = {
                        val next = if (on) selected - opt else selected + opt
                        set(def.attr, next.joinToString("|").ifEmpty { null })
                    }, label = { Text(opt) })
                }
            }
        }
        PropertyType.Color -> Row(verticalAlignment = Alignment.CenterVertically) {
            val parsed = value?.let(Values::parseColor)
            Box(
                Modifier.padding(end = Spacing.sm).size(24.dp)
                    .background(parsed?.let { Color(it) } ?: Color.Transparent, CircleShape)
                    .semantics { contentDescription = value ?: "" },
            )
            CommitTextField(label, value.orEmpty(), { set(def.attr, it.trim().ifEmpty { null }) }, Modifier.weight(1f), monospace = true)
        }
        else -> CommitTextField(label, value.orEmpty(), { set(def.attr, it.ifEmpty { null }) }, monospace = def.type != PropertyType.Text)
    }
}

@Composable
private fun ChoiceField(label: String, value: String?, options: List<String?>, onChoose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value ?: stringResource(R.string.ds_unset), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt ?: stringResource(R.string.ds_unset)) }, onClick = { open = false; onChoose(opt) })
            }
        }
    }
}

/**
 * Text field that edits a local draft and commits on Done or focus loss, so each typed character
 * doesn't become its own undo step and invalid intermediate values never reach the document.
 */
@Composable
fun CommitTextField(label: String, value: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier, monospace: Boolean = false) {
    var draft by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val commit = { if (draft != value) onCommit(draft) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(label) },
        singleLine = true,
        textStyle = if (monospace) CodeTypography.body else MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xs).onFocusChanged { f ->
            if (focused && !f.isFocused) commit()
            focused = f.isFocused
        },
    )
}

@Composable
private fun AddAttributeRow(onAdd: (String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val valid = Regex("[a-zA-Z_][\\w.-]*(:[a-zA-Z_][\\w.-]*)?").matches(name.trim()) && !name.startsWith("xmlns")
    Column(Modifier.padding(top = Spacing.sm)) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ds_attribute_name)) }, singleLine = true, textStyle = CodeTypography.body, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.ds_attribute_value)) }, singleLine = true, textStyle = CodeTypography.body, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = valid && value.isNotEmpty(), onClick = { onAdd(name.trim(), value); name = ""; value = "" }) {
            Text(stringResource(R.string.ds_add_attribute))
        }
    }
}

// ---------------------------------------------------------------- Problems

@Composable
fun ProblemsList(state: DesignerUiState, previewIssues: List<PreviewIssue>, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    val root = state.root
    if (state.diagnostics.isEmpty() && previewIssues.isEmpty()) {
        Text(stringResource(R.string.ds_no_problems), modifier = modifier.padding(Spacing.lg), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        items(state.diagnostics) { d ->
            ProblemRow(
                error = d.severity == LayoutDiagnostic.Severity.Error,
                title = diagnosticText(d),
                subtitle = root?.find(d.uid)?.label(),
                onClick = { onSelect(d.uid) },
            )
        }
        items(previewIssues) { i ->
            ProblemRow(false, previewIssueText(i), root?.find(i.uid)?.label(), onClick = { onSelect(i.uid) })
        }
    }
}

@Composable
private fun ProblemRow(error: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(BiIcons.Warning, null, tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = Spacing.md)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    HorizontalDivider()
}

@Composable
private fun diagnosticText(d: LayoutDiagnostic): String = when (d.code) {
    "missing_width" -> stringResource(R.string.ds_problem_missing_width)
    "missing_height" -> stringResource(R.string.ds_problem_missing_height)
    "invalid_id" -> stringResource(R.string.ds_problem_invalid_id, d.detail)
    "duplicate_id" -> stringResource(R.string.ds_problem_duplicate_id, d.detail)
    "unknown_view" -> stringResource(R.string.ds_problem_unknown_view, d.detail)
    "children_not_allowed" -> stringResource(R.string.ds_problem_children_not_allowed, d.detail)
    "too_many_children" -> stringResource(R.string.ds_problem_too_many_children, d.detail)
    "missing_content_description" -> stringResource(R.string.ds_problem_missing_content_description)
    "weight_outside_linear" -> stringResource(R.string.ds_problem_weight_outside_linear)
    else -> d.code
}

@Composable
private fun previewIssueText(i: PreviewIssue): String {
    val res = when (i.code) {
        "too_many_views" -> R.string.ds_preview_too_many_views
        "not_previewable" -> R.string.ds_preview_not_previewable
        "create_failed" -> R.string.ds_preview_create_failed
        "attribute_failed" -> R.string.ds_preview_attribute_failed
        "attach_failed" -> R.string.ds_preview_attach_failed
        "children_ignored" -> R.string.ds_preview_children_ignored
        "runtime_content" -> R.string.ds_preview_runtime_content
        "menu_not_previewed" -> R.string.ds_preview_menu_not_previewed
        "attribute_not_previewed" -> R.string.ds_preview_attribute_not_previewed
        else -> R.string.ds_problem_preview
    }
    return stringResource(res, i.detail)
}

@Composable
fun PanelTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(Spacing.md))
}
