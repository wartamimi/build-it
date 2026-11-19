package dev.buildit.feature.blocks

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.ui.draganddrop.DragAndDropTransferData

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
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
import dev.buildit.engine.blocks.BlockDocument
import dev.buildit.engine.blocks.ProcParam
import dev.buildit.engine.blocks.Procedures
import dev.buildit.engine.blocks.Block
import dev.buildit.engine.blocks.BlockCategory
import dev.buildit.engine.blocks.BlockDef
import dev.buildit.engine.blocks.BlockDiagnostic
import dev.buildit.engine.blocks.BlockRegistry
import dev.buildit.engine.blocks.BlockShape
import androidx.compose.ui.semantics.onLongClick
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
    var saveBlocks by remember { mutableStateOf<String?>(null) }

    val rejected = rejectionTexts()
    val failed = stringResource(R.string.bk_failed)
    val permissionsNotWritten = stringResource(R.string.bk_permissions_not_written)
    val savedToCollection = stringResource(R.string.bk_collection_saved)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { m ->
            snackbar.showSnackbar(when (m) {
                is BlocksMessage.Rejected -> rejected(m.code)
                is BlocksMessage.Failed -> failed.format(m.detail)
                is BlocksMessage.PermissionsNotWritten -> permissionsNotWritten.format(m.permissions.joinToString(", ") { it.substringAfterLast('.') })
                is BlocksMessage.SavedToCollection -> savedToCollection.format(m.name)
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
                    val res = LocalContext.current.resources
                    val title = if (onBack != null) focus?.let { state.document?.find(it) }?.let { eventTitle(it, res) } else null
                    Toolbar(state, viewModel, canvas, onCode = { showCode = true }, onProblems = { showProblems = true }, onBack = onBack?.let { b -> { viewModel.flush(); b() } }, title = title)
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
                                    onDropNew = viewModel::drop,
                                    // Opened on one event: show only that event and loose blocks, like a page per event.
                                    showStack = if (onBack != null && focus != null) ({ st ->
                                        val head = st.blocks.firstOrNull()
                                        head == null || head.id == focus || BlockRegistry.find(head.type)?.shape != BlockShape.Hat
                                    }) else null,
                                )
                                state.selectedId?.let { id -> SelectionBar(id, state, viewModel, canvas, Modifier.align(Alignment.TopEnd), onSave = { saveBlocks = id }) }
                            }
                        }
                        val palette: @Composable (Modifier) -> Unit = { m ->
                            Palette(state, onAdd = { type -> canvas.center().let { c -> viewModel.add(type, c.x, c.y) } },
                                onAddCollected = { name -> canvas.center().let { c -> viewModel.addFromCollection(name, c.x, c.y) } },
                                onDeleteCollected = viewModel::deleteFromCollection,
                                onNewVariable = { newVariable = true }, onRenameVariable = { renameVariable = it },
                                onRemoveVariable = viewModel::removeVariable, modifier = m, bubbles = maxWidth < 840.dp)
                        }
                        if (maxWidth >= 840.dp) {
                            Row(Modifier.fillMaxSize()) {
                                palette(Modifier.width(300.dp).fillMaxHeight())
                                VerticalDivider()
                                canvasView(Modifier.weight(1f).fillMaxHeight())
                            }
                        } else {
                            // Phone: the blocks drawer slides up from the bottom, like Sketchware's: blocks on
                            // the left, categories on the right, and the canvas keeps the space above it.
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    canvasView(Modifier.fillMaxSize())
                                    ExtendedFloatingActionButton(
                                        onClick = { paletteOpen = !paletteOpen },
                                        icon = { Icon(if (paletteOpen) BiIcons.Close else BiIcons.Blocks, null) },
                                        text = { Text(stringResource(if (paletteOpen) R.string.bk_hide_palette else R.string.bk_show_palette)) },
                                        expanded = !paletteOpen,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.md),
                                    )
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = paletteOpen,
                                    enter = expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(220)),
                                    exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(180)),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                                        shadowElevation = 10.dp,
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        modifier = Modifier.fillMaxWidth().height(panelHeight),
                                    ) {
                                        Column {
                                            Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
                                                Box(Modifier.width(36.dp).height(4.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
                                            }
                                            palette(Modifier.fillMaxSize())
                                        }
                                    }
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
    saveBlocks?.let { id ->
        val res = LocalContext.current.resources
        var name by remember { mutableStateOf(state.document?.find(id)?.let { b -> eventTitle(b, res) }.orEmpty().filter { it.isLetterOrDigit() || it in " _.-" }.take(40).trim()) }
        val clash = name.trim() in state.collectedBlocks.orEmpty()
        AlertDialog(
            onDismissRequest = { saveBlocks = null },
            title = { Text(stringResource(R.string.bk_collection_save)) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.bk_collection_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(if (clash) R.string.bk_collection_replace_hint else R.string.bk_collection_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (clash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { saveBlocks = null; viewModel.saveToCollection(id, name.trim(), clash) }) {
                    Text(stringResource(if (clash) R.string.bk_collection_replace else R.string.bk_save))
                }
            },
            dismissButton = { TextButton(onClick = { saveBlocks = null }) { Text(stringResource(R.string.bk_cancel)) } },
        )
    }
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

/** One compact row: back, the event (or screen), undo/redo, problems, a menu for the rest, save. */
@Composable
private fun Toolbar(state: BlocksUiState, vm: BlocksViewModel, canvas: BlockCanvasState, onCode: () -> Unit, onProblems: () -> Unit, onBack: (() -> Unit)?, title: String?) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) BiIconButton(BiIcons.Back, stringResource(R.string.bk_back_to_events), onBack)
        Box(Modifier.weight(1f).padding(start = if (onBack == null) Spacing.xs else 0.dp)) {
            if (onBack != null) {
                Text(title ?: state.activity.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            } else {
                var menu by remember { mutableStateOf(false) }
                TextButton(onClick = { menu = true }) { Text((state.activity ?: stringResource(R.string.bk_screen)) + " ▾", maxLines = 1) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    state.activities.forEach { a -> DropdownMenuItem(text = { Text(a) }, onClick = { menu = false; vm.load(a) }) }
                }
            }
        }
        BiIconButton(BiIcons.Undo, stringResource(R.string.bk_undo), vm::undo, enabled = state.canUndo)
        BiIconButton(BiIcons.Redo, stringResource(R.string.bk_redo), vm::redo, enabled = state.canRedo)
        val errors = state.diagnostics.count { it.severity == BlockDiagnostic.Severity.Error }
        val total = state.diagnostics.size
        if (total > 0) Box {
            BiIconButton(BiIcons.Warning, stringResource(R.string.bk_problems, total), onProblems)
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(16.dp)
                    .background(if (errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(if (total > 9) "9+" else "$total", color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.labelSmall) }
        }
        var more by remember { mutableStateOf(false) }
        Box {
            BiIconButton(BiIcons.More, stringResource(R.string.bk_more_actions), { more = true })
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.bk_zoom_in)) }, onClick = { canvas.zoomBy(1.25f) })
                DropdownMenuItem(text = { Text(stringResource(R.string.bk_zoom_out)) }, onClick = { canvas.zoomBy(1f / 1.25f) })
                DropdownMenuItem(text = { Text(stringResource(R.string.bk_zoom_reset) + " · ${(canvas.zoom * 100).toInt()}%") }, onClick = { more = false; canvas.reset() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.bk_code)) }, leadingIcon = { Icon(BiIcons.Code, null) }, onClick = { more = false; onCode() })
                DropdownMenuItem(text = { Text(stringResource(R.string.bk_problems, total)) }, leadingIcon = { Icon(BiIcons.Warning, null) }, onClick = { more = false; onProblems() })
            }
        }
        // Save: highlighted while there are unsaved changes.
        BiIconButton(BiIcons.Save, stringResource(R.string.bk_save), { vm.save() }, enabled = state.dirty && !state.saving)
    }
}

