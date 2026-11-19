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

/** One card of the Events tab: a thing that has events, with the events it can have. */
internal data class EventSource(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: androidx.compose.ui.graphics.Color,
    /** (type, fields) of every event this source can have. */
    val candidates: List<Pair<String, Map<String, String>>>,
    /** Stacks of the document that belong here. */
    val entries: List<EventEntry>,
)

private fun matches(h: Block, type: String, fields: Map<String, String>) = h.type == type && fields.all { (k, v) -> h.field(k) == v }

internal fun viewCandidates(v: dev.buildit.engine.blocks.ViewInfo): List<Pair<String, Map<String, String>>> =
    listOf("event_click" to mapOf("view" to v.id)) +
        (if (ViewFamilies.matches(ViewKind.Compound, v.tag)) listOf("event_checked" to mapOf("view" to v.id)) else emptyList()) +
        ViewEvents.specs.filter { ViewFamilies.matches(it.kind, v.tag) }.map { it.hat to mapOf("view" to v.id) }

/**
 * The Events tab: one card per thing that has events (the screen, each view, each component),
 * each with a chip per event. A chip with a tick is already on the screen and opens the block
 * editor on it; a chip with + adds that event and opens it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EventsScreen(viewModel: BlocksViewModel, onExit: () -> Unit, request: EventsRequest? = null) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    var highlight by rememberSaveable { mutableStateOf<String?>(null) }
    // A request from the designer: back to the list, on that source's card.
    val asked = request?.source
    LaunchedEffect(asked) {
        if (asked != null) {
            editing = false
            highlight = asked
            request.source = null
            viewModel.refreshViews()
        }
    }
    if (editing) {
        BlocksScreen(viewModel, onExit, onBack = { editing = false }, focus = focus)
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshViews() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { viewModel.flush() } }
    var naming by remember { mutableStateOf(false) }
    val open = { id: String -> focus = id; editing = true }

    when {
        state.loading && state.document == null -> BiLoadingState()
        state.error != null -> BiErrorState(stringResource(R.string.bk_open_failed), state.error!!, retryLabel = stringResource(R.string.bk_retry), onRetry = { viewModel.load(state.activity) })
        state.document == null -> BiEmptyState(BiIcons.Event, stringResource(R.string.bk_no_activities_title), stringResource(R.string.bk_no_activities_body))
        else -> {
            val doc = state.document!!
            val res = LocalContext.current.resources
            val sources = remember(doc, state.views, res) { eventSources(doc, state.views, res) }
            val list = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(highlight, sources) {
                val i = sources.indexOfFirst { it.key == highlight }
                if (i >= 0) list.animateScrollToItem(i)
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(Spacing.md, Spacing.md, Spacing.md, Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(sources, key = { it.key }) { src ->
                    SourceCard(
                        src, highlighted = src.key == highlight, res = res,
                        onOpen = open,
                        onAdd = { type, fields -> viewModel.addEvent(type, fields)?.let(open) },
                        onNewProcedure = if (src.key == EventSection.More.name) ({ naming = true }) else null,
                    )
                }
                if (state.views.isEmpty()) item(key = "hint") {
                    Text(
                        stringResource(R.string.bk_no_views),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.sm),
                    )
                }
            }
            if (naming) ProcedureDialog(onDismiss = { naming = false }) { name ->
                naming = false
                viewModel.addEvent("proc_define", mapOf("name" to name))?.let(open)
            }
        }
    }
}

/** The cards of the Events tab, in order: screen, views, components, procedures, leftovers. */
internal fun eventSources(doc: BlockDocument, views: List<dev.buildit.engine.blocks.ViewInfo>, res: android.content.res.Resources): List<EventSource> {
    val entries = eventEntries(doc)
    val used = mutableSetOf<String>()
    fun take(cands: List<Pair<String, Map<String, String>>>) =
        entries.filter { e -> e.head.id !in used && cands.any { (t, f) -> matches(e.head, t, f) } }.also { l -> l.forEach { used += it.head.id } }
    val out = mutableListOf<EventSource>()
    val screen = EventSection.Screen.types.map { it to emptyMap<String, String>() }
    out += EventSource(SCREEN_SOURCE, res.getString(R.string.bk_section_screen), res.getString(R.string.bk_source_screen), BiIcons.Screens, categoryColor(BlockCategory.Events), screen, take(screen))
    views.forEach { v ->
        val c = viewCandidates(v)
        out += EventSource(v.id, v.id, v.tag.substringAfterLast('.'), BiIcons.Widgets, categoryColor(BlockCategory.Components), c, take(c))
    }
    doc.components.forEach { comp ->
        val spec = Components.spec(comp.type)
        val c = spec.events.map { it.hat to mapOf(spec.field to comp.name) }
        out += EventSource("comp:" + comp.name, comp.name, res.getString(componentLabel(comp.type)), componentIcon(comp.type), categoryColor(BlockCategory.Parts), c, take(c))
    }
    val more = entries.filter { it.head.id !in used && it.section == EventSection.More }.also { l -> l.forEach { used += it.head.id } }
    out += EventSource(EventSection.More.name, res.getString(R.string.bk_section_more), res.getString(R.string.bk_source_more), BiIcons.Blocks, categoryColor(BlockCategory.Code), emptyList(), more)
    // Events of views or components that are gone, and stacks without an event.
    val rest = entries.filter { it.head.id !in used }
    if (rest.isNotEmpty()) out += EventSource(EventSection.Loose.name, res.getString(R.string.bk_section_loose), res.getString(R.string.bk_source_loose), BiIcons.Warning, categoryColor(BlockCategory.Variables), emptyList(), rest)
    return out
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(
    src: EventSource,
    highlighted: Boolean,
    res: android.content.res.Resources,
    onOpen: (String) -> Unit,
    onAdd: (String, Map<String, String>) -> Unit,
    onNewProcedure: (() -> Unit)?,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = if (highlighted) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(src.color, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Icon(src.icon, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.padding(start = Spacing.md).weight(1f)) {
                    Text(src.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(src.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Events already on the screen first, then the ones that can be added.
                src.entries.forEach { e -> EventChip(eventTitle(e.head, res), added = true, color = src.color, blocks = e.blockCount) { onOpen(e.head.id) } }
                src.candidates.filter { (t, f) -> src.entries.none { matches(it.head, t, f) } }.forEach { (t, f) ->
                    EventChip(eventTitle(Block("", t, f), res), added = false, color = src.color) { onAdd(t, f) }
                }
                onNewProcedure?.let { EventChip(stringResource(R.string.bk_new_procedure), added = false, color = src.color, onClick = it) }
            }
        }
    }
}

/** An event as a chip: filled with a tick when the screen has it, outlined with + when it can be added. */
@Composable
private fun EventChip(title: String, added: Boolean, color: androidx.compose.ui.graphics.Color, blocks: Int = 0, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .clip(shape)
            .then(if (added) Modifier.background(color.copy(alpha = 0.16f)) else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (added) BiIcons.Check else BiIcons.Add, null, Modifier.size(16.dp), tint = if (added) color else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = if (added) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        if (added && blocks > 0) {
            Box(Modifier.padding(start = 6.dp).background(color, RoundedCornerShape(8.dp)).padding(horizontal = 5.dp)) {
                Text("$blocks", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
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

@Composable
private fun ProcedureDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var procName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bk_new_procedure)) },
        text = {
            OutlinedTextField(procName, { procName = it.trim() }, label = { Text(stringResource(R.string.bk_procedure_name)) }, singleLine = true, textStyle = CodeTypography.body,
                isError = procName.isNotEmpty() && !Names.isIdentifier(procName))
        },
        confirmButton = { TextButton(enabled = Names.isIdentifier(procName), onClick = { onCreate(procName) }) { Text(stringResource(R.string.bk_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bk_cancel)) } },
    )
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
