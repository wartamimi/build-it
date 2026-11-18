package dev.buildit.feature.home

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.component.BiButton
import dev.buildit.core.designsystem.component.BiButtonStyle
import dev.buildit.core.designsystem.component.BiCard
import dev.buildit.core.designsystem.component.BiEmptyState
import dev.buildit.core.designsystem.component.BiLoadingState
import dev.buildit.core.designsystem.component.BiSectionHeader
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.core.model.ProjectSummary
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNewProject: () -> Unit,
    onImportProject: () -> Unit,
    onOpenProject: (String) -> Unit,
    onAllProjects: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.projectCount) { viewModel.refreshStorage() }
    Scaffold { padding ->
        if (state.loading) {
            BiLoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Header()
                QuickActions(onNewProject, onImportProject)
                BiSectionHeader(
                    stringResource(R.string.home_recent),
                    trailing = if (state.projectCount > 0) {
                        { BiButton(stringResource(R.string.home_all_projects, state.projectCount), onAllProjects, style = BiButtonStyle.Text) }
                    } else null,
                )
                when {
                    state.recent.isNotEmpty() -> Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        state.recent.forEach { RecentProject(it) { onOpenProject(it.id) } }
                    }
                    state.projectCount > 0 -> Text(
                        stringResource(R.string.home_recent_none_opened),
                        Modifier.padding(horizontal = Spacing.lg),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> BiEmptyState(BiIcons.Logo, stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body)) {
                        BiButton(stringResource(R.string.home_new_project), onNewProject, icon = BiIcons.Add)
                    }
                }
                state.storage?.let { StorageCard(it.workspaceBytes, it.freeBytes) }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        Icon(BiIcons.Logo, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Column(Modifier.padding(start = Spacing.md)) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickActions(onNew: () -> Unit, onImport: () -> Unit) {
    FlowRow(
        Modifier.padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        BiButton(stringResource(R.string.home_new_project), onNew, icon = BiIcons.Add)
        BiButton(stringResource(R.string.home_import_project), onImport, style = BiButtonStyle.Tonal, icon = BiIcons.Import)
    }
}

@Composable
private fun RecentProject(p: ProjectSummary, onClick: () -> Unit) {
    BiCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Text(p.name, style = MaterialTheme.typography.titleMedium)
        Text(p.packageName, style = CodeTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        p.lastOpenedAt?.let {
            Text(
                stringResource(R.string.home_opened, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun StorageCard(used: Long, free: Long) {
    val context = LocalContext.current
    BiCard(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
        Text(stringResource(R.string.home_storage), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.home_storage_detail, Formatter.formatShortFileSize(context, used), Formatter.formatShortFileSize(context, free)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}
