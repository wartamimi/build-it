package dev.buildit.feature.code

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.component.BiEmptyState
import dev.buildit.core.designsystem.component.BiIconButton
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.editor.EditorDiagnostic
import dev.buildit.engine.editor.Search
import dev.buildit.engine.editor.SearchQuery
import dev.buildit.engine.editor.TextPositions

/** Code editor: open files as tabs, syntax highlighting, find/replace, go to line, syntax checks. */
@Composable
fun CodeScreen(viewModel: CodeViewModel, onExit: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val autoSave by viewModel.autoSaveEnabled.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    var editor by remember { mutableStateOf<CodeEditText?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var showProblems by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var historyTick by remember { mutableIntStateOf(0) }
    var editTick by remember { mutableIntStateOf(0) }

    val failed = stringResource(R.string.cd_failed)
    val cannotOpen = stringResource(R.string.cd_cannot_open)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { m ->
            snackbar.showSnackbar(when (m) {
                is CodeMessage.Failed -> failed.format(m.detail)
                is CodeMessage.CannotOpen -> cannotOpen.format(m.path, m.detail)
            })
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) viewModel.flush() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); viewModel.flush() }
    }
    val anyDirty = state.files.any { it.dirty }
    BackHandler(enabled = anyDirty) { if (autoSave?.autoSave == true) viewModel.saveAllThen(onExit) else confirmLeave = true }

    val colors = editorColors()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.files.isEmpty()) {
                BiEmptyState(BiIcons.Edit, stringResource(R.string.cd_empty_title), stringResource(R.string.cd_empty_body))
                return@Column
            }
            FileTabs(state, onSelect = viewModel::select, onClose = { p ->
                if (state.files.first { it.path == p }.dirty && autoSave?.autoSave != true) confirmClose = p
                else if (state.files.first { it.path == p }.dirty) viewModel.saveAndClose(p) else viewModel.close(p)
            })
            val file = state.currentFile ?: return@Column
            Toolbar(file, state, editor, historyTick, viewModel,
                onSearch = { showSearch = !showSearch }, onGoTo = { showGoTo = true }, onProblems = { showProblems = !showProblems })
            HorizontalDivider()
            if (file.conflict) Banner(stringResource(R.string.cd_conflict), MaterialTheme.colorScheme.errorContainer) {
                TextButton(onClick = { viewModel.save(file.path, force = true) }) { Text(stringResource(R.string.cd_overwrite)) }
                TextButton(onClick = { viewModel.reload(file.path) }) { Text(stringResource(R.string.cd_reload)) }
            }
            if (file.generated) Banner(stringResource(R.string.cd_generated), MaterialTheme.colorScheme.tertiaryContainer)
            if (showSearch) SearchBar(editor, file.path, editTick, viewModel, onClose = { showSearch = false; editor?.setMatches(emptyList(), -1) })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(file.path, file.revision) {
                    AndroidView(
                        factory = { ctx ->
                            CodeEditText(ctx, viewModel.history(file.path)!!).apply {
                                language = file.language
                                this.colors = colors
                                setEditorTextSize(state.fontSizeSp.toFloat())
                                load(viewModel.text(file.path).orEmpty(), viewModel.history(file.path)!!)
                                onEdited = { t -> viewModel.onEdited(file.path, t); editTick++ }
                                onHistoryChanged = { historyTick++ }
                                contentDescription = file.path
                            }
                        },
                        update = { v ->
                            editor = v
                            if (v.colors != colors) v.colors = colors
                            v.setEditorTextSize(state.fontSizeSp.toFloat())
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (showProblems) Problems(file.diagnostics) { d ->
                editor?.let { e -> e.goTo(TextPositions.lineStart(e.text ?: "", d.line) + d.column - 1) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showGoTo) GoToLineDialog(onDismiss = { showGoTo = false }) { line ->
        showGoTo = false
        editor?.let { e -> e.goTo(TextPositions.lineStart(e.text ?: "", line)) }
    }
    confirmClose?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmClose = null },
            title = { Text(stringResource(R.string.cd_close_title, p.substringAfterLast('/'))) },
            text = { Text(stringResource(R.string.cd_close_body)) },
            confirmButton = { TextButton(onClick = { confirmClose = null; viewModel.saveAndClose(p) }) { Text(stringResource(R.string.cd_save)) } },
            dismissButton = { TextButton(onClick = { confirmClose = null; viewModel.close(p) }) { Text(stringResource(R.string.cd_discard), color = MaterialTheme.colorScheme.error) } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.cd_leave_title)) },
            text = { Text(stringResource(R.string.cd_leave_body)) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; viewModel.saveAllThen(onExit) }) { Text(stringResource(R.string.cd_save)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; viewModel.discardAll(); onExit() }) { Text(stringResource(R.string.cd_discard), color = MaterialTheme.colorScheme.error) } },
        )
    }
}