@Composable
private fun StatusLine(state: BlocksUiState) {
    val (text, color) = when (val s = state.codeStatus) {
        CodeStatus.UpToDate -> return
        is CodeStatus.HasErrors -> stringResource(R.string.bk_status_errors, s.count) to MaterialTheme.colorScheme.error
        is CodeStatus.HandEdited -> stringResource(R.string.bk_status_hand_edited) to MaterialTheme.colorScheme.tertiary
        CodeStatus.Unknown -> return
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xxs))
}

@Composable
private fun SelectionBar(id: String, state: BlocksUiState, vm: BlocksViewModel, canvas: BlockCanvasState, modifier: Modifier, onSave: () -> Unit) {
    val block = state.document?.find(id) ?: return
    Surface(modifier.padding(Spacing.sm), shape = RoundedCornerShape(12.dp), tonalElevation = 4.dp, shadowElevation = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BiIconButton(BiIcons.Duplicate, stringResource(R.string.bk_duplicate), {
                val c = canvas.center(); vm.duplicate(id, c.x, c.y)
            })
            if (state.collectedBlocks != null) BiIconButton(BiIcons.Library, stringResource(R.string.bk_collection_save), onSave)
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
    onAddCollected: (String) -> Unit,
    onDeleteCollected: (String) -> Unit,
    onNewVariable: () -> Unit,
    onRenameVariable: (String) -> Unit,
    onRemoveVariable: (String) -> Unit,
    modifier: Modifier,
    /** Phone drawer: a search field and a row of round category bubbles above the blocks. */
    bubbles: Boolean = false,
) {
    // null: the collection
    var category by rememberSaveable { mutableStateOf<BlockCategory?>(BlockCategory.Events) }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = query.isNotBlank()
    val res = LocalContext.current.resources
    val painter = rememberBlockPainter()
    val doc = state.document
    // Sample blocks as they will be created, so the palette shows real shapes with real defaults
    val samples = remember(category, query, doc?.variables, doc?.components, state.views, state.sounds, state.images) {
        var n = 0
        val q = query.trim()
        BlockRegistry.all.filter { if (q.isNotEmpty()) paletteText(it, res).contains(q, ignoreCase = true) else it.category == category }.map { def ->
            def to runCatching {
                BlockRegistry.create(def.type, { "p" + n++ }, doc?.variables.orEmpty(), state.views, doc?.components.orEmpty(), state.sounds, state.images)
            }.getOrNull()
        }
    }
    val categories: @Composable () -> Unit = {
        if (state.collectedBlocks != null) {
            CategoryTile(stringResource(R.string.bk_collection), MaterialTheme.colorScheme.tertiary, category == null && !searching, bubbles) { category = null; query = "" }
        }
        BlockCategory.entries.forEach { c ->
            CategoryTile(stringResource(categoryRes(c)), categoryColor(c), c == category && !searching, bubbles) { category = c; query = "" }
        }
    }
    val content: @Composable (Modifier) -> Unit = { m -> LazyColumn(
            m.testTag(PALETTE_TAG),
            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (searching && samples.isEmpty()) item { Text(stringResource(R.string.bk_search_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.xs)) }
            if (!searching) item { Text(stringResource(R.string.bk_palette_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.xs)) }
            if (!searching && (category == BlockCategory.Variables || category == BlockCategory.Lists)) {
                item {
                    Surface(onClick = onNewVariable, shape = RoundedCornerShape(8.dp), color = categoryColor(category), contentColor = androidx.compose.ui.graphics.Color.White) {
                        Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Icon(BiIcons.Add, null, Modifier.size(18.dp)); Text(stringResource(R.string.bk_new_variable), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = Spacing.xs))
                        }
                    }
                }
                items(state.document?.variables.orEmpty(), key = { "v-" + it.name }) { v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${v.name} · ${typeLabel(v.type)}", modifier = Modifier.weight(1f).padding(start = Spacing.sm), style = MaterialTheme.typography.bodyMedium)
                        BiIconButton(BiIcons.Edit, stringResource(R.string.bk_rename), { onRenameVariable(v.name) })
                        BiIconButton(BiIcons.Delete, stringResource(R.string.bk_delete), { onRemoveVariable(v.name) })
                    }
                }
            }
            if (!searching && category == null) {
                val names = state.collectedBlocks.orEmpty()
                if (names.isEmpty()) item {
                    Text(stringResource(R.string.bk_collection_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.xs))
                }
                items(names, key = { "col-$it" }) { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = { onAddCollected(name) }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(Modifier.padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                Icon(BiIcons.Library, null, Modifier.size(18.dp))
                                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.sm))
                            }
                        }
                        BiIconButton(BiIcons.Delete, stringResource(R.string.bk_collection_remove), { onDeleteCollected(name) })
                    }
                }
            }
            items(samples, key = { it.first.type }) { (def, sample) ->
                PaletteBlock(def, paletteText(def, res), sample, painter, onClick = { onAdd(def.type) })
            }
        } }
    if (bubbles) {
        Column(modifier) {
            PaletteSearch(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.sm, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) { categories() }
            HorizontalDivider(Modifier.padding(top = 2.dp))
            content(Modifier.weight(1f).fillMaxWidth())
        }
    } else Row(modifier) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            PaletteSearch(query, { query = it }, Modifier.fillMaxWidth().padding(Spacing.sm))
            content(Modifier.weight(1f).fillMaxWidth())
        }
        // Category rail on the right: a coloured tile per category.
        Column(
            Modifier.width(100.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer)
                .verticalScroll(rememberScrollState()).padding(vertical = Spacing.xs, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) { categories() }
    }
}

