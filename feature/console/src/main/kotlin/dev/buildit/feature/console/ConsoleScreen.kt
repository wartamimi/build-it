package dev.buildit.feature.console

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Console tab: logs, lifecycle events and crashes from this project's debug builds on this device. */
@Composable
fun ConsoleScreen(viewModel: ConsoleViewModel, onOpenSource: (path: String, line: Int) -> Unit, onAskAi: (text: String, path: String?) -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(s.entries.size, follow) { if (follow && s.entries.isNotEmpty()) list.scrollToItem(s.entries.size - 1) }
    fun open(f: Frame) = scope.launch { viewModel.resolve(f)?.let { (p, l) -> onOpenSource(p, l) } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxSize()) {
            Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(s.filter.query, viewModel::setQuery, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.cn_search)) }, leadingIcon = { Icon(BiIcons.Search, null) })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    listOf(ConsoleLevel.Verbose, ConsoleLevel.Debug, ConsoleLevel.Info, ConsoleLevel.Warn, ConsoleLevel.Error).forEach { l ->
                        FilterChip(selected = s.filter.minLevel == l, onClick = { viewModel.setMinLevel(l) }, label = { Text(levelName(l)) })
                    }
                    FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text(stringResource(R.string.cn_follow)) })
                    TextButton(onClick = viewModel::clear, enabled = s.total > 0) { Text(stringResource(R.string.cn_clear)) }
                }
            }
            if (s.total == 0) {
                Text(stringResource(R.string.cn_empty, s.applicationId ?: "…"), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.lg))
            } else if (s.entries.isEmpty()) {
                Text(stringResource(R.string.cn_no_match), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(Spacing.lg))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list, contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                items(s.entries, key = { it.id }) { e ->
                    if (e.level == ConsoleLevel.Crash) CrashCard(e, ::open, viewModel, onAskAi) else LogRow(e, ::open)
                }
            }
        }
    }
}

@Composable
private fun levelName(l: ConsoleLevel) = stringResource(when (l) {
    ConsoleLevel.Verbose -> R.string.cn_level_verbose
    ConsoleLevel.Debug -> R.string.cn_level_debug
    ConsoleLevel.Info -> R.string.cn_level_info
    ConsoleLevel.Warn -> R.string.cn_level_warn
    else -> R.string.cn_level_error
})

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

@Composable
private fun LogRow(e: ConsoleEntry, open: (Frame) -> Unit) {
    val color = when (e.level) {
        ConsoleLevel.Error, ConsoleLevel.Assert -> MaterialTheme.colorScheme.error
        ConsoleLevel.Warn -> MaterialTheme.colorScheme.tertiary
        ConsoleLevel.Event -> MaterialTheme.colorScheme.primary
        ConsoleLevel.Verbose, ConsoleLevel.Debug -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val frame = SourceLinks.frames(e.text).firstOrNull()
    Text(
        "${timeFormat.format(Date(e.time))} ${e.level.letter} ${e.tag}: ${e.text}",
        style = CodeTypography.small, color = color,
        modifier = Modifier.fillMaxWidth().let { m -> if (frame != null) m.clickable { open(frame) } else m }.padding(vertical = 1.dp),
    )
}

@Composable
private fun CrashCard(e: ConsoleEntry, open: (Frame) -> Unit, vm: ConsoleViewModel, onAskAi: (String, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val frames = remember(e.text) { SourceLinks.frames(e.text) }
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.cn_crash, e.tag, timeFormat.format(Date(e.time))), style = MaterialTheme.typography.titleSmall)
            e.text.lines().forEach { line ->
                val f = frames.firstOrNull { line.contains("(${it.fileName}:${it.line})") && line.contains(it.method) }
                if (f == null) Text(line, style = CodeTypography.small)
                else Text(
                    buildAnnotatedString { withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = MaterialTheme.colorScheme.primary)) { append(line) } },
                    style = CodeTypography.small, modifier = Modifier.clickable { open(f) },
                )
            }
            OutlinedButton(onClick = {
                scope.launch {
                    val first = frames.firstNotNullOfOrNull { vm.resolve(it) }
                    onAskAi(e.text, first?.first)
                }
            }) { Text(stringResource(R.string.cn_ask_ai)) }
        }
    }
}
