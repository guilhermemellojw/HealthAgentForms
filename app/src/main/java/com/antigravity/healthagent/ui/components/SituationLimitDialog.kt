package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SituationLimitDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isEasyMode: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "Meta Diária Atingida", 
                fontWeight = FontWeight.ExtraBold,
                style = if (isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
            ) 
        },
        text = { 
            Text(
                "Você está prestes a exceder a Meta Diária de imóveis abertos para hoje.\n\nDeseja continuar?",
                style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
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
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Continuar", fontWeight = FontWeight.Bold)
                }
            }
        },
        shape = RoundedCornerShape(if (isEasyMode) 28.dp else 24.dp)
    )
}
