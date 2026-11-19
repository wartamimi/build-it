package dev.buildit.feature.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import dev.buildit.engine.blocks.BlockDocument
import dev.buildit.engine.blocks.BlockOps
import dev.buildit.engine.blocks.BlockRegistry
import dev.buildit.engine.blocks.BlockShape
import dev.buildit.engine.blocks.ComponentType
import dev.buildit.engine.blocks.Components
import dev.buildit.engine.blocks.MessagePart
import dev.buildit.engine.blocks.Names
import dev.buildit.engine.blocks.ViewEvents
import dev.buildit.engine.blocks.ViewFamilies
import dev.buildit.engine.blocks.ViewKind
import kotlinx.coroutines.launch

/** Groups of events (the kind of thing each event belongs to). */
internal enum class EventSection(val label: Int, val types: List<String>) {
    Screen(R.string.bk_section_screen, listOf("event_create", "event_resume", "event_pause", "event_destroy")),
    Views(R.string.bk_section_views, listOf("event_click", "event_checked") + ViewEvents.specs.map { it.hat }),
    Components(R.string.bk_section_components, dev.buildit.engine.blocks.Components.specs.flatMap { s -> s.events.map { it.hat } }),
    More(R.string.bk_section_more, listOf("proc_define")),
    Loose(R.string.bk_section_loose, emptyList()),
}

/** An entry of the event list: a stack of the document. */
internal data class EventEntry(val head: Block, val blockCount: Int, val section: EventSection)

internal fun eventEntries(doc: BlockDocument): List<EventEntry> = doc.stacks.mapNotNull { s ->
    val head = s.blocks.firstOrNull() ?: return@mapNotNull null
    val count = s.blocks.sumOf { it.walk().count() } - 1
    val section = EventSection.entries.firstOrNull { head.type in it.types }
        ?: if (BlockRegistry.find(head.type)?.shape == BlockShape.Hat) EventSection.More else EventSection.Loose
    EventEntry(head, count, section)
}

/** Source key of the screen's own events in [EventsRequest]. */
const val SCREEN_SOURCE = ":screen"

/**
 * Asks the Events tab to show one source: a view's id name (e.g. "button1") or [SCREEN_SOURCE].
 * The designer sets it from a view's action bubble; the Events tab clears it once shown.
 */
class EventsRequest {
    var source by mutableStateOf<String?>(null)
}

internal fun viewCandidates(v: dev.buildit.engine.blocks.ViewInfo): List<Pair<String, Map<String, String>>> =
    listOf("event_click" to mapOf("view" to v.id)) +
        (if (ViewFamilies.matches(ViewKind.Compound, v.tag)) listOf("event_checked" to mapOf("view" to v.id)) else emptyList()) +
        ViewEvents.specs.filter { ViewFamilies.matches(it.kind, v.tag) }.map { it.hat to mapOf("view" to v.id) }

/** What an event belongs to, for the second line of its row: a view or component name, or the screen. */
internal fun eventOwner(head: Block, activity: String?): String =
    head.fields["view"] ?: Components.specs.firstNotNullOfOrNull { s -> head.fields[s.field]?.takeIf { s.events.any { it.hat == head.type } } }
        ?: head.fields["name"] ?: activity.orEmpty()

/**
 * The Events tab, laid out like Sketchware's: kinds of events on the left (Activity, View,
 * Component, More block), the screen's events of that kind on the right, and + to add one.
 * Tapping an event opens it in its own full-screen block editor.
 */
