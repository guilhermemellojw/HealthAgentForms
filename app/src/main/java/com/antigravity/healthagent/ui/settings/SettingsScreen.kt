package com.antigravity.healthagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.healthagent.data.backup.BackupFrequency
import com.antigravity.healthagent.ui.home.HomeViewModel
import com.antigravity.healthagent.ui.theme.AppColors 
import com.antigravity.healthagent.ui.components.PremiumCard
import com.antigravity.healthagent.ui.components.GlassTopAppBar
import com.antigravity.healthagent.ui.components.MeshGradient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenAdmin: () -> Unit = {},
    isAdmin: Boolean = false,
    isSupervisor: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
    user: com.antigravity.healthagent.domain.repository.AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {}
) {
    val canAccessAdmin = isAdmin || isSupervisor
    // Intercept system back button
    androidx.activity.compose.BackHandler {
        onNavigateBack()
    }

    val selectedFrequency by viewModel.backupFrequency.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    
    val snackbarHostState = remember { SnackbarHostState() }
    val uiEvent by viewModel.uiEvent.collectAsState()

    LaunchedEffect(uiEvent) {
        uiEvent?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearUiEvent()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshConfig()
    }

    // State for Restore Dialog
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showEraseConfirm by remember { mutableStateOf(false) }
    var showCleanupPicker by remember { mutableStateOf(false) }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    var selectedCleanupDate by remember { mutableStateOf("") }
    
    val datePickerState = rememberDatePickerState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val context = LocalContext.current

    // Launcher de importação de dados
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.restoreData(context, it) }
    }

    val importDayLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
         uri?.let { viewModel.importDayData(context, it) }
    }

    // Audio file pickers for custom sounds
    val importPopSoundLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importCustomSound(it, com.antigravity.healthagent.utils.SoundCategory.POP) }
    }

    val importSuccessSoundLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importCustomSound(it, com.antigravity.healthagent.utils.SoundCategory.SUCCESS) }
    }

    val importCelebrationSoundLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importCustomSound(it, com.antigravity.healthagent.utils.SoundCategory.CELEBRATION) }
    }

    val importWarningSoundLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importCustomSound(it, com.antigravity.healthagent.utils.SoundCategory.WARNING) }
    }

    if (showRestoreConfirm) {
         AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restaurar Backup") },
            text = { Text("Atenção: Restaurar um backup substituirá TODOS os dados atuais. Deseja continuar?") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    importLauncher.launch(arrayOf("application/json"))
                }) {
                    Text("Continuar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancelar") }
            }
        )
    }

    if (showCleanupPicker) {
        DatePickerDialog(
            onDismissRequest = { showCleanupPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val selection = datePickerState.selectedDateMillis
                    if (selection != null) {
                        val sdf = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US)
                        selectedCleanupDate = sdf.format(java.util.Date(selection))
                        showCleanupPicker = false
                        showCleanupConfirm = true
                    }
                }) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupPicker = false }) { Text("Cancelar") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showCleanupConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirm = false },
            title = { Text("Confirmar Limpeza GLOBAL") },
            text = { Text("ATENÇÃO: Isso removerá permanentemente os dados de TODOS OS AGENTES anteriores a $selectedCleanupDate, tanto localmente quanto na nuvem. Esta ação afetará todo o sistema. Deseja continuar?") },
            confirmButton = {
                Button(
                    onClick = {
                        showCleanupConfirm = false
                        viewModel.cleanupHistoricalData(selectedCleanupDate)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sim, Limpar Histórico")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) { Text("Cancelar") }
            }
        )
    }

    if (showEraseConfirm) {
        AlertDialog(
           onDismissRequest = { showEraseConfirm = false },
           title = { Text("Apagar TUDO (Global)?") },
           text = { Text("NUCLEAR: Isso removerá permanentemente TODOS os imóveis e dados de produção de TODOS OS AGENTES na nuvem e localmente. O sistema será resetado. Essa ação NÃO pode ser desfeita.") },
           confirmButton = {
               Button(
                   onClick = {
                       showEraseConfirm = false
                       viewModel.clearAllData()
                   },
                   colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
               ) {
                   Text("Sim, Apagar Tudo", fontWeight = FontWeight.Bold)
               }
           },
           dismissButton = {
               TextButton(onClick = { showEraseConfirm = false }) { Text("Cancelar") }
           }
       )
    }

    // State for tracking which sound category is being picked
    var activeSoundCategory by remember { mutableStateOf<com.antigravity.healthagent.utils.SoundCategory?>(null) }
    
    // Ringtone Picker Intent Launcher
    val ringtonePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
             val uri: android.net.Uri? = result.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
             uri?.let {
                 val uriString = it.toString()
                 when (activeSoundCategory) {
                     com.antigravity.healthagent.utils.SoundCategory.POP -> viewModel.updatePopSound(uriString)
                     com.antigravity.healthagent.utils.SoundCategory.SUCCESS -> viewModel.updateSuccessSound(uriString)
                     com.antigravity.healthagent.utils.SoundCategory.CELEBRATION -> viewModel.updateCelebrationSound(uriString)
                     com.antigravity.healthagent.utils.SoundCategory.WARNING -> viewModel.updateWarningSound(uriString)
                     null -> {}
                 }
             }
        }
    }

    // Function to launch picker
    fun launchSystemPicker(category: com.antigravity.healthagent.utils.SoundCategory, currentUri: String) {
        activeSoundCategory = category
        val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            
            val existingUri = if (currentUri.startsWith("content://")) android.net.Uri.parse(currentUri) else null
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
        }
        ringtonePickerLauncher.launch(intent)
    }

    val isEasyMode by viewModel.easyMode.collectAsState()
    val isSolarMode by viewModel.solarMode.collectAsState()
    
    val itemContainerColor = if (isSolarMode) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val itemBorderColor = if (isSolarMode) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    
    val dangerContainerColor = if (isSolarMode) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
    val dangerBorderColor = if (isSolarMode) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { 
                    Text(
                        "Configurações", 
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                },
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount
            )
        },
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            MeshGradient(modifier = Modifier.fillMaxSize())
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // --- SEÇÃO 1: PERSONALIZAÇÃO ---
                item {
                    SettingsSection(
                        title = "Personalização",
                        icon = Icons.Outlined.Palette,
                        isSolarMode = isSolarMode
                    ) {
                        val currentMode by viewModel.themeMode.collectAsState(initial = "SYSTEM")
                        
                        Text(
                            text = "Modo do Tema",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val options = listOf("SYSTEM" to "Sistema", "LIGHT" to "Claro", "DARK" to "Escuro")
                            options.forEachIndexed { index, (mode, label) ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                                    onClick = { viewModel.updateThemeMode(mode) },
                                    selected = currentMode == mode,
                                    label = { 
                                        Text(
                                            text = label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        ) 
                                    },
                                    icon = {
                                        SegmentedButtonDefaults.Icon(active = currentMode == mode)
                                    }
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                        val currentColor by viewModel.themeColor.collectAsState(initial = "EMERALD")
                        
                        Text(
                            text = "Cor de Destaque (${currentColor?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            items(AppColors.toList()) { (name, color) ->
                                val isSelected = currentColor == name
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 54.dp else 48.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .clickable { 
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            viewModel.updateThemeColor(name) 
                                        }
                                        .then(
                                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier
                                        )
                                        .animateContentSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        
                        // Efeitos Sonoros moved inside Personalização
                        val popSound by viewModel.popSound.collectAsState()
                        val successSound by viewModel.successSound.collectAsState()
                        val celebrationSound by viewModel.celebrationSound.collectAsState()
                        val warningSound by viewModel.warningSound.collectAsState()
                        
                        var expandedCategory by remember { mutableStateOf<String?>(null) }
                        
                        SoundSelectionGroup(
                            title = "Sons do Aplicativo",
                            description = "Clique, Sucesso, Celebração e Alerta",
                            popSound = popSound,
                            successSound = successSound,
                            celebrationSound = celebrationSound,
                            warningSound = warningSound,
                            expandedCategory = expandedCategory,
                            onExpandToggle = { cat -> expandedCategory = if (expandedCategory == cat) null else cat },
                            onSoundSelect = { cat, uri -> 
                                when(cat) {
                                    "POP" -> viewModel.updatePopSound(uri)
                                    "SUCCESS" -> viewModel.updateSuccessSound(uri)
                                    "CELEBRATION" -> viewModel.updateCelebrationSound(uri)
                                    "WARNING" -> viewModel.updateWarningSound(uri)
                                }
                            },
                            onSystemPickerClick = { cat, uri -> launchSystemPicker(cat, uri) },
                            onCustomFileClick = { cat -> 
                                when(cat) {
                                    com.antigravity.healthagent.utils.SoundCategory.POP -> importPopSoundLauncher.launch(arrayOf("audio/*"))
                                    com.antigravity.healthagent.utils.SoundCategory.SUCCESS -> importSuccessSoundLauncher.launch(arrayOf("audio/*"))
                                    com.antigravity.healthagent.utils.SoundCategory.CELEBRATION -> importCelebrationSoundLauncher.launch(arrayOf("audio/*"))
                                    com.antigravity.healthagent.utils.SoundCategory.WARNING -> importWarningSoundLauncher.launch(arrayOf("audio/*"))
                                }
                            },
                            onTestSound = { uri -> viewModel.playPreview(uri) },
                            context = context,
                            viewModel = viewModel
                        )
                    }
                }

                // --- SEÇÃO 2: PREFERÊNCIAS DE TRABALHO ---
                item {
                    SettingsSection(
                        title = "Preferências de Trabalho",
                        icon = Icons.Outlined.Assignment,
                        isSolarMode = isSolarMode
                    ) {
                        
                        
                        // Modo Fácil card
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.updateEasyMode(!isEasyMode) },
                            color = itemContainerColor,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, itemBorderColor)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.AccessibilityNew, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Modo Fácil", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                                    Text("Botões maiores e interface simples", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = isEasyMode,
                                    onCheckedChange = { viewModel.updateEasyMode(it) },
                                    modifier = Modifier.scale(0.8f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Modo Solar card
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.updateSolarMode(!isSolarMode) },
                            color = itemContainerColor,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, itemBorderColor)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.WbSunny, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Modo Solar", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                                    Text("Tema de alto contraste para visibilidade externa", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = isSolarMode,
                                    onCheckedChange = { viewModel.updateSolarMode(it) },
                                    modifier = Modifier.scale(0.8f)
                                )
                            }
                        }
                    }
                }

                // --- SEÇÃO 3: DADOS E BACKUP LOCAL ---
                item {
                    SettingsSection(
                        title = "Dados e Backup Local",
                        icon = Icons.Outlined.Storage,
                        isSolarMode = isSolarMode
                    ) {
                        SettingsItemCard(
                            onClick = { viewModel.backupDataAndShare(context) },
                            headline = "Exportar Backup",
                            supportingText = "Gerar arquivo JSON para segurança",
                            leadingIcon = Icons.Default.Share,
                            isSolarMode = isSolarMode
                        )
                        
                        if (isAdmin) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            SettingsItemCard(
                                onClick = { showRestoreConfirm = true },
                                headline = "Restaurar Backup",
                                supportingText = "Importar dados de um arquivo JSON",
                                leadingIcon = Icons.Default.FolderOpen,
                                color = MaterialTheme.colorScheme.error,
                                isSolarMode = isSolarMode
                            )
                        }
                    }
                }

                // --- SEÇÃO 4: ADMINISTRAÇÃO ---
                if (canAccessAdmin) {
                    item {
                        SettingsSection(
                            title = "Administração",
                            icon = Icons.Outlined.AdminPanelSettings,
                            isSolarMode = isSolarMode
                        ) {
                            val pendingCount by viewModel.pendingAccessRequestsCount.collectAsState()

                            SettingsItemCard(
                                onClick = { onOpenAdmin() },
                                headline = "Painel de Gestão",
                                supportingText = "Controle de usuários e sincronização",
                                leadingIcon = Icons.Default.Security,
                                badgeCount = if (pendingCount > 0) pendingCount else null,
                                isSolarMode = isSolarMode
                            )

                            if (isAdmin) {
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                // Zona de Perigo label
                                Row(
                                    modifier = Modifier.padding(bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "ZONA DE PERIGO", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Black, 
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                // Limpar Histórico card
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable { showCleanupPicker = true },
                                    color = dangerContainerColor,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, dangerBorderColor)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text("Limpar Histórico Antigo", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.error)
                                            Text("Remover dados de meses passados", style = MaterialTheme.typography.bodySmall, color = if (isSolarMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Apagar Tudo card
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable { showEraseConfirm = true },
                                    color = dangerContainerColor,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, dangerBorderColor)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text("Apagar Tudo", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.error)
                                            Text("Resetar banco de dados local", style = MaterialTheme.typography.bodySmall, color = if (isSolarMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val pInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
                        Text("Eu ACE", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Versão ${pInfo?.versionName ?: "1.0.0"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector,
    isSolarMode: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    PremiumCard(
        modifier = Modifier.fillMaxWidth(),
        isSolarMode = isSolarMode
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

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
    onSystemPickerClick: (com.antigravity.healthagent.utils.SoundCategory, String) -> Unit,
    onCustomFileClick: (com.antigravity.healthagent.utils.SoundCategory) -> Unit,
    onTestSound: (String) -> Unit,
    context: android.content.Context,
    viewModel: HomeViewModel
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
                        onSystemPickerClick(com.antigravity.healthagent.utils.SoundCategory.valueOf(id), currentUri) 
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentUri.startsWith("content://"), onClick = { 
                            onSystemPickerClick(com.antigravity.healthagent.utils.SoundCategory.valueOf(id), currentUri) 
                        })
                        Text("Som do Sistema", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(modifier = Modifier.fillMaxWidth().clickable { 
                        onCustomFileClick(com.antigravity.healthagent.utils.SoundCategory.valueOf(id)) 
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentUri.startsWith("file://"), onClick = { 
                            onCustomFileClick(com.antigravity.healthagent.utils.SoundCategory.valueOf(id)) 
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

@Composable
fun SettingsItemCard(
    onClick: () -> Unit,
    headline: String,
    supportingText: String,
    leadingIcon: ImageVector,
    isSolarMode: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    badgeCount: Int? = null
) {
    val containerColor = if (isSolarMode) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        color.copy(alpha = 0.08f)
    }

    val borderColor = if (isSolarMode) {
        MaterialTheme.colorScheme.outline
    } else {
        color.copy(alpha = 0.15f)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick() },
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        ListItem(
            headlineContent = { Text(headline, fontWeight = FontWeight.Bold, color = textColor) },
            supportingContent = { Text(supportingText, style = MaterialTheme.typography.bodySmall) },
            leadingContent = { Icon(leadingIcon, null, tint = color) },
            trailingContent = {
                if (badgeCount != null) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ) {
                        Text(badgeCount.toString())
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
