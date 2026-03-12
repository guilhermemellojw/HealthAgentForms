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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info

import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.healthagent.data.backup.BackupFrequency
import com.antigravity.healthagent.ui.home.HomeViewModel
import com.antigravity.healthagent.ui.theme.AppColors 
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenAdmin: () -> Unit = {},
    isAdmin: Boolean = false,
    isSupervisor: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val canAccessAdmin = isAdmin || isSupervisor
    // Intercept system back button
    androidx.activity.compose.BackHandler {
        onNavigateBack()
    }

    // Choice IS persisted in DataStore so the UI remembers the selection on restart.
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

    // State for Restore Dialog
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showEraseConfirm by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val context = LocalContext.current

    // File Pickers
    // Launcher de importação de dados
    // removed exportLauncher as we now use share intent
    
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

    if (showEraseConfirm) {
        AlertDialog(
           onDismissRequest = { showEraseConfirm = false },
           title = { Text("Apagar Tudo?") },
           text = { Text("Isso removerá TODOS os imóveis e dados de produção permanentemente. Essa ação não pode ser desfeita.") },
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Configurações", 
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimary
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack, 
                            contentDescription = "Voltar",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(
                    title = "Aparência",
                    icon = Icons.Outlined.Palette
                ) {
                    // Theme Mode Setup
                    val currentMode by viewModel.themeMode.collectAsState(initial = "SYSTEM")
                    
                    Text(
                        text = "Modo do Tema",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("SYSTEM" to "Sistema", "LIGHT" to "Claro", "DARK" to "Escuro").forEach { (mode, label) ->
                            FilterChip(
                                selected = currentMode == mode,
                                onClick = { viewModel.updateThemeMode(mode) },
                                label = { Text(label) },
                                leadingIcon = if (currentMode == mode) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    // Theme Color Setup
                    val currentColor by viewModel.themeColor.collectAsState(initial = "EMERALD")
                    
                    Text(
                        text = "Cor de Destaque (${currentColor?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Use the master list from ColorPalette.kt
                        val colors = AppColors
                        
                        colors.forEach { (name, color) ->
                            val isSelected = currentColor == name
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .background(color)
                                    .clickable { 
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        viewModel.updateThemeColor(name) 
                                    }
                                    .then(
                                        if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    // Easy Mode Toggle
                    val isEasyMode by viewModel.easyMode.collectAsState()
                    
                    ListItem(
                        headlineContent = { Text("Modo Fácil") },
                        supportingContent = { Text("Interface simplificada com visualização otimizada") },
                        leadingContent = { Icon(Icons.Outlined.AccessibilityNew, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = isEasyMode,
                                onCheckedChange = { viewModel.updateEasyMode(it) }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                viewModel.updateEasyMode(!isEasyMode) 
                            }
                    )

                    // Solar Mode Toggle
                    val isSolarMode by viewModel.solarMode.collectAsState()
                    
                    ListItem(
                        headlineContent = { Text("Modo Solar") },
                        supportingContent = { Text("Alto contraste para visibilidade sob luz solar direta") },
                        leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, // Using Refresh as a Sun-like icon or similar
                        trailingContent = {
                            Switch(
                                checked = isSolarMode,
                                onCheckedChange = { viewModel.updateSolarMode(it) }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                viewModel.updateSolarMode(!isSolarMode) 
                            }
                    )
                }
            }

            item {
                SettingsSection(
                    title = "Efeitos Sonoros",
                    icon = Icons.Default.NotificationsActive
                ) {
                    val popSound by viewModel.popSound.collectAsState()
                    val successSound by viewModel.successSound.collectAsState()
                    val celebrationSound by viewModel.celebrationSound.collectAsState()
                    val warningSound by viewModel.warningSound.collectAsState()
                    
                    var expandedCategory by remember { mutableStateOf<String?>(null) }
                    
                    Text(
                        text = "Personalize cada som individualmente",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    // Pop Sound
                    SoundSelectionGroup(
                        title = "Som de Clique",
                        description = "Ao adicionar imóveis",
                        currentSoundUri = popSound,
                        isExpanded = expandedCategory == "POP",
                        onExpandToggle = { expandedCategory = if (expandedCategory == "POP") null else "POP" },
                        onSoundSelect = { viewModel.updatePopSound(it) },
                        onSystemPickerClick = { launchSystemPicker(com.antigravity.healthagent.utils.SoundCategory.POP, popSound) },
                        onCustomFileClick = { importPopSoundLauncher.launch(arrayOf("audio/*")) },
                        onTestSound = { viewModel.playPreview(popSound) },
                        context = context,
                        viewModel = viewModel
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // Success Sound
                    SoundSelectionGroup(
                        title = "Som de Sucesso",
                        description = "Ao fechar o dia",
                        currentSoundUri = successSound,
                        isExpanded = expandedCategory == "SUCCESS",
                        onExpandToggle = { expandedCategory = if (expandedCategory == "SUCCESS") null else "SUCCESS" },
                        onSoundSelect = { viewModel.updateSuccessSound(it) },
                        onSystemPickerClick = { launchSystemPicker(com.antigravity.healthagent.utils.SoundCategory.SUCCESS, successSound) },
                        onCustomFileClick = { importSuccessSoundLauncher.launch(arrayOf("audio/*")) },
                        onTestSound = { viewModel.playPreview(successSound) },
                        context = context,
                        viewModel = viewModel
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // Celebration Sound
                    SoundSelectionGroup(
                        title = "Som de Celebração",
                        description = "Ao atingir a meta diária",
                        currentSoundUri = celebrationSound,
                        isExpanded = expandedCategory == "CELEBRATION",
                        onExpandToggle = { expandedCategory = if (expandedCategory == "CELEBRATION") null else "CELEBRATION" },
                        onSoundSelect = { viewModel.updateCelebrationSound(it) },
                        onSystemPickerClick = { launchSystemPicker(com.antigravity.healthagent.utils.SoundCategory.CELEBRATION, celebrationSound) },
                        onCustomFileClick = { importCelebrationSoundLauncher.launch(arrayOf("audio/*")) },
                        onTestSound = { viewModel.playPreview(celebrationSound) },
                        context = context,
                        viewModel = viewModel
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // Warning Sound
                    SoundSelectionGroup(
                        title = "Som de Alerta",
                        description = "Ao detectar erros",
                        currentSoundUri = warningSound,
                        isExpanded = expandedCategory == "WARNING",
                        onExpandToggle = { expandedCategory = if (expandedCategory == "WARNING") null else "WARNING" },
                        onSoundSelect = { viewModel.updateWarningSound(it) },
                        onSystemPickerClick = { launchSystemPicker(com.antigravity.healthagent.utils.SoundCategory.WARNING, warningSound) },
                        onCustomFileClick = { importWarningSoundLauncher.launch(arrayOf("audio/*")) },
                        onTestSound = { viewModel.playPreview(warningSound) },
                        context = context,
                        viewModel = viewModel
                    )
                }
            }

            item {
                SettingsSection(
                    title = "Limites de Produção",
                    icon = Icons.Outlined.Schedule // Using Schedule as a proxy for limits/time/management
                ) {
                    val maxHouses by viewModel.maxOpenHouses.collectAsState()
                    
                    Text(
                        text = "Limite de Casas Abertas: $maxHouses",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Slider(
                        value = maxHouses.toFloat(),
                        onValueChange = { viewModel.updateMaxOpenHouses(it.toInt()) },
                        valueRange = 25f..35f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Text(
                        text = "Define o máximo de casas com status 'Aberto' permitidas por dia. Casas adicionais serão bloqueadas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingsSection(
                    title = "Gerenciamento de Dados",
                    icon = Icons.Outlined.Backup
                ) {
                    ListItem(
                        headlineContent = { Text("Importar Produção") },
                        supportingContent = { Text("Adicionar dados de outro dia (JSON)") },
                        leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { importDayLauncher.launch(arrayOf("application/json")) }
                    )
                    
                    HorizontalDivider()
                    
                    // Backup Share Logic


                    ListItem(
                        headlineContent = { Text("Backup Manual") },
                        supportingContent = { Text("Salvar uma cópia de segurança agora") },
                        leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                viewModel.backupDataAndShare(context) 
                            }
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    val isSyncing by viewModel.isSyncing.collectAsState()
                    
                    ListItem(
                        headlineContent = { Text("Sincronizar com a Nuvem") },
                        supportingContent = { Text("Enviar dados atualizados para o servidor") },
                        leadingContent = { 
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isSyncing) { 
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                viewModel.syncDataToCloud()
                            }
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isAdmin) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        ListItem(
                            headlineContent = { Text("Painel de Gestão (Admin)") },
                            supportingContent = { Text("Gerenciar acessos e permissões") },
                            leadingContent = { Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    onOpenAdmin()
                                }
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // Danger Zone Section - Admin Only
            if (isAdmin) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Zona de Perigo",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            ListItem(
                                headlineContent = { Text("Restaurar Backup", color = MaterialTheme.colorScheme.error) },
                                supportingContent = { Text("Substitui TODOS os dados atuais", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                leadingContent = { Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.error) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable { 
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    showRestoreConfirm = true 
                                }
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f))

                            ListItem(
                                headlineContent = { Text("Apagar Tudo", color = MaterialTheme.colorScheme.error) },
                                supportingContent = { Text("Exclui permanentemente o banco de dados", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable { 
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    showEraseConfirm = true 
                                }
                            )
                        }
                    }
                }
            }

            // About Section
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val pInfo = try {
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    } catch (e: Exception) { null }
                    
                    val version = pInfo?.versionName ?: "Unknown"
                    val build = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        pInfo?.longVersionCode ?: 0
                    } else {
                        @Suppress("DEPRECATION")
                        pInfo?.versionCode ?: 0
                    }

                    Icon(
                        imageVector = Icons.Default.Info, // Or App Icon
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Eu ACE",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Versão $version ($build)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp) // Subtle elevation
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
fun BackupFrequencyOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp), // Adjust padding for touch target
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null // Handled by Row
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun SoundSelectionGroup(
    title: String,
    description: String,
    currentSoundUri: String,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    onSoundSelect: (String) -> Unit,
    onSystemPickerClick: () -> Unit,
    onCustomFileClick: () -> Unit,
    onTestSound: () -> Unit,
    context: android.content.Context,
    viewModel: HomeViewModel
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpandToggle)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = description, // e.g. "Current: Celebration"
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            if (isExpanded) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val currentTitle = remember(currentSoundUri) { viewModel.getSoundTitle(currentSoundUri, context) }
                    
                    Text(
                        text = "Selecionado: $currentTitle",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Option 1: Silent
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSoundSelect("SILENT") },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentSoundUri == "SILENT",
                            onClick = { onSoundSelect("SILENT") }
                        )
                        Text("Silencioso", modifier = Modifier.padding(start = 8.dp))
                    }

                    // Option 2: System Sound
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { 
                            if (!currentSoundUri.startsWith("content://") && !currentSoundUri.startsWith("SYSTEM_")) {
                                // If switching to system but no uri set, maybe trigger picker? 
                                // For now just select the mode, user must pick file.
                                // Actually, let's just trigger picker if they click the text/row?
                                // Standard pattern: Radio updates mode, params shown below.
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentSoundUri.startsWith("content://") || currentSoundUri.startsWith("SYSTEM_"),
                            onClick = { onSystemPickerClick() } // Direct action: Open Picker
                        )
                        Column(modifier = Modifier.clickable { onSystemPickerClick() }.padding(start = 8.dp)) {
                            Text("Som do Sistema")
                            if (currentSoundUri.startsWith("content://") || currentSoundUri.startsWith("SYSTEM_")) {
Text("Toque para alterar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }

                    // Option 3: Custom File
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { 
                             // Similar here
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentSoundUri.startsWith("file://"),
                            onClick = { onCustomFileClick() }
                        )
                        Column(modifier = Modifier.clickable { onCustomFileClick() }.padding(start = 8.dp)) {
                            Text("Arquivo Personalizado")
                            if (currentSoundUri.startsWith("file://")) {
Text("Toque para alterar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action Buttons
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Test Button
                        if (currentSoundUri != "SILENT") {
                            TextButton(onClick = onTestSound) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Testar Som")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
