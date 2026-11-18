package dev.buildit.feature.libraries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.deps.LibraryHit
import dev.buildit.engine.deps.Versions
import dev.buildit.engine.project.LibraryDependency
import java.util.Locale

/**
 * Libraries tab: the project's libraries (on/off, version, remove), everything the build includes
 * with who needs it, and search across Google Maven and Maven Central.
 */
@Composable
fun LibrariesScreen(viewModel: LibrariesViewModel) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var showIncluded by rememberSaveable { mutableStateOf(false) }
    val installed = s.dependencies.map { it.module }.toSet()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxWidth(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { AppCompatCard(s.appCompat, enabled = s.progress == null && !s.loading, onChange = viewModel::setAppCompat) }
            item { Text(stringResource(R.string.lb_project_title), style = MaterialTheme.typography.titleMedium) }
            if (s.loading) item { CircularProgressIndicator(Modifier.size(24.dp)) }
            else if (s.dependencies.isEmpty()) item {
                Text(stringResource(R.string.lb_project_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(s.dependencies, key = { "dep-" + it.module }) { d -> DependencyCard(d, s.progress == null, viewModel) }

            s.progress?.let { p ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.lb_working, p), style = CodeTypography.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.lb_cancel)) }
                    }
                }
            }
            if (s.problems.isNotEmpty()) item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.lb_problems), style = MaterialTheme.typography.titleSmall)
                        s.problems.take(10).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.lb_ok)) }
                    }
                }
            }
            if (s.included.isNotEmpty()) item {
                val mb = String.format(Locale.ROOT, "%.1f", s.included.sumOf { it.sizeBytes } / 1_048_576.0)
                Row(Modifier.fillMaxWidth().clickable { showIncluded = !showIncluded }.padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.lb_included_title, s.included.size, mb), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(if (showIncluded) "▴" else "▾", style = MaterialTheme.typography.titleSmall)
                }
            }
            if (showIncluded) items(s.included, key = { "inc-" + it.coordinate }) { l ->
                Column(Modifier.fillMaxWidth().padding(start = Spacing.sm)) {
                    Text(l.coordinate, style = CodeTypography.small)
                    Text(
                        if (l.direct) stringResource(R.string.lb_included_direct) else stringResource(R.string.lb_included_by, l.requestedBy.joinToString { it.substringAfter(':') }),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (s.dependencies.any { it.enabled } && s.progress == null) item {
                OutlinedButton(onClick = viewModel::refresh) { Icon(BiIcons.Refresh, null); Text(stringResource(R.string.lb_refresh), Modifier.padding(start = Spacing.sm)) }
            }

            item { HorizontalDivider(Modifier.padding(vertical = Spacing.sm)) }
            item {
                OutlinedTextField(
                    value = s.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text(stringResource(R.string.lb_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                    trailingIcon = {
                        if (s.searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = viewModel::search) { Icon(BiIcons.Search, stringResource(R.string.lb_search)) }
                    },
                )
            }
            item {
                Text(
                    stringResource(if (s.query.isBlank() || s.results === dev.buildit.engine.deps.LibrarySearch.popular) R.string.lb_popular else R.string.lb_results),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            s.searchErrors.forEach { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            if (s.results.isEmpty() && !s.searching) item { Text(stringResource(R.string.lb_no_results), style = MaterialTheme.typography.bodySmall) }
            items(s.results, key = { "hit-" + it.module }) { hit -> HitRow(hit, hit.module in installed, enabled = s.progress == null) { viewModel.choose(hit.group, hit.artifact) } }

            item {
                Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)) {
                    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.lb_limits_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.lb_limits_body), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    s.choice?.let { c -> VersionDialog(c, viewModel) }
    s.message?.let { m ->
        val text = when (m) {
            is LibrariesMessage.Added -> stringResource(R.string.lb_msg_added, m.coordinate, m.total, String.format(Locale.ROOT, "%.1f", m.bytes / 1_048_576.0))
            is LibrariesMessage.Removed -> stringResource(R.string.lb_msg_removed, m.module)
            is LibrariesMessage.GradleFileKept -> stringResource(R.string.lb_msg_gradle_kept, m.path)
            is LibrariesMessage.Failed -> stringResource(R.string.lb_msg_failed, m.text)
            is LibrariesMessage.AppCompatChanged -> listOfNotNull(
                stringResource(if (m.on) R.string.lb_appcompat_on_done else R.string.lb_appcompat_off_done),
                m.updated.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.lb_appcompat_updated, it.joinToString()) },
                m.needsChange.takeIf { it.isNotEmpty() }?.let {
                    stringResource(if (m.on) R.string.lb_appcompat_change_to_compat else R.string.lb_appcompat_change_to_plain, it.joinToString())
                },
                m.keptFiles.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.lb_appcompat_kept, it.joinToString()) },
            ).joinToString("\n\n")
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(text) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.lb_ok)) } },
        )
    }
}

/** The AppCompat and Material switch, like Sketchware's "AppCompat and Design" in its Library Manager. */
@Composable
private fun AppCompatCard(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(R.string.lb_appcompat_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.lb_appcompat_body), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = on, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.padding(start = Spacing.sm))
        }
    }
}

@Composable
private fun DependencyCard(d: LibraryDependency, idle: Boolean, vm: LibrariesViewModel) {
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.module.substringAfter(':'), style = MaterialTheme.typography.titleSmall)
                    Text(d.coordinate, style = CodeTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = d.enabled, onCheckedChange = { vm.setEnabled(d.module, it) }, enabled = idle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = { vm.choose(d.module.substringBefore(':'), d.module.substringAfter(':')) }, enabled = idle) { Text(stringResource(R.string.lb_change_version)) }
                TextButton(onClick = { vm.remove(d.module) }, enabled = idle) {
                    Icon(BiIcons.Delete, null, Modifier.size(18.dp)); Text(stringResource(R.string.lb_remove), Modifier.padding(start = Spacing.xs))
                }
            }
        }
    }
}

@Composable
private fun HitRow(hit: LibraryHit, added: Boolean, enabled: Boolean, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(hit.artifact, style = MaterialTheme.typography.bodyLarge)
            Text(listOfNotNull(hit.group, hit.latest).joinToString("  ·  "), style = CodeTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hit.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (added) Text(stringResource(R.string.lb_added_badge), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        else Button(onClick = onAdd, enabled = enabled) { Text(stringResource(R.string.lb_add)) }
    }
}

@Composable
private fun VersionDialog(c: VersionChoice, vm: LibrariesViewModel) {
    AlertDialog(
        onDismissRequest = vm::dismissChoice,
        title = { Text(stringResource(R.string.lb_choose_version, c.artifact)) },
        text = {
            when {
                c.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.lb_loading_versions), Modifier.padding(start = Spacing.sm))
                }
                c.error != null -> Text(stringResource(R.string.lb_versions_failed, c.error), color = MaterialTheme.colorScheme.error)
                else -> Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    c.versions.take(60).forEach { v ->
                        Row(Modifier.fillMaxWidth().clickable { vm.add(c.group, c.artifact, v) }.padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(v, style = CodeTypography.body, modifier = Modifier.weight(1f))
                            if (!Versions.isStable(v)) Text(stringResource(R.string.lb_prerelease), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::dismissChoice) { Text(stringResource(R.string.lb_cancel)) } },
    )
}