@Composable
private fun editorColors(): EditorColors {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    return remember(cs, dark) {
        if (dark) EditorColors(
            text = cs.onSurface.toArgb(), background = cs.surface.toArgb(), gutter = cs.surfaceContainer.toArgb(), gutterText = cs.onSurfaceVariant.toArgb(),
            keyword = 0xFFE7A35B.toInt(), type = 0xFF8FB8F0.toInt(), string = 0xFF9CCC8A.toInt(), number = 0xFF7FD3D3.toInt(),
            comment = 0xFF8A8F98.toInt(), annotation = 0xFFD7BA7D.toInt(), tag = 0xFF8FB8F0.toInt(), attribute = 0xFFE7A35B.toInt(),
            punctuation = cs.onSurfaceVariant.toArgb(), match = 0x553F6EC4, currentMatch = 0xAA3F6EC4.toInt(),
        ) else EditorColors(
            text = cs.onSurface.toArgb(), background = cs.surface.toArgb(), gutter = cs.surfaceContainer.toArgb(), gutterText = cs.onSurfaceVariant.toArgb(),
            keyword = 0xFF9A3D00.toInt(), type = 0xFF1A4199.toInt(), string = 0xFF136B50.toInt(), number = 0xFF00696B.toInt(),
            comment = 0xFF6B6F78.toInt(), annotation = 0xFF7A5C00.toInt(), tag = 0xFF1A4199.toInt(), attribute = 0xFF9A3D00.toInt(),
            punctuation = cs.onSurfaceVariant.toArgb(), match = 0x553F6EC4, currentMatch = 0x993F6EC4.toInt(),
        )
    }
}

@Composable
private fun FileTabs(state: CodeUiState, onSelect: (String) -> Unit, onClose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        state.files.forEach { f ->
            val selected = f.path == state.current
            Row(
                Modifier.padding(Spacing.xxs)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.small)
                    .clickable { onSelect(f.path) }
                    .padding(start = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (f.dirty) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = Spacing.xs))
                BiIconButton(BiIcons.Close, stringResource(R.string.cd_close_tab, f.path.substringAfterLast('/')), { onClose(f.path) })
            }
        }
    }
}

@Composable
private fun Toolbar(file: OpenFile, state: CodeUiState, editor: CodeEditText?, historyTick: Int, vm: CodeViewModel, onSearch: () -> Unit, onGoTo: () -> Unit, onProblems: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") historyTick // read so undo/redo enabled state recomposes
    val history = vm.history(file.path)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            BiIconButton(BiIcons.Undo, stringResource(R.string.cd_undo), { editor?.undo() }, enabled = history?.canUndo == true)
            BiIconButton(BiIcons.Redo, stringResource(R.string.cd_redo), { editor?.redo() }, enabled = history?.canRedo == true)
            BiIconButton(BiIcons.Search, stringResource(R.string.cd_find), onSearch)
            TextButton(onClick = onGoTo) { Text(stringResource(R.string.cd_go_to_line)) }
            TextButton(onClick = onProblems) {
                Icon(BiIcons.Warning, null, tint = if (file.diagnostics.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                Text(" ${file.diagnostics.size}")
            }
            Text(file.language.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.sm))
        }
        val status = when {
            state.saving -> stringResource(R.string.cd_saving)
            file.dirty -> stringResource(R.string.cd_unsaved)
            else -> stringResource(R.string.cd_saved)
        }
        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            modifier = Modifier.padding(start = Spacing.sm).widthIn(max = 96.dp))
        BiIconButton(BiIcons.Save, stringResource(R.string.cd_save), { vm.save(file.path) }, enabled = file.dirty && !state.saving)
    }
}

