package dev.buildit.feature.designer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.buildit.engine.layout.Attr
import dev.buildit.engine.layout.LayoutEditor
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.component.BiButton
import dev.buildit.core.designsystem.component.BiConfirmDialog
import dev.buildit.core.designsystem.component.BiEmptyState
import dev.buildit.core.designsystem.component.BiErrorState
import dev.buildit.core.designsystem.component.BiIconButton
import dev.buildit.core.designsystem.component.BiLoadingState
import dev.buildit.core.designsystem.component.BiTextField
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.layout.PropertyDef
import dev.buildit.engine.layout.ComponentCatalog
import dev.buildit.engine.layout.DropResolver
import dev.buildit.engine.layout.LayoutCommand
import dev.buildit.engine.project.ActivityConfig
import dev.buildit.engine.project.KeyboardMode
import dev.buildit.engine.project.ScreenOrientation
import dev.buildit.engine.project.WindowStyle

private enum class Panel(val label: Int) { Library(R.string.ds_panel_library), Tree(R.string.ds_panel_tree), Properties(R.string.ds_panel_properties), Problems(R.string.ds_panel_problems) }

/**
 * The visual layout designer: a live preview of real Android views with a component library,
 * component tree, properties inspector and problems list. Edits go through [DesignerViewModel],
 * which writes the layout XML (the source of truth) back to the project.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignerScreen(
    viewModel: DesignerViewModel,
    onExit: () -> Unit,
    /** Opens the events of a view (its id name) or, with null, the screen's own events. */
    onOpenEvents: ((String?) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val autoSave by viewModel.autoSaveEnabled.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    val handle = remember { CanvasHandle() }
    val drag = remember { LibraryDrag() }
    var previewIssues by remember { mutableStateOf(emptyList<PreviewIssue>()) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var confirmLeave by remember { mutableStateOf(false) }
    var newLayout by remember { mutableStateOf(false) }
    var newActivity by remember { mutableStateOf(false) }
    var screenOptions by remember { mutableStateOf(false) }
    var saveWidget by remember { mutableStateOf<Long?>(null) }
    val collection = state.collectedWidgets?.let { WidgetCollection(it, viewModel::addFromCollection, viewModel::deleteFromCollection) }
    var sheet by remember { mutableStateOf<Panel?>(null) }

    val messages = rememberMessages()
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { m -> snackbar.showSnackbar(messages(m)) }
    }

    // Save when the app goes to the background or the tab is left (autosave setting is respected in flush()).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) viewModel.flush() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); viewModel.flush() }
    }

    // Leaving with unsaved edits: save first (autosave on) or ask (autosave off).
    BackHandler(enabled = state.dirty && !state.externalChange) {
        if (autoSave?.autoSave == true) viewModel.saveThen(onExit) else confirmLeave = true
    }

    val onDragMove: () -> Unit = {
        val host = handle.host
        val root = state.root
        if (host != null && root != null) {
            val p = if (drag.tag != null) handle.toPreview(drag.position.x, drag.position.y) else null
            host.highlightUid = p?.let { (x, y) -> DropResolver.resolve(root, host.boxes(), x, y)?.parentUid }
        }
    }
    val onDrop: () -> Unit = {
        val host = handle.host
        val root = state.root
        val tag = drag.tag
        if (host != null && root != null && tag != null) {
            handle.toPreview(drag.position.x, drag.position.y)?.let { (x, y) ->
                DropResolver.resolve(root, host.boxes(), x, y)?.let { t -> viewModel.apply(LayoutCommand.Insert(t.parentUid, t.index, tag, drag.attrs)) }
            }
            host.highlightUid = null
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOffset = it.positionInWindow() }) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(state, viewModel, onNewLayout = { newLayout = true }, onNewActivity = { newActivity = true }, onScreenOptions = { screenOptions = true }, onScreenEvents = onOpenEvents?.let { f -> { viewModel.flush(); f(null) } })
            HorizontalDivider()
            when {
                state.loading && state.root == null -> BiLoadingState()
                state.parseError != null -> BiErrorState(
                    stringResource(R.string.ds_parse_error_title),
                    stringResource(R.string.ds_parse_error_body, state.parseError!!),
                    retryLabel = stringResource(R.string.ds_reload),
                    onRetry = viewModel::reloadFromDisk,
                )
                state.root == null -> BiEmptyState(
                    BiIcons.Widgets,
                    stringResource(R.string.ds_empty_title),
                    stringResource(R.string.ds_empty_body),
                    action = { BiButton(stringResource(R.string.ds_new_layout), onClick = { newLayout = true }) },
                )
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val canvas: @Composable (Modifier) -> Unit = { m ->
                        DesignerCanvas(state, handle, onSelect = viewModel::select, onCommand = viewModel::apply, onIssues = { previewIssues = it }, modifier = m)
                    }
                    val library: @Composable (Modifier) -> Unit = { m -> ComponentLibrary({ t, a -> viewModel.addComponent(t, a) }, drag, onDragMove, onDrop, m, collection = collection) }
                    val panelHeight = maxHeight * 0.45f
                    if (maxWidth >= 840.dp) {
                        Row(Modifier.fillMaxSize()) {
                            Column(Modifier.width(260.dp).fillMaxHeight()) {
                                library(Modifier.weight(1f))
                                HorizontalDivider()
                                PanelTitle(stringResource(R.string.ds_panel_tree))
                                ComponentTree(state, viewModel, Modifier.weight(1f))
                            }
                            VerticalDivider()
                            Box(Modifier.weight(1f).fillMaxHeight()) { canvas(Modifier) }
                            VerticalDivider()
                            Column(Modifier.width(320.dp).fillMaxHeight()) {
                                PropertiesInspector(state, viewModel, Modifier.weight(2f))
                                HorizontalDivider()
                                PanelTitle(stringResource(R.string.ds_panel_problems) + " (${state.diagnostics.size + previewIssues.size})")
                                ProblemsList(state, previewIssues, viewModel::select, Modifier.weight(1f))
                            }
                        }
                    } else {
                        // Phone layout (preview first): the preview fills the screen. "+" opens a compact
                        // widget column beside the layout (and turns into "×"); the selected view gets a
                        // bubble of actions.
                        var dockOpen by rememberSaveable { mutableStateOf(false) }
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = dockOpen,
                                    enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(),
                                    exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut(),
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 2.dp) {
                                        Column(Modifier.width(118.dp).fillMaxHeight()) {
                                            DockButton(open = true, onToggle = { dockOpen = false }, Modifier.padding(start = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm))
                                            ComponentLibrary(
                                                { t, a -> viewModel.addComponent(t, a) }, drag, onDragMove, onDrop,
                                                Modifier.weight(1f).fillMaxWidth(), compact = true, collection = collection,
                                            )
                                        }
                                    }
                                }
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    DesignerCanvas(
                                        state, handle, onSelect = viewModel::select, onCommand = viewModel::apply, onIssues = { previewIssues = it },
                                        startInset = if (dockOpen) 0.dp else 48.dp,
                                        bubble = {
                                            state.selectedUid?.let { uid -> state.root?.find(uid) }?.let { el ->
                                                SelectionActions(
                                                    onProperties = { sheet = Panel.Properties },
                                                    onEvents = onOpenEvents?.let { f ->
                                                        el[Attr.ID]?.let(LayoutEditor::idName)?.let { id -> { viewModel.flush(); f(id) } }
                                                    },
                                                    onCollect = if (state.collectedWidgets != null) ({ saveWidget = el.uid }) else null,
                                                    onDuplicate = { viewModel.apply(LayoutCommand.Duplicate(el.uid)) },
                                                    onDelete = { viewModel.apply(LayoutCommand.Remove(el.uid)) },
                                                )
                                            }
                                        },
                                    )
                                    if (!dockOpen) DockButton(open = false, onToggle = { dockOpen = true }, Modifier.align(Alignment.TopStart).padding(Spacing.sm))
                                }
                            }
                            PropertyBar(state, state.diagnostics.size + previewIssues.size, viewModel, onPanel = { sheet = it })
                        }
                    }
                }
            }
        }
        DragGhost(drag, rootOffset)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (state.externalChange) {
        BiConfirmDialog(
            title = stringResource(R.string.ds_conflict_title),
            message = stringResource(R.string.ds_conflict_body),
            confirmLabel = stringResource(R.string.ds_conflict_overwrite),
            dismissLabel = stringResource(R.string.ds_conflict_reload),
            onConfirm = { viewModel.save(force = true) },
            onDismiss = viewModel::reloadFromDisk,
            destructive = true,
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.ds_leave_title)) },
            text = { Text(stringResource(R.string.ds_leave_body)) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; viewModel.saveThen(onExit) }) { Text(stringResource(R.string.ds_leave_save)) } },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false; viewModel.discard(); onExit() }) {
                    Text(stringResource(R.string.ds_leave_discard), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
    sheet?.let { p ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text(stringResource(p.label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.lg))
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.75f)) {
                when (p) {
                    Panel.Library -> ComponentLibrary({ t, a -> viewModel.addComponent(t, a); sheet = null }, drag, onDragMove, onDrop, Modifier.fillMaxSize(), collection = collection)
                    Panel.Tree -> ComponentTree(state, viewModel)
                    Panel.Properties -> PropertiesInspector(state, viewModel)
                    Panel.Problems -> ProblemsList(state, previewIssues, { viewModel.select(it); sheet = Panel.Properties })
                }
            }
        }
    }
    if (newLayout) NewLayoutDialog(onDismiss = { newLayout = false }, onCreate = { n, r -> newLayout = false; viewModel.createLayout(n, r) })
    if (newActivity) NewActivityDialog(onDismiss = { newActivity = false }, onCreate = { a, l -> newActivity = false; viewModel.addActivity(a, l) })
    val screen = state.screen
    saveWidget?.let { uid ->
        SaveToCollectionDialog(
            suggested = state.root?.find(uid)?.label().orEmpty().substringBefore(" ·"),
            existing = state.collectedWidgets.orEmpty(),
            onDismiss = { saveWidget = null },
            onSave = { name, replace -> saveWidget = null; viewModel.saveToCollection(uid, name, replace) },
        )
    }
    if (screenOptions && screen != null) ScreenOptionsDialog(screen, onDismiss = { screenOptions = false }, onSave = { screenOptions = false; viewModel.setScreenOptions(it) })
}

