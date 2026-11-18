package dev.buildit.feature.blocks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.component.BiEmptyState
import dev.buildit.core.designsystem.component.BiErrorState
import dev.buildit.core.designsystem.component.BiIconButton
import dev.buildit.core.designsystem.component.BiLoadingState
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.blocks.Block
import dev.buildit.engine.blocks.BlockCategory
import dev.buildit.engine.blocks.BlockDef
import dev.buildit.engine.blocks.BlockDiagnostic
import dev.buildit.engine.blocks.BlockRegistry
import dev.buildit.engine.blocks.FieldKind
import dev.buildit.engine.blocks.MessagePart
import dev.buildit.engine.blocks.ValueType
import dev.buildit.engine.blocks.ViewFamilies

/**
 * The block editor for one activity at a time: palette, canvas, variables, problems and a
 * read-only preview of the generated source. Saving writes the blocks and regenerates the
 * activity's source file (never over hand edits without asking).
 */
@Composable
fun BlocksScreen(
    viewModel: BlocksViewModel,
    onExit: () -> Unit,
    /** Set when the editor was opened from the event list: back returns there. */
    onBack: (() -> Unit)? = null,
    /** Block to scroll to and select when the editor opens (e.g. the event that was tapped). */
    focus: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val autoSave by viewModel.autoSaveEnabled.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    val canvas = remember { BlockCanvasState() }
    var fieldEdit by remember { mutableStateOf<Pair<Block, String>?>(null) }
    var showCode by remember { mutableStateOf(false) }
    var showProblems by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var newVariable by remember { mutableStateOf(false) }
    var renameVariable by remember { mutableStateOf<String?>(null) }

    val rejected = rejectionTexts()
    val failed = stringResource(R.string.bk_failed)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { m ->
            snackbar.showSnackbar(when (m) {
                is BlocksMessage.Rejected -> rejected(m.code)
                is BlocksMessage.Failed -> failed.format(m.detail)
            })
        }
    }

    // The layout may have changed in the designer: refresh the views when this tab is shown.
    LaunchedEffect(Unit) { viewModel.refreshViews() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) viewModel.flush() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); viewModel.flush() }
    }
    BackHandler(enabled = state.dirty) {
        if (autoSave?.autoSave == true) viewModel.saveThen(onExit) else confirmLeave = true
    }
    // Registered last, so it wins: back from the editor returns to the event list (saving continues in the background).
    BackHandler(enabled = onBack != null) { viewModel.flush(); onBack?.invoke() }
    var paletteOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focus, canvas.size, state.document != null) {
        val doc = state.document ?: return@LaunchedEffect
        if (focus == null || canvas.size.width == 0) return@LaunchedEffect
        BlockLocator.position(doc, focus)?.let { (x, y) -> canvas.pan = androidx.compose.ui.geometry.Offset(32f - x * canvas.zoom, 32f - y * canvas.zoom) }
        viewModel.select(focus)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when {
                state.loading && state.document == null -> BiLoadingState()
                state.error != null -> BiErrorState(stringResource(R.string.bk_open_failed), state.error!!, retryLabel = stringResource(R.string.bk_retry), onRetry = { viewModel.load(state.activity) })
                state.document == null -> BiEmptyState(BiIcons.Widgets, stringResource(R.string.bk_no_activities_title), stringResource(R.string.bk_no_activities_body))
                else -> {
                    Toolbar(state, viewModel, canvas, onCode = { showCode = true }, onProblems = { showProblems = true }, onBack = onBack?.let { b -> { viewModel.flush(); b() } })
                    StatusLine(state)
                    HorizontalDivider()
                    val errorIds = remember(state.diagnostics) { state.diagnostics.filter { it.severity == BlockDiagnostic.Severity.Error }.mapNotNull { it.blockId }.toSet() }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val panelHeight = maxHeight * 0.45f
                        val canvasView: @Composable (Modifier) -> Unit = { m ->
                            Box(m) {
                                BlockCanvas(
                                    document = state.document!!,
                                    selectedId = state.selectedId,
                                    errorIds = errorIds,
                                    canvas = canvas,
                                    onSelect = viewModel::select,
                                    onFieldTap = { b, f -> viewModel.select(b.id); fieldEdit = b to f },
                                    onDragCommit = viewModel::commitDrag,
                                    description = stringResource(R.string.bk_canvas),
                                )
                                state.selectedId?.let { id -> SelectionBar(id, state, viewModel, canvas, Modifier.align(Alignment.TopEnd)) }
                            }
                        }
                        val palette: @Composable (Modifier) -> Unit = { m ->
                            Palette(state, onAdd = { type -> canvas.center().let { c -> viewModel.add(type, c.x, c.y) } },
                                onNewVariable = { newVariable = true }, onRenameVariable = { renameVariable = it },
                                onRemoveVariable = viewModel::removeVariable, modifier = m)
                        }
                        if (maxWidth >= 840.dp) {
                            Row(Modifier.fillMaxSize()) {
                                palette(Modifier.width(300.dp).fillMaxHeight())
                                VerticalDivider()
                                canvasView(Modifier.weight(1f).fillMaxHeight())
                            }
                        } else {
                            // Phone: the canvas gets the whole screen; blocks slide up in a drawer.
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    canvasView(Modifier.fillMaxSize())
                                    ExtendedFloatingActionButton(
                                        onClick = { paletteOpen = !paletteOpen },
                                        icon = { Icon(if (paletteOpen) BiIcons.Close else BiIcons.Blocks, null) },
                                        text = { Text(stringResource(if (paletteOpen) R.string.bk_hide_palette else R.string.bk_show_palette)) },
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.md),
                                    )
                                }
                                if (paletteOpen) {
                                    HorizontalDivider()
                                    Surface(tonalElevation = 2.dp) { palette(Modifier.fillMaxWidth().height(panelHeight)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    fieldEdit?.let { (block, field) ->
        val current = state.document?.find(block.id)
        if (current == null) fieldEdit = null else FieldEditor(current, field, state, onDismiss = { fieldEdit = null }, onSet = { v ->
            fieldEdit = null
            viewModel.setField(current.id, field, v)
        }, onNewVariable = { fieldEdit = null; newVariable = true })
    }
    if (showCode) CodeDialog(state, onDismiss = { showCode = false })
    if (showProblems) ProblemsDialog(state, onSelect = { id ->
        showProblems = false
        viewModel.select(id)
        BlockLocator.position(state.document!!, id)?.let { (x, y) -> canvas.centerOn(x, y) }
    }, onDismiss = { showProblems = false })
    if (newVariable) VariableDialog(null, onDismiss = { newVariable = false }, onConfirm = { n, t -> newVariable = false; viewModel.addVariable(n, t) })
    renameVariable?.let { old ->
        VariableDialog(old, onDismiss = { renameVariable = null }, onConfirm = { n, _ -> renameVariable = null; viewModel.renameVariable(old, n) })
    }
    (state.codeStatus as? CodeStatus.HandEdited)?.takeIf { !state.keepHandEdits }?.let { st ->
        AlertDialog(
            onDismissRequest = viewModel::keepHandEditedSource,
            title = { Text(stringResource(R.string.bk_hand_edited_title)) },
            text = { Text(stringResource(R.string.bk_hand_edited_body, st.path)) },
            confirmButton = { TextButton(onClick = { viewModel.save(force = true) }) { Text(stringResource(R.string.bk_hand_edited_replace), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = viewModel::keepHandEditedSource) { Text(stringResource(R.string.bk_hand_edited_keep)) } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.bk_leave_title)) },
            text = { Text(stringResource(R.string.bk_leave_body)) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; viewModel.saveThen(onExit) }) { Text(stringResource(R.string.bk_leave_save)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; viewModel.discard(); onExit() }) { Text(stringResource(R.string.bk_leave_discard), color = MaterialTheme.colorScheme.error) } },
        )
    }
}

@Composable
private fun Toolbar(state: BlocksUiState, vm: BlocksViewModel, canvas: BlockCanvasState, onCode: () -> Unit, onProblems: () -> Unit, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) BiIconButton(BiIcons.Back, stringResource(R.string.bk_back_to_events), onBack)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            var menu by remember { mutableStateOf(false) }
            if (onBack == null) Box {
                TextButton(onClick = { menu = true }) { Text((state.activity ?: stringResource(R.string.bk_screen)) + " ▾") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    state.activities.forEach { a -> DropdownMenuItem(text = { Text(a) }, onClick = { menu = false; vm.load(a) }) }
                }
            }
            BiIconButton(BiIcons.Undo, stringResource(R.string.bk_undo), vm::undo, enabled = state.canUndo)
            BiIconButton(BiIcons.Redo, stringResource(R.string.bk_redo), vm::redo, enabled = state.canRedo)
            IconButton(onClick = { canvas.zoomBy(1f / 1.25f) }) { Text("−") }
            TextButton(onClick = canvas::reset) { Text("${(canvas.zoom * 100).toInt()}%") }
            IconButton(onClick = { canvas.zoomBy(1.25f) }) { Text("+") }
            val errors = state.diagnostics.count { it.severity == BlockDiagnostic.Severity.Error }
            val total = state.diagnostics.size
            TextButton(onClick = onProblems) {
                Icon(BiIcons.Warning, null, tint = if (errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" " + stringResource(R.string.bk_problems, total))
            }
            TextButton(onClick = onCode) { Text(stringResource(R.string.bk_code)) }
        }
        val status = when {
            state.saving -> stringResource(R.string.bk_saving)
            state.dirty -> stringResource(R.string.bk_unsaved)
            else -> stringResource(R.string.bk_saved)
        }
        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            modifier = Modifier.padding(start = Spacing.sm).widthIn(max = 96.dp))
        BiIconButton(BiIcons.Save, stringResource(R.string.bk_save), { vm.save() }, enabled = state.dirty && !state.saving)
    }
}

