package com.antigravity.healthagent.ui.admin

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.healthagent.domain.repository.BackupMetadata
import com.antigravity.healthagent.ui.components.GlassTopAppBar
import com.antigravity.healthagent.ui.components.MeshGradient
import com.antigravity.healthagent.ui.components.PremiumCard
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminTimelineScreen(
    agentUid: String,
    agentName: String,
    viewModel: AdminViewModel,
    onNavigateBack: () -> Unit
) {
    val timeline by viewModel.timeline.collectAsState()
    val isLoading by viewModel.isTimelineLoading.collectAsState()
    
    var selectedBackup by remember { mutableStateOf<BackupMetadata?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(agentUid) {
        viewModel.loadTimeline(agentUid)
    }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { 
                    Column {
                        Text("Timeline de Backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                        Text(agentName, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            MeshGradient(modifier = Modifier.fillMaxSize())
            
            if (isLoading && timeline.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (timeline.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.History, null, modifier = Modifier.size(64.dp), tint = Color.White.copy(alpha = 0.3f))
                    Spacer(Modifier.height(16.dp))
                    Text("Nenhum backup encontrado na nuvem.", color = Color.White.copy(alpha = 0.7f))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(timeline) { backup ->
                        TimelineItem(
                            backup = backup,
                            onRestore = {
                                selectedBackup = backup
                                showConfirmDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showConfirmDialog && selectedBackup != null) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Restaurar Snapshot") },
            text = {
                val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(selectedBackup!!.timestamp))
                Text("Deseja restaurar o estado de produção de $date?\n\nIsso irá substituir os dados ATUAIS da nuvem por esta versão. O agente receberá um aviso para resetar o app local.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.restoreFromTimeline(agentUid, selectedBackup!!.storagePath)
                        showConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Restaurar Agora") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
fun TimelineItem(
    backup: BackupMetadata,
    onRestore: () -> Unit
) {
    val date = remember(backup.timestamp) {
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(backup.timestamp))
    }
    val time = remember(backup.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(backup.timestamp))
    }

    PremiumCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Timeline Node Icon
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.CloudDownload, null, modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Box(modifier = Modifier.width(2.dp).height(24.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)))
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(date, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                
                Spacer(Modifier.height(4.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BackupBadge(Icons.Default.Home, "${backup.houseCount} imóveis")
                    BackupBadge(Icons.Default.Assignment, "${backup.activityCount} dias")
                }
            }
            
            IconButton(onClick = onRestore) {
                Icon(Icons.Default.SettingsBackupRestore, "Restaurar", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun BackupBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