/** A small rounded search field for the blocks palette. */
@Composable
private fun PaletteSearch(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    Row(
        modifier.height(38.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(19.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BiIcons.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).padding(start = 8.dp)) {
            if (query.isEmpty()) Text(stringResource(R.string.bk_search_blocks), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.text.BasicTextField(
                query, onQuery, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) Icon(BiIcons.Close, stringResource(R.string.bk_search_clear), Modifier.size(18.dp).clip(CircleShape).clickable { onQuery("") })
    }
}

@Composable
private fun CategoryTile(label: String, color: androidx.compose.ui.graphics.Color, selected: Boolean, bubble: Boolean, onClick: () -> Unit) {
    if (bubble) {
        // A round coloured bubble with the name under it; the selected one gets a ring.
        Column(
            Modifier.width(60.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(30.dp)
                    .then(if (selected) Modifier.border(2.dp, color, CircleShape).padding(4.dp) else Modifier.padding(4.dp))
                    .background(color, CircleShape),
            )
            Text(
                label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        return
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) color.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).background(color, RoundedCornerShape(3.dp)))
        Text(
            label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(start = 6.dp),
        )
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
                is FieldKind.Color -> "■"
                else -> "▾"
            }
        }
    }

@Composable
private fun PaletteBlock(def: BlockDef, text: String, sample: Block?, painter: BlockPainter, onClick: () -> Unit) {
    val dragSource = Modifier
        // Long-press and drag onto the canvas to drop the block where it should go
        .dragAndDropSource { _ ->
            DragAndDropTransferData(
                ClipData(ClipDescription("$PALETTE_CLIP_LABEL:${def.type}", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)), ClipData.Item(def.type)),
            )
        }
        // Blocks are added by a long press (drag onto the canvas to place it); for screen readers
        // the long-press action adds it after the selected block.
        .semantics(mergeDescendants = true) {
            this.text = AnnotatedString(text)
            onLongClick { onClick(); true }
        }
    if (sample != null) {
        Box(dragSource) { BlockPreview(sample, painter) }
    } else {
        Box(
            Modifier.background(categoryColor(def.category), RoundedCornerShape(6.dp)).then(dragSource).heightIn(min = 40.dp).padding(horizontal = Spacing.md, vertical = Spacing.sm),
            contentAlignment = Alignment.CenterStart,
        ) { Text(text, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun typeLabel(t: ValueType) = stringResource(typeLabelRes(t))

@Composable
private fun typeLabelText(t: ValueType) = typeLabel(t)

private fun typeLabelRes(t: ValueType) =
    when (t) {
        ValueType.Boolean -> R.string.bk_type_boolean
        ValueType.Number -> R.string.bk_type_number
        ValueType.Text -> R.string.bk_type_text
        ValueType.TextList -> R.string.bk_type_textList
        ValueType.NumberList -> R.string.bk_type_numberList
        ValueType.TextMap -> R.string.bk_type_map
    }

/** Edits a procedure's parameters: a list with remove buttons, and a name plus type to add one. */
@Composable
private fun ParamsEditor(current: String, doc: BlockDocument, viewIds: Set<String>, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var params by remember { mutableStateOf(Procedures.parse(current).orEmpty()) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ValueType.Number) }
    val problem = name.trim().takeIf { it.isNotEmpty() }?.let { Procedures.problem(it, params.map { p -> p.name }, doc, viewIds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bk_params_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                params.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${p.name} · ${typeLabel(p.type)}", Modifier.weight(1f))
                        TextButton(onClick = { params = params - p }) { Text(stringResource(R.string.bk_param_remove, p.name)) }
                    }
                }
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.bk_param_name)) },
                    isError = problem != null,
                    supportingText = problem?.let { code ->
                        {
                            Text(stringResource(when (code) {
                                "duplicate_name" -> R.string.bk_param_duplicate_name
                                "name_clash" -> R.string.bk_param_name_clash
                                else -> R.string.bk_param_invalid_name
                            }))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ValueType.entries.forEach { t ->
                        androidx.compose.material3.FilterChip(selected = t == type, onClick = { type = t }, label = { Text(typeLabel(t)) })
                    }
                }
                TextButton(
                    enabled = name.isNotBlank() && problem == null,
                    onClick = { params = params + ProcParam(name.trim(), type); name = "" },
                ) { Text(stringResource(R.string.bk_param_add)) }
            }
        },
        confirmButton = { TextButton(onClick = { onSet(Procedures.format(params)) }) { Text(stringResource(R.string.bk_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bk_cancel)) } },
    )
}

