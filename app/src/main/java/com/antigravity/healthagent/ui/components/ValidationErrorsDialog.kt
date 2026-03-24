package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase

@Composable
fun ValidationErrorsDialog(
    errors: List<HouseValidationUseCase.ErrorDetail>,
    onHouseClick: (Int) -> Unit,
    onDismiss: () -> Unit,
    isEasyMode: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "Pendências de Dados", 
                fontWeight = FontWeight.ExtraBold, 
                color = MaterialTheme.colorScheme.error,
                style = if (isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium
            ) 
        },
        text = { 
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Clique em uma pendência para ir ao imóvel:",
                    style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
                )
                
                LazyColumn(
                    modifier = Modifier.heightIn(max = if (isEasyMode) 400.dp else 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(errors) { error ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onHouseClick(error.houseId) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${error.streetName} (${error.location})",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = error.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(if (isEasyMode) 52.dp else 44.dp),
                shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
            ) {
                Text("Fechar", fontWeight = FontWeight.Bold)
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
