package dev.buildit.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.ai.AiConfig
import dev.buildit.engine.ai.AiException
import dev.buildit.engine.ai.DiffLine
import dev.buildit.engine.ai.FileChange
import dev.buildit.engine.ai.ProviderKind
import dev.buildit.engine.ai.SetupProblem
import dev.buildit.engine.ai.StopKind

/** AI tab: provider setup, chat with chosen project files as context, and reviewable file changes. */
@Composable
fun AiScreen(viewModel: AiViewModel) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val setup = s.setup
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxSize().imePadding()) {
            when {
                setup == null -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                s.editingSetup -> SetupCard(setup, viewModel)
                else -> {
                    Header(setup.config, viewModel, s.running)
                    Conversation(s, viewModel, Modifier.weight(1f))
                    Composer(s, viewModel)
                }
            }
        }
    }
}

@Composable
private fun Header(config: AiConfig, vm: AiViewModel, running: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (config.provider == ProviderKind.Anthropic) R.string.ai_provider_anthropic else R.string.ai_provider_openai) + " · " + config.model,
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = vm::newChat, enabled = !running) { Text(stringResource(R.string.ai_new_chat)) }
        IconButton(onClick = { vm.editSetup(true) }) { Icon(BiIcons.Settings, stringResource(R.string.ai_setup_title)) }
    }
}

@Composable
private fun SetupCard(setup: SetupState, vm: AiViewModel) {
    var provider by rememberSaveable { mutableStateOf(setup.config.provider) }
    var model by rememberSaveable { mutableStateOf(setup.config.model) }
    var url by rememberSaveable { mutableStateOf(setup.config.baseUrl) }
    var maxTokens by rememberSaveable { mutableStateOf(setup.config.maxTokens.toString()) }
    var key by rememberSaveable { mutableStateOf("") }
    val stored = remember(provider, setup) { vm.hasStoredKey(provider) }
    var removeKey by rememberSaveable { mutableStateOf(false) }
    val config = AiConfig(provider, model.trim(), url.trim(), maxTokens.toIntOrNull() ?: 0)
    val hasKey = (stored && !removeKey) || key.isNotBlank()
    val problem = config.problem(hasKey)

    LazyColumn(contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        item { Text(stringResource(R.string.ai_setup_title), style = MaterialTheme.typography.titleMedium) }
        item { Text(stringResource(R.string.ai_setup_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(selected = provider == ProviderKind.Anthropic, onClick = {
                    if (provider != ProviderKind.Anthropic) { provider = ProviderKind.Anthropic; model = AiConfig.DEFAULT_ANTHROPIC_MODEL; url = ""; removeKey = false }
                }, label = { Text(stringResource(R.string.ai_provider_anthropic)) })
                FilterChip(selected = provider == ProviderKind.OpenAiCompatible, onClick = {
                    if (provider != ProviderKind.OpenAiCompatible) { provider = ProviderKind.OpenAiCompatible; model = ""; removeKey = false }
                }, label = { Text(stringResource(R.string.ai_provider_openai)) })
            }
        }
        item {
            OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.ai_model)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { if (provider == ProviderKind.Anthropic) Text(stringResource(R.string.ai_model_hint_anthropic)) })
        }
        item {
            OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (provider == ProviderKind.Anthropic) R.string.ai_url_optional else R.string.ai_url)) },
                placeholder = { Text(if (provider == ProviderKind.Anthropic) "https://api.anthropic.com" else "https://…/v1") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = problem == SetupProblem.BadUrl,
                supportingText = { if (problem == SetupProblem.BadUrl) Text(stringResource(R.string.ai_url_invalid)) })
        }
        item {
            OutlinedTextField(key, { key = it; removeKey = false }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_key)) },
                placeholder = { if (stored && !removeKey) Text(stringResource(R.string.ai_key_saved)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                supportingText = { Text(stringResource(R.string.ai_key_hint)) })
            if (stored && !removeKey) TextButton(onClick = { removeKey = true; key = "" }) { Text(stringResource(R.string.ai_key_remove)) }
        }
        item {
            OutlinedTextField(maxTokens, { maxTokens = it.filter(Char::isDigit).take(6) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_max_tokens)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = problem == SetupProblem.BadMaxTokens)
        }
        item {
            problem?.let {
                Text(stringResource(when (it) {
                    SetupProblem.NoModel -> R.string.ai_problem_model
                    SetupProblem.NoKey -> R.string.ai_problem_key
                    SetupProblem.BadUrl -> R.string.ai_url_invalid
                    SetupProblem.BadMaxTokens -> R.string.ai_problem_tokens
                }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.sm)) {
                Button(onClick = { vm.saveSetup(config, if (removeKey) "" else key.takeIf { it.isNotBlank() }) }, enabled = problem == null) {
                    Text(stringResource(R.string.ai_save))
                }
                if (setup.problem == null) OutlinedButton(onClick = { vm.editSetup(false) }) { Text(stringResource(R.string.ai_cancel)) }
            }
        }
    }
}