/**
 * Bottom bar of the phone layout, in the Sketchware arrangement: the selected view (tap its name to
 * pick another view), quick actions, then property tabs and one card per property.
 */
@Composable
private fun PropertyBar(state: DesignerUiState, problems: Int, vm: DesignerViewModel, onPanel: (Panel) -> Unit) {
    val el = state.selectedUid?.let { state.root?.find(it) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        // Fixed height, so the preview above keeps its size whether or not a view is selected.
        Column(Modifier.height(164.dp).padding(start = Spacing.sm, end = Spacing.xs, top = 2.dp, bottom = Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                var picker by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { picker = true }.padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val glyph = el?.let { e -> Palette.glyphFor(e.name) { e[it] } } ?: Glyph.View
                        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                            WidgetGlyph(glyph, MaterialTheme.colorScheme.primary, size = 18.dp)
                        }
                        Column(Modifier.padding(start = Spacing.sm).weight(1f, fill = false)) {
                            if (el == null) {
                                Text(stringResource(R.string.ds_bar_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            } else {
                                Text(el[Attr.ID]?.let(LayoutEditor::idName) ?: el.name.substringAfterLast('.'), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(el.name.substringAfterLast('.'), style = CodeTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        Icon(BiIcons.MoveDown, stringResource(R.string.ds_pick_view), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                        state.root?.let(::flattenTree).orEmpty().forEach { (e, depth) ->
                            DropdownMenuItem(
                                text = { Text(e.label(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (e.uid == el?.uid) FontWeight.Bold else null) },
                                leadingIcon = { Row { Spacer(Modifier.width((depth * 12).dp)); WidgetGlyph(Palette.glyphFor(e.name) { e[it] }, MaterialTheme.colorScheme.primary, size = 18.dp) } },
                                onClick = { picker = false; vm.select(e.uid) },
                            )
                        }
                    }
                }
                BadgedIcon(BiIcons.Warning, stringResource(R.string.ds_panel_problems), problems, { onPanel(Panel.Problems) })
                BiIconButton(BiIcons.Layers, stringResource(R.string.ds_panel_tree), { onPanel(Panel.Tree) })
            }
            // One card per property, like Sketchware: tap a card to edit it in a popup made for that kind of value
            if (el != null) {
                val all = remember(el.name) { ComponentCatalog.propertiesFor(el.name) }
                var editing by remember(el.uid) { mutableStateOf<PropertyDef?>(null) }
                var tab by rememberSaveable { mutableStateOf(PropertyTab.Basic) }
                val defs = propertiesForTab(all, tab, state.recentProperties)
                PropertyTabs(tab, onTab = { tab = it }, onAllProperties = { onPanel(Panel.Properties) })
                LazyRow(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(end = Spacing.sm),
                ) {
                    if (defs.isEmpty()) item { Text(stringResource(R.string.ds_recent_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.sm)) }
                    items(defs, key = { it.attr }) { d -> PropertyCard(d, el[d.attr]) { editing = d } }
                }
                editing?.let { d ->
                    PropertyPopup(d, el[d.attr], state.resDir, onSet = { vm.setProperty(el.uid, d.attr, it) }, onDismiss = { editing = null })
                }
            }
        }
    }
}

/** The round "+" that opens the widget column; it shows "×" while the column is open. */
@Composable
private fun DockButton(open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 45f else 0f, label = "dock")
    Box(
        modifier.size(40.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        // One "+" that turns into "×"
        Icon(
            BiIcons.Add, stringResource(if (open) R.string.ds_dock_close else R.string.ds_dock_open),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.graphicsLayer { rotationZ = turn },
        )
    }
}

/** Actions for the selected view, shown in a dark bubble right next to it on the preview. */
@Composable
private fun SelectionActions(
    onProperties: () -> Unit,
    onEvents: (() -> Unit)?,
    onCollect: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            BubbleAction(BiIcons.Tune, stringResource(R.string.ds_panel_properties), onProperties)
            onEvents?.let { BubbleAction(BiIcons.Event, stringResource(R.string.ds_view_events), it, accent = true) }
            onCollect?.let { BubbleAction(BiIcons.Library, stringResource(R.string.ds_collection), it) }
            BubbleAction(BiIcons.Duplicate, stringResource(R.string.ds_duplicate), onDuplicate)
            BubbleAction(BiIcons.Delete, stringResource(R.string.ds_delete), onDelete)
        }
    }
}

@Composable
private fun BubbleAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, accent: Boolean = false) {
    val tint = if (accent) MaterialTheme.colorScheme.inversePrimary else androidx.compose.material3.LocalContentColor.current
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
private fun BadgedIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int, onClick: () -> Unit) {
    Box {
        BiIconButton(icon, if (count > 0) "$label ($count)" else label, onClick)
        if (count > 0) Box(
            Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(16.dp).background(MaterialTheme.colorScheme.error, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(if (count > 9) "9+" else "$count", color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp) }
    }
}

/** One compact row: the layout picker, screen events, undo/redo, a menu for the preview options, save. */
@Composable
private fun Toolbar(state: DesignerUiState, vm: DesignerViewModel, onNewLayout: () -> Unit, onNewActivity: () -> Unit, onScreenOptions: () -> Unit, onScreenEvents: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        var layoutMenu by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { layoutMenu = true }) { Text((state.current ?: stringResource(R.string.ds_layout)) + ".xml ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            DropdownMenu(expanded = layoutMenu, onDismissRequest = { layoutMenu = false }) {
                state.layouts.forEach { l -> DropdownMenuItem(text = { Text("$l.xml") }, onClick = { layoutMenu = false; vm.open(l) }) }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.ds_new_layout)) }, leadingIcon = { Icon(BiIcons.Add, null) }, onClick = { layoutMenu = false; onNewLayout() })
                DropdownMenuItem(text = { Text(stringResource(R.string.ds_new_activity)) }, leadingIcon = { Icon(BiIcons.Add, null) }, onClick = { layoutMenu = false; onNewActivity() })
                state.screen?.let { s ->
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ds_screen_options, s.name)) },
                        leadingIcon = { Icon(BiIcons.Tune, null) },
                        onClick = { layoutMenu = false; onScreenOptions() },
                    )
                }
            }
        }
        if (onScreenEvents != null) BiIconButton(BiIcons.Event, stringResource(R.string.ds_screen_events), onScreenEvents)
        BiIconButton(BiIcons.Undo, stringResource(R.string.ds_undo), vm::undo, enabled = state.canUndo)
        BiIconButton(BiIcons.Redo, stringResource(R.string.ds_redo), vm::redo, enabled = state.canRedo)
        var more by remember { mutableStateOf(false) }
        Box {
            BiIconButton(BiIcons.Device, stringResource(R.string.ds_device), { more = true })
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DeviceProfile.entries.forEach { d ->
                    DropdownMenuItem(
                        text = { Text("${d.name} · ${d.widthDp}×${d.heightDp}dp") },
                        trailingIcon = { if (d == state.preview.device) Icon(BiIcons.Check, null) },
                        onClick = { more = false; vm.setPreview { it.copy(device = d) } },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.ds_rotate)) }, leadingIcon = { Icon(BiIcons.Rotate, null) }, onClick = { vm.setPreview { it.copy(landscape = !it.landscape) } })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ds_night)) }, leadingIcon = { Icon(BiIcons.Night, null) },
                    trailingIcon = { if (state.preview.night) Icon(BiIcons.Check, null) },
                    onClick = { vm.setPreview { it.copy(night = !it.night) } },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ds_zoom, (state.preview.zoom * 100).toInt())) },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { vm.setPreview { it.copy(zoom = (it.zoom - 0.25f).coerceAtLeast(0.5f)) } }) { Text("−") }
                            IconButton(onClick = { vm.setPreview { it.copy(zoom = (it.zoom + 0.25f).coerceAtMost(3f)) } }) { Text("+") }
                        }
                    },
                    onClick = { vm.setPreview { it.copy(zoom = 1f) } },
                )
            }
        }
        // Save: enabled while there are unsaved changes (autosave also saves on leaving).
        BiIconButton(BiIcons.Save, stringResource(R.string.ds_save), { vm.save() }, enabled = state.dirty && !state.saving)
    }
}

