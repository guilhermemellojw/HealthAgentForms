package com.antigravity.healthagent.ui.supervisor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.healthagent.ui.components.GlassTopAppBar
import com.antigravity.healthagent.ui.components.MeshGradient
import com.antigravity.healthagent.ui.components.PremiumCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorSummaryScreen(
    viewModel: SupervisorViewModel = hiltViewModel(),
    user: com.antigravity.healthagent.domain.repository.AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val summary by viewModel.aggregatedWeeklySummary.collectAsState()
    val weekRange by viewModel.weekRangeText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    var selectedStatLabel by remember { mutableStateOf<String?>(null) }
    var selectedStatDetails by remember { mutableStateOf<List<StatDetail>>(emptyList()) }
    var showStatDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { 
                    Text(
                        "Resumo da Rede", 
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    ) 
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshData() }) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Atualizar")
                        }
                    }
                },
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onOpenSettings = onOpenSettings
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        val pullToRefreshState = rememberPullToRefreshState()

        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refreshData() },
            state = pullToRefreshState,
            modifier = Modifier.padding(paddingValues).fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                MeshGradient(modifier = Modifier.fillMaxSize())
                
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                // Error Message
                errorMessage?.let { error ->
                    PremiumCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Week Selector
                PremiumCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledIconButton(onClick = { viewModel.previousWeek() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Anterior")
                        }
                        
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "PERÍODO DA SEMANA",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = weekRange,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black
                            )
                        }
                        
                        FilledIconButton(onClick = { viewModel.nextWeek() }) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "Próximo")
                        }
                    }
                }

                // Main Dashboard Card
                PremiumCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(24.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "PRODUÇÃO CONSOLIDADA",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Grid Layout for Stats
                        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatItem(
                                    label = "TRABALHADOS",
                                    value = summary.totalHouses.toString(),
                                    icon = Icons.Default.Home,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        selectedStatLabel = "TRABALHADOS"
                                        selectedStatDetails = summary.housesDetails
                                        showStatDialog = true
                                    }
                                )
                                StatItem(
                                    label = "TRATADOS",
                                    value = summary.totalTratados.toString(),
                                    icon = Icons.Default.WaterDrop,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        selectedStatLabel = "TRATADOS"
                                        selectedStatDetails = summary.tratadosDetails
                                        showStatDialog = true
                                    }
                                )
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatItem(
                                    label = "COM FOCO",
                                    value = summary.totalFoci.toString(),
                                    icon = Icons.Default.Warning,
                                    color = if (summary.totalFoci > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        selectedStatLabel = "COM FOCO"
                                        selectedStatDetails = summary.fociDetails
                                        showStatDialog = true
                                    }
                                )
                                StatItem(
                                    label = "FECHADOS",
                                    value = summary.totalFechados.toString(),
                                    icon = Icons.Default.DoorFront,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        selectedStatLabel = "FECHADOS"
                                        selectedStatDetails = summary.fechadosDetails
                                        showStatDialog = true
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatItem(
                                    label = "ABANDONADOS",
                                    value = summary.totalAbandonados.toString(),
                                    icon = Icons.Default.House,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        selectedStatLabel = "ABANDONADOS"
                                        selectedStatDetails = summary.abandonadosDetails
                                        showStatDialog = true
                                    }
                                )
                                StatItem(
                                    label = "RECUSADOS",
                                    value = summary.totalRecusados.toString(),
                                    icon = Icons.Default.Block,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        selectedStatLabel = "RECUSADOS"
                                        selectedStatDetails = summary.recusadosDetails
                                        showStatDialog = true
                                    }
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem(
                                label = "AGENTES ATIVOS",
                                value = "${summary.activeAgents}/${summary.totalAgents}",
                                icon = Icons.Default.Person
                            )
                        }
                    }
                }
                
                // Helper message
                Text(
                    text = "Este resumo contempla todos os registros enviados pelos agentes para o período selecionado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            if (showStatDialog && selectedStatLabel != null) {
                StatDetailsDialog(
                    onDismissRequest = { showStatDialog = false },
                    label = selectedStatLabel!!,
                    details = selectedStatDetails
                )
            }
        }
    }
}

}
@Composable
fun StatItem(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color.copy(alpha = 0.7f),
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatDetailsDialog(
    onDismissRequest: () -> Unit,
    label: String,
    details: List<StatDetail>
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxWidth(0.9f)
    ) {
        PremiumCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "DETALHAMENTO",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black
                        )
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (details.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Nenhum dado registrado",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        details.forEach { detail ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = detail.agentName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = detail.agentEmail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.padding(start = 12.dp)
                                ) {
                                    Text(
                                        text = detail.count.toString(),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
