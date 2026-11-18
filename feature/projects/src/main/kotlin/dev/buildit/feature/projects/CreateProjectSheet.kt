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
import dev.buildit.engine.project.ProjectTemplate
import dev.buildit.engine.project.ProjectTemplates
import dev.buildit.engine.project.ProjectValidation
import dev.buildit.engine.project.SourceLanguage
import dev.buildit.engine.project.ValidationIssue

@Composable
fun CreateProjectSheet(
    validate: (String, String) -> List<ValidationIssue>,
    onCreate: (name: String, packageName: String, template: ProjectTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var pkgEdited by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(SourceLanguage.Java) }
    var kind by remember { mutableStateOf<AppTemplates.Kind?>(null) }
    val template = kind?.let { AppTemplates.of(it, language) } ?: if (language == SourceLanguage.Java) ProjectTemplates.EmptyActivity else ProjectTemplates.EmptyActivityKotlin
    var attempted by remember { mutableStateOf(false) }

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
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
            BiButton(stringResource(R.string.projects_cancel), onDismiss, style = BiButtonStyle.Text)
            BiButton(stringResource(R.string.projects_create), onClick = {
                attempted = true
                if (issues.isEmpty()) onCreate(name.trim(), effectivePkg.trim(), template)
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
