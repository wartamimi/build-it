package dev.buildit.feature.blocks

import androidx.compose.foundation.background
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
import dev.buildit.engine.blocks.ViewFamilies
import dev.buildit.engine.blocks.ViewKind
import kotlinx.coroutines.launch

/** Groups of events, shown as a rail beside the event list. */
internal enum class EventSection(val label: Int, val types: List<String>) {
    Screen(R.string.bk_section_screen, listOf("event_create", "event_resume", "event_pause", "event_destroy")),
    Views(R.string.bk_section_views, listOf("event_click", "event_checked")),
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

/**
 * The Events tab: the screen's events grouped by kind. Tapping one opens the block editor on it;
 * + adds an event (or opens it if the screen already has it).
 */
@Composable
fun EventsScreen(viewModel: BlocksViewModel, onExit: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    if (editing) {
        BlocksScreen(viewModel, onExit, onBack = { editing = false }, focus = focus)
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshViews() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { viewModel.flush() } }
    var section by rememberSaveable { mutableStateOf(EventSection.Screen) }
    var adding by remember { mutableStateOf(false) }
    val open = { id: String -> focus = id; editing = true }

    when {
        state.loading && state.document == null -> BiLoadingState()
        state.error != null -> BiErrorState(stringResource(R.string.bk_open_failed), state.error!!, retryLabel = stringResource(R.string.bk_retry), onRetry = { viewModel.load(state.activity) })
        state.document == null -> BiEmptyState(BiIcons.Event, stringResource(R.string.bk_no_activities_title), stringResource(R.string.bk_no_activities_body))
        else -> {
            val doc = state.document!!
            val entries = remember(doc) { eventEntries(doc) }
            val res = LocalContext.current.resources
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    SectionRail(section, entries, onSelect = { section = it })
                    VerticalDivider()
                    val shown = entries.filter { it.section == section }
                    if (shown.isEmpty()) {
                        BiEmptyState(BiIcons.Event, stringResource(R.string.bk_no_events_title), stringResource(R.string.bk_no_events_body), Modifier.weight(1f))
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(Spacing.md, Spacing.md, Spacing.md, 88.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            items(shown, key = { it.head.id }) { e ->
                                EventCard(e, eventTitle(e.head, res), onClick = { open(e.head.id) })
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
            if (adding) AddEventDialog(section, state, doc, res, onDismiss = { adding = false }) { type, fields ->
                adding = false
                viewModel.addEvent(type, fields)?.let(open)
            }
        }
    }
}

@Composable
private fun SectionRail(selected: EventSection, entries: List<EventEntry>, onSelect: (EventSection) -> Unit) {
    Column(Modifier.width(124.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Spacing.sm)) {
        EventSection.entries.forEach { s ->
            val count = entries.count { it.section == s }
            if (s == EventSection.Loose && count == 0) return@forEach
            val on = s == selected
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = 2.dp)
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onSelect(s) }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.md),
            ) {
                Text(stringResource(s.label), style = MaterialTheme.typography.labelLarge, softWrap = false, overflow = TextOverflow.Ellipsis, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
                if (count > 0) Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EventCard(e: EventEntry, title: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = categoryColor(BlockRegistry.find(e.head.type)?.category ?: BlockCategory.Code)
            Box(Modifier.width(6.dp).height(64.dp).background(color))
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.bk_event_blocks, e.blockCount) + if (e.head.disabled) " · " + stringResource(R.string.bk_disable) else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(BiIcons.Blocks, stringResource(R.string.bk_open_editor), tint = color, modifier = Modifier.padding(end = Spacing.md))
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
private fun AddEventDialog(
    section: EventSection,
    state: BlocksUiState,
    doc: BlockDocument,
    res: android.content.res.Resources,
    onDismiss: () -> Unit,
    onAdd: (String, Map<String, String>) -> Unit,
) {
    // Candidate events: (type, fields) for this section.
    val candidates: List<Pair<String, Map<String, String>>> = when (section) {
        EventSection.Screen -> section.types.map { it to emptyMap() }
        EventSection.Views -> state.views.flatMap { v ->
            listOf("event_click" to mapOf("view" to v.id)) +
                if (ViewFamilies.matches(ViewKind.Compound, v.tag)) listOf("event_checked" to mapOf("view" to v.id)) else emptyList()
        }
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
                    val title = eventTitle(Block("", type, fields), res)
                    Row(Modifier.fillMaxWidth().clickable { onAdd(type, fields) }.padding(vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(categoryColor(BlockCategory.Events), RoundedCornerShape(5.dp)))
                        Text(title, modifier = Modifier.weight(1f).padding(start = Spacing.md))
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
