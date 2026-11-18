package dev.buildit.studio.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.component.BiIconButton
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.feature.blocks.BlocksViewModel
import dev.buildit.feature.build.BuildViewModel
import dev.buildit.feature.build.PlatformState
import dev.buildit.feature.build.StepState
import dev.buildit.feature.designer.DesignerViewModel
import dev.buildit.feature.workspace.WorkspaceTabScope
import dev.buildit.studio.R
import java.io.File

/**
 * Bottom bar of the editors: which screen is being edited (drives both the designer and the
 * blocks), and Run, which saves everything, builds a debug APK and opens the installer.
 */
@Composable
internal fun RunBar(
    blocks: BlocksViewModel,
    designer: DesignerViewModel,
    build: BuildViewModel,
    scope: WorkspaceTabScope,
    buildTabKey: String,
    onInstall: (File) -> Unit,
    onShare: (File) -> Unit,
) {
    val bs by blocks.state.collectAsStateWithLifecycle()
    val b by build.state.collectAsStateWithLifecycle()
    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.sm, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                var screens by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { blocks.refreshActivities(); screens = true }) {
                        Icon(BiIcons.Screens, null)
                        Spacer(Modifier.size(Spacing.xs))
                        Text(
                            (bs.activity ?: stringResource(R.string.ws_screen)) + " ▾",
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp),
                        )
                    }
                    DropdownMenu(expanded = screens, onDismissRequest = { screens = false }) {
                        bs.activities.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a + (bs.layouts[a]?.let { "  ·  $it.xml" } ?: "")) },
                                trailingIcon = { if (a == bs.activity) Icon(BiIcons.Check, null) },
                                onClick = {
                                    screens = false
                                    blocks.load(a)
                                    bs.layouts[a]?.let(designer::open)
                                },
                            )
                        }
                    }
                }
                when {
                    b.running -> {
                        val done = b.steps.count { it.state == StepState.Done }
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.ws_building, done + 1, b.steps.size.coerceAtLeast(1)),
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.sm),
                        )
                    }
                    b.failedStep != null || (b.diagnostics.isNotEmpty() && b.apk == null && !b.cancelled && b.steps.isNotEmpty()) ->
                        TextButton(onClick = { scope.showTab(buildTabKey) }) {
                            Icon(BiIcons.Warning, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.size(Spacing.xs))
                            Text(stringResource(R.string.ws_build_failed), color = MaterialTheme.colorScheme.error)
                        }
                }
                Button(
                    enabled = !b.running,
                    onClick = {
                        when (b.platform) {
                            is PlatformState.Ready, PlatformState.Checking -> designer.saveThen { blocks.saveThen { build.build(onSuccess = onInstall) } }
                            // No Android platform yet: the Build tool offers the download.
                            else -> scope.showTab(buildTabKey)
                        }
                    },
                ) {
                    Icon(BiIcons.Run, null)
                    Spacer(Modifier.size(Spacing.xs))
                    Text(stringResource(R.string.ws_run))
                }
                var more by remember { mutableStateOf(false) }
                Box {
                    BiIconButton(BiIcons.More, stringResource(R.string.ws_run_options), { more = true })
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ws_build_details)) }, leadingIcon = { Icon(BiIcons.Build, null) }, onClick = { more = false; scope.showTab(buildTabKey) })
                        if (b.running) DropdownMenuItem(text = { Text(stringResource(R.string.ws_cancel_build)) }, leadingIcon = { Icon(BiIcons.Close, null) }, onClick = { more = false; build.cancel() })
                        b.apk?.let { apk ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.ws_install_apk)) }, leadingIcon = { Icon(BiIcons.Device, null) }, onClick = { more = false; onInstall(apk.file) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ws_share_apk)) }, leadingIcon = { Icon(BiIcons.Share, null) }, onClick = { more = false; onShare(apk.file) })
                        }
                    }
                }
            }
        }
    }
}
