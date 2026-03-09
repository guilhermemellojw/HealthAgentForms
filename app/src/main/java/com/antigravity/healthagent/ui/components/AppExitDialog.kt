package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppExitDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isEasyMode: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (isEasyMode) 32.dp else 24.dp)
                )
                Text(
                    "Sair do Aplicativo",
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = if (isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Text(
                "Deseja realmente sair do aplicativo? Suas alterações salvas não serão perdidas.",
                style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                lineHeight = if (isEasyMode) 22.sp else 20.sp
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(if (isEasyMode) 52.dp else 48.dp),
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                ) {
                    Text("Cancelar", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1.3f).height(if (isEasyMode) 52.dp else 48.dp),
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                ) {
                    Text("Sair", fontWeight = FontWeight.Bold)
                }
            }
        },
        shape = RoundedCornerShape(if (isEasyMode) 28.dp else 24.dp)
    )
}
