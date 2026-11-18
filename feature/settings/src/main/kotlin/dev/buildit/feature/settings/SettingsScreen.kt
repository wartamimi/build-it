package dev.buildit.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.component.BiClickRow
import dev.buildit.core.designsystem.component.BiLoadingState
import dev.buildit.core.designsystem.component.BiSectionHeader
import dev.buildit.core.designsystem.component.BiSwitchRow
import dev.buildit.core.designsystem.component.BiTopBar
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.core.designsystem.theme.supportsDynamicColor
import dev.buildit.core.model.AppLanguage
import dev.buildit.core.model.ThemeMode
import dev.buildit.core.model.UserSettings
import dev.buildit.engine.project.ProjectFormat
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, appVersion: String, workspacePath: String) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val msgReindexed = stringResource(R.string.settings_reindexed)
    val msgReindexedSkipped = stringResource(R.string.settings_reindexed_skipped)
    val msgFailed = stringResource(R.string.settings_failed)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { m ->
            snackbar.showSnackbar(
                when (m) {
                    is SettingsMessage.Reindexed -> if (m.failed == 0) msgReindexed else msgReindexedSkipped.format(m.failed)
                    is SettingsMessage.Failed -> msgFailed.format(m.detail)
                },
            )
        }
    }
    Scaffold(topBar = { BiTopBar(stringResource(R.string.settings_title)) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val s = settings
        if (s == null) {
            BiLoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Appearance(s, viewModel)
                HorizontalDivider()
                Language(s, viewModel)
                HorizontalDivider()
                Editor(s, viewModel)
                HorizontalDivider()
                BiSectionHeader(stringResource(R.string.settings_workspace))
                BiClickRow(stringResource(R.string.settings_reindex), viewModel::reindex, summary = stringResource(R.string.settings_reindex_summary))
                Text(
                    workspacePath, style = CodeTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
                HorizontalDivider()
                BiSectionHeader(stringResource(R.string.settings_about))
                Text(
                    stringResource(R.string.settings_about_detail, appVersion, ProjectFormat.CURRENT_VERSION),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
                )
            }
        }
    }
}

@Composable
private fun Appearance(s: UserSettings, vm: SettingsViewModel) {
    BiSectionHeader(stringResource(R.string.settings_appearance))
    Text(stringResource(R.string.settings_theme), Modifier.padding(horizontal = Spacing.lg), style = MaterialTheme.typography.bodyLarge)
    val modes = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        modes.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = s.themeMode == mode,
                onClick = { vm.setTheme(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
            ) {
                Text(stringResource(when (mode) {
                    ThemeMode.System -> R.string.settings_theme_system
                    ThemeMode.Light -> R.string.settings_theme_light
                    ThemeMode.Dark -> R.string.settings_theme_dark
                }))
            }
        }
    }
    BiSwitchRow(
        title = stringResource(R.string.settings_dynamic_color),
        checked = s.dynamicColor && supportsDynamicColor,
        onCheckedChange = vm::setDynamicColor,
        enabled = supportsDynamicColor,
        summary = stringResource(if (supportsDynamicColor) R.string.settings_dynamic_color_summary else R.string.settings_dynamic_color_unsupported),
    )
}

@Composable
private fun Language(s: UserSettings, vm: SettingsViewModel) {
    BiSectionHeader(stringResource(R.string.settings_language))
    val options = AppLanguage.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        options.forEachIndexed { i, lang ->
            SegmentedButton(selected = s.language == lang, onClick = { vm.setLanguage(lang) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                Text(when (lang) {
                    AppLanguage.System -> stringResource(R.string.settings_language_system)
                    AppLanguage.English -> "English"
                    AppLanguage.Indonesian -> "Indonesia"
                })
            }
        }
    }
}

@Composable
private fun Editor(s: UserSettings, vm: SettingsViewModel) {
    BiSectionHeader(stringResource(R.string.settings_editor))
    var size by remember(s.editorFontSizeSp) { mutableFloatStateOf(s.editorFontSizeSp.toFloat()) }
    val range = UserSettings.EDITOR_FONT_SIZE_RANGE
    Text(stringResource(R.string.settings_font_size, size.roundToInt()), Modifier.padding(horizontal = Spacing.lg), style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = size,
        onValueChange = { size = it },
        onValueChangeFinished = { vm.setEditorFontSize(size.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        modifier = Modifier.padding(horizontal = Spacing.lg),
    )
    Text(
        "fun main() = println(\"Build It\")",
        style = CodeTypography.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(size, androidx.compose.ui.unit.TextUnitType.Sp)),
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
    BiSwitchRow(stringResource(R.string.settings_autosave), s.autoSave, vm::setAutoSave, summary = stringResource(R.string.settings_autosave_summary))
}
