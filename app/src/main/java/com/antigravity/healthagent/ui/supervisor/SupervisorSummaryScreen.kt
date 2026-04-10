package com.antigravity.healthagent.ui.supervisor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
    val summary by viewModel.aggregatedSummary.collectAsState()
    val weekRange by viewModel.weekRangeText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    val selectedYear by viewModel.selectedYear.collectAsState()
    val selectedMonth by viewModel.selectedMonth.collectAsState()
    val selectedWeekIndex by viewModel.selectedWeekIndex.collectAsState()
    val weeksInMonth by viewModel.weeksInMonth.collectAsState()
    
    var filterExpanded by remember { mutableStateOf(false) }
    
    var selectedStatLabel by remember { mutableStateOf<String?>(null) }
    var selectedStatDetails by remember { mutableStateOf<List<StatDetail>>(emptyList()) }
    var showStatDialog by remember { mutableStateOf(false) }
    
    val uiEvent by viewModel.uiEvent.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiEvent) {
        uiEvent?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUiEvent()
        }
    }

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
                actions = {},
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onOpenSettings = onOpenSettings
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

                    // Collapsible Filter Selector
                    PremiumCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { filterExpanded = !filterExpanded }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.FilterList, 
                                        contentDescription = null, 
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "FILTRAR PRODUÇÃO",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Black
                                        )
                                        Text(
                                            text = weekRange,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = if (filterExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            androidx.compose.animation.AnimatedVisibility(visible = filterExpanded) {
                                Column {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    HorizontalDivider(
                                        modifier = Modifier.padding(bottom = 12.dp),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    )
                                    
                                    // Year Selection
                                    Text("Ano", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 4.dp))
                                    androidx.compose.foundation.lazy.LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(viewModel.availableYears.size) { index ->
                                            val year = viewModel.availableYears[index]
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
                                    
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    // Month Selection
                                    Text("Mês", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 4.dp))
                                    val filteredMonths = viewModel.getFilteredMonths()
                                    androidx.compose.foundation.lazy.LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(filteredMonths.size) { index ->
                                            val monthName = filteredMonths[index]
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
                                    
                                    // Week Selection (Only if a month is selected)
                                    if (selectedMonth != -1 && weeksInMonth.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("Semana", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 4.dp))
                                        androidx.compose.foundation.lazy.LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            item {
                                                FilterChip(
                                                    selected = selectedWeekIndex == -1,
                                                    onClick = { viewModel.updateWeek(-1) },
                                                    label = { Text("Mês Todo", fontWeight = FontWeight.Bold) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                                                        selectedLabelColor = MaterialTheme.colorScheme.onTertiary
                                                    )
                                                )
                                            }
                                            items(weeksInMonth.size) { index ->
                                                val week = weeksInMonth[index]
                                                FilterChip(
                                                    selected = selectedWeekIndex == index,
                                                    onClick = { viewModel.updateWeek(index) },
                                                    label = { Text(week.label.split(" (")[0], fontWeight = FontWeight.Bold) }, // Just "Semana X"
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                                                        selectedLabelColor = MaterialTheme.colorScheme.onTertiary
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Main Dashboard Card
                    PremiumCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(20.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "PRODUÇÃO CONSOLIDADA",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Grid Layout for Stats - More Compact
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    StatItem(
                                        label = "TRABALHADOS",
                                        value = summary.totalWorked.toString(),
                                        icon = Icons.Default.Home,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            selectedStatLabel = "TRABALHADOS"
                                            selectedStatDetails = summary.housesDetails
                                            showStatDialog = true
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
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
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            
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
                        color = Color.White.copy(alpha = 0.85f),
                        fontWeight = FontWeight.Bold,
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

                // Premium Loading Overlay (Matches Agent Design)
                com.antigravity.healthagent.ui.components.SupervisorLoadingOverlay(isVisible = isLoading)
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
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
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
    BasicAlertDialog(
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
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                com.antigravity.healthagent.ui.components.UserAvatar(
                                    uid = detail.agentUid,
                                    displayName = detail.agentName,
                                    email = detail.agentEmail,
                                    photoUrl = detail.photoUrl,
                                    size = 40.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
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
                                    modifier = Modifier.padding(start = 8.dp)
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
