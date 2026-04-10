package com.antigravity.healthagent.ui.supervisor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.ui.components.GlassTopAppBar
import com.antigravity.healthagent.ui.components.MeshGradient
import com.antigravity.healthagent.ui.components.PremiumCard
import java.text.SimpleDateFormat
import java.util.*
import android.content.Context
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorAgentsScreen(
    viewModel: SupervisorViewModel = hiltViewModel(),
    user: com.antigravity.healthagent.domain.repository.AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val agents by viewModel.agents.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isSolarMode by viewModel.solarMode.collectAsState()
    val selectedYear by viewModel.selectedYear.collectAsState()
    val selectedMonth by viewModel.selectedMonth.collectAsState()

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { 
                    Text(
                        "Lista de Agentes", 
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    ) 
                },
                actions = {},
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
            modifier = Modifier.padding(paddingValues).fillMaxSize(),
            indicator = { /* Hide the simple loading circle as we use a premium overlay */ }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                MeshGradient(modifier = Modifier.fillMaxSize())
                
                Column(modifier = Modifier.fillMaxSize()) {
                    // Filter Card
                    PremiumCard(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        isSolarMode = isSolarMode
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.FilterList, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("FILTRAR PRODUÇÃO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Year Selection
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(viewModel.availableYears) { year ->
                                    FilterChip(
                                        selected = selectedYear == year,
                                        onClick = { viewModel.updateYear(year) },
                                        label = { Text(year.toString(), fontWeight = FontWeight.Bold) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Month Selection
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(viewModel.availableMonths.size) { index ->
                                    val monthName = viewModel.availableMonths[index]
                                    val monthValue = index - 1 // -1 for "Ano Todo", 0-11 for months
                                    FilterChip(
                                        selected = selectedMonth == monthValue,
                                        onClick = { viewModel.updateMonth(monthValue) },
                                        label = { Text(monthName, fontWeight = FontWeight.Bold) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.secondary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onSecondary
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Error Message
                    errorMessage?.let { error ->
                        PremiumCard(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            isSolarMode = isSolarMode,
                            containerColor = if (isSolarMode) MaterialTheme.colorScheme.surface 
                                            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
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

                    if (agents.isEmpty() && !isLoading) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = if (errorMessage != null) Icons.Default.Error else Icons.Default.GroupOff, 
                                contentDescription = null, 
                                modifier = Modifier.size(64.dp),
                                tint = if (errorMessage != null) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (errorMessage != null) "Erro ao carregar dados." else "Nenhum agente encontrado.",
                                color = Color.White.copy(alpha = 0.75f)
                            )
                        }
                    } else {
                        val listPadding = if (errorMessage != null) 0.dp else 16.dp
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = listPadding, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(agents) { agent ->
                                SupervisorAgentCard(agent = agent, viewModel = viewModel, isSolarMode = isSolarMode)
                            }
                        }
                    }
                }

                // Premium Loading Overlay (Matches Agent Design)
                com.antigravity.healthagent.ui.components.SupervisorLoadingOverlay(isVisible = isLoading)
            }
        }
    }
}

@Composable
fun SupervisorAgentCard(agent: AgentData, viewModel: SupervisorViewModel, isSolarMode: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val lastSync = remember(agent.lastSyncTime) {
        if (agent.lastSyncTime > 0) {
            SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(agent.lastSyncTime))
        } else "Nunca"
    }

    PremiumCard(
        modifier = Modifier.fillMaxWidth(),
        isSolarMode = isSolarMode,
        onClick = { expanded = !expanded }
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.antigravity.healthagent.ui.components.UserAvatar(
                    uid = agent.uid,
                    displayName = agent.agentName,
                    email = agent.email,
                    photoUrl = agent.photoUrl,
                    size = 48.dp
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    val displayName = agent.agentName?.takeIf { it.isNotBlank() } ?: agent.email.ifBlank { "Sem Email" }
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (agent.agentName?.isNotBlank() == true) {
                        Text(
                            text = agent.email,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Sinc: $lastSync",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val treatedValue = agent.summary?.treatedCount?.toString() ?: agent.houses.count { house ->
                            (house.a1 + house.a2 + house.b + house.c + house.d1 + house.d2 + house.e + house.eliminados) > 0 ||
                            house.larvicida > 0.0 || house.comFoco
                        }.toString()
                        
                        val focusValue = agent.summary?.focusCount?.toString() ?: agent.houses.count { it.comFoco }.toString()
                        val hasFoci = (agent.summary?.focusCount ?: 0) > 0 || agent.houses.any { it.comFoco }

                        AgentStatItem(label = "TRATADOS", value = treatedValue, modifier = Modifier.weight(1f))
                        AgentStatItem(
                            label = "FOCOS", 
                            value = focusValue,
                            color = if (hasFoci) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "SITUAÇÃO DAS VISITAS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Improved Grid Layout for Chips
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val openCount = agent.summary?.let { s -> 
                                (s.situationCounts["NONE"] ?: 0) + (s.situationCounts["EMPTY"] ?: 0)
                            }?.toString() ?: agent.houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.NONE || it.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY }.toString()

                            CompactStatChip(
                                label = "ABERTOS", 
                                value = openCount,
                                modifier = Modifier.weight(1f)
                            )
                            
                            val vCount = agent.summary?.situationCounts?.get("V")?.toString() 
                                ?: agent.houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.V }.toString()

                            CompactStatChip(
                                label = "V", 
                                value = vCount,
                                modifier = Modifier.weight(1f)
                            )
                            
                            val fCount = agent.summary?.situationCounts?.get("F")?.toString() 
                                ?: agent.houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.F }.toString()

                            CompactStatChip(
                                label = "F", 
                                value = fCount,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val recCount = agent.summary?.situationCounts?.get("REC")?.toString() 
                                ?: agent.houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.REC }.toString()

                            CompactStatChip(
                                label = "REC", 
                                value = recCount,
                                modifier = Modifier.weight(1f)
                            )
                            
                            val aCount = agent.summary?.situationCounts?.get("A")?.toString() 
                                ?: agent.houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.A }.toString()

                            CompactStatChip(
                                label = "A", 
                                value = aCount,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.weight(1f)) // Balance the row
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "TIPOS DE IMÓVEIS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val resCount = agent.summary?.propertyTypeCounts?.get("R")?.toString() ?: agent.houses.count { it.propertyType == com.antigravity.healthagent.data.local.model.PropertyType.R }.toString()
                            val comCount = agent.summary?.propertyTypeCounts?.get("C")?.toString() ?: agent.houses.count { it.propertyType == com.antigravity.healthagent.data.local.model.PropertyType.C }.toString()
                            val tbCount = agent.summary?.propertyTypeCounts?.get("TB")?.toString() ?: agent.houses.count { it.propertyType == com.antigravity.healthagent.data.local.model.PropertyType.TB }.toString()

                            CompactStatChip(label = "RES", value = resCount, modifier = Modifier.weight(1f))
                            CompactStatChip(label = "COM", value = comCount, modifier = Modifier.weight(1f))
                            CompactStatChip(label = "TB", value = tbCount, modifier = Modifier.weight(1f))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val peCount = agent.summary?.propertyTypeCounts?.get("PE")?.toString() ?: agent.houses.count { it.propertyType == com.antigravity.healthagent.data.local.model.PropertyType.PE }.toString()
                            val outCount = agent.summary?.propertyTypeCounts?.get("O")?.toString() ?: agent.houses.count { it.propertyType == com.antigravity.healthagent.data.local.model.PropertyType.O }.toString()

                            CompactStatChip(label = "PE", value = peCount, modifier = Modifier.weight(1f))
                            CompactStatChip(label = "OUT", value = outCount, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.weight(1f)) // Balance the row
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "ÚLTIMAS PRODUÇÕES",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val sortedActivities = remember(agent.activities) {
                        val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
                        agent.activities.sortedByDescending { activity ->
                            try {
                                val normalized = activity.date.replace("/", "-")
                                sdf.parse(normalized)?.time ?: 0L
                            } catch (e: Exception) {
                                0L
                            }
                        }.take(5)
                    }

                    if (sortedActivities.isEmpty()) {
                        Text("Nenhuma produção registrada", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        sortedActivities.forEach { activity ->
                            val activityHouses = agent.houses.filter { it.data == activity.date }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (activity.isClosed) Icons.Default.Lock else Icons.Default.LockOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (activity.isClosed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        activity.date,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    "${activityHouses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.NONE || it.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY }} trabalhados",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AgentStatItem(label: String, value: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun CompactStatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