@Composable
private fun NewLayoutDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("LinearLayout") }
    val roots = listOf("LinearLayout", "FrameLayout", "RelativeLayout", "androidx.constraintlayout.widget.ConstraintLayout", "ScrollView")
    val valid = Regex("[a-z][a-z0-9_]*").matches(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ds_new_layout)) },
        text = {
            Column {
                BiTextField(name, { name = it.lowercase() }, stringResource(R.string.ds_layout_name),
                    error = if (name.isNotEmpty() && !valid) stringResource(R.string.ds_rejected_invalid_resource) else null, monospace = true)
                Text(stringResource(R.string.ds_root_view), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.sm))
                roots.forEach { r ->
                    FilterChip(selected = root == r, onClick = { root = r }, label = { Text(r.substringAfterLast('.')) }, modifier = Modifier.padding(end = Spacing.xs))
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onCreate(name, root) }) { Text(stringResource(R.string.ds_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ds_cancel)) } },
    )
}

@Composable
private fun NewActivityDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var layout by remember { mutableStateOf("") }
    var layoutEdited by remember { mutableStateOf(false) }
    val suggested = remember(name) { suggestLayoutName(name) }
    val effectiveLayout = if (layoutEdited) layout else suggested
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ds_new_activity)) },
        text = {
            Column {
                BiTextField(name, { name = it.trim() }, stringResource(R.string.ds_activity_name), supporting = stringResource(R.string.ds_activity_hint), monospace = true)
                BiTextField(effectiveLayout, { layout = it.lowercase(); layoutEdited = true }, stringResource(R.string.ds_layout_name), monospace = true)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && effectiveLayout.isNotBlank(), onClick = { onCreate(name, effectiveLayout) }) { Text(stringResource(R.string.ds_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ds_cancel)) } },
    )
}