// ------------------------------------------------------------ dialogs

private val PRESET_COLORS = listOf("#FF000000", "#FFFFFFFF", "#FFE53935", "#FFFB8C00", "#FFFDD835", "#FF43A047", "#FF1E88E5", "#FF8E24AA")
private val COLOR_VALUE = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

private fun fieldValueOk(kind: FieldKind, text: String) = when (kind) {
    is FieldKind.Number -> text.toDoubleOrNull()?.isFinite() == true
    is FieldKind.Color -> COLOR_VALUE.matches(text)
    else -> true
}

@Composable
private fun FieldEditor(block: Block, field: String, state: BlocksUiState, onDismiss: () -> Unit, onSet: (String) -> Unit, onNewVariable: () -> Unit) {
    val def = BlockRegistry.find(block.type) ?: return onDismiss()
    val kind = def.fieldDef(field)?.kind ?: return onDismiss()
    val current = block.field(field)
    val doc = state.document ?: return
    if (kind == FieldKind.Params) {
        ParamsEditor(current, doc, state.views.map { it.id }.toSet(), onDismiss, onSet)
        return
    }
    val choices: List<Pair<String, String>>? = when (kind) {
        is FieldKind.Dropdown -> kind.options.map { it to it }
        is FieldKind.View -> state.views.filter { ViewFamilies.matches(kind.kind, it.tag) }.map { it.id to "${it.id} · ${it.tag.substringAfterLast('.')}" }
        is FieldKind.VariableRef -> doc.variables.filter { kind.type == null || it.type == kind.type }.map { it.name to it.name }
        is FieldKind.Procedure -> if (kind.defines) null else doc.stacks.mapNotNull { s -> s.blocks.firstOrNull()?.takeIf { it.type == "proc_define" }?.field("name") }.map { it to it }
        FieldKind.Activity -> state.activities.map { it to it }
        is FieldKind.ComponentRef -> doc.components.filter { it.type == kind.type }.map { it.name to it.name }
        FieldKind.RawResource -> state.sounds.map { it to it }
        FieldKind.DrawableResource -> state.images.map { it to it }
        FieldKind.Param -> Procedures.enclosingParams(doc, block.id).orEmpty()
            .map { it.name to "${it.name} · ${typeLabelText(it.type)}" }
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
                        FieldKind.DrawableResource -> R.string.bk_no_images
                        FieldKind.Param -> R.string.bk_no_params
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
                Column {
                    if (kind is FieldKind.Color) {
                        Row(Modifier.fillMaxWidth().padding(bottom = Spacing.md), horizontalArrangement = Arrangement.SpaceBetween) {
                            PRESET_COLORS.forEach { c ->
                                Box(
                                    Modifier.size(28.dp).background(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(c)), RoundedCornerShape(6.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                                        .clickable { text = c },
                                )
                            }
                        }
                    }
                    OutlinedTextField(text, { text = it }, singleLine = !code, minLines = if (code) 4 else 1,
                        textStyle = if (code || kind is FieldKind.Number || kind is FieldKind.Color) CodeTypography.body else MaterialTheme.typography.bodyLarge,
                        isError = !fieldValueOk(kind, text),
                        supportingText = if (kind is FieldKind.Color) ({ Text(stringResource(R.string.bk_color_hint)) }) else null,
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = fieldValueOk(kind, text), onClick = { onSet(text) }) { Text(stringResource(R.string.bk_ok)) }
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
        "collection_exists" to stringResource(R.string.bk_collection_exists),
        "invalid_name" to stringResource(R.string.bk_collection_invalid_name),
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
