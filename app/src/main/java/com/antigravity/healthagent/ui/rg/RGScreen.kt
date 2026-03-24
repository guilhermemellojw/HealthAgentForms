package com.antigravity.healthagent.ui.rg

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.healthagent.ui.components.SyncStatusOverlay
import com.antigravity.healthagent.ui.components.HouseRowItem
import com.antigravity.healthagent.ui.home.HomeViewModel
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.outlined.Info
import kotlinx.coroutines.launch
import com.antigravity.healthagent.utils.formatStreetName


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RGScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    user: com.antigravity.healthagent.domain.repository.AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // State for showing loading or error
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Back Handler Logic (optional, but good for UX)
    BackHandler(enabled = uiState.selectedRgBlock.isNotBlank()) {
        viewModel.selectRgBlock("")
    }



    Scaffold(
        topBar = {
            com.antigravity.healthagent.ui.components.GlassTopAppBar(
                title = { 
                    Text(
                        if (uiState.selectedRgBlock.isNotBlank()) "Detalhes do Quarteirão" else "Registro Geral", 
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    ) 
                },
                navigationIcon = {
                    if (uiState.selectedRgBlock.isNotBlank()) {
                        IconButton(onClick = { viewModel.selectRgBlock("") }) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                                contentDescription = "Voltar"
                            )
                        }
                    }
                },
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onOpenSettings = onOpenSettings
            )
        },
        floatingActionButton = {
            if (uiState.selectedRgBlock.isNotBlank() && uiState.rgFilteredList.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                             try {
                                 // Find the selected block label for the PDF name
                                 val block = uiState.rgBlocks.find { it.id == uiState.selectedRgBlock }
                                 val blockLabel = if (block != null) {
                                     "${block.blockNumber}${if (block.blockSequence.isNotBlank()) " / ${block.blockSequence}" else ""}"
                                 } else {
                                     "Quarteirao"
                                 }
                                 
                                 val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                     com.antigravity.healthagent.utils.RGPdfGenerator.generatePdf(
                                         context, 
                                         uiState.rgFilteredList, 
                                         uiState.selectedRgBairro, 
                                         blockLabel, // Pass the formatted label
                                         municipio = uiState.municipality
                                     )
                                 }
                                 sharePdf(context, file)
                             } catch (e: Exception) {
                                 e.printStackTrace()
                                 Toast.makeText(context, "Erro ao gerar PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                             }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.PictureAsPdf, "Exportar PDF")
                }
            }
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
            // Filters Header (Only visible in Selection Mode)
            if (uiState.selectedRgBlock.isBlank()) {
                com.antigravity.healthagent.ui.components.PremiumCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    isSolarMode = uiState.isSolarMode
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Filtros",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val availableYears = uiState.availableYears
                            val selectedYear = uiState.rgYear
                            
                            Box(modifier = Modifier.weight(1f)) {
                                com.antigravity.healthagent.ui.components.CompactDropdown(
                                    label = "Ano",
                                    currentValue = selectedYear,
                                    options = availableYears,
                                    onOptionSelected = { viewModel.selectRgYear(it) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
    
                            // Semester Filter REMOVED
                            Box(modifier = Modifier.weight(2f)) {
                                com.antigravity.healthagent.ui.components.CompactDropdown(
                                    label = "Bairro",
                                    currentValue = uiState.selectedRgBairro,
                                    options = uiState.rgBairros,
                                    onOptionSelected = { viewModel.selectRgBairro(it) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            // Body Content
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (uiState.selectedRgBlock.isBlank()) {
                    // SELECTION MODE: Grid of Cards
                    if (uiState.selectedRgBairro.isNotBlank()) {
                        if (uiState.rgBlocks.isEmpty()) {
                             Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text(
                                    "Nenhum quarteirão encontrado.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 300.dp),
                                contentPadding = PaddingValues(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(uiState.rgBlocks, key = { _, segment -> segment.id }) { index, segment ->
                                    Box(
                                        modifier = Modifier.let { 
                                            if (index == 0) it else it 
                                        }
                                    ) {
                                        com.antigravity.healthagent.ui.components.RGBlockCard(
                                            segment = segment,
                                            isEasyMode = uiState.isEasyMode,
                                            isSolarMode = uiState.isSolarMode,
                                            onClick = { viewModel.selectRgBlock(segment.id) }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                         Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text(
                                "Selecione um Bairro acima.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // DETAIL MODE: List of Houses
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 100.dp)
                    ) {
                        if (uiState.rgFilteredList.isEmpty()) {
                             item {
                                Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Outlined.Info,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            tint = MaterialTheme.colorScheme.outline
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "Nenhum imóvel encontrado.",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            items(uiState.rgFilteredList, key = { it.id }) { house ->
                                RGHouseRow(
                                    house = house,
                                    isEasyMode = uiState.isEasyMode
                                )
                            }
                        }
                    }
                }
            }
        
        }
    }
}
}
}

private fun sharePdf(context: android.content.Context, file: java.io.File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    
    try {
        context.startActivity(android.content.Intent.createChooser(intent, "Compartilhar RG"))
    } catch (e: Exception) {
        Toast.makeText(context, "Erro ao compartilhar PDF", Toast.LENGTH_SHORT).show()
    }
}
