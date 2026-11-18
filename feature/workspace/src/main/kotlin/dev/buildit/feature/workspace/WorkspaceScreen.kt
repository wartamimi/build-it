package dev.buildit.feature.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.component.BiButton
import dev.buildit.core.designsystem.component.BiButtonStyle
import dev.buildit.core.designsystem.component.BiConfirmDialog
import dev.buildit.core.designsystem.component.BiEmptyState
import dev.buildit.core.designsystem.component.BiErrorState
import dev.buildit.core.designsystem.component.BiIconButton
import dev.buildit.core.designsystem.component.BiLoadingState
import dev.buildit.core.designsystem.component.BiSectionHeader
import dev.buildit.core.designsystem.component.BiTextField
import dev.buildit.core.designsystem.component.BiTopBar
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.project.ProjectEntry
import dev.buildit.engine.project.ValidationIssue

@Composable
fun WorkspaceScreen(viewModel: WorkspaceViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val msgSaved = stringResource(R.string.ws_saved)
    val msgFailed = stringResource(R.string.ws_failed)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { m ->
            snackbar.showSnackbar(when (m) {
                WorkspaceMessage.Saved -> msgSaved
                is WorkspaceMessage.Failed -> msgFailed.format(m.detail)
            })
        }
    }
    var confirmLeave by remember { mutableStateOf(false) }
    val leave = { if (state.dirty) confirmLeave = true else onBack() }
    BackHandler(enabled = state.dirty) { confirmLeave = true }

    Scaffold(
        topBar = {
            BiTopBar(
                title = state.project?.manifest?.name ?: stringResource(R.string.ws_title),
                subtitle = state.project?.manifest?.app?.packageName,
                onBack = leave,
                backLabel = stringResource(R.string.ws_back),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> BiLoadingState()
                state.error != null -> BiErrorState(stringResource(R.string.ws_open_failed), state.error!!, retryLabel = stringResource(R.string.ws_retry), onRetry = { viewModel.load() })
                else -> {
                    PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                        WorkspaceTab.entries.forEach { t ->
                            Tab(selected = state.tab == t, onClick = { viewModel.selectTab(t) }, text = {
                                Text(stringResource(if (t == WorkspaceTab.Project) R.string.ws_tab_project else R.string.ws_tab_files))
                            })
                        }
                    }
                    when (state.tab) {
                        WorkspaceTab.Project -> ProjectSettingsTab(state, viewModel)
                        WorkspaceTab.Files -> FilesTab(state.files, viewModel)
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        BiConfirmDialog(
            title = stringResource(R.string.ws_unsaved_title),
            message = stringResource(R.string.ws_unsaved_body),
            confirmLabel = stringResource(R.string.ws_discard),
            dismissLabel = stringResource(R.string.ws_keep_editing),
            onConfirm = { confirmLeave = false; viewModel.discardDraft(); onBack() },
            onDismiss = { confirmLeave = false },
            destructive = true,
        )
    }
    if (state.pendingConflicts.isNotEmpty()) {
        BiConfirmDialog(
            title = stringResource(R.string.ws_conflict_title),
            message = stringResource(R.string.ws_conflict_body, state.pendingConflicts.joinToString("\n")),
            confirmLabel = stringResource(R.string.ws_conflict_overwrite),
            dismissLabel = stringResource(R.string.ws_conflict_keep),
            onConfirm = { viewModel.dismissConflicts(); viewModel.save(force = true) },
            onDismiss = viewModel::dismissConflicts,
            destructive = true,
        )
    }
}

@Composable
private fun ProjectSettingsTab(state: WorkspaceUiState, vm: WorkspaceViewModel) {
    val d = state.draft ?: return
    fun issue(field: String): ValidationIssue? = state.issues.firstOrNull { it.field == field }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            BiSectionHeader(stringResource(R.string.ws_section_app), Modifier.padding(horizontal = 0.dp))
            BiTextField(d.name, { v -> vm.editDraft { it.copy(name = v) } }, stringResource(R.string.ws_field_name), error = issue("name")?.text())
            BiTextField(d.packageName, { v -> vm.editDraft { it.copy(packageName = v) } }, stringResource(R.string.ws_field_package),
                error = issue("packageName")?.text(), supporting = stringResource(R.string.ws_field_package_hint), monospace = true)
            BiSectionHeader(stringResource(R.string.ws_section_version))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BiTextField(d.versionCode, { v -> vm.editDraft { it.copy(versionCode = v.filter(Char::isDigit)) } }, stringResource(R.string.ws_field_version_code),
                    Modifier.weight(1f), error = issue("versionCode")?.text(), keyboardType = KeyboardType.Number)
                BiTextField(d.versionName, { v -> vm.editDraft { it.copy(versionName = v) } }, stringResource(R.string.ws_field_version_name),
                    Modifier.weight(1f), error = issue("versionName")?.text())
            }
            BiSectionHeader(stringResource(R.string.ws_section_sdk))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BiTextField(d.minSdk, { v -> vm.editDraft { it.copy(minSdk = v.filter(Char::isDigit)) } }, stringResource(R.string.ws_field_min_sdk),
                    Modifier.weight(1f), error = issue("minSdk")?.text(), keyboardType = KeyboardType.Number)
                BiTextField(d.targetSdk, { v -> vm.editDraft { it.copy(targetSdk = v.filter(Char::isDigit)) } }, stringResource(R.string.ws_field_target_sdk),
                    Modifier.weight(1f), error = issue("targetSdk")?.text(), keyboardType = KeyboardType.Number)
                BiTextField(d.compileSdk, { v -> vm.editDraft { it.copy(compileSdk = v.filter(Char::isDigit)) } }, stringResource(R.string.ws_field_compile_sdk),
                    Modifier.weight(1f), keyboardType = KeyboardType.Number)
            }
            issue("number")?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().padding(top = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
                BiButton(stringResource(R.string.ws_discard_changes), vm::discardDraft, style = BiButtonStyle.Text, enabled = state.dirty)
                BiButton(stringResource(R.string.ws_save), { vm.save() }, enabled = state.dirty && state.issues.isEmpty(), loading = state.saving)
            }
            Text(
                stringResource(R.string.ws_generated_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

@Composable
private fun ValidationIssue.text(): String = when (field to code) {
    "name" to "empty" -> stringResource(R.string.ws_err_required)
    "name" to "invalid_chars" -> stringResource(R.string.ws_err_name_chars)
    "packageName" to "keyword" -> stringResource(R.string.ws_err_pkg_keyword, detail)
    "packageName" to "empty" -> stringResource(R.string.ws_err_required)
    "packageName" to "too_few_segments", "packageName" to "empty_segment", "packageName" to "invalid_segment" -> stringResource(R.string.ws_err_pkg_format)
    "minSdk" to "below_supported" -> stringResource(R.string.ws_err_min_sdk_low, detail)
    "minSdk" to "above_target" -> stringResource(R.string.ws_err_min_above_target)
    "targetSdk" to "above_compile" -> stringResource(R.string.ws_err_target_above_compile)
    "versionCode" to "not_positive" -> stringResource(R.string.ws_err_version_code)
    "versionName" to "empty" -> stringResource(R.string.ws_err_required)
    "number" to "not_a_number" -> stringResource(R.string.ws_err_number)
    else -> "$field: $code"
}

@Composable
private fun FilesTab(files: FilesState, vm: WorkspaceViewModel) {
    var newItem by remember { mutableStateOf<Boolean?>(null) } // true = folder, false = file
    var fabMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectEntry?>(null) }
    BackHandler(enabled = files.path.isNotEmpty() && files.openFile == null) { vm.up() }

    if (files.openFile != null) {
        FileViewer(files.openFile, files.openFileText.orEmpty(), vm::closeFile)
        return
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                BiIconButton(dev.buildit.core.designsystem.icon.BiIcons.Folder, stringResource(R.string.ws_files_up), vm::up, enabled = files.path.isNotEmpty())
                Text("/" + files.path, style = CodeTypography.small, modifier = Modifier.horizontalScroll(rememberScrollState()))
            }
            if (files.entries.isEmpty()) {
                BiEmptyState(BiIcons.Folder, stringResource(R.string.ws_files_empty_title), stringResource(R.string.ws_files_empty_body))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files.entries, key = { it.path }) { e ->
                        FileRow(e, protected = vm.isProtected(e),
                            onClick = { if (e.isDirectory) vm.browse(e.path) else vm.openFile(e) },
                            onRename = { renameTarget = e }, onDelete = { deleteTarget = e })
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(Spacing.lg)) {
            FloatingActionButton(onClick = { fabMenu = true }) { Icon(BiIcons.Add, stringResource(R.string.ws_files_new)) }
            DropdownMenu(fabMenu, { fabMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ws_files_new_file)) }, onClick = { fabMenu = false; newItem = false })
                DropdownMenuItem(text = { Text(stringResource(R.string.ws_files_new_folder)) }, onClick = { fabMenu = false; newItem = true })
            }
        }
    }
    newItem?.let { folder ->
        NameDialog(stringResource(if (folder) R.string.ws_files_new_folder else R.string.ws_files_new_file), "", { newItem = null }) {
            if (folder) vm.createFolder(it) else vm.createFile(it); newItem = null
        }
    }
    renameTarget?.let { e -> NameDialog(stringResource(R.string.ws_files_rename), e.name, { renameTarget = null }) { vm.rename(e, it); renameTarget = null } }
    deleteTarget?.let { e ->
        BiConfirmDialog(
            stringResource(R.string.ws_files_delete_title, e.name),
            stringResource(if (e.isDirectory) R.string.ws_files_delete_folder_body else R.string.ws_files_delete_file_body),
            stringResource(R.string.ws_files_delete), stringResource(R.string.ws_cancel),
            onConfirm = { vm.delete(e); deleteTarget = null }, onDismiss = { deleteTarget = null }, destructive = true,
        )
    }
}

@Composable
private fun FileRow(e: ProjectEntry, protected: Boolean, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { if (!protected) menu = true })
            .heightIn(min = 52.dp).padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (e.isDirectory) BiIcons.Folder else BiIcons.Edit, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = Spacing.md)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge)
            if (protected) Text(stringResource(R.string.ws_files_managed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!protected) {
            Box {
                BiIconButton(BiIcons.More, stringResource(R.string.ws_files_actions, e.name), { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ws_files_rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ws_files_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun FileViewer(path: String, text: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            BiIconButton(BiIcons.Close, stringResource(R.string.ws_close), onClose)
            Text(path, style = CodeTypography.small, modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.ws_viewer_readonly), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg))
        SelectionContainer {
            Text(
                text,
                style = CodeTypography.body,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(Spacing.lg),
            )
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { BiTextField(value, { value = it }, stringResource(R.string.ws_files_name), monospace = true) },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.ws_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ws_cancel)) } },
    )
}