/** Names a widget (or blocks) saved to the collection; an existing name needs a second tap to replace. */
@Composable
private fun SaveToCollectionDialog(suggested: String, existing: List<String>, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf(suggested) }
    val clash = name.trim() in existing
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ds_collection_save)) },
        text = {
            Column {
                BiTextField(name, { name = it }, stringResource(R.string.ds_collection_name))
                Text(
                    stringResource(if (clash) R.string.ds_collection_replace_hint else R.string.ds_collection_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (clash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), clash) }) {
                Text(stringResource(if (clash) R.string.ds_collection_replace else R.string.ds_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ds_cancel)) } },
    )
}

/** Orientation, keyboard and window options of one screen, written to AndroidManifest.xml. */
@Composable
private fun ScreenOptionsDialog(screen: ActivityConfig, onDismiss: () -> Unit, onSave: (ActivityConfig) -> Unit) {
    var edited by remember(screen) { mutableStateOf(screen) }
    @Composable
    fun <T> Choices(@StringRes title: Int, options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs))
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) }).padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Text(stringResource(label), modifier = Modifier.padding(start = Spacing.sm))
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ds_screen_options, screen.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Choices(
                    R.string.ds_orientation,
                    listOf(
                        ScreenOrientation.Unspecified to R.string.ds_orientation_any, ScreenOrientation.Portrait to R.string.ds_orientation_portrait,
                        ScreenOrientation.Landscape to R.string.ds_orientation_landscape, ScreenOrientation.Sensor to R.string.ds_orientation_sensor,
                    ),
                    edited.orientation,
                ) { edited = edited.copy(orientation = it) }
                Choices(
                    R.string.ds_keyboard,
                    listOf(
                        KeyboardMode.Default to R.string.ds_keyboard_default, KeyboardMode.Resize to R.string.ds_keyboard_resize,
                        KeyboardMode.Pan to R.string.ds_keyboard_pan, KeyboardMode.HiddenOnStart to R.string.ds_keyboard_hidden,
                        KeyboardMode.VisibleOnStart to R.string.ds_keyboard_visible,
                    ),
                    edited.keyboard,
                ) { edited = edited.copy(keyboard = it) }
                Choices(
                    R.string.ds_window,
                    listOf(WindowStyle.Default to R.string.ds_window_default, WindowStyle.NoActionBar to R.string.ds_window_no_action_bar, WindowStyle.Fullscreen to R.string.ds_window_fullscreen),
                    edited.window,
                ) { edited = edited.copy(window = it) }
                Text(stringResource(R.string.ds_screen_options_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.md))
            }
        },
        confirmButton = { TextButton(enabled = edited != screen, onClick = { onSave(edited) }) { Text(stringResource(R.string.ds_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ds_cancel)) } },
    )
}

