package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun IntegrityDialog(
    message: String,
    onDismiss: () -> Unit,
    isEasyMode: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "Pendências Encontradas", 
                fontWeight = FontWeight.ExtraBold, 
                color = MaterialTheme.colorScheme.error,
                style = if (isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium
            ) 
        },
        text = { 
            Text(
                message,
                style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
            ) 
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(if (isEasyMode) 52.dp else 44.dp),
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                ) {
                    Text("OK", fontWeight = FontWeight.Bold, fontSize = if (isEasyMode) 18.sp else 16.sp)
                }
            }
        },
        icon = { 
            Icon(
                Icons.Default.Info, 
                contentDescription = null, 
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(if (isEasyMode) 40.dp else 32.dp)
            ) 
        },
        shape = RoundedCornerShape(if (isEasyMode) 28.dp else 24.dp)
    )
}