@Composable
private fun StatusLine(state: BlocksUiState) {
    val (text, color) = when (val s = state.codeStatus) {
        CodeStatus.UpToDate -> stringResource(R.string.bk_status_up_to_date) to MaterialTheme.colorScheme.onSurfaceVariant
        is CodeStatus.HasErrors -> stringResource(R.string.bk_status_errors, s.count) to MaterialTheme.colorScheme.error
        is CodeStatus.HandEdited -> stringResource(R.string.bk_status_hand_edited) to MaterialTheme.colorScheme.tertiary
        CodeStatus.Unknown -> return
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xxs))
}

@Composable
private fun SelectionBar(id: String, state: BlocksUiState, vm: BlocksViewModel, canvas: BlockCanvasState, modifier: Modifier) {
    val block = state.document?.find(id) ?: return
    Surface(modifier.padding(Spacing.sm), shape = RoundedCornerShape(12.dp), tonalElevation = 4.dp, shadowElevation = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BiIconButton(BiIcons.Duplicate, stringResource(R.string.bk_duplicate), {
                val c = canvas.center(); vm.duplicate(id, c.x, c.y)
            })
            TextButton(onClick = { vm.toggleDisabled(id) }) { Text(stringResource(if (block.disabled) R.string.bk_enable else R.string.bk_disable)) }
            BiIconButton(BiIcons.Delete, stringResource(R.string.bk_delete), { vm.delete(id) })
            BiIconButton(BiIcons.Close, stringResource(R.string.bk_close), { vm.select(null) })
        }
    }
}

