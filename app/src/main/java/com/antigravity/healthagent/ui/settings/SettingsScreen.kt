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
    viewModel: HomeViewModel = hiltViewModel()
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

    // State for Restore Dialog
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showEraseConfirm by remember { mutableStateOf(false) }
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
                }
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
                item {
                    SettingsSection(
                        title = "Aparência",
                        icon = Icons.Outlined.Palette
                    ) {
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
                            val colors = AppColors
                            colors.forEach { (name, color) ->
                                val isSelected = currentColor == name
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
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
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.updateEasyMode(!isEasyMode) }
                        )

                        val isSolarMode by viewModel.solarMode.collectAsState()
                        
                        ListItem(
                            headlineContent = { Text("Modo Solar") },
                            supportingContent = { Text("Alto contraste para visibilidade sob luz solar direta") },
                            leadingContent = { Icon(Icons.Outlined.WbSunny, contentDescription = null) },
                            trailingContent = {
                                Switch(
                                    checked = isSolarMode,
                                    onCheckedChange = { viewModel.updateSolarMode(it) }
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.updateSolarMode(!isSolarMode) }
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
                        icon = Icons.Outlined.Schedule
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
                    }
                }

                item {
                    SettingsSection(
                        title = "Gerenciamento de Dados",
                        icon = Icons.Outlined.Backup
                    ) {
                        ListItem(
                            headlineContent = { Text("Backup Manual") },
                            supportingContent = { Text("Salvar uma cópia de segurança agora") },
                            leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { 
                                viewModel.backupDataAndShare(context) 
                            }
                        )
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        val isSyncing by viewModel.isSyncing.collectAsState()
                        
                        ListItem(
                            headlineContent = { Text("Sincronizar com a Nuvem") },
                            supportingContent = { Text("Enviar dados atualizados para o servidor") },
                            leadingContent = { 
                                if (isSyncing) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                else Icon(Icons.Default.CloudUpload, contentDescription = null)
                            },
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isSyncing) { 
                                viewModel.syncDataToCloud()
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        ListItem(
                            headlineContent = { Text("Baixar dados da Nuvem") },
                            supportingContent = { Text("Recuperar seus dados guardados no servidor") },
                            leadingContent = { 
                                if (isSyncing) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                else Icon(Icons.Default.CloudDownload, contentDescription = null)
                            },
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isSyncing) { 
                                viewModel.pullDataFromCloud()
                            }
                        )

                        if (isAdmin) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            ListItem(
                                headlineContent = { Text("Painel de Gestão (Admin)") },
                                leadingContent = { Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.fillMaxWidth().clickable { onOpenAdmin() }
                            )
                        }
                    }
                }

                if (isAdmin) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Zona de Perigo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.height(8.dp))
                                ListItem(
                                    headlineContent = { Text("Restaurar Backup", color = MaterialTheme.colorScheme.error) },
                                    leadingContent = { Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.error) },
                                    modifier = Modifier.clickable { showRestoreConfirm = true },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                                ListItem(
                                    headlineContent = { Text("Apagar Tudo", color = MaterialTheme.colorScheme.error) },
                                    leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    modifier = Modifier.clickable { showEraseConfirm = true },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
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
    content: @Composable ColumnScope.() -> Unit
) {
    PremiumCard(modifier = Modifier.fillMaxWidth()) {
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
    PremiumCard(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onExpandToggle).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            if (isExpanded) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val currentTitle = remember(currentSoundUri) { viewModel.getSoundTitle(currentSoundUri, context) }
                    Text(text = "Selecionado: $currentTitle", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    
                    Row(modifier = Modifier.fillMaxWidth().clickable { onSoundSelect("SILENT") }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentSoundUri == "SILENT", onClick = { onSoundSelect("SILENT") })
                        Text("Silencioso", modifier = Modifier.padding(start = 8.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth().clickable { onSystemPickerClick() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentSoundUri.startsWith("content://"), onClick = { onSystemPickerClick() })
                        Text("Som do Sistema", modifier = Modifier.padding(start = 8.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth().clickable { onCustomFileClick() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = currentSoundUri.startsWith("file://"), onClick = { onCustomFileClick() })
                        Text("Personalizado", modifier = Modifier.padding(start = 8.dp))
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (currentSoundUri != "SILENT") {
                        TextButton(onClick = onTestSound, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Testar Som")
                        }
                    }
                }
            }
        }
    }
}
