package dev.buildit.feature.build

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.buildit.core.designsystem.component.BiConfirmDialog
import dev.buildit.core.designsystem.component.BiTextField
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.core.designsystem.theme.CodeTypography
import dev.buildit.core.designsystem.theme.Spacing
import dev.buildit.engine.build.BuildType
import dev.buildit.engine.build.NewKey
import dev.buildit.engine.build.ReleaseKeyException
import dev.buildit.engine.build.ReleaseKeyInfo
import java.text.DateFormat
import java.util.Date

/**
 * Release APK: choose, create, import, back up or remove a signing key, then build. Passwords are
 * typed here and go straight to the encrypted store; they are never shown again.
 */
@Composable
internal fun ReleaseCard(s: BuildUiState, vm: BuildViewModel) {
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    var deleting by remember { mutableStateOf<ReleaseKeyInfo?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
        if (bytes != null) importing = bytes to name
    }
    val selected = s.releaseKeys.firstOrNull { it.id == s.releaseKey }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri: Uri? ->
        val key = selected ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        vm.exportKey(key.id) { bytes -> context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot write the file") }
    }
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.bd_release_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(if (s.releaseKeys.isEmpty()) R.string.bd_release_none else R.string.bd_release_backup),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.releaseKeys.forEach { k -> KeyRow(k, k.id == s.releaseKey) { vm.selectKey(k.id) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = { creating = true }, enabled = !s.keyBusy) { Text(stringResource(R.string.bd_key_new)) }
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !s.keyBusy) { Text(stringResource(R.string.bd_key_import)) }
                if (selected != null) {
                    TextButton(onClick = { export.launch("${selected.name}.p12") }, enabled = !s.keyBusy) { Text(stringResource(R.string.bd_key_export)) }
                    TextButton(onClick = { deleting = selected }, enabled = !s.keyBusy) { Text(stringResource(R.string.bd_key_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
            s.keyMessage?.let { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.text(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        color = if (m is KeyMessage.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = vm::dismissKeyMessage) { Text(stringResource(R.string.bd_key_ok)) }
                }
            }
            Button(
                onClick = { vm.build(BuildType.Release) },
                enabled = selected != null && !s.running && !s.keyBusy && s.platform is PlatformState.Ready,
            ) {
                androidx.compose.material3.Icon(BiIcons.Build, null)
                Text(stringResource(R.string.bd_build_release), Modifier.padding(start = Spacing.sm))
            }
        }
    }
    if (creating) NewKeyDialog(onDismiss = { creating = false }) { vm.createKey(it); creating = false }
    importing?.let { (bytes, name) -> ImportKeyDialog(name, onDismiss = { importing = null }) { n, sp, a, kp -> vm.importKey(bytes, n, sp, a, kp); importing = null } }
    deleting?.let { k ->
        BiConfirmDialog(
            title = stringResource(R.string.bd_key_delete_title, k.name),
            message = stringResource(R.string.bd_key_delete_message),
            confirmLabel = stringResource(R.string.bd_key_delete), dismissLabel = stringResource(R.string.bd_cancel),
            onConfirm = { vm.deleteKey(k.id); deleting = null }, onDismiss = { deleting = null }, destructive = true,
        )
    }
}

@Composable
private fun KeyRow(k: ReleaseKeyInfo, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = Spacing.sm).weight(1f)) {
            Text("${k.name} · ${k.alias}", style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.bd_key_expires, DateFormat.getDateInstance().format(Date(k.expires))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("SHA-256 ${k.sha256}", style = CodeTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String? = null) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        isError = error != null, supportingText = error?.let { { Text(it) } },
    )
}

@Composable
private fun NewKeyDialog(onDismiss: () -> Unit, onCreate: (NewKey) -> Unit) {
    var name by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("upload") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var years by remember { mutableStateOf("25") }
    val shortPassword = password.isNotEmpty() && password.length < 6
    val mismatch = confirm.isNotEmpty() && confirm != password
    val y = years.toIntOrNull()
    val valid = alias.isNotBlank() && owner.isNotBlank() && password.length >= 6 && confirm == password && y != null && y in 1..100
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bd_key_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                BiTextField(name, { name = it }, stringResource(R.string.bd_key_name))
                BiTextField(alias, { alias = it.filter { c -> !c.isWhitespace() } }, stringResource(R.string.bd_key_alias), monospace = true)
                PasswordField(password, { password = it }, stringResource(R.string.bd_key_password), stringResource(R.string.bd_key_password_short).takeIf { shortPassword })
                PasswordField(confirm, { confirm = it }, stringResource(R.string.bd_key_password_confirm), stringResource(R.string.bd_key_password_mismatch).takeIf { mismatch })
                BiTextField(owner, { owner = it }, stringResource(R.string.bd_key_owner))
                BiTextField(org, { org = it }, stringResource(R.string.bd_key_org))
                BiTextField(country, { country = it.take(2) }, stringResource(R.string.bd_key_country))
                BiTextField(years, { years = it.filter(Char::isDigit).take(3) }, stringResource(R.string.bd_key_years), keyboardType = KeyboardType.Number)
                Text(stringResource(R.string.bd_release_backup), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onCreate(NewKey(name, alias, password.toCharArray(), owner, org, country, y ?: 25)) }) { Text(stringResource(R.string.bd_key_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bd_cancel)) } },
    )
}

@Composable
private fun ImportKeyDialog(fileName: String, onDismiss: () -> Unit, onImport: (String, CharArray, String?, CharArray?) -> Unit) {
    var name by remember { mutableStateOf(fileName) }
    var storePassword by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var keyPassword by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bd_key_import)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(R.string.bd_key_import_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BiTextField(name, { name = it }, stringResource(R.string.bd_key_name))
                PasswordField(storePassword, { storePassword = it }, stringResource(R.string.bd_key_store_password))
                BiTextField(alias, { alias = it.trim() }, stringResource(R.string.bd_key_alias_optional), monospace = true)
                PasswordField(keyPassword, { keyPassword = it }, stringResource(R.string.bd_key_key_password_optional))
            }
        },
        confirmButton = {
            TextButton(enabled = storePassword.isNotEmpty(), onClick = {
                onImport(name, storePassword.toCharArray(), alias.ifBlank { null }, keyPassword.ifEmpty { null }?.toCharArray())
            }) { Text(stringResource(R.string.bd_key_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bd_cancel)) } },
    )
}

@Composable
private fun KeyMessage.text(): String = when (this) {
    is KeyMessage.Created -> stringResource(R.string.bd_key_created, name)
    is KeyMessage.Imported -> stringResource(R.string.bd_key_imported, name)
    is KeyMessage.Exported -> stringResource(R.string.bd_key_exported, name)
    is KeyMessage.Deleted -> stringResource(R.string.bd_key_deleted, name)
    is KeyMessage.Failed -> when (reason) {
        ReleaseKeyException.Reason.NotPkcs12 -> stringResource(R.string.bd_key_err_jks)
        ReleaseKeyException.Reason.WrongPassword -> stringResource(R.string.bd_key_err_password)
        ReleaseKeyException.Reason.NoKey -> stringResource(R.string.bd_key_err_alias, detail)
        ReleaseKeyException.Reason.Invalid, null -> stringResource(R.string.bd_key_err_other, detail)
    }
}