// ------------------------------------------------------------ palette

/** Test tag of the palette list, so tests can scroll to blocks that are not on screen yet. */
const val PALETTE_TAG = "blocks-palette"

@Composable
private fun Palette(
    state: BlocksUiState,
    onAdd: (String) -> Unit,
    onNewVariable: () -> Unit,
    onRenameVariable: (String) -> Unit,
    onRemoveVariable: (String) -> Unit,
    modifier: Modifier,
) {
    var category by rememberSaveable { mutableStateOf(BlockCategory.Events) }
    val res = LocalContext.current.resources
    Row(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(horizontal = Spacing.sm).testTag(PALETTE_TAG)) {
            item { Text(stringResource(R.string.bk_palette_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.xs)) }
            if (category == BlockCategory.Variables || category == BlockCategory.Lists) {
                item { TextButton(onClick = onNewVariable) { Icon(BiIcons.Add, null); Text(stringResource(R.string.bk_new_variable)) } }
                items(state.document?.variables.orEmpty(), key = { "v-" + it.name }) { v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${v.name} · ${typeLabel(v.type)}", modifier = Modifier.weight(1f).padding(start = Spacing.sm), style = MaterialTheme.typography.bodyMedium)
                        BiIconButton(BiIcons.Edit, stringResource(R.string.bk_rename), { onRenameVariable(v.name) })
                        BiIconButton(BiIcons.Delete, stringResource(R.string.bk_delete), { onRemoveVariable(v.name) })
                    }
                }
            }
            items(BlockRegistry.all.filter { it.category == category }, key = { it.type }) { def ->
                PaletteBlock(def, paletteText(def, res), onClick = { onAdd(def.type) })
            }
        }
        VerticalDivider()
        // Category rail on the right, within reach of the thumb.
        Column(Modifier.width(104.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Spacing.xs)) {
            BlockCategory.entries.forEach { c ->
                val selected = c == category
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { category = c }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(4.dp).height(18.dp).background(categoryColor(c), RoundedCornerShape(2.dp)))
                    Text(
                        stringResource(categoryRes(c)), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
        }
    }
}

