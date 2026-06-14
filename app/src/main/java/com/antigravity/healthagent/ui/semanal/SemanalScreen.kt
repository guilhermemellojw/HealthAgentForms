package com.antigravity.healthagent.ui.semanal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.ui.state.SyncUiState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.healthagent.ui.semanal.WeeklySummaryViewModel
import com.antigravity.healthagent.ui.components.CompactDropdown
import com.antigravity.healthagent.ui.components.SyncStatusOverlay
import com.antigravity.healthagent.ui.home.DaySummary
import com.antigravity.healthagent.utils.AppConstants
import kotlinx.coroutines.launch
import com.antigravity.healthagent.ui.components.SyncFloatingBalloon
import com.antigravity.healthagent.ui.components.CustomSyncPullIndicator
import com.antigravity.healthagent.ui.components.PremiumCard


import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanalScreen(
    viewModel: WeeklySummaryViewModel = hiltViewModel(),
    onNavigateToDate: (String) -> Unit = {},
    user: com.antigravity.healthagent.domain.repository.AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSyncPullActive: (Boolean) -> Unit = {}
) {
    val weeklySummary by viewModel.weeklySummary.collectAsState()
    val weeklySummaryTotals by viewModel.weeklySummaryTotals.collectAsState()
    val weekRangeText by viewModel.weekRangeText.collectAsState()
    val uiEvent by viewModel.uiEvent.collectAsState()
    val isEasyMode by viewModel.isEasyMode.collectAsState()
    val isSolarMode by viewModel.isSolarMode.collectAsState()
    val weeklyObservations by viewModel.weeklyObservations.collectAsState()
    val activityOptions by viewModel.activityOptions.collectAsState()
    val customActivities by viewModel.customActivities.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState()
    var showAddActivityDialog by remember { mutableStateOf(false) }
    var newActivityName by remember { mutableStateOf("") }

    val context = LocalContext.current
    LaunchedEffect(uiEvent) {
        uiEvent?.let {
            if (it.startsWith("SUCCESS_NAVIGATE_TO:")) {
                val targetDate = it.substringAfter("SUCCESS_NAVIGATE_TO:")
                onNavigateToDate(targetDate)
                viewModel.clearUiEvent()
            } else {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                viewModel.clearUiEvent()
            }
        }
    }



    Scaffold(
        topBar = {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            com.antigravity.healthagent.ui.components.GlassTopAppBar(
                title = { 
                    Text(
                        "Resumo Semanal", 
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    ) 
                },
                actions = {
                    val iconButtonSize = if (isEasyMode) 56.dp else 48.dp
                    val iconSize = if (isEasyMode) 32.dp else 24.dp

                    IconButton(
                        onClick = { showAddActivityDialog = true },
                        modifier = Modifier.size(iconButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Adicionar Atividade",
                            modifier = Modifier.size(iconSize)
                        )
                    }

                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val file = viewModel.exportSemanalPdf(context)
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Compartilhar Resumo Semanal"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Erro ao gerar PDF", Toast.LENGTH_SHORT).show()
                                    AppLogger.e("SemanalScreen", "Erro ao gerar PDF compartilhado", e)
                                }
                            }
                        },
                        modifier = Modifier.size(iconButtonSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = "Gerar PDF",
                            modifier = Modifier.size(iconSize)
                        )
                    }
                },
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onOpenSettings = onOpenSettings
            )
        },
        containerColor = Color.Transparent,
        floatingActionButton = {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        try {
                            val file = viewModel.exportWeeklyBatchPdf(context)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Imprimir Boletins da Semana"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Erro ao gerar PDF", Toast.LENGTH_SHORT).show()
                            AppLogger.e("SemanalScreen", "Erro ao gerar PDF em lote", e)
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Print, "Imprimir Produção") },
                text = { Text(text = "Imprimir Produção") }
            )
        }
    ) { paddingValues ->
        val syncState by viewModel.syncState.collectAsState()
        val pullToRefreshState = rememberPullToRefreshState()

        val isRefreshing = syncState is SyncUiState.Syncing
        val isPullActive = pullToRefreshState.distanceFraction > 0.01f || isRefreshing
        LaunchedEffect(isPullActive) {
            onSyncPullActive(isPullActive)
        }
        DisposableEffect(Unit) {
            onDispose {
                onSyncPullActive(false)
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.syncDataToCloud() },
            state = pullToRefreshState,
            indicator = {
                CustomSyncPullIndicator(
                    state = pullToRefreshState,
                    isRefreshing = isRefreshing,
                    isSolarMode = isSolarMode,
                    syncStatus = syncState
                )
            },
            modifier = Modifier.padding(paddingValues).fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                com.antigravity.healthagent.ui.components.MeshGradient(modifier = Modifier.fillMaxSize())
                
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Week Selector Row
                    PremiumCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        isSolarMode = isSolarMode
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledIconButton(
                                onClick = { viewModel.previousWeek() },
                                modifier = Modifier.size(if (isEasyMode) 56.dp else 44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Semana Anterior",
                                    modifier = Modifier.size(if (isEasyMode) 32.dp else 24.dp)
                                )
                            }
                            
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "PERÍODO DA SEMANA",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Black,
                                    fontSize = if (isEasyMode) 12.sp else 10.sp
                                )
                                Text(
                                    text = weekRangeText,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = if (isEasyMode) 18.sp else 16.sp
                                )
                            }
                            
                            FilledIconButton(
                                onClick = { viewModel.nextWeek() },
                                modifier = Modifier.size(if (isEasyMode) 56.dp else 44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = "Próxima Semana",
                                    modifier = Modifier.size(if (isEasyMode) 32.dp else 24.dp)
                                )
                            }
                        }
                    }

                    // Weekly Summary Card
                    PremiumCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        isSolarMode = isSolarMode
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "ESTATÍSTICAS DA SEMANA",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = if (isEasyMode) 11.sp else 9.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                CompactStatItem("ABERTOS", weeklySummaryTotals.totalWorked.toString(), Icons.Default.Home, isEasyMode = isEasyMode)
                                CompactStatItem("TRATADOS", weeklySummaryTotals.totalTratados.toString(), Icons.Default.WaterDrop, isEasyMode = isEasyMode)
                                CompactStatItem("COM FOCO", weeklySummaryTotals.totalFoci.toString(), Icons.Default.Warning, color = if (weeklySummaryTotals.totalFoci > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, isEasyMode = isEasyMode)
                                CompactStatItem("FECHADOS", weeklySummaryTotals.totalFechados.toString(), Icons.Default.DoorFront, isEasyMode = isEasyMode)
                                CompactStatItem("RECUSADOS", weeklySummaryTotals.totalRecusados.toString(), Icons.Default.Block, isEasyMode = isEasyMode)
                            }
                        }
                    }


                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp, start = 12.dp, end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 16.dp else 12.dp)
                    ) {
                        item {
                            Text(
                                text = "RESUMO POR DIA",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp),
                                fontSize = if (isEasyMode) 12.sp else 10.sp
                            )
                        }

                        itemsIndexed(weeklySummary, key = { _, day -> day.date }) { _, day ->
                            WeeklyDayRow(
                                day = day,
                                options = activityOptions,
                                onStatusChange = { viewModel.updateDayStatus(day.date, it) },
                                onToggleLock = { viewModel.toggleDayLock(day.date) },
                                onClick = { onNavigateToDate(day.date) },
                                isEasyMode = isEasyMode,
                                isSolarMode = isSolarMode,
                                enabled = true
                            )
                        }

                        if (weeklyObservations.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "OBSERVAÇÕES DA SEMANA",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    fontSize = if (isEasyMode) 12.sp else 10.sp
                                )
                            }

                            items(weeklyObservations) { house ->
                                ObservationCard(
                                    house = house,
                                    isEasyMode = isEasyMode,
                                    isSolarMode = isSolarMode,
                                    onClick = { onNavigateToDate(house.data) }
                                )
                            }
                        }
                    }
                }

                if (showAddActivityDialog) {
                    val customActivities = customActivities
                    
                    AlertDialog(
                        onDismissRequest = { showAddActivityDialog = false },
                        title = { Text("Gerenciar Status") },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (customActivities.isNotEmpty()) {
                                    Text(
                                        "Status Personalizados:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp)
                                    ) {
                                        items(customActivities.toList()) { activity ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = activity,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(
                                                    onClick = { viewModel.removeActivity(activity) },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Remover",
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                        }
                                    }
                                } else {
                                    Text(
                                        "Nenhum status personalizado.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Text(
                                    "Adicionar Novo:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = newActivityName,
                                        onValueChange = { newActivityName = it },
                                        label = { Text("Nome do Status") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilledIconButton(
                                        onClick = {
                                            viewModel.addNewActivity(newActivityName.uppercase())
                                            newActivityName = ""
                                        },
                                        enabled = newActivityName.isNotBlank()
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Adicionar")
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showAddActivityDialog = false }) {
                                Text("FECHAR")
                            }
                        }
                    )
                }
            }
        }
    }
}