@Composable
fun EventsScreen(
    viewModel: BlocksViewModel,
    onExit: () -> Unit,
    request: EventsRequest? = null,
    /** Called with true while an event is open, so the workspace can hide its tabs and bars. */
    onFullScreen: (Boolean) -> Unit = {},
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf(EventSection.Screen) }
    var highlight by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    // A request from the designer: back to the list, on that view's events (or the screen's).
    val asked = request?.source
    LaunchedEffect(asked) {
        if (asked != null) {
            editing = false
            section = if (asked == SCREEN_SOURCE) EventSection.Screen else EventSection.Views
            highlight = asked.takeIf { it != SCREEN_SOURCE }
            request.source = null
            viewModel.refreshViews()
        }
    }
    val fullScreen by androidx.compose.runtime.rememberUpdatedState(onFullScreen)
    androidx.compose.runtime.DisposableEffect(editing) {
        fullScreen(editing)
        onDispose { if (editing) fullScreen(false) }
    }
    if (editing) {
        BlocksScreen(viewModel, onExit, onBack = { editing = false }, focus = focus)
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshViews() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { viewModel.flush() } }
    val open = { id: String -> focus = id; editing = true }

    when {
        state.loading && state.document == null -> BiLoadingState()
        state.error != null -> BiErrorState(stringResource(R.string.bk_open_failed), state.error!!, retryLabel = stringResource(R.string.bk_retry), onRetry = { viewModel.load(state.activity) })
        state.document == null -> BiEmptyState(BiIcons.Event, stringResource(R.string.bk_no_activities_title), stringResource(R.string.bk_no_activities_body))
        else -> {
            val doc = state.document!!
            val entries = remember(doc) { eventEntries(doc) }
            val res = LocalContext.current.resources
            // Opened from a view without events yet: offer that view's events right away.
            LaunchedEffect(highlight, entries, state.views) {
                val h = highlight ?: return@LaunchedEffect
                if (state.views.any { it.id == h } && entries.none { it.section == EventSection.Views && it.head.field("view") == h }) adding = true
            }
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    SectionRail(section, entries, onSelect = { section = it; highlight = null })
                    VerticalDivider()
                    val shown = entries.filter { it.section == section }
                    if (shown.isEmpty()) {
                        BiEmptyState(BiIcons.Event, stringResource(R.string.bk_no_events_title), stringResource(R.string.bk_no_events_body), Modifier.weight(1f))
                    } else {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(Spacing.sm, Spacing.sm, Spacing.sm, 88.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(shown, key = { it.head.id }) { e ->
                                EventRow(
                                    e, eventTitle(e.head, res), eventOwner(e.head, state.activity),
                                    highlighted = highlight != null && e.head.field("view") == highlight,
                                    onClick = { open(e.head.id) },
                                )
                            }
                        }
                    }
                }
                if (section != EventSection.Loose) {
                    FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.lg)) {
                        Icon(BiIcons.Add, stringResource(R.string.bk_add_event))
                    }
                }
            }
            if (adding) AddEventDialog(section, state, doc, res, onlyView = highlight, onDismiss = { adding = false; highlight = null }) { type, fields ->
                adding = false
                val existing = entries.firstOrNull { it.head.type == type && fields.all { (k, v) -> it.head.field(k) == v } }
                if (existing != null) open(existing.head.id) else viewModel.addEvent(type, fields)?.let(open)
            }
        }
    }
}

