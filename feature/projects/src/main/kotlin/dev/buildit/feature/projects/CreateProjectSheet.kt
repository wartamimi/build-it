package dev.buildit.feature.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.buildit.core.designsystem.component.BiBottomSheet
import dev.buildit.core.designsystem.component.BiButton
import dev.buildit.core.designsystem.component.BiButtonStyle
import dev.buildit.core.designsystem.component.BiTextField
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.blocks.AppTemplates
import dev.buildit.engine.project.NewProjectOptions
import dev.buildit.engine.project.ProjectTemplate
import dev.buildit.engine.project.ThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.buildit.engine.project.ProjectTemplates
import dev.buildit.engine.project.ProjectValidation
import dev.buildit.engine.project.SourceLanguage
import dev.buildit.engine.project.ValidationIssue

@Composable
fun CreateProjectSheet(
    validate: (String, String) -> List<ValidationIssue>,
    onCreate: (name: String, packageName: String, template: ProjectTemplate, options: NewProjectOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var pkgEdited by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(SourceLanguage.Java) }
    var kind by remember { mutableStateOf<AppTemplates.Kind?>(null) }
    val template = kind?.let { AppTemplates.of(it, language) } ?: if (language == SourceLanguage.Java) ProjectTemplates.EmptyActivity else ProjectTemplates.EmptyActivityKotlin
    var attempted by remember { mutableStateOf(false) }
    // Settings chosen before the project exists, like Sketchware's new-project screen.
    var versionCode by remember { mutableStateOf("1") }
    var versionName by remember { mutableStateOf("1.0") }
    var minSdk by remember { mutableStateOf(24) }
    var appCompat by remember { mutableStateOf(false) }
    var primary by remember { mutableStateOf(ThemePresets.primaries.first()) }
    var accent by remember { mutableStateOf(ThemePresets.accents.first()) }

    val effectivePkg = if (pkgEdited) pkg else "com.example." + ProjectValidation.suggestPackageSegment(name)
    val issues = validate(name, effectivePkg)
    val nameIssue = issues.firstOrNull { it.field == "name" }.takeIf { attempted || name.isNotEmpty() }
    val pkgIssue = issues.firstOrNull { it.field == "packageName" }.takeIf { attempted || pkgEdited }

    BiBottomSheet(onDismiss) {
        Text(stringResource(R.string.projects_create_title), style = MaterialTheme.typography.titleLarge)
        Column(Modifier.padding(top = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            BiTextField(name, { name = it }, stringResource(R.string.projects_field_name), error = nameIssue?.message())
            BiTextField(
                value = effectivePkg,
                onValueChange = { pkg = it; pkgEdited = true },
                label = stringResource(R.string.projects_field_package),
                error = pkgIssue?.message(),
                supporting = stringResource(R.string.projects_field_package_hint),
                monospace = true,
            )
            Text(stringResource(R.string.projects_field_language), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SourceLanguage.entries.forEach { l ->
                    FilterChip(selected = l == language, onClick = { language = l }, label = { Text(if (l == SourceLanguage.Java) "Java" else "Kotlin") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BiTextField(versionCode, { v -> versionCode = v.filter(Char::isDigit).take(9) }, stringResource(R.string.projects_field_version_code), Modifier.weight(1f),
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                BiTextField(versionName, { versionName = it.take(40) }, stringResource(R.string.projects_field_version_name), Modifier.weight(1f))
            }
            if (language == SourceLanguage.Kotlin) {
                Text(stringResource(R.string.projects_kotlin_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.projects_field_template), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                (listOf<AppTemplates.Kind?>(null) + AppTemplates.Kind.entries).forEach { k ->
                    FilterChip(selected = k == kind, onClick = { kind = k }, label = { Text(stringResource(templateTitle(k))) })
                }
            }
            Text(stringResource(templateDescription(kind)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text(stringResource(R.string.projects_field_min_sdk), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf(21 to "5.0", 23 to "6.0", 24 to "7.0", 26 to "8.0", 28 to "9", 30 to "11").forEach { (api, version) ->
                    FilterChip(selected = api == minSdk, onClick = { minSdk = api }, label = { Text("API $api · Android $version") })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.projects_field_appcompat), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.projects_appcompat_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = appCompat, onCheckedChange = { appCompat = it })
            }

            Text(stringResource(R.string.projects_field_colors), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm))
            ThemePreview(name.ifBlank { stringResource(R.string.projects_field_name) }, primary, accent)
            ColorRow(stringResource(R.string.projects_color_primary), ThemePresets.primaries, primary) { primary = it }
            ColorRow(stringResource(R.string.projects_color_accent), ThemePresets.accents, accent) { accent = it }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
            BiButton(stringResource(R.string.projects_cancel), onDismiss, style = BiButtonStyle.Text)
            BiButton(stringResource(R.string.projects_create), onClick = {
                attempted = true
                if (issues.isEmpty()) onCreate(
                    name.trim(), effectivePkg.trim(), template,
                    NewProjectOptions(
                        versionCode = versionCode.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        versionName = versionName.trim().ifEmpty { "1.0" },
                        minSdk = minSdk,
                        appCompat = appCompat,
                        colors = ThemeColors(hex(primary), hex(ThemePresets.darker(primary)), hex(accent)),
                    ),
                )
            })
        }
    }
}

private fun templateTitle(k: AppTemplates.Kind?) = when (k) {
    null -> R.string.projects_template_empty
    AppTemplates.Kind.Counter -> R.string.projects_template_counter
    AppTemplates.Kind.Calculator -> R.string.projects_template_calculator
    AppTemplates.Kind.Todo -> R.string.projects_template_todo
    AppTemplates.Kind.Notes -> R.string.projects_template_notes
    AppTemplates.Kind.Stopwatch -> R.string.projects_template_stopwatch
    AppTemplates.Kind.Dice -> R.string.projects_template_dice
}

private fun templateDescription(k: AppTemplates.Kind?) = when (k) {
    null -> R.string.projects_template_empty_desc
    AppTemplates.Kind.Counter -> R.string.projects_template_counter_desc
    AppTemplates.Kind.Calculator -> R.string.projects_template_calculator_desc
    AppTemplates.Kind.Todo -> R.string.projects_template_todo_desc
    AppTemplates.Kind.Notes -> R.string.projects_template_notes_desc
    AppTemplates.Kind.Stopwatch -> R.string.projects_template_stopwatch_desc
    AppTemplates.Kind.Dice -> R.string.projects_template_dice_desc
}

/** Colour choices for a new project's theme. */
internal object ThemePresets {
    val primaries = listOf(0xFF3F51B5, 0xFF2196F3, 0xFF009688, 0xFF4CAF50, 0xFFFF9800, 0xFFF44336, 0xFF9C27B0, 0xFF795548, 0xFF607D8B, 0xFF212121).map { it.toInt() }
    val accents = listOf(0xFFFF4081, 0xFFFFC107, 0xFF00BCD4, 0xFF8BC34A, 0xFFFF5722, 0xFF7C4DFF, 0xFF448AFF, 0xFF64FFDA).map { it.toInt() }

    /** The status bar colour for [c]: the same hue, about 20% darker. */
    fun darker(c: Int): Int {
        fun ch(shift: Int) = (((c shr shift) and 0xFF) * 0.8f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

internal fun hex(c: Int) = "#%08X".format(c)

/** A small picture of the app's title bar in the chosen colours. */
@Composable
private fun ThemePreview(title: String, primary: Int, accent: Int) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth().height(10.dp).background(Color(ThemePresets.darker(primary))))
        Box(Modifier.fillMaxWidth().height(40.dp).background(Color(primary)).padding(horizontal = Spacing.md), contentAlignment = Alignment.CenterStart) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(44.dp).padding(Spacing.sm), contentAlignment = Alignment.BottomEnd) {
            Box(Modifier.size(28.dp).background(Color(accent), CircleShape))
        }
    }
}

@Composable
private fun ColorRow(label: String, colors: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            Box(
                Modifier.size(32.dp)
                    .then(if (c == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape).padding(3.dp) else Modifier)
                    .clip(CircleShape).background(Color(c)).clickable { onSelect(c) }
                    .semantics { contentDescription = hex(c) },
            )
        }
    }
}
