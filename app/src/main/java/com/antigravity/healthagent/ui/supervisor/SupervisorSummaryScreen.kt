package com.antigravity.healthagent.ui.supervisor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.healthagent.ui.components.GlassTopAppBar
import com.antigravity.healthagent.ui.components.MeshGradient
import com.antigravity.healthagent.ui.components.PremiumCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorSummaryScreen(
    viewModel: SupervisorViewModel = hiltViewModel()
) {
    val summary by viewModel.aggregatedWeeklySummary.collectAsState()
    val weekRange by viewModel.weekRangeText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

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
                }
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            MeshGradient(modifier = Modifier.fillMaxSize())
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem(
                                label = "IMÓVEIS",
                                value = summary.totalHouses.toString(),
                                icon = Icons.Default.Home
                            )
                            StatItem(
                                label = "FOCOS",
                                value = summary.totalFoci.toString(),
                                icon = Icons.Default.Warning,
                                color = if (summary.totalFoci > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
fun StatItem(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