/** The kinds of events, as a narrow list on the left. */
@Composable
private fun SectionRail(selected: EventSection, entries: List<EventEntry>, onSelect: (EventSection) -> Unit) {
    Column(Modifier.width(112.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer).verticalScroll(rememberScrollState()).padding(vertical = Spacing.xs)) {
        EventSection.entries.forEach { s ->
            val count = entries.count { it.section == s }
            if (s == EventSection.Loose && count == 0) return@forEach
            val on = s == selected
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(s) }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(s.label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                if (count > 0) Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One event: a coloured icon, the event, and what it belongs to. */
@Composable
private fun EventRow(e: EventEntry, title: String, owner: String, highlighted: Boolean, onClick: () -> Unit) {
    val color = categoryColor(BlockRegistry.find(e.head.type)?.category ?: BlockCategory.Code)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = Spacing.sm, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(color, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(if (e.section == EventSection.More) BiIcons.Blocks else BiIcons.Event, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = Spacing.sm)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    owner + " · " + stringResource(R.string.bk_event_blocks, e.blockCount) + if (e.head.disabled) " · " + stringResource(R.string.bk_disable) else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AddEventDialog(
    section: EventSection,
    state: BlocksUiState,
    doc: BlockDocument,
    res: android.content.res.Resources,
    /** Only this view's events (opened from the designer). */
    onlyView: String? = null,
    onDismiss: () -> Unit,
    onAdd: (String, Map<String, String>) -> Unit,
) {
    val candidates: List<Pair<String, Map<String, String>>> = when (section) {
        EventSection.Screen -> section.types.map { it to emptyMap() }
        EventSection.Views -> state.views.filter { onlyView == null || it.id == onlyView }.flatMap(::viewCandidates)
        EventSection.Components -> doc.components.flatMap { c ->
            val spec = Components.spec(c.type)
            spec.events.map { it.hat to mapOf(spec.field to c.name) }
        }
        EventSection.More, EventSection.Loose -> emptyList()
    }
    val existing = remember(doc) { eventEntries(doc).map { it.head } }
    fun added(type: String, fields: Map<String, String>) = existing.any { h -> h.type == type && fields.all { (k, v) -> h.field(k) == v } }
    var procName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bk_add_event)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (section == EventSection.More) {
                    OutlinedTextField(procName, { procName = it.trim() }, label = { Text(stringResource(R.string.bk_procedure_name)) }, singleLine = true, textStyle = CodeTypography.body,
                        isError = procName.isNotEmpty() && !Names.isIdentifier(procName))
                } else if (candidates.isEmpty()) {
                    Text(stringResource(if (section == EventSection.Views) R.string.bk_no_views else R.string.bk_no_components_of_type))
                }
                candidates.forEach { (type, fields) ->
                    val already = added(type, fields)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onAdd(type, fields) }.padding(vertical = Spacing.md, horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (already) BiIcons.Check else BiIcons.Add, null, Modifier.size(18.dp), tint = categoryColor(BlockCategory.Events))
                        Text(eventTitle(Block("", type, fields), res), modifier = Modifier.weight(1f).padding(start = Spacing.md))
                        if (already) Text(stringResource(R.string.bk_already_added), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (section == EventSection.More) {
                TextButton(enabled = Names.isIdentifier(procName), onClick = { onAdd("proc_define", mapOf("name" to procName)) }) { Text(stringResource(R.string.bk_ok)) }
            } else TextButton(onClick = onDismiss) { Text(stringResource(R.string.bk_cancel)) }
        },
        dismissButton = if (section == EventSection.More) ({ TextButton(onClick = onDismiss) { Text(stringResource(R.string.bk_cancel)) } }) else null,
    )
}

/** The block's label with its field values filled in, e.g. "when button1 is clicked". */
internal fun eventTitle(b: Block, res: android.content.res.Resources): String {
    val def = BlockRegistry.find(b.type) ?: return b.type
    return localizedParts(def, res).joinToString(" ") { p ->
        when (p) {
            is MessagePart.Label -> p.text
            is MessagePart.Field -> b.field(p.name).ifEmpty { "?" }
            is MessagePart.Input -> "…"
        }
    }
}

// ------------------------------------------------------------ components

internal fun componentIcon(t: ComponentType): ImageVector = when (t) {
    ComponentType.Timer -> BiIcons.History
    ComponentType.Storage -> BiIcons.Save
    ComponentType.Dialog -> BiIcons.Info
    ComponentType.Sound -> BiIcons.Sound
    ComponentType.Intent -> BiIcons.OpenOut
    ComponentType.Vibrator -> BiIcons.Vibrate
    ComponentType.Calendar -> BiIcons.Calendar
    ComponentType.Animator -> BiIcons.Motion
    ComponentType.Sensor -> BiIcons.Sensor
    ComponentType.TextToSpeech -> BiIcons.Speak
    ComponentType.SpeechToText -> BiIcons.Mic
    ComponentType.FilePicker -> BiIcons.File
    ComponentType.Network -> BiIcons.Network
    ComponentType.Notification -> BiIcons.Bell
    ComponentType.DatePicker -> BiIcons.Calendar
    ComponentType.TimePicker -> BiIcons.Clock
}

internal fun componentLabel(t: ComponentType): Int = when (t) {
    ComponentType.Timer -> R.string.bk_comp_Timer
    ComponentType.Storage -> R.string.bk_comp_Storage
    ComponentType.Dialog -> R.string.bk_comp_Dialog
    ComponentType.Sound -> R.string.bk_comp_Sound
    ComponentType.Intent -> R.string.bk_comp_Intent
    ComponentType.Vibrator -> R.string.bk_comp_Vibrator
    ComponentType.Calendar -> R.string.bk_comp_Calendar
    ComponentType.Animator -> R.string.bk_comp_Animator
    ComponentType.Sensor -> R.string.bk_comp_Sensor
    ComponentType.TextToSpeech -> R.string.bk_comp_TextToSpeech
    ComponentType.SpeechToText -> R.string.bk_comp_SpeechToText
    ComponentType.FilePicker -> R.string.bk_comp_FilePicker
    ComponentType.Network -> R.string.bk_comp_Network
    ComponentType.Notification -> R.string.bk_comp_Notification
    ComponentType.DatePicker -> R.string.bk_comp_DatePicker
    ComponentType.TimePicker -> R.string.bk_comp_TimePicker
}

internal fun componentDescription(t: ComponentType): Int = when (t) {
    ComponentType.Timer -> R.string.bk_comp_Timer_desc
    ComponentType.Storage -> R.string.bk_comp_Storage_desc
    ComponentType.Dialog -> R.string.bk_comp_Dialog_desc
    ComponentType.Sound -> R.string.bk_comp_Sound_desc
    ComponentType.Intent -> R.string.bk_comp_Intent_desc
    ComponentType.Vibrator -> R.string.bk_comp_Vibrator_desc
    ComponentType.Calendar -> R.string.bk_comp_Calendar_desc
    ComponentType.Animator -> R.string.bk_comp_Animator_desc
    ComponentType.Sensor -> R.string.bk_comp_Sensor_desc
    ComponentType.TextToSpeech -> R.string.bk_comp_TextToSpeech_desc
    ComponentType.SpeechToText -> R.string.bk_comp_SpeechToText_desc
    ComponentType.FilePicker -> R.string.bk_comp_FilePicker_desc
    ComponentType.Network -> R.string.bk_comp_Network_desc
    ComponentType.Notification -> R.string.bk_comp_Notification_desc
    ComponentType.DatePicker -> R.string.bk_comp_DatePicker_desc
    ComponentType.TimePicker -> R.string.bk_comp_TimePicker_desc
}

/**
 * The Components tab: the non-visual parts of the current screen. Each component brings its own
 * blocks (in the Components category of the block editor) and, where it has one, an event.
 */
@Composable
fun ComponentsScreen(viewModel: BlocksViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val inUse = stringResource(R.string.bk_component_uses)
    // Like the editors: save (when autosave is on) when the app goes to the background or the tab is left.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.flush() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); viewModel.flush() }
    }

    when {
        state.loading && state.document == null -> BiLoadingState()
        state.error != null -> BiErrorState(stringResource(R.string.bk_open_failed), state.error!!, retryLabel = stringResource(R.string.bk_retry), onRetry = { viewModel.load(state.activity) })
        state.document == null -> BiEmptyState(BiIcons.Component, stringResource(R.string.bk_no_activities_title), stringResource(R.string.bk_no_activities_body))
        else -> Box(Modifier.fillMaxSize()) {
            val doc = state.document!!
            if (doc.components.isEmpty()) {
                BiEmptyState(BiIcons.Component, stringResource(R.string.bk_no_components_title), stringResource(R.string.bk_no_components_body))
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.md, Spacing.md, Spacing.md, 88.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(doc.components, key = { it.name }) { c ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(12.dp), color = categoryColor(BlockCategory.Parts)) {
                                    Icon(componentIcon(c.type), null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(Spacing.sm))
                                }
                                Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                                    Text(c.name, style = CodeTypography.body, fontWeight = FontWeight.Medium)
                                    Text(stringResource(componentLabel(c.type)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    BiIconButton(BiIcons.More, stringResource(R.string.bk_rename), { menu = true })
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.bk_rename)) }, leadingIcon = { Icon(BiIcons.Edit, null) }, onClick = { menu = false; renaming = c.name })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.bk_delete)) }, leadingIcon = { Icon(BiIcons.Delete, null) }, onClick = {
                                            menu = false
                                            if (BlockOps.usesComponent(doc, c.name)) scope.launch { snackbar.showSnackbar(inUse) } else viewModel.removeComponent(c.name)
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            FloatingActionButton(onClick = { picking = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.lg)) {
                Icon(BiIcons.Add, stringResource(R.string.bk_add_component))
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.bk_add_component)) },
            text = {
                // Two-column grid (plain rows: dialogs can't hold lazy layouts reliably).
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ComponentType.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            row.forEach { t ->
                                Card(onClick = { picking = false; viewModel.addComponent(viewModel.suggestComponentName(t), t) }, modifier = Modifier.weight(1f)) {
                                    Column(Modifier.padding(Spacing.md)) {
                                        Icon(componentIcon(t), null, tint = categoryColor(BlockCategory.Parts))
                                        Text(stringResource(componentLabel(t)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.xs))
                                        Text(stringResource(componentDescription(t)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.bk_cancel)) } },
        )
    }
    renaming?.let { old ->
        NameDialog(stringResource(R.string.bk_rename), old, onDismiss = { renaming = null }) { name ->
            renaming = null
            viewModel.renameComponent(old, name)
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it.trim() }, label = { Text(stringResource(R.string.bk_component_name)) }, singleLine = true, textStyle = CodeTypography.body,
                isError = name.isNotEmpty() && !Names.isIdentifier(name))
        },
        confirmButton = { TextButton(enabled = Names.isIdentifier(name), onClick = { onConfirm(name) }) { Text(stringResource(R.string.bk_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bk_cancel)) } },
    )
}
