package dev.buildit.feature.workspace

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buildit.core.designsystem.component.BiEmptyState
import dev.buildit.core.designsystem.component.BiIconButton
import dev.buildit.core.designsystem.component.BiLoadingState
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.project.AssetKind
import dev.buildit.engine.project.ProjectAsset
import dev.buildit.engine.project.ProjectAssets

/** Mime types offered by the system file picker for each kind. */
private fun mimeTypes(kind: AssetKind) = when (kind) {
    AssetKind.Image -> arrayOf("image/*", "text/xml", "application/xml")
    AssetKind.Sound -> arrayOf("audio/*", "application/ogg")
    AssetKind.Font -> arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "application/vnd.ms-opentype")
}

/** Images, sounds or fonts of the project, with import, preview, rename and delete. */
@Composable
fun AssetsScreen(viewModel: AssetsViewModel, kind: AssetKind) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        viewModel.picked(kind, name, bytes)
    }
    var renaming by remember { mutableStateOf<ProjectAsset?>(null) }
    var deleting by remember { mutableStateOf<ProjectAsset?>(null) }
    val items = state.assets[kind].orEmpty()

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> BiLoadingState()
            items.isEmpty() -> BiEmptyState(icon(kind), stringResource(R.string.as_empty_title), stringResource(emptyBody(kind)))
            kind == AssetKind.Image -> LazyVerticalGrid(
                GridCells.Adaptive(112.dp), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.md, Spacing.md, Spacing.md, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(items, key = { it.file.path }) { a -> ImageTile(a, onRename = { renaming = a }, onDelete = { deleting = a }) }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.md, Spacing.md, Spacing.md, 96.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(items, key = { it.file.path }) { a ->
                    if (kind == AssetKind.Sound) SoundRow(a, onRename = { renaming = a }, onDelete = { deleting = a })
                    else FontRow(a, onRename = { renaming = a }, onDelete = { deleting = a })
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { picker.launch(mimeTypes(kind)) },
            icon = { Icon(BiIcons.Import, null) },
            text = { Text(stringResource(R.string.as_import)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.lg),
        )
    }

    state.pending?.takeIf { it.kind == kind }?.let { p ->
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text(stringResource(R.string.as_import_title, p.displayName)) },
            text = {
                Column {
                    OutlinedTextField(
                        p.name, { viewModel.setPendingName(it.trim()) }, singleLine = true, textStyle = CodeTypography.body,
                        label = { Text(stringResource(R.string.as_resource_name)) },
                        isError = p.error != null || !ProjectAssets.isValidName(p.name),
                        supportingText = { Text(errorText(p.error) ?: stringResource(R.string.as_name_hint, refPrefix(kind) + p.name)) },
                    )
                }
            },
            confirmButton = { TextButton(enabled = ProjectAssets.isValidName(p.name), onClick = viewModel::confirmImport) { Text(stringResource(R.string.as_add)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.as_cancel)) } },
        )
    }
    renaming?.let { a ->
        var name by remember(a) { mutableStateOf(a.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.as_rename)) },
            text = {
                OutlinedTextField(name, { name = it.trim() }, singleLine = true, textStyle = CodeTypography.body, label = { Text(stringResource(R.string.as_resource_name)) },
                    isError = !ProjectAssets.isValidName(name), supportingText = { Text(stringResource(R.string.as_rename_hint)) })
            },
            confirmButton = { TextButton(enabled = ProjectAssets.isValidName(name), onClick = { renaming = null; viewModel.rename(a, name) }) { Text(stringResource(R.string.as_rename)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.as_cancel)) } },
        )
    }
    deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.as_delete_title, a.file.name)) },
            text = { Text(stringResource(R.string.as_delete_body)) },
            confirmButton = { TextButton(onClick = { deleting = null; viewModel.delete(a) }) { Text(stringResource(R.string.as_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.as_cancel)) } },
        )
    }
    if (state.error != null && state.pending == null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            text = { Text(errorText(state.error) ?: state.error!!) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.as_ok)) } },
        )
    }
}

private fun icon(kind: AssetKind) = when (kind) { AssetKind.Image -> BiIcons.Image; AssetKind.Sound -> BiIcons.Sound; AssetKind.Font -> BiIcons.Font }
private fun emptyBody(kind: AssetKind) = when (kind) { AssetKind.Image -> R.string.as_empty_images; AssetKind.Sound -> R.string.as_empty_sounds; AssetKind.Font -> R.string.as_empty_fonts }
private fun refPrefix(kind: AssetKind) = when (kind) { AssetKind.Image -> "@drawable/"; AssetKind.Sound -> "R.raw."; AssetKind.Font -> "@font/" }

@Composable
private fun errorText(code: String?): String? = when (code) {
    null -> null
    "exists" -> stringResource(R.string.as_error_exists)
    "invalid_name" -> stringResource(R.string.as_error_name)
    "unsupported" -> stringResource(R.string.as_error_unsupported)
    else -> code
}

@Composable
private fun AssetMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        BiIconButton(BiIcons.More, stringResource(R.string.as_more), { menu = true })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.as_rename)) }, leadingIcon = { Icon(BiIcons.Edit, null) }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.as_delete)) }, leadingIcon = { Icon(BiIcons.Delete, null) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun ImageTile(a: ProjectAsset, onRename: () -> Unit, onDelete: () -> Unit) {
    val bitmap = remember(a.file.path, a.file.lastModified()) {
        if (a.extension == "xml") null else runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(a.file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
            BitmapFactory.decodeFile(a.file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, a.name, Modifier.fillMaxSize().padding(Spacing.sm), contentScale = ContentScale.Fit)
            else Text(if (a.extension == "xml") "XML" else a.extension.uppercase(), style = CodeTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = Spacing.sm))
            AssetMenu(onRename, onDelete)
        }
    }
}

@Composable
private fun SoundRow(a: ProjectAsset, onRename: () -> Unit, onDelete: () -> Unit) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(a.file.path) { onDispose { player?.release(); player = null } }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(start = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            BiIconButton(if (player == null) BiIcons.Run else BiIcons.Close, stringResource(if (player == null) R.string.as_play else R.string.as_stop), {
                val p = player
                if (p != null) { p.release(); player = null }
                else player = runCatching {
                    MediaPlayer().apply {
                        setDataSource(a.file.path)
                        setOnCompletionListener { it.release(); player = null }
                        prepare(); start()
                    }
                }.getOrNull()
            })
            Column(Modifier.weight(1f).padding(horizontal = Spacing.sm, vertical = Spacing.md)) {
                Text(a.name, style = CodeTypography.body)
                Text("${a.file.name} · ${size(a.file.length())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AssetMenu(onRename, onDelete)
        }
    }
}

@Composable
private fun FontRow(a: ProjectAsset, onRename: () -> Unit, onDelete: () -> Unit) {
    val family = remember(a.file.path, a.file.lastModified()) { runCatching { FontFamily(Typeface.createFromFile(a.file)) }.getOrNull() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(start = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = Spacing.md)) {
                Text(stringResource(R.string.as_font_sample), style = MaterialTheme.typography.titleLarge, fontFamily = family, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${a.name} · ${a.file.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AssetMenu(onRename, onDelete)
        }
    }
}

private fun size(bytes: Long) = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** Title of an asset manager, for the tools drawer. */
@Composable
fun assetsTitle(kind: AssetKind): String = stringResource(when (kind) { AssetKind.Image -> R.string.as_images; AssetKind.Sound -> R.string.as_sounds; AssetKind.Font -> R.string.as_fonts })

fun assetsIcon(kind: AssetKind) = icon(kind)