private fun paletteText(def: BlockDef, res: android.content.res.Resources): String =
    localizedParts(def, res).joinToString(" ") { p ->
        when (p) {
            is MessagePart.Label -> p.text
            is MessagePart.Input -> "▢"
            is MessagePart.Field -> when (val k = def.fieldDef(p.name)?.kind) {
                is FieldKind.Dropdown -> k.default
                is FieldKind.Number -> k.default
                is FieldKind.Text -> "…"
                else -> "▾"
            }
        }
    }

@Composable
private fun PaletteBlock(def: BlockDef, text: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(vertical = Spacing.xs)
            .background(categoryColor(def.category), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun typeLabel(t: ValueType) = stringResource(
    when (t) {
        ValueType.Boolean -> R.string.bk_type_boolean
        ValueType.Number -> R.string.bk_type_number
        ValueType.Text -> R.string.bk_type_text
        ValueType.TextList -> R.string.bk_type_textList
    },
)

// ------------------------------------------------------------ dialogs

@Composable
private fun FieldEditor(block: Block, field: String, state: BlocksUiState, onDismiss: () -> Unit, onSet: (String) -> Unit, onNewVariable: () -> Unit) {
    val def = BlockRegistry.find(block.type) ?: return onDismiss()
    val kind = def.fieldDef(field)?.kind ?: return onDismiss()
    val current = block.field(field)
    val doc = state.document ?: return
    val choices: List<Pair<String, String>>? = when (kind) {
        is FieldKind.Dropdown -> kind.options.map { it to it }
        is FieldKind.View -> state.views.filter { ViewFamilies.matches(kind.kind, it.tag) }.map { it.id to "${it.id} · ${it.tag.substringAfterLast('.')}" }
        is FieldKind.VariableRef -> doc.variables.filter { kind.type == null || it.type == kind.type }.map { it.name to it.name }
        is FieldKind.Procedure -> if (kind.defines) null else doc.stacks.mapNotNull { s -> s.blocks.firstOrNull()?.takeIf { it.type == "proc_define" }?.field("name") }.map { it to it }
        FieldKind.Activity -> state.activities.map { it to it }
        is FieldKind.ComponentRef -> doc.components.filter { it.type == kind.type }.map { it.name to it.name }
        FieldKind.RawResource -> state.sounds.map { it to it }
        else -> null
    }
    if (choices != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.bk_choose)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (choices.isEmpty()) Text(stringResource(when (kind) {
                        is FieldKind.View -> R.string.bk_no_views
                        is FieldKind.ComponentRef -> R.string.bk_no_components_of_type
                        FieldKind.RawResource -> R.string.bk_no_sounds
                        else -> R.string.bk_none_available
                    }))
                    choices.forEach { (value, label) ->
                        Text(label, fontWeight = if (value == current) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { onSet(value) }.padding(vertical = Spacing.md))
                    }
                    if (kind is FieldKind.VariableRef) TextButton(onClick = onNewVariable) { Text(stringResource(R.string.bk_new_variable)) }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bk_cancel)) } },
        )
    } else {
        var text by remember { mutableStateOf(current) }
        val code = kind is FieldKind.Code
        AlertDialog(
            onDismissRequest = onDismiss,
            text = {
                OutlinedTextField(text, { text = it }, singleLine = !code, minLines = if (code) 4 else 1,
                    textStyle = if (code || kind is FieldKind.Number) CodeTypography.body else MaterialTheme.typography.bodyLarge,
                    isError = kind is FieldKind.Number && text.toDoubleOrNull() == null,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = kind !is FieldKind.Number || text.toDoubleOrNull() != null, onClick = { onSet(text) }) { Text(stringResource(R.string.bk_ok)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bk_cancel)) } },
        )
    }
}

