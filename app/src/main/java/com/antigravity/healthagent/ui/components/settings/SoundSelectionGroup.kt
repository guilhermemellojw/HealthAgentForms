package com.antigravity.healthagent.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateContentSize
import com.antigravity.healthagent.ui.settings.SettingsViewModel
import com.antigravity.healthagent.utils.SoundCategory

@Composable
fun SoundSelectionGroup(
    title: String,
    description: String,
    popSound: String,
    successSound: String,
    celebrationSound: String,
    warningSound: String,
    expandedCategory: String?,
    onExpandToggle: (String) -> Unit,
    onSoundSelect: (String, String) -> Unit,
    onSystemPickerClick: (SoundCategory, String) -> Unit,
    onCustomFileClick: (SoundCategory) -> Unit,
    onTestSound: (String) -> Unit,
    context: android.content.Context,
    viewModel: SettingsViewModel
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 16.dp)
    ) {
        Icon(Icons.Default.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    
    Spacer(modifier = Modifier.height(16.dp))
    
    val sounds = listOf(
        Triple("POP", "Som de Clique (Adicionar Imóvel)", popSound),
        Triple("SUCCESS", "Som de Sucesso (Fechar Dia)", successSound),
        Triple("CELEBRATION", "Som de Celebração (Meta)", celebrationSound),
        Triple("WARNING", "Som de Alerta (Erros)", warningSound)
    )
    
    sounds.forEachIndexed { index, (id, label, currentUri) ->
        val isExpanded = expandedCategory == id
        val currentTitle = remember(currentUri) { viewModel.getSoundTitle(currentUri, context) }
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onExpandToggle(id) }
                .padding(vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(currentTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            if (isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp).animateContentSize()) {
                    Row(modifier = Modifier.fillMaxWidth().clickable { onSoundSelect(id, "SILENT") }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentUri == "SILENT", onClick = { onSoundSelect(id, "SILENT") })
                        Text("Silencioso", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(modifier = Modifier.fillMaxWidth().clickable { 
                        onSystemPickerClick(SoundCategory.valueOf(id), currentUri) 
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentUri.startsWith("content://"), onClick = { 
                            onSystemPickerClick(SoundCategory.valueOf(id), currentUri) 
                        })
                        Text("Som do Sistema", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(modifier = Modifier.fillMaxWidth().clickable { 
                        onCustomFileClick(SoundCategory.valueOf(id)) 
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentUri.startsWith("file://"), onClick = { 
                            onCustomFileClick(SoundCategory.valueOf(id)) 
                        })
                        Text("Personalizado", style = MaterialTheme.typography.bodyMedium)
                    }
                    
                    if (currentUri != "SILENT") {
                        TextButton(
                            onClick = { onTestSound(currentUri) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Testar")
                        }
                    }
                }
            }
        }
        
        if (index < sounds.size - 1) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}