@Composable
private fun Banner(text: String, color: Color, actions: @Composable () -> Unit = {}) {
    Surface(color = color, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
private fun SearchBar(editor: CodeEditText?, path: String, editTick: Int, vm: CodeViewModel, onClose: () -> Unit) {
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(-1) }
    var version by remember { mutableIntStateOf(0) }
    val query = SearchQuery(find, caseSensitive, wholeWord, regex)
    val text = vm.text(path).orEmpty()
    val matches = remember(query, text, version, editTick) { Search.findAll(text, query) }
    LaunchedEffect(matches) {
        current = if (matches.isEmpty()) -1 else Search.next(matches, editor?.selectionStart ?: 0)
        editor?.setMatches(matches, current)
    }
    fun go(i: Int) { current = i; editor?.setMatches(matches, i); if (i >= 0) editor?.select(matches[i]) }

    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(find, { find = it }, placeholder = { Text(stringResource(R.string.cd_find)) }, singleLine = true,
                    isError = !Search.isValid(query), textStyle = CodeTypography.body, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (matches.isNotEmpty()) go(Search.next(matches, (matches.getOrNull(current)?.last ?: -1) + 1)) }))
                Text(if (matches.isEmpty()) "0" else "${current + 1}/${matches.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.sm))
                BiIconButton(BiIcons.MoveUp, stringResource(R.string.cd_previous), { if (matches.isNotEmpty()) go(Search.previous(matches, matches.getOrNull(current)?.first ?: 0)) }, enabled = matches.isNotEmpty())
                BiIconButton(BiIcons.MoveDown, stringResource(R.string.cd_next), { if (matches.isNotEmpty()) go(Search.next(matches, (matches.getOrNull(current)?.last ?: -1) + 1)) }, enabled = matches.isNotEmpty())
                BiIconButton(BiIcons.Close, stringResource(R.string.cd_close), onClose)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(replace, { replace = it }, placeholder = { Text(stringResource(R.string.cd_replace_with)) }, singleLine = true,
                    textStyle = CodeTypography.body, modifier = Modifier.weight(1f))
                TextButton(enabled = current >= 0, onClick = {
                    val m = matches.getOrNull(current) ?: return@TextButton
                    editor?.replaceRange(m.first, m.last + 1, Search.replacement(text, m, query, replace))
                    version++
                }) { Text(stringResource(R.string.cd_replace)) }
                TextButton(enabled = matches.isNotEmpty(), onClick = {
                    val e = editor ?: return@TextButton
                    // Replace from the end so earlier offsets stay valid; each replacement is undoable.
                    matches.asReversed().forEach { m -> e.replaceRange(m.first, m.last + 1, Search.replacement(text, m, query, replace)) }
                    version++
                }) { Text(stringResource(R.string.cd_replace_all)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(caseSensitive, { caseSensitive = !caseSensitive }, label = { Text(stringResource(R.string.cd_match_case)) }, modifier = Modifier.padding(end = Spacing.xs))
                FilterChip(wholeWord, { wholeWord = !wholeWord }, label = { Text(stringResource(R.string.cd_whole_word)) }, modifier = Modifier.padding(end = Spacing.xs))
                FilterChip(regex, { regex = !regex }, label = { Text(stringResource(R.string.cd_regex)) })
            }
        }
    }
}

@Composable
private fun Problems(diagnostics: List<EditorDiagnostic>, onClick: (EditorDiagnostic) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        if (diagnostics.isEmpty()) {
            Text(stringResource(R.string.cd_no_problems), modifier = Modifier.padding(Spacing.md), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                items(diagnostics) { d ->
                    Row(Modifier.fillMaxWidth().clickable { onClick(d) }.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Icon(BiIcons.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Text("${d.line}:${d.column}", style = CodeTypography.small, modifier = Modifier.padding(horizontal = Spacing.sm))
                        Text(diagnosticText(d), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun diagnosticText(d: EditorDiagnostic): String = when (d.code) {
    "unterminated_string" -> stringResource(R.string.cd_diag_unterminated_string)
    "unterminated_comment" -> stringResource(R.string.cd_diag_unterminated_comment)
    "unmatched_close" -> stringResource(R.string.cd_diag_unmatched_close, d.detail)
    "mismatched" -> stringResource(R.string.cd_diag_mismatched, d.detail)
    "unclosed" -> stringResource(R.string.cd_diag_unclosed, d.detail)
    "xml" -> stringResource(R.string.cd_diag_xml, d.detail)
    "json" -> stringResource(R.string.cd_diag_json, d.detail)
    else -> d.code
}

@Composable
private fun GoToLineDialog(onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cd_go_to_line)) },
        text = {
            OutlinedTextField(text, { text = it.filter(Char::isDigit) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.cd_line_number)) })
        },
        confirmButton = { TextButton(enabled = text.toIntOrNull() != null, onClick = { onGo(text.toInt()) }) { Text(stringResource(R.string.cd_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cd_cancel)) } },
    )
}
