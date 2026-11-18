package dev.buildit.feature.build

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.build.BuildDiagnostic
import dev.buildit.engine.build.BuildStep
import java.io.File
import java.util.Locale

/**
 * Build tab: platform status, "Build debug APK", live steps, problems that open the file at the
 * line, and the signed APK with Install and Share.
 */
@Composable
fun BuildScreen(
    viewModel: BuildViewModel,
    onOpenFile: (path: String, line: Int) -> Unit,
    onInstall: (File) -> Unit,
    onShare: (File) -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshPlatform() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item { PlatformCard(s.platform, viewModel) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = { viewModel.build() }, enabled = !s.running && s.platform is PlatformState.Ready) {
                        Icon(BiIcons.Build, null); Text(stringResource(R.string.bd_build_debug), Modifier.padding(start = Spacing.sm))
                    }
                    if (s.running) OutlinedButton(onClick = viewModel::cancel) { Text(stringResource(R.string.bd_cancel)) }
                }
                Text(stringResource(R.string.bd_saved_files_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
            }
            if (s.steps.isNotEmpty()) item { Steps(s.steps) }
            if (!s.running && s.steps.isNotEmpty()) item { ResultCard(s, onInstall, onShare) }
            val errors = s.diagnostics
            if (errors.isNotEmpty()) {
                item { Text(stringResource(R.string.bd_problems, errors.size), style = MaterialTheme.typography.titleSmall) }
                items(errors) { d -> DiagnosticRow(d, onOpenFile) }
            }
            if (s.log.isNotEmpty()) item {
                Text(s.log.joinToString("\n"), style = CodeTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    (s.platform as? PlatformState.License)?.let { l -> LicenseDialog(l, viewModel) }
}

@Composable
private fun PlatformCard(p: PlatformState, vm: BuildViewModel) {
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.bd_platform_title), style = MaterialTheme.typography.titleSmall)
            when (p) {
                PlatformState.Checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                is PlatformState.Ready -> Text(stringResource(R.string.bd_platform_ready, p.api), style = MaterialTheme.typography.bodyMedium)
                is PlatformState.Missing -> {
                    Text(stringResource(R.string.bd_platform_missing, p.api), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = vm::requestPlatform) { Text(stringResource(R.string.bd_platform_download)) }
                }
                is PlatformState.FetchingIndex -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.bd_platform_checking), Modifier.padding(start = Spacing.sm))
                }
                is PlatformState.License -> Text(stringResource(R.string.bd_platform_license_needed))
                is PlatformState.Downloading -> {
                    val fraction = if (p.total > 0) (p.read.toFloat() / p.total).coerceIn(0f, 1f) else 0f
                    Text(stringResource(R.string.bd_platform_downloading, p.api, mb(p.read), mb(p.total)))
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = vm::cancel) { Text(stringResource(R.string.bd_cancel)) }
                }
                is PlatformState.Failed -> {
                    Text(stringResource(R.string.bd_platform_failed, p.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = vm::requestPlatform) { Text(stringResource(R.string.bd_retry)) }
                }
            }
        }
    }
}

@Composable
private fun LicenseDialog(l: PlatformState.License, vm: BuildViewModel) {
    AlertDialog(
        onDismissRequest = vm::declineLicense,
        title = { Text(stringResource(R.string.bd_license_title, l.pkg.displayName)) },
        text = {
            Column {
                Text(stringResource(R.string.bd_license_body, mb(l.pkg.size)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = Spacing.sm))
                Text(l.text, style = CodeTypography.small, modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { TextButton(onClick = vm::acceptLicense) { Text(stringResource(R.string.bd_license_accept)) } },
        dismissButton = { TextButton(onClick = vm::declineLicense) { Text(stringResource(R.string.bd_license_decline)) } },
    )
}

@Composable
private fun Steps(steps: List<StepRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        steps.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    when (r.state) {
                        StepState.Running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        StepState.Done -> Icon(BiIcons.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        StepState.Failed -> Icon(BiIcons.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        StepState.Pending -> Unit
                    }
                }
                Text(stepName(r.step), Modifier.padding(start = Spacing.sm).weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (r.state == StepState.Pending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (r.state == StepState.Done) Text(stringResource(R.string.bd_ms, r.millis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun stepName(s: BuildStep) = stringResource(
    when (s) {
        BuildStep.Check -> R.string.bd_step_check
        BuildStep.Resources -> R.string.bd_step_resources
        BuildStep.Compile -> R.string.bd_step_compile
        BuildStep.Dex -> R.string.bd_step_dex
        BuildStep.Package -> R.string.bd_step_package
        BuildStep.Sign -> R.string.bd_step_sign
        BuildStep.Verify -> R.string.bd_step_verify
    },
)

@Composable
private fun ResultCard(s: BuildUiState, onInstall: (File) -> Unit, onShare: (File) -> Unit) {
    val apk = s.apk
    val color = when {
        apk != null -> MaterialTheme.colorScheme.primaryContainer
        s.cancelled -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.errorContainer
    }
    Surface(color = color, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            when {
                apk != null -> {
                    Text(stringResource(R.string.bd_success), style = MaterialTheme.typography.titleSmall)
                    Text("${apk.file.name} · ${mb(apk.sizeBytes)} MB", style = CodeTypography.small)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button(onClick = { onInstall(apk.file) }) { Text(stringResource(R.string.bd_install)) }
                        OutlinedButton(onClick = { onShare(apk.file) }) { Text(stringResource(R.string.bd_share)) }
                    }
                }
                s.cancelled -> Text(stringResource(R.string.bd_cancelled), style = MaterialTheme.typography.titleSmall)
                else -> Text(
                    s.failedStep?.let { stringResource(R.string.bd_failed_at, stepName(it)) } ?: stringResource(R.string.bd_failed),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(d: BuildDiagnostic, onOpenFile: (String, Int) -> Unit) {
    val error = d.severity == BuildDiagnostic.Severity.Error
    Row(
        Modifier.fillMaxWidth().let { m -> d.file?.let { f -> m.clickable { onOpenFile(f, d.line) } } ?: m }.padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(BiIcons.Warning, null, tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
        Column(Modifier.padding(start = Spacing.sm)) {
            d.file?.let { Text(if (d.line > 0) "$it:${d.line}" else it, style = CodeTypography.small, color = MaterialTheme.colorScheme.primary) }
            Text(d.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun mb(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / 1_048_576.0)