/** `SettingsActivity` → `activity_settings`, following the platform naming convention. */
internal fun suggestLayoutName(activity: String): String {
    val base = activity.removeSuffix("Activity").ifEmpty { activity }
    val snake = base.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().replace(Regex("[^a-z0-9_]"), "")
    return if (snake.isEmpty()) "" else "activity_$snake"
}

@Composable
private fun rememberMessages(): (DesignerMessage) -> String {
    val saved = stringResource(R.string.ds_saved)
    val failed = stringResource(R.string.ds_failed)
    val added = stringResource(R.string.ds_activity_added)
    val savedToCollection = stringResource(R.string.ds_collection_saved)
    val optionsSaved = stringResource(R.string.ds_screen_options_saved)
    val manifestNotWritten = stringResource(R.string.ds_manifest_not_written)
    val generic = stringResource(R.string.ds_rejected_generic)
    val byCode = mapOf(
        "invalid_resource" to stringResource(R.string.ds_rejected_invalid_resource),
        "collection_exists" to stringResource(R.string.ds_collection_exists),
        "invalid_name" to stringResource(R.string.ds_collection_invalid_name),
        "exists" to stringResource(R.string.ds_rejected_exists),
        "not_container" to stringResource(R.string.ds_rejected_not_container),
        "duplicate_id" to stringResource(R.string.ds_rejected_duplicate_id),
        "invalid_id" to stringResource(R.string.ds_rejected_invalid_id),
        "move_into_self" to stringResource(R.string.ds_rejected_move_into_self),
        "remove_root" to stringResource(R.string.ds_rejected_remove_root),
        "move_root" to stringResource(R.string.ds_rejected_remove_root),
        "too_many_children" to stringResource(R.string.ds_rejected_too_many_children),
        "size_required" to stringResource(R.string.ds_rejected_size_required),
        "invalid_class" to stringResource(R.string.ds_rejected_activity_invalid),
        "activity_exists" to stringResource(R.string.ds_rejected_activity_exists),
    )
    return { m ->
        when (m) {
            DesignerMessage.Saved -> saved
            is DesignerMessage.Failed -> failed.format(m.detail)
            is DesignerMessage.ActivityAdded -> added.format(m.name)
            is DesignerMessage.SavedToCollection -> savedToCollection.format(m.name)
            DesignerMessage.ScreenOptionsSaved -> optionsSaved
            DesignerMessage.ManifestNotWritten -> manifestNotWritten
            is DesignerMessage.EditRejected -> byCode[m.code] ?: generic.format(m.code)
        }
    }
}
