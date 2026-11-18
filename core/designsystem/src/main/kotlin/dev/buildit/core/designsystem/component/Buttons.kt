package dev.buildit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.buildit.core.designsystem.theme.Spacing

enum class BiButtonStyle { Primary, Tonal, Outlined, Text }

/**
 * The single button used across Build It. [loading] disables the button and shows progress, so
 * long operations never accept a second tap.
 */
@Composable
fun BiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BiButtonStyle = BiButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            when {
                loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(text)
        }
    }
    val m = modifier.defaultMinSize(minHeight = Spacing.touchTarget)
    val isEnabled = enabled && !loading
    when (style) {
        BiButtonStyle.Primary -> Button(onClick, m, isEnabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { content() }
        BiButtonStyle.Tonal -> FilledTonalButton(onClick, m, isEnabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { content() }
        BiButtonStyle.Outlined -> OutlinedButton(onClick, m, isEnabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { content() }
        BiButtonStyle.Text -> TextButton(onClick, m, isEnabled) { content() }
    }
}

/** Icon-only action with a mandatory content description for accessibility. */
@Composable
fun BiIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(icon, contentDescription = contentDescription)
    }
}
