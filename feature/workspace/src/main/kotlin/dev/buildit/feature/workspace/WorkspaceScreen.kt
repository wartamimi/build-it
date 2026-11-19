package dev.buildit.feature.workspace

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.launch

@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    onBack: () -> Unit,
    extraTabs: List<WorkspaceExtraTab> = emptyList(),
    /** Shown under the main editor tabs (e.g. the screen picker and Run button). */
    bottomBar: (@Composable (WorkspaceTabScope) -> Unit)? = null,
) {
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
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val leave = { if (state.dirty) confirmLeave = true else onBack() }
    BackHandler(enabled = state.dirty) { confirmLeave = true }

    // Tabs: contributed ones first, then the built-in Project and Files tools.
    val allTabs = extraTabs + listOf(
        WorkspaceExtraTab(title = { stringResource(R.string.ws_tab_project) }, content = { ProjectSettingsTab(state, viewModel) }, icon = BiIcons.Settings, tool = true, key = PROJECT_TAB),
        WorkspaceExtraTab(title = { stringResource(R.string.ws_tab_files) }, content = { scope -> FilesTab(state.files, viewModel, openInTab = scope.openFile) }, icon = BiIcons.Folder, tool = true, key = FILES_TAB),
    )
    val primary = allTabs.indices.filter { !allTabs[it].tool }
    val current = selected.coerceIn(0, allTabs.lastIndex)
    var lastPrimary by rememberSaveable { mutableIntStateOf(primary.firstOrNull() ?: 0) }
    LaunchedEffect(current) { if (current in primary) lastPrimary = current }
    val onTool = current !in primary
    // Back from a tool returns to the editor it was opened from.
    BackHandler(enabled = onTool && !state.dirty) { selected = lastPrimary }

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scopeCoroutine = rememberCoroutineScope()
    val openInTab = { path: String ->
        val i = allTabs.indexOfFirst { it.opens?.invoke(path) == true }
        if (i >= 0) selected = i
        i >= 0
    }
    val showTab = { key: String ->
        val i = allTabs.indexOfFirst { it.key == key }
        if (i >= 0) selected = i
    }
    var fullScreen by remember { mutableStateOf(false) }
    val tabScope = WorkspaceTabScope(leave, openInTab, showTab, fullScreen = { fullScreen = it })

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
                Text(
                    stringResource(R.string.ws_tools), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                )
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    allTabs.forEachIndexed { i, t ->
                        if (!t.tool) return@forEachIndexed
                        NavigationDrawerItem(
                            label = { Text(t.title()) },
                            icon = t.icon?.let { ic -> { Icon(ic, null) } },
                            selected = i == current,
                            onClick = { selected = i; scopeCoroutine.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = Spacing.md),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (!fullScreen) BiTopBar(
                    title = if (onTool) allTabs[current].title() else state.project?.manifest?.name ?: stringResource(R.string.ws_title),
                    subtitle = if (onTool) state.project?.manifest?.name else state.project?.manifest?.app?.packageName,
                    // Route through the back dispatcher so a tab (e.g. the designer) can finish its work first.
                    onBack = { if (onTool) selected = lastPrimary else backDispatcher?.onBackPressed() ?: leave() },
                    backLabel = stringResource(if (onTool) R.string.ws_back_to_editor else R.string.ws_back),
                    actions = {
                        BiIconButton(BiIcons.Menu, stringResource(R.string.ws_tools), { scopeCoroutine.launch { drawer.open() } })
                    },
                )
            },
            bottomBar = { if (!fullScreen && !onTool && !state.loading && state.error == null) bottomBar?.invoke(tabScope) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                when {
                    state.loading -> BiLoadingState()
                    state.error != null -> BiErrorState(stringResource(R.string.ws_open_failed), state.error!!, retryLabel = stringResource(R.string.ws_retry), onRetry = { viewModel.load() })
                    else -> {
                        if (!fullScreen && !onTool && primary.size > 1) {
                            PrimaryTabRow(selectedTabIndex = primary.indexOf(current)) {
                                primary.forEach { i ->
                                    val t = allTabs[i]
                                    Tab(
                                        selected = current == i, onClick = { selected = i },
                                        text = { Text(t.title(), maxLines = 1) },
                                        icon = t.icon?.let { ic -> { Icon(ic, null) } },
                                    )
                                }
                            }
                        }
                        Box(Modifier.fillMaxSize()) { allTabs[current].content(tabScope) }
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

/**
 * What a contributed tab can ask of the workspace: [leave] it (after finishing its own work), or
 * [openFile] in whichever tab opens that file (returns false if none can), or [showTab] by its key.
 */
class WorkspaceTabScope(
    val leave: () -> Unit,
    val openFile: (path: String) -> Boolean,
    val showTab: (key: String) -> Unit = {},
    /** True hides the workspace's title bar, tabs and bottom bar (e.g. while one event is edited). */
    val fullScreen: (Boolean) -> Unit = {},
)

/** A tab contributed by another feature (e.g. the designer). Extra tabs come first. */
class WorkspaceExtraTab(
    val title: @Composable () -> String,
    val content: @Composable (WorkspaceTabScope) -> Unit,
    /** Opens a project file in this tab; returns false if the tab can't handle it. */
    val opens: ((path: String) -> Boolean)? = null,
    /** Lets other tabs switch to this one with [WorkspaceTabScope.showTab]. */
    val key: String? = null,
    val icon: ImageVector? = null,
    /** Tools live in the project tools drawer; the others are the main editor tabs. */
    val tool: Boolean = false,
)

const val PROJECT_TAB = "project"
const val FILES_TAB = "files"

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
            PermissionsSection(d.permissions) { list -> vm.editDraft { it.copy(permissions = list) } }
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

/** Manifest permissions: common ones as switches, any other by name. */
@Composable
private fun PermissionsSection(selected: List<String>, onChange: (List<String>) -> Unit) {
    BiSectionHeader(stringResource(R.string.ws_section_permissions))
    Text(stringResource(R.string.ws_permissions_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val all = (SettingsDraft.commonPermissions + selected).distinct()
    all.forEach { p ->
        val on = p in selected
        Row(
            Modifier.fillMaxWidth().toggleable(on, role = Role.Switch) { onChange(if (on) selected - p else selected + p) }.padding(vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(p.removePrefix("android.permission."), style = CodeTypography.small, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = null)
        }
    }
    var custom by remember { mutableStateOf("") }
    val name = custom.trim().let { if ('.' in it) it else "android.permission.$it" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BiTextField(custom, { custom = it }, stringResource(R.string.ws_permission_other), Modifier.weight(1f), monospace = true)
        TextButton(enabled = custom.isNotBlank() && SettingsDraft.isPermissionName(name) && name !in selected, onClick = { onChange(selected + name); custom = "" }) {
            Text(stringResource(R.string.ws_permission_add))
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
private fun FilesTab(files: FilesState, vm: WorkspaceViewModel, openInTab: (String) -> Boolean) {
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
                            onClick = {
                                when {
                                    e.isDirectory -> vm.browse(e.path)
                                    // Editable files open in the code editor; Build It's own metadata stays read-only here.
                                    !vm.isProtected(e) && openInTab(e.path) -> Unit
                                    else -> vm.openFile(e)
                                }
                            },
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
