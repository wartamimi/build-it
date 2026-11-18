package dev.buildit.feature.projects

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.buildit.engine.project.ValidationIssue

/** Maps validation codes from the engine to localized, human explanations. */
@Composable
fun ValidationIssue.message(): String = when (field to code) {
    "name" to "empty" -> stringResource(R.string.projects_err_name_empty)
    "name" to "too_long" -> stringResource(R.string.projects_err_name_long, detail)
    "name" to "invalid_chars" -> stringResource(R.string.projects_err_name_chars)
    "packageName" to "empty" -> stringResource(R.string.projects_err_pkg_empty)
    "packageName" to "too_few_segments" -> stringResource(R.string.projects_err_pkg_segments)
    "packageName" to "empty_segment" -> stringResource(R.string.projects_err_pkg_empty_segment)
    "packageName" to "invalid_segment" -> stringResource(R.string.projects_err_pkg_invalid, detail)
    "packageName" to "keyword" -> stringResource(R.string.projects_err_pkg_keyword, detail)
    else -> "$field: $code"
}
