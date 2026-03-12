package com.antigravity.healthagent.ui.semanal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.healthagent.ui.home.HomeViewModel
import com.antigravity.healthagent.ui.components.CompactDropdown
import com.antigravity.healthagent.ui.home.DaySummary
import com.antigravity.healthagent.utils.AppConstants
import kotlinx.coroutines.launch
import com.antigravity.healthagent.ui.components.PremiumCard


import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanalScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    user: com.antigravity.healthagent.domain.repository.AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {}
) {
    val weeklySummary by viewModel.weeklySummary.collectAsState()
    val activityOptions by viewModel.activityOptions.collectAsState()
    val isEasyMode by viewModel.easyMode.collectAsState()
    val isSolarMode by viewModel.solarMode.collectAsState()
    var showAddActivityDialog by remember { mutableStateOf(false) }
    var newActivityName by remember { mutableStateOf("") }



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
                                    e.printStackTrace()
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
                onSwitchAccount = onSwitchAccount
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
                            e.printStackTrace()
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
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            com.antigravity.healthagent.ui.components.MeshGradient(modifier = Modifier.fillMaxSize())
            val currentWeekDates by viewModel.currentWeekDates.collectAsState()
            val weekRangeText by viewModel.weekRangeText.collectAsState()

            Column(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            // Week Selector Row
                PremiumCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
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

            // Agent Selection
            val agentName by viewModel.agentName.collectAsState()
            PremiumCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                isSolarMode = isSolarMode
            ) {
                CompactDropdown(
                    label = "Agente Responsável",
                    currentValue = agentName,
                    options = AppConstants.AGENT_NAMES,
                    onOptionSelected = { viewModel.updateAgentName(it) },
                    modifier = Modifier.fillMaxWidth(),
                    isEasyMode = isEasyMode
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp, start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 16.dp else 12.dp)
            ) {
                itemsIndexed(weeklySummary, key = { _, day -> day.date }) { index, day ->
                    WeeklyDayRow(
                        day = day,
                        options = activityOptions,
                        onStatusChange = { viewModel.updateDayStatus(day.date, it) },
                        onClick = { viewModel.navigateToDate(day.date) },
                        isEasyMode = isEasyMode,
                        isSolarMode = isSolarMode,
                        modifier = Modifier
                    )
                }
            }

            if (showAddActivityDialog) {
                val customActivities by viewModel.customActivities.collectAsState()
                
                AlertDialog(
                    onDismissRequest = { showAddActivityDialog = false },
                    title = { Text("Gerenciar Status") },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // List of existing custom activities
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
                            
                            // Input for new activity
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

@Composable
fun WeeklyDayRow(
    day: DaySummary,
    options: List<String>,
    onStatusChange: (String) -> Unit,
    onClick: () -> Unit,
    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val dayOfWeek = remember(day.date) {
        val cal = java.util.Calendar.getInstance()
        val parts = day.date.split("-")
        if (parts.size == 3) {
            cal.set(parts[2].toInt(), parts[1].toInt() - 1, parts[0].toInt())
            when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> "Segunda-feira"
                java.util.Calendar.TUESDAY -> "Terça-feira"
                java.util.Calendar.WEDNESDAY -> "Quarta-feira"
                java.util.Calendar.THURSDAY -> "Quinta-feira"
                java.util.Calendar.FRIDAY -> "Sexta-feira"
                else -> ""
            }
        } else ""
    }

    PremiumCard(
        modifier = Modifier.fillMaxWidth().then(modifier),
        onClick = onClick,
        isSolarMode = isSolarMode,
        contentPadding = if (isEasyMode) PaddingValues(8.dp) else PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1.2f)) {
                Text(
                    text = dayOfWeek.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    fontSize = if (isEasyMode) 12.sp else 10.sp
                )
                Text(
                    text = day.date,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = if (isEasyMode) 18.sp else 16.sp
                )
            }

            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.width(if (isEasyMode) 72.dp else 64.dp).height(if (isEasyMode) 56.dp else 50.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "ABERTOS", 
                        style = MaterialTheme.typography.labelSmall, 
                        color = MaterialTheme.colorScheme.primary, 
                        fontSize = if (isEasyMode) 10.sp else 8.sp, 
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        day.totalHouses.toString(), 
                        style = MaterialTheme.typography.titleMedium, 
                        fontWeight = FontWeight.Black, 
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = if (isEasyMode) 20.sp else 16.sp
                    )
                }
            }

            CompactDropdown(
                label = "Status",
                currentValue = day.status.ifEmpty { "NORMAL" },
                options = options,
                onOptionSelected = onStatusChange,
                modifier = Modifier.weight(2f),
                isEasyMode = isEasyMode
            )
        }
    }
}
