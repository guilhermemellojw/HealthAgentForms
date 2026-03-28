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
    onDismiss: () -> Unit,
    isEasyMode: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "Meta Diária Atingida", 
                fontWeight = FontWeight.ExtraBold,
                style = if (isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            ) 
        },
        text = { 
            Text(
                "O limite diário de imóveis trabalhados (Meta Diária) foi atingido.\n\nPara adicionar mais imóveis ou alterar situações para 'Trabalhado', você deve primeiro concluir o dia atual ou ajustar a meta nas configurações.",
                style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
            ) 
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(if (isEasyMode) 52.dp else 48.dp),
                shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
            ) {
                Text("Entendido", fontWeight = FontWeight.Bold)
            }
        },
        shape = RoundedCornerShape(if (isEasyMode) 28.dp else 24.dp)
    )
}
