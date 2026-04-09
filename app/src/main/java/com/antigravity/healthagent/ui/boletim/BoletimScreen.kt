package com.antigravity.healthagent.ui.boletim

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.alpha

import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.antigravity.healthagent.ui.home.HomeViewModel
import com.antigravity.healthagent.ui.home.BoletimSummary
import com.antigravity.healthagent.ui.home.BlockSummary
import com.antigravity.healthagent.ui.home.DashboardTotals
import com.antigravity.healthagent.data.local.model.House

import com.antigravity.healthagent.ui.components.PremiumCard
import com.antigravity.healthagent.ui.components.CompactDropdown
import com.antigravity.healthagent.ui.components.MeshGradient
import com.antigravity.healthagent.ui.components.SyncStatusOverlay
import com.antigravity.healthagent.ui.components.GlassTopAppBar
import com.antigravity.healthagent.utils.formatStreetName


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoletimScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onOpenSettings: () -> Unit = {},
    user: com.antigravity.healthagent.domain.repository.AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    
    // Logic for Manual Date Transfer
    var moveSourceDate by remember { mutableStateOf("") }
    if (moveSourceDate.isNotBlank()) {
        val calendar = java.util.Calendar.getInstance()
        try {
            val parts = moveSourceDate.split("-")
            if (parts.size == 3) {
                calendar.set(parts[2].toInt(), parts[1].toInt() - 1, parts[0].toInt())
            }
        } catch (e: Exception) { e.printStackTrace() }

        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val newDate = String.format(java.util.Locale("pt", "BR"), "%02d-%02d-%04d", dayOfMonth, month + 1, year)
                if (newDate != moveSourceDate) {
                    viewModel.moveHousesToDate(moveSourceDate, newDate)
                    // Snackbar removed here; ViewModel's uiEvent will handle success/error feedback
                }
                moveSourceDate = ""
            },
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH),
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        ).apply {
            setOnDismissListener { moveSourceDate = "" }
            show()
        }
    }
    

    // Collect UI Events from ViewModel
    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            event?.let {
                snackbarHostState.showSnackbar(it)
                viewModel.clearUiEvent()
            }
        }
    }

    // History Warning Dialog
    var showHistoryWarningDialog by remember { mutableStateOf(false) }
    var pendingHistoryAction by remember { mutableStateOf<() -> Unit>({}) }

    // Delete Confirmation Dialog
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteDate by remember { mutableStateOf("") }
    
    val dateSdf = remember { java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US) }
    
    fun isDateOld(dateStr: String): Boolean {
        return try {
            val dateObj = dateSdf.parse(dateStr)
            val today = java.util.Date()
            if (dateObj != null) {
                val diff = today.time - dateObj.time
                val daysDiff = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diff)
                daysDiff > 7
            } else false
        } catch (e: Exception) {
            false
        }
    }
    
    fun checkHistoryAndProceed(date: String, action: () -> Unit) {
        if (isDateOld(date)) {
            pendingHistoryAction = action
            showHistoryWarningDialog = true
        } else {
            action()
        }
    }

    if (showHistoryWarningDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryWarningDialog = false },
            title = { 
                Text(
                    "Atenção - Dados Históricos", 
                    fontWeight = FontWeight.ExtraBold,
                    style = if (uiState.isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
                ) 
            },
            text = { 
                Text(
                    "Esta produção é anterior a uma semana. Compartilhar ou transferir dados antigos pode afetar a consistência histórica e relatórios de outros agentes.\n\nDeseja realmente prosseguir?",
                    style = if (uiState.isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
                ) 
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showHistoryWarningDialog = false },
                        modifier = Modifier.weight(1f).height(if (uiState.isEasyMode) 52.dp else 48.dp),
                        shape = RoundedCornerShape(if (uiState.isEasyMode) 16.dp else 12.dp)
                    ) {
                        Text("Cancelar", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            showHistoryWarningDialog = false
                            pendingHistoryAction()
                        },
                        modifier = Modifier.weight(1.3f).height(if (uiState.isEasyMode) 52.dp else 48.dp),
                        shape = RoundedCornerShape(if (uiState.isEasyMode) 16.dp else 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Confirmar", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            },
            shape = RoundedCornerShape(if (uiState.isEasyMode) 28.dp else 24.dp)
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { 
                Text(
                    "Excluir Produção", 
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.error,
                    style = if (uiState.isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
                ) 
            },
            text = { 
                Text(
                    "Tem certeza que deseja excluir toda a produção do dia $deleteDate? Esta ação não pode ser desfeita.",
                    style = if (uiState.isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
                ) 
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showDeleteDialog = false },
                        modifier = Modifier.weight(1f).height(if (uiState.isEasyMode) 52.dp else 48.dp),
                        shape = RoundedCornerShape(if (uiState.isEasyMode) 16.dp else 12.dp)
                    ) {
                        Text("Cancelar", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            viewModel.deleteProduction(deleteDate)
                            showDeleteDialog = false
                            // snackbar removed: uiEvent will handle it
                        },
                        modifier = Modifier.weight(1.3f).height(if (uiState.isEasyMode) 52.dp else 48.dp),
                        shape = RoundedCornerShape(if (uiState.isEasyMode) 16.dp else 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Excluir", fontWeight = FontWeight.Bold)
                    }
                }
            },
            shape = RoundedCornerShape(if (uiState.isEasyMode) 28.dp else 24.dp)
        )
    }



    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassTopAppBar(
                title = { 
                    Text(
                        "Produção Diária", 
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    ) 
                },
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onOpenSettings = onOpenSettings
            )
        }
    ) { paddingValues ->
        val isSyncing by viewModel.isSyncing.collectAsState()
        val pullToRefreshState = rememberPullToRefreshState()

        PullToRefreshBox(
            isRefreshing = isSyncing,
            onRefresh = { viewModel.syncDataToCloud() },
            state = pullToRefreshState,
            modifier = Modifier.padding(paddingValues).fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
            com.antigravity.healthagent.ui.components.MeshGradient(modifier = Modifier.fillMaxSize())
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            itemsIndexed(uiState.boletimList, key = { _, summary -> "${summary.date}|${summary.agentName}" }) { index, summary ->
                PremiumCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.navigateToDate(summary.date) },
                    isSolarMode = uiState.isSolarMode
                ) {
                    Column(modifier = Modifier.padding(if (uiState.isEasyMode) 20.dp else 16.dp)) {
                        // Header Row: Status and Title
                        // Header Row: Status and Title
                        // Header Row: Date and Agent
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Date (Primary Title)
                            Text(
                                text = summary.date,
                                style = if (uiState.isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )

                            // Agent Name in Top-Right
                            if (summary.agentName.isNotBlank()) {
                                Text(
                                    text = summary.agentName.uppercase(),
                                    style = if (uiState.isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Detailed Blocks Section
                        summary.blocks.forEachIndexed { index, block ->
                             Column {
                                 Row(
                                     modifier = Modifier.fillMaxWidth(),
                                     horizontalArrangement = Arrangement.SpaceBetween,
                                     verticalAlignment = Alignment.CenterVertically
                                 ) {
                                     Column(modifier = Modifier.weight(1f)) {
                                         Row(verticalAlignment = Alignment.CenterVertically) {
                                             Text(
                                                 text = block.bairro.uppercase(),
                                                 style = if (uiState.isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                                                 fontWeight = FontWeight.Bold,
                                                 color = MaterialTheme.colorScheme.onSurface
                                             )
                                             
                                             if (block.isLocalidadeConcluded) {
                                                 Spacer(Modifier.width(8.dp))
                                                 StatusBadge(
                                                     text = "LOCALIDADE CONCLUÍDA",
                                                     containerColor = androidx.compose.ui.graphics.Color(0xFF009688), // Teal
                                                     contentColor = androidx.compose.ui.graphics.Color.White
                                                 )
                                             }
                                         }
                                         Text(
                                             text = "Quarteirão ${block.number}",
                                             style = if (uiState.isEasyMode) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                                             color = MaterialTheme.colorScheme.onSurfaceVariant
                                         )
                                         
                                         Spacer(Modifier.height(4.dp))
                                         
                                         // Inline Stats per Block
                                         Row(verticalAlignment = Alignment.CenterVertically) {
                                             Text(
                                                 text = "Trabalhados: ${block.totalHouses}",
                                                 style = if (uiState.isEasyMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium,
                                                 color = MaterialTheme.colorScheme.primary,
                                                 fontWeight = FontWeight.Bold
                                             )
                                             Text(" • ", color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                             Text(
                                                 text = "Visitas: ${block.totalVisits}",
                                                 style = if (uiState.isEasyMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium,
                                                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                 fontWeight = FontWeight.Bold
                                             )
                                             if (block.focos > 0) {
                                                 Text(" • ", color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                                 Text(
                                                     text = "Focos: ${block.focos}",
                                                     style = if (uiState.isEasyMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium,
                                                     color = MaterialTheme.colorScheme.error,
                                                     fontWeight = FontWeight.Black
                                                 )
                                             }
                                         }
                                     }
                                     
                                     // Individual Status Badge
                                     StatusBadge(
                                         text = if (block.isCompleted) "CONCLUÍDO" else "EM ABERTO",
                                         containerColor = if (block.isCompleted) 
                                             androidx.compose.ui.graphics.Color(0xFF4CAF50) // Green 
                                         else 
                                             androidx.compose.ui.graphics.Color(0xFFFF9800), // Orange
                                         contentColor = androidx.compose.ui.graphics.Color.White
                                     )
                                 }
                                 
                                 if (index < summary.blocks.size - 1) {
                                     Divider(
                                         modifier = Modifier.padding(vertical = if (uiState.isEasyMode) 16.dp else 12.dp),
                                         color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                     )
                                 }
                             }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Actions row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(if (uiState.isEasyMode) 10.dp else 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Generate PDF Button
                            FilledTonalButton(
                                onClick = {
                                    checkHistoryAndProceed(summary.date) {
                                        scope.launch {
                                            try {
                                                val houses = viewModel.getHousesForDate(summary.date, summary.agentName)
                                                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    com.antigravity.healthagent.utils.BoletimPdfGenerator.generatePdf(
                                                        context,
                                                        houses,
                                                        summary.date,
                                                        if (summary.agentName.isNotBlank()) summary.agentName else uiState.agentName
                                                    )
                                                }
                                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    file
                                                )
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "application/pdf"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Compartilhar FAD"))
                                            } catch (e: Exception) {
                                                scope.launch { snackbarHostState.showSnackbar("Erro ao gerar PDF") }
                                                e.printStackTrace()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(if (uiState.isEasyMode) 52.dp else 40.dp),
                                shape = RoundedCornerShape(if (uiState.isEasyMode) 16.dp else 12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(if (uiState.isEasyMode) 20.dp else 16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Gerar PDF", style = if (uiState.isEasyMode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge)
                            }

                            // WhatsApp Button
                            Button(
                                onClick = {
                                    checkHistoryAndProceed(summary.date) {
                                        scope.launch {
                                            try {
                                                val shareName = if (summary.agentName.isNotBlank()) summary.agentName else uiState.agentName
                                                val houses = viewModel.getHousesForDate(summary.date, summary.agentName)
                                                shareToWhatsApp(context, shareName, summary.date, houses)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Erro ao compartilhar.", Toast.LENGTH_SHORT).show()
                                                e.printStackTrace()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(if (uiState.isEasyMode) 52.dp else 40.dp),
                                shape = RoundedCornerShape(if (uiState.isEasyMode) 16.dp else 12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF25D366)),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Default.Send, null, modifier = Modifier.size(if (uiState.isEasyMode) 20.dp else 16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("WhatsApp", style = if (uiState.isEasyMode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge)
                            }
                            
                            var showMenu by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier
                                    .size(if (uiState.isEasyMode) 48.dp else 40.dp)
                            ) {
                                Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Ver Detalhes") },
                                        onClick = { showMenu = false; viewModel.navigateToDate(summary.date) },
                                        leadingIcon = { Icon(imageVector = Icons.Default.Visibility, contentDescription = null) }
                                    )
                                    // Move Date
                                    DropdownMenuItem(
                                        text = { Text("Mover Data") },
                                        leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            checkHistoryAndProceed(summary.date) {
                                                moveSourceDate = summary.date
                                            }
                                        }
                                    )
                                    // Export JSON
                                    DropdownMenuItem(
                                        text = { Text("Exportar JSON") },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            checkHistoryAndProceed(summary.date) {
                                                viewModel.exportDayDataAndShare(context, summary.date)
                                            }
                                        }
                                    )
                                    Divider()
                                    // Delete
                                    DropdownMenuItem(
                                        text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showMenu = false
                                            checkHistoryAndProceed(summary.date) {
                                                deleteDate = summary.date
                                                showDeleteDialog = true
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.boletimList.isEmpty()) {
                item {
                    com.antigravity.healthagent.ui.components.EmptyStateView(
                        message = "Nenhum registro encontrado",
                        subMessage = "Não há boletins de produção para exibir. Inicie um novo dia na tela inicial para começar.",
                        icon = Icons.Default.DateRange
                    )
                }
            }
            }
        }
    }
}
}



@Composable
fun StatusBadge(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, containerColor.copy(alpha = 0.5f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = containerColor,
            fontSize = 9.sp
        )
    }
}

private fun shareToWhatsApp(
    context: Context,
    agentName: String,
    date: String,
    houses: List<com.antigravity.healthagent.data.local.model.House>
) {
    if (houses.isEmpty()) {
        Toast.makeText(context, "Nenhum imóvel encontrado para esta data", Toast.LENGTH_SHORT).show()
        return
    }

    // Calculate statistics
    val trabalhados = houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.NONE }
    val tratados = houses.count {
        (it.a1 + it.a2 + it.b + it.c + it.d1 + it.d2 + it.e + it.eliminados) > 0 || 
        it.larvicida > 0.0 || it.comFoco
    }
    val fechados = houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.F }
    val recusados = houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.REC }
    val abandonados = houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.A }
    val comFoco = houses.count { it.comFoco }

    // Format WhatsApp message
    val sb = StringBuilder()
    sb.append("📊 *Relatório Diário - $date*\n")
    sb.append("👤 *Agente:* ${agentName.uppercase()}\n\n")

    sb.append("*RESUMO:*\n")
    sb.append("🏡 Trabalhados: $trabalhados\n")
    sb.append("🚪 Fechados: $fechados\n")
    sb.append("💧 Tratados: $tratados\n")
    sb.append("🦟 Com Foco: $comFoco\n")
    sb.append("🚫 Recusados: $recusados\n")
    sb.append("🏚️ Abandonados: $abandonados\n")


    val message = sb.toString().trim()

    // Create WhatsApp share intent
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
        setPackage("com.whatsapp") // Specific to WhatsApp
    }

    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // WhatsApp not installed, use generic share
        val genericIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        context.startActivity(Intent.createChooser(genericIntent, "Compartilhar via"))
    }
}