@Composable
private fun Conversation(s: AiUiState, vm: AiViewModel, modifier: Modifier) {
    val list = rememberLazyListState()
    val count = s.items.size + if (s.streaming != null) 1 else 0
    LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count - 1) }
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (s.items.isEmpty() && s.streaming == null) item {
            Text(stringResource(R.string.ai_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(s.items, key = { it.id }) { item ->
            when (item) {
                is ChatItem.User -> UserBubble(item)
                is ChatItem.Assistant -> AssistantBlock(item, vm)
                is ChatItem.Failure -> FailureRow(item)
            }
        }
        s.streaming?.let { text ->
            item(key = "streaming") {
                Column {
                    if (text.isEmpty()) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun UserBubble(item: ChatItem.User) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.widthIn(max = 560.dp)) {
            Column(Modifier.padding(Spacing.md)) {
                SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyMedium) }
                val extra = item.files.map { it.substringAfterLast('/') } + listOfNotNull(
                    if (item.withProblems > 0) stringResource(R.string.ai_with_problems, item.withProblems) else null,
                )
                if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
            }
        }
    }
}

@Composable
private fun AssistantBlock(item: ChatItem.Assistant, vm: AiViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (item.prose.isNotBlank()) SelectionContainer { Text(item.prose, style = MaterialTheme.typography.bodyMedium) }
        when (item.stop) {
            StopKind.MaxTokens -> Note(stringResource(R.string.ai_stop_max_tokens), error = true)
            StopKind.Refusal -> Note(stringResource(R.string.ai_stop_refusal), error = true)
            else -> Unit
        }
        item.changes.forEach { c -> ChangeCard(c, onApply = { vm.apply(item.id, c.change.path) }, onDiscard = { vm.discard(item.id, c.change.path) }) }
        val pending = item.changes.count { it.status == ChangeStatus.Pending && it.change.blocked == null }
        if (pending > 1) Button(onClick = { vm.applyAll(item.id) }) { Text(stringResource(R.string.ai_apply_all, pending)) }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) = Text(
    text, style = MaterialTheme.typography.bodySmall,
    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun ChangeCard(item: ChangeItem, onApply: () -> Unit, onDiscard: () -> Unit) {
    val c = item.change
    var expanded by rememberSaveable(c.path) { mutableStateOf(true) }
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { expanded = !expanded }) {
                Text(c.path, style = CodeTypography.small, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                val added = c.hunks.sumOf { it.added }
                val removed = c.hunks.sumOf { it.removed }
                if (c.blocked == null) Text("+$added −$removed", style = MaterialTheme.typography.labelSmall)
            }
            if (c.isNew && c.blocked == null) Note(stringResource(R.string.ai_new_file))
            if (c.generated) Note(stringResource(R.string.ai_generated_file))
            if (c.changedSinceSent) Note(stringResource(R.string.ai_changed_since_sent), error = true)
            c.blocked?.let { Note(stringResource(blockedText(it)), error = true) }
            if (expanded && c.blocked == null) Diff(c)
            when (item.status) {
                ChangeStatus.Pending -> if (c.blocked == null) Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = onApply) { Text(stringResource(R.string.ai_apply)) }
                    OutlinedButton(onClick = onDiscard) { Text(stringResource(R.string.ai_discard)) }
                }
                ChangeStatus.Applied -> Note(stringResource(R.string.ai_applied))
                ChangeStatus.Discarded -> Note(stringResource(R.string.ai_discarded))
                ChangeStatus.Stale -> Note(stringResource(R.string.ai_stale), error = true)
                ChangeStatus.Failed -> Note(stringResource(R.string.ai_apply_failed, item.detail.orEmpty()), error = true)
            }
        }
    }
}

