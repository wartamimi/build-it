package dev.buildit.feature.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.buildit.core.designsystem.component.BiBottomSheet
import dev.buildit.core.designsystem.component.BiButton
import dev.buildit.core.designsystem.component.BiButtonStyle
import dev.buildit.core.designsystem.component.BiTextField
import dev.buildit.core.designsystem.theme.Spacing
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
    var template by remember { mutableStateOf(ProjectTemplates.EmptyActivity) }
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
            Column(Modifier.selectableGroup()) {
                ProjectTemplates.all.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = t == template, role = Role.RadioButton, onClick = { template = t })
                            .padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = t == template, onClick = null)
                        Column(Modifier.padding(start = Spacing.md)) {
                            Text(
                                stringResource(if (t.language == SourceLanguage.Java) R.string.projects_template_java else R.string.projects_template_kotlin),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(stringResource(R.string.projects_template_empty_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
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
