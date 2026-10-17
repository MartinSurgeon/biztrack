package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmButtonText: String = "Confirm",
    cancelButtonText: String = "Cancel",
    isDestructive: Boolean = true,
    icon: ImageVector = if (isDestructive) Icons.Default.Warning else Icons.Default.CheckCircle,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .padding(28.dp)
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
                .testTag("action_confirmation_dialog"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconBadge(
                icon = icon,
                tone = if (isDestructive) Tone.DANGER else Tone.PRIMARY,
                size = 56.dp,
                iconSize = 28.dp,
                shape = CircleShape
            )
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            AppButton(
                text = confirmButtonText,
                onClick = onConfirm,
                variant = if (isDestructive) ButtonVariant.DANGER else ButtonVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth().testTag("confirmation_dialog_confirm_button")
            )
            Spacer(Modifier.height(8.dp))
            AppButton(
                text = cancelButtonText,
                onClick = onDismiss,
                variant = ButtonVariant.SECONDARY,
                modifier = Modifier.fillMaxWidth().testTag("confirmation_dialog_cancel_button")
            )
        }
    }
}