private fun blockedText(b: FileChange.Blocked) = when (b) {
    FileChange.Blocked.Incomplete -> R.string.ai_blocked_incomplete
    FileChange.Blocked.InvalidPath -> R.string.ai_blocked_path
    FileChange.Blocked.Protected -> R.string.ai_blocked_protected
    FileChange.Blocked.BuildOutput -> R.string.ai_blocked_build
    FileChange.Blocked.NotText -> R.string.ai_blocked_not_text
    FileChange.Blocked.TooLarge -> R.string.ai_blocked_large
}

@Composable
private fun Diff(c: FileChange) {
    val added = Color(0x3328A745)
    val removed = Color(0x33D73A49)
    Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).horizontalScroll(rememberScrollState())) {
        c.hunks.forEachIndexed { i, h ->
            if (i > 0) Text("⋯", style = CodeTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            h.lines.forEach { l ->
                val (prefix, bg) = when (l.kind) {
                    DiffLine.Kind.Added -> "+" to added
                    DiffLine.Kind.Removed -> "−" to removed
                    DiffLine.Kind.Same -> " " to Color.Transparent
                }
                val number = if (l.kind == DiffLine.Kind.Removed) l.oldLine else l.newLine
                Text(
                    "%4d %s %s".format(number, prefix, l.text), style = CodeTypography.small, softWrap = false,
                    modifier = Modifier.background(bg).semantics { contentDescription = "$prefix line $number: ${l.text}" },
                )
            }
        }
    }
}

@Composable
private fun FailureRow(item: ChatItem.Failure) {
    val text = when (item.kind) {
        AiException.Kind.Auth -> stringResource(R.string.ai_err_auth)
        AiException.Kind.RateLimit -> stringResource(R.string.ai_err_rate)
        AiException.Kind.Overloaded -> stringResource(R.string.ai_err_overloaded)
        AiException.Kind.NotFound -> stringResource(R.string.ai_err_not_found)
        AiException.Kind.Network -> stringResource(R.string.ai_err_network)
        AiException.Kind.Cancelled -> stringResource(R.string.ai_err_cancelled)
        AiException.Kind.BadRequest, AiException.Kind.Server -> stringResource(R.string.ai_err_other)
    }
    Column {
        Note(text, error = item.kind != AiException.Kind.Cancelled)
        if (item.kind != AiException.Kind.Cancelled && item.message.isNotBlank()) Text(item.message, style = CodeTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Composer(s: AiUiState, vm: AiViewModel) {
    var picking by remember { mutableStateOf(false) }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                s.contextFiles.forEach { p ->
                    InputChip(selected = true, onClick = { vm.toggleContext(p) }, label = { Text(p.substringAfterLast('/')) },
                        trailingIcon = { Icon(BiIcons.Close, stringResource(R.string.ai_remove_file, p), Modifier.size(16.dp)) })
                }
                AssistChip(onClick = { vm.refreshFiles(); picking = true }, label = { Text(stringResource(R.string.ai_add_files)) },
                    leadingIcon = { Icon(BiIcons.Add, null, Modifier.size(16.dp)) })
            }
            val problems = vm.buildProblemCount
            if (problems > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(s.includeProblems, vm::setIncludeProblems)
                Text(stringResource(R.string.ai_include_problems, problems), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(s.input, vm::onInput, modifier = Modifier.weight(1f).heightIn(max = 180.dp), placeholder = { Text(stringResource(R.string.ai_input_hint)) })
                if (s.running) TextButton(onClick = vm::stop) { Text(stringResource(R.string.ai_stop)) }
                else TextButton(onClick = vm::send, enabled = s.input.isNotBlank()) { Text(stringResource(R.string.ai_send)) }
            }
        }
    }
    if (picking) FilePicker(s, vm) { picking = false }
}

@Composable
private fun FilePicker(s: AiUiState, vm: AiViewModel, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.ai_pick_title)) },
        text = {
            if (s.projectFiles.isEmpty()) Text(stringResource(R.string.ai_pick_empty))
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(s.projectFiles) { p ->
                    Row(Modifier.fillMaxWidth().clickable { vm.toggleContext(p) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p in s.contextFiles, { vm.toggleContext(p) })
                        Text(p, style = CodeTypography.small)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.ai_done)) } },
    )
}
