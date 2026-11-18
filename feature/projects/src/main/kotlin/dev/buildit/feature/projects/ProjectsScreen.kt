package dev.buildit.feature.projects

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.component.BiButton
import dev.buildit.core.designsystem.component.BiCard
import dev.buildit.core.designsystem.component.BiConfirmDialog
import dev.buildit.core.designsystem.component.BiEmptyState
import dev.buildit.core.designsystem.component.BiIconButton
import dev.buildit.core.designsystem.component.BiLoadingState
import dev.buildit.core.designsystem.component.BiTextField
import dev.buildit.core.designsystem.component.BiTopBar
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.core.model.ProjectSummary
import dev.buildit.engine.project.ProjectValidation
import java.text.DateFormat
import java.util.Date

/** What the screen should do as soon as it opens (from dashboard quick actions). */
enum class ProjectsStartAction { None, Create, Import }

@Composable
fun ProjectsScreen(
    viewModel: ProjectsViewModel,
    onOpenProject: (String) -> Unit,
    startAction: ProjectsStartAction = ProjectsStartAction.None,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backups by viewModel.backups.collectAsStateWithLifecycle()
    var restoreTarget by remember { mutableStateOf<java.io.File?>(null) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showCreate by rememberSaveable { mutableStateOf(startAction == ProjectsStartAction.Create) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var duplicateTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var exportTarget by remember { mutableStateOf<ProjectSummary?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.import { context.contentResolver.openInputStream(uri) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val target = exportTarget
        if (uri != null && target != null) viewModel.export(target.id) { context.contentResolver.openOutputStream(uri) }
        exportTarget = null
    }
    LaunchedEffect(startAction) {
        if (startAction == ProjectsStartAction.Import) importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
    }

    val msgCreated = stringResource(R.string.projects_msg_created)
    val msgImported = stringResource(R.string.projects_msg_imported)
    val msgExported = stringResource(R.string.projects_msg_exported)
    val msgBackup = stringResource(R.string.projects_msg_backup)
    val msgDeleted = stringResource(R.string.projects_msg_deleted)
    val msgFailed = stringResource(R.string.projects_msg_failed)
    val msgRestored = stringResource(R.string.projects_msg_restored)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { m ->
            snackbar.showSnackbar(
                when (m) {
                    is ProjectsMessage.Created -> msgCreated.format(m.name)
                    is ProjectsMessage.Imported -> msgImported.format(m.name)
                    ProjectsMessage.Exported -> msgExported
                    is ProjectsMessage.BackedUp -> msgBackup.format(m.fileName)
                    ProjectsMessage.Deleted -> msgDeleted
                    ProjectsMessage.Restored -> msgRestored
                    is ProjectsMessage.Failed -> msgFailed.format(m.detail)
                },
            )
        }
    }
    LaunchedEffect(viewModel) { viewModel.openRequests.collect(onOpenProject) }

    Scaffold(
        topBar = {
            BiTopBar(
                title = stringResource(R.string.projects_title),
                actions = {
                    BiIconButton(BiIcons.Search, stringResource(R.string.projects_search), { searching = !searching; if (!searching) viewModel.setQuery("") })
                    BiIconButton(BiIcons.Import, stringResource(R.string.projects_import), { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) })
                    Box {
                        BiIconButton(BiIcons.More, stringResource(R.string.projects_sort), { sortMenu = true })
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            ProjectSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(s.label())) },
                                    leadingIcon = { if (s == state.sort) Icon(BiIcons.Check, null) },
                                    onClick = { viewModel.setSort(s); sortMenu = false },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(BiIcons.Add, null) },
                text = { Text(stringResource(R.string.projects_new)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (searching) {
                BiTextField(
                    state.query, viewModel::setQuery, stringResource(R.string.projects_search_hint),
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
            when {
                state.loading -> BiLoadingState()
                state.projects.isEmpty() && state.query.isNotEmpty() -> BiEmptyState(
                    BiIcons.Search, stringResource(R.string.projects_no_match_title), stringResource(R.string.projects_no_match_body, state.query),
                )
                state.projects.isEmpty() -> BiEmptyState(
                    BiIcons.Folder, stringResource(R.string.projects_empty_title), stringResource(R.string.projects_empty_body),
                ) { BiButton(stringResource(R.string.projects_new), { showCreate = true }, icon = BiIcons.Add) }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = 96.dpBottom),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(state.projects, key = { it.id }) { p ->
                        ProjectRow(
                            p,
                            modifier = Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxWidth(),
                            onOpen = { onOpenProject(p.id) },
                            onRename = { renameTarget = p },
                            onDuplicate = { duplicateTarget = p },
                            onExport = { exportTarget = p; exportLauncher.launch("${p.name}.buildit.zip") },
                            onBackup = { viewModel.backup(p.id) },
                            onRestore = { viewModel.showBackups(p) },
                            onDelete = { deleteTarget = p },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateProjectSheet(
            validate = viewModel::validateNew,
            onCreate = { n, pkg, t -> showCreate = false; viewModel.create(n, pkg, t) },
            onDismiss = { showCreate = false },
        )
    }
    renameTarget?.let { p ->
        NameDialog(stringResource(R.string.projects_rename), p.name, { renameTarget = null }) { viewModel.rename(p.id, it); renameTarget = null }
    }
    duplicateTarget?.let { p ->
        NameDialog(stringResource(R.string.projects_duplicate), stringResource(R.string.projects_copy_of, p.name), { duplicateTarget = null }) {
            viewModel.duplicate(p.id, it); duplicateTarget = null
        }
    }
    backups?.let { list ->
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        AlertDialog(
            onDismissRequest = viewModel::hideBackups,
            title = { Text(stringResource(R.string.projects_restore_title, list.project.name)) },
            text = {
                if (list.files.isEmpty()) {
                    Text(stringResource(R.string.projects_restore_none))
                } else {
                    Column {
                        list.files.forEach { f ->
                            val time = f.name.removePrefix("backup-").removeSuffix(".zip").toLongOrNull()
                            TextButton(onClick = { restoreTarget = f }, modifier = Modifier.fillMaxWidth()) {
                                Text(time?.let { fmt.format(Date(it)) } ?: f.name, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::hideBackups) { Text(stringResource(R.string.projects_cancel)) } },
        )
        restoreTarget?.let { f ->
            BiConfirmDialog(
                title = stringResource(R.string.projects_restore_confirm_title),
                message = stringResource(R.string.projects_restore_confirm_body),
                confirmLabel = stringResource(R.string.projects_restore),
                dismissLabel = stringResource(R.string.projects_cancel),
                onConfirm = { restoreTarget = null; viewModel.restore(list.project.id, f) },
                onDismiss = { restoreTarget = null },
            )
        }
    }
    deleteTarget?.let { p ->
        BiConfirmDialog(
            title = stringResource(R.string.projects_delete_title, p.name),
            message = stringResource(R.string.projects_delete_body),
            confirmLabel = stringResource(R.string.projects_delete),
            dismissLabel = stringResource(R.string.projects_cancel),
            onConfirm = { viewModel.delete(p.id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
            destructive = true,
        )
    }
}

private val Int.dpBottom get() = androidx.compose.ui.unit.Dp(this.toFloat())

private fun ProjectSort.label() = when (this) {
    ProjectSort.Updated -> R.string.projects_sort_updated
    ProjectSort.Name -> R.string.projects_sort_name
    ProjectSort.Created -> R.string.projects_sort_created
}

@Composable
private fun ProjectRow(
    p: ProjectSummary,
    modifier: Modifier,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    BiCard(modifier, onClick = onOpen) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Text(p.packageName, style = CodeTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.projects_updated, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.updatedAt))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            Box {
                BiIconButton(BiIcons.More, stringResource(R.string.projects_actions_for, p.name), { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    MenuItem(R.string.projects_rename, BiIcons.Edit) { menu = false; onRename() }
                    MenuItem(R.string.projects_duplicate, BiIcons.Duplicate) { menu = false; onDuplicate() }
                    MenuItem(R.string.projects_export, BiIcons.Export) { menu = false; onExport() }
                    MenuItem(R.string.projects_backup, BiIcons.History) { menu = false; onBackup() }
                    MenuItem(R.string.projects_restore, BiIcons.Refresh) { menu = false; onRestore() }
                    MenuItem(R.string.projects_delete, BiIcons.Delete) { menu = false; onDelete() }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val issue = ProjectValidation.validateName(value).firstOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { BiTextField(value, { value = it }, stringResource(R.string.projects_field_name), error = issue?.message()) },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }, enabled = issue == null) { Text(stringResource(R.string.projects_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.projects_cancel)) } },
    )
}