@Composable
private fun VariableDialog(existing: String?, onDismiss: () -> Unit, onConfirm: (String, ValueType) -> Unit) {
    var name by remember { mutableStateOf(existing.orEmpty()) }
    var type by remember { mutableStateOf(ValueType.Number) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.bk_new_variable else R.string.bk_rename)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it.trim() }, label = { Text(stringResource(R.string.bk_variable_name)) }, singleLine = true, textStyle = CodeTypography.body)
                if (existing == null) {
                    Text(stringResource(R.string.bk_variable_type), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.md))
                    ValueType.entries.forEach { t ->
                        FilterChip(selected = t == type, onClick = { type = t }, label = { Text(typeLabel(t)) }, modifier = Modifier.padding(end = Spacing.xs))
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotEmpty(), onClick = { onConfirm(name, type) }) { Text(stringResource(R.string.bk_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bk_cancel)) } },
    )
}

@Composable
private fun CodeDialog(state: BlocksUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bk_code_title, state.sourcePath?.substringAfterLast('/') ?: "")) },
        text = {
            val code = state.code
            if (code == null) Text(stringResource(R.string.bk_code_errors))
            else SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    Text(code, style = CodeTypography.small)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bk_close)) } },
    )
}

@Composable
private fun ProblemsDialog(state: BlocksUiState, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bk_problems, state.diagnostics.size)) },
        text = {
            if (state.diagnostics.isEmpty()) Text(stringResource(R.string.bk_no_problems))
            else LazyColumn {
                items(state.diagnostics) { d ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = d.blockId != null) { d.blockId?.let(onSelect) }.padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Icon(BiIcons.Warning, null, tint = if (d.severity == BlockDiagnostic.Severity.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                        Text(diagnosticText(d), modifier = Modifier.padding(start = Spacing.sm))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bk_close)) } },
    )
}

@Composable
private fun diagnosticText(d: BlockDiagnostic): String =
    diagnosticRes(d.code)?.let { stringResource(it, d.detail.substringBefore(':')) } ?: d.code

@Composable
private fun rejectionTexts(): (String) -> String {
    val m = mapOf(
        "incompatible" to stringResource(R.string.bk_rejected_incompatible),
        "invalid_name" to stringResource(R.string.bk_rejected_invalid_name),
        "duplicate_name" to stringResource(R.string.bk_rejected_duplicate_name),
        "in_use" to stringResource(R.string.bk_rejected_in_use),
    )
    val generic = stringResource(R.string.bk_rejected_generic)
    return { code -> m[code] ?: generic.format(code) }
}

/** Finds the canvas position of a block (its stack's origin) to scroll problems into view. */
internal object BlockLocator {
    fun position(doc: dev.buildit.engine.blocks.BlockDocument, id: String): Pair<Float, Float>? =
        doc.stacks.firstOrNull { s -> s.blocks.any { b -> b.walk().any { it.id == id } } }?.let { it.x to it.y }
}
