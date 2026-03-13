package com.antigravity.healthagent.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.widget.Toast
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.ui.components.*
import com.antigravity.healthagent.ui.home.components.*
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.ui.components.ProductionProgressBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.healthagent.ui.components.HouseRowItem
import com.antigravity.healthagent.ui.components.CompactDropdown
import com.antigravity.healthagent.ui.components.CompactInputBox
import com.antigravity.healthagent.ui.components.AutocompleteInputBox
import androidx.compose.material.icons.filled.List
import android.app.DatePickerDialog
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import java.util.Calendar
import kotlinx.coroutines.launch
import com.antigravity.healthagent.utils.AppConstants

import com.antigravity.healthagent.ui.components.PremiumCard
import com.antigravity.healthagent.ui.components.IntegrityDialog
import com.antigravity.healthagent.ui.components.SituationLimitDialog


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    user: com.antigravity.healthagent.domain.repository.AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val housesUiStates by viewModel.filteredHousesUiState.collectAsState() // Use pre-calculated UI state
    val dashboardTotals by viewModel.dashboardTotals.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    // Helper for robust vibration
    fun performVibration() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibrator = context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(android.os.Vibrator::class.java)
            vibrator?.vibrate(100)
        }
    }

    val currentBlock by viewModel.currentBlock.collectAsState()
    val currentBlockSequence by viewModel.currentBlockSequence.collectAsState()
    val currentStreet by viewModel.currentStreet.collectAsState()

    // Header State
    val municipio by viewModel.municipio.collectAsState()
    val bairro by viewModel.bairro.collectAsState()
    val categoria by viewModel.categoria.collectAsState()
    val zona by viewModel.zona.collectAsState()
    val tipo by viewModel.tipo.collectAsState()
    val data by viewModel.data.collectAsState()
    val ciclo by viewModel.ciclo.collectAsState()
    val atividade by viewModel.atividade.collectAsState()
    val agentName by viewModel.agentName.collectAsState()

    val uiEvent by viewModel.uiEvent.collectAsState()
    val isDayClosed by viewModel.isDayClosed.collectAsState()
    val isSupervisor by viewModel.isSupervisor.collectAsState()
    val isEasyMode by viewModel.easyMode.collectAsState()
    val isSolarMode by viewModel.solarMode.collectAsState()
    val maxOpenHouses by viewModel.maxOpenHouses.collectAsState()
    val streetSuggestions by viewModel.streetSuggestions.collectAsState()
    val daysWithErrors by viewModel.daysWithErrors.collectAsState()
    val showMultiDayErrorDialog by viewModel.showMultiDayErrorDialog.collectAsState()
    var showUnlockDialog by remember { mutableStateOf(false) }
    val isAppModeSelected by viewModel.isAppModeSelected.collectAsState()
    val integrityDialogMessage by viewModel.integrityDialogMessage.collectAsState()
    val validationErrorHouseIds by viewModel.validationErrorHouseIds.collectAsState()
    
    // Logic: Only show error dialogs if App Mode is selected
    // This prevents errors from interrupting the onboarding flow
    val areDialogsAllowed = (isAppModeSelected == true)

    if (areDialogsAllowed && integrityDialogMessage != null) {
        IntegrityDialog(
            message = integrityDialogMessage ?: "",
            onDismiss = { viewModel.dismissIntegrityDialog() },
            isEasyMode = isEasyMode
        )
    }

    if (areDialogsAllowed && showMultiDayErrorDialog) {
        MultiDayErrorDialog(
            daysWithErrors = daysWithErrors,
            onNavigateToDay = { viewModel.navigateToErroneousDay(it) },
            isEasyMode = isEasyMode
        )
    }

    val bairrosList by viewModel.bairrosList.collectAsState()
    
    LaunchedEffect(uiEvent) {
        uiEvent?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUiEvent()
        }
    }

    if (showUnlockDialog) {
        AlertDialog(
            onDismissRequest = { showUnlockDialog = false },
            title = { 
                Text(
                    "Reabrir Dia", 
                    fontWeight = FontWeight.ExtraBold,
                    style = if (isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
                ) 
            },
            text = { 
                Text(
                    "Deseja reabrir este dia para edição? Todas as edições serão permitidas novamente.",
                    style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
                ) 
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showUnlockDialog = false },
                        modifier = Modifier.weight(1f).height(if (isEasyMode) 52.dp else 48.dp),
                        shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                    ) {
                        Text("Cancelar", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            viewModel.toggleDayLock()
                            showUnlockDialog = false
                        },
                        modifier = Modifier.weight(1.3f).height(if (isEasyMode) 52.dp else 48.dp),
                        shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                    ) {
                        Text("Reabrir", fontWeight = FontWeight.Bold)
                    }
                }
            },
            shape = RoundedCornerShape(if (isEasyMode) 28.dp else 24.dp)
        )
    }

    var isHeaderExpanded by remember { mutableStateOf(false) }
    var isDashboardOpen by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var isReorderMode by remember { mutableStateOf(false) } // Moved here for scope visibility


    val showHistoryConfirmation by viewModel.showHistoryUnlockConfirmation.collectAsState()

    // Intercept system back button ONLY for Reorder Mode or Search
    androidx.activity.compose.BackHandler(enabled = isReorderMode || isSearchActive) {
        if (isReorderMode) {
            isReorderMode = false
        } else if (isSearchActive) {
            isSearchActive = false
            viewModel.updateSearchQuery("")
        }
    }

    if (showHistoryConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissHistoryUnlockConfirmation() },
            title = { 
                Text(
                    "Reabrir Dia Antigo", 
                    fontWeight = FontWeight.ExtraBold,
                    style = if (isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
                ) 
            },
            text = { 
                Text(
                    "Atenção: Você está tentando reabrir um dia anterior a uma semana.\n\nFazer alterações pode afetar relatórios históricos e dados de produtividade já consolidados.\n\nDeseja continuar?",
                    style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
                ) 
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.dismissHistoryUnlockConfirmation() },
                        modifier = Modifier.weight(1f).height(if (isEasyMode) 52.dp else 48.dp),
                        shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                    ) {
                        Text("Cancelar", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { viewModel.confirmUnlockHistory() },
                        modifier = Modifier.weight(1.3f).height(if (isEasyMode) 52.dp else 48.dp),
                        shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Sim, Reabrir", fontWeight = FontWeight.Bold)
                    }
                }
            },
            shape = RoundedCornerShape(if (isEasyMode) 28.dp else 24.dp)
        )
    }

    val showGoalReached by viewModel.showGoalReached.collectAsState()
    if (showGoalReached) {
        LaunchedEffect(Unit) {
            viewModel.testCelebration()
            performVibration()
            kotlinx.coroutines.delay(100)
            performVibration()
        }
        GoalReachedOverlay(onDismiss = { viewModel.advanceToNextDay() })
    }

    val showClosingAudit by viewModel.showClosingAudit.collectAsState()
    showClosingAudit?.let { audit ->
        ClosingAuditDialog(
            audit = audit,
            onConfirm = { viewModel.confirmAndCloseDay(it) },
            onDismiss = { viewModel.dismissClosingAudit() },
            isEasyMode = isEasyMode
        )
    }


    val situationLimitHouse by viewModel.situationLimitConfirmation.collectAsState()
    if (situationLimitHouse != null) {
        SituationLimitDialog(
            onConfirm = { viewModel.confirmSituationExceeded() },
            onDismiss = { viewModel.dismissSituationLimitConfirmation() },
            isEasyMode = isEasyMode
        )
    }



    
    


    var lastHouseErrorId by remember { mutableStateOf<Int?>(null) }
    
    // Auto-scroll logic
    var overscrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Drag State (Overlay Strategy)
    val uiHouses = remember { mutableStateListOf<HouseUiState>() }
    var draggingHouse by remember { mutableStateOf<HouseUiState?>(null) }
    var ghostY by remember { mutableStateOf(0f) }
    var initialTouchY by remember { mutableStateOf(0f) }

    // Focus Management for new houses
    val focusRequesters = remember { mutableMapOf<Int, androidx.compose.ui.focus.FocusRequester>() }

    LaunchedEffect(housesUiStates) {
        if (draggingHouse == null) {
            uiHouses.clear()
            uiHouses.addAll(housesUiStates)
        }
    }

    // Auto-scroll when new house added (if not searching)
    // Auto-Scroll Logic
    var lastAddRequestTime by remember { mutableLongStateOf(0L) }
    var previousHouseCount by remember { mutableIntStateOf(housesUiStates.size) }

    LaunchedEffect(housesUiStates.size) {
        if (housesUiStates.size > previousHouseCount && System.currentTimeMillis() - lastAddRequestTime < 2000) {
            // Delay slightly more to ensure item is rendered/measured and animations start
            kotlinx.coroutines.delay(100)
            if (housesUiStates.isNotEmpty()) {
                val newHouse = housesUiStates.last()
                // Scroll to the new house (index = housesUiStates.size because index 0 is the header)
                listState.animateScrollToItem(housesUiStates.size)
            }
        }
        previousHouseCount = housesUiStates.size
    }

    // Auto-scroll to validation error house
    LaunchedEffect(validationErrorHouseIds) {
        if (validationErrorHouseIds.isNotEmpty()) {
            val firstErrorId = validationErrorHouseIds.first()
            val indexInUi = uiHouses.indexOfFirst { it.house.id == firstErrorId }
            if (indexInUi != -1) {
                // Delay slightly to ensure layout is ready
                kotlinx.coroutines.delay(100)
                listState.animateScrollToItem(indexInUi + 1) // +1 for header
            }
        }
    }


    // Move House State
    var houseToMove by remember { mutableStateOf<House?>(null) }
    var showLongPressMenu by remember { mutableStateOf(false) }
    var showMoveDatePicker by remember { mutableStateOf(false) }

    // Header Date Picker
    val calendar = Calendar.getInstance()
    val datePickerDialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val formattedDate = String.format("%02d-%02d-%04d", dayOfMonth, month + 1, year)
            viewModel.updateHeader(municipio, bairro, "BRR", zona, tipo, formattedDate, ciclo, atividade)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )

    // Move House Date Picker
    val moveDatePickerDialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val formattedDate = String.format("%02d/%02d/%04d", dayOfMonth, month + 1, year)
            houseToMove?.let { 
                viewModel.moveHouseToDate(it, formattedDate)
                houseToMove = null 
            }
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )

    // Show date picker when triggered
    if (showMoveDatePicker) {
        LaunchedEffect(Unit) {
             moveDatePickerDialog.show()
             showMoveDatePicker = false
        }
    }

    if (showLongPressMenu && houseToMove != null && !isSupervisor) {
        AlertDialog(
            onDismissRequest = { showLongPressMenu = false; houseToMove = null },
            title = { Text("Opções do Imóvel") },
            text = { Text("O que deseja fazer com este imóvel?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLongPressMenu = false
                        showMoveDatePicker = true
                    }
                ) {
                    Text("Mover para outra Data")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showLongPressMenu = false
                        isReorderMode = true
                    }
                ) {
                    Text("Reordenar Lista")
                }
            }
        )
    }

    if (isDashboardOpen) {
        AlertDialog(
            onDismissRequest = { isDashboardOpen = false },
            title = { Text("Resumo do Dia") },
            text = {
                Column {
                    Text("Total Imóveis: ${dashboardTotals.totalHouses}")
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("A1: ${dashboardTotals.a1} | A2: ${dashboardTotals.a2}")
                    Text("B: ${dashboardTotals.b} | C: ${dashboardTotals.c}")
                    Text("D1: ${dashboardTotals.d1} | D2: ${dashboardTotals.d2}")
                    Text("E: ${dashboardTotals.e}")
                    Text("Eliminados: ${dashboardTotals.eliminados}")
                    Text("Larvicida: ${dashboardTotals.larvicida}g")
                    Text("Com Foco: ${dashboardTotals.comFoco}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = { isDashboardOpen = false }) { Text("Fechar") }
            }
        )
    }

    val moveConfirmationData by viewModel.moveConfirmationData.collectAsState()
    if (moveConfirmationData != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissMoveConfirmation() },
            title = { Text("Confirmar Movimentação") },
            text = { Text("O dia selecionado já atingiu o limite de produção. Deseja mover o imóvel mesmo assim?") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmMoveHouse() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Sim, Mover")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissMoveConfirmation() }) {
                    Text("Cancelar")
                }
            }
        )
    }

    val pendingCount by viewModel.pendingHousesCount.collectAsState()
    val strictPendingHousesCount by viewModel.strictPendingHousesCount.collectAsState()

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Solid Background for better visibility
                MeshGradient(
                    modifier = Modifier
                        .matchParentSize()
                        .let { 
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                it.blur(30.dp)
                            } else it
                        },
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    )
                )

                if (isSearchActive) {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            placeholder = { Text("Buscar Logradouro") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            singleLine = true
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { 
                            isSearchActive = false
                            viewModel.updateSearchQuery("")
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Close Search", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            } else if (isReorderMode) {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = { Text("Reordenar Imóveis", color = MaterialTheme.colorScheme.onPrimary) },
                    navigationIcon = {
                        IconButton(onClick = { isReorderMode = false }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Sair Reordenação", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            } else {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = { 
                        Column {
                            Text(
                                "Produção Diária", 
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold, 
                                color = MaterialTheme.colorScheme.onPrimary 
                            ) 
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = data.ifEmpty { "Selecione a Data" },
                                style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    actions = {
                        // Reorganized Stats (Vertical)
                        // Reorganized Stats (Horizontal)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {

                            
                            // Visual Alert for Strict Pending (Red if > 0)
                            val hasStrictPending = strictPendingHousesCount > 0
                            Surface(
                                color = if (hasStrictPending) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f) 
                                        else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                AnimatedContent(
                                    targetState = strictPendingHousesCount,
                                    transitionSpec = {
                                        if (targetState > initialState) {
                                            slideInVertically(animationSpec = tween(150)) { height -> height } + fadeIn(animationSpec = tween(150)) togetherWith
                                                    slideOutVertically(animationSpec = tween(150)) { height -> -height } + fadeOut(animationSpec = tween(150))
                                        } else {
                                            slideInVertically(animationSpec = tween(150)) { height -> -height } + fadeIn(animationSpec = tween(150)) togetherWith
                                                    slideOutVertically(animationSpec = tween(150)) { height -> height } + fadeOut(animationSpec = tween(150))
                                        }.using(
                                            SizeTransform(clip = false)
                                        )
                                    }
                                ) { count ->
                                    Text(
                                        text = "Pendentes: $count",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Black,
                                        color = if (hasStrictPending) Color.White else Color.White.copy(alpha = 0.9f),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }


                        // Locker Icon
                        IconButton(
                            onClick = {
                                if (!isSupervisor) {
                                    if (isDayClosed) {
                                        showUnlockDialog = true
                                    } else {
                                        viewModel.toggleDayLock()
                                    }
                                }
                            },
                            modifier = Modifier
                        ) {
                            val isDark = isSystemInDarkTheme()
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = if (isDayClosed) "Dia Fechado" else "Dia Aberto",
                                tint = if (isDayClosed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    // Open = "Success" or "Standard". Since the bar is Primary, use onPrimary for best contrast.
                                    // Alternatively, a custom Success color that works on Primary.
                                    // Let's use onPrimary.copy(alpha=0.8f) for "Open/Standard" look, 
                                    // or a bright Success color if we want green.
                                    // Given the custom themes, onPrimary is safest.
                                    MaterialTheme.colorScheme.onPrimary
                                }
                            )
                        }

                        if (user != null) {
                            UserIconMenu(
                                user = user,
                                onLogout = onLogout,
                                onSwitchAccount = onSwitchAccount
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.drawBehind {
                        // Optional: Add a subtle bottom separator that fits the glass look
                        drawLine(
                            color = Color.White.copy(alpha = 0.1f),
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                )
            }
        }
    },

        floatingActionButton = {
            if (!isReorderMode && !isDayClosed && !isSupervisor) {
                // Scroll Logic for FAB
                val isScrollingUp = remember {
                    derivedStateOf {
                        listState.firstVisibleItemIndex == 0 || !listState.canScrollBackward || !listState.isScrollInProgress
                    }
                }
                
                // More robust scroll direction detection
                var isFabExpanded by remember { mutableStateOf(true) }

                
                // We need to attach this connection to the Scaffold or LazyColumn. 
                // Since this block is inside 'floatingActionButton', we can't easily attach to the Scaffold content from here.
                // Instead, we'll use a snapshotFlow approach or rely on the state derived above.
                // Let's use the list state to detect scroll direction cleanly.
                
                val lastFirstVisibleItemIndex = remember { mutableIntStateOf(0) }
                val lastFirstVisibleItemScrollOffset = remember { mutableIntStateOf(0) }
                
                LaunchedEffect(listState) {
                    snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                        .collect { (index, offset) ->
                            val isScrollingDown = index > lastFirstVisibleItemIndex.intValue || 
                                (index == lastFirstVisibleItemIndex.intValue && offset > lastFirstVisibleItemScrollOffset.intValue)
                            val isScrollingUp = index < lastFirstVisibleItemIndex.intValue || 
                                (index == lastFirstVisibleItemIndex.intValue && offset < lastFirstVisibleItemScrollOffset.intValue)
                            
                            if (isScrollingDown && (index > 0 || offset > 20)) {
                                isFabExpanded = false
                            } else if (isScrollingUp) {
                                isFabExpanded = true
                            }
                            
                            lastFirstVisibleItemIndex.intValue = index
                            lastFirstVisibleItemScrollOffset.intValue = offset
                        }
                }

                val isGoalReached = pendingCount >= maxOpenHouses && maxOpenHouses > 0
                val hasErrors = strictPendingHousesCount > 0
                
                val fabColor = when {
                    hasErrors -> MaterialTheme.colorScheme.errorContainer
                    isGoalReached -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.primary
                }
                val fabContentColor = if (hasErrors) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary
                
                val fabOnClick: () -> Unit = {
                    if (hasErrors) {
                        val firstErrorId = validationErrorHouseIds.firstOrNull()
                        if (firstErrorId != null) {
                            val indexInUi = uiHouses.indexOfFirst { it.house.id == firstErrorId }
                            if (indexInUi != -1) {
                                scope.launch {
                                    listState.animateScrollToItem(indexInUi + 1) // +1 for header
                                    performVibration()
                                }
                            }
                        }
                    } else {
                        val isHeaderValid = municipio.isNotBlank() &&
                                bairro.isNotBlank() &&
                                zona.isNotBlank() &&
                                ciclo.isNotBlank() &&
                                tipo > 0 &&
                                atividade > 0

                        val skipHeaderCheck = housesUiStates.isEmpty()

                        if (!isHeaderValid && !skipHeaderCheck) {
                            scope.launch {
                                performVibration()
                                snackbarHostState.showSnackbar("Preencha todos os campos do cabeçalho")
                            }
                        } else {
                            if (viewModel.validateCurrentDay(showDialog = true)) {
                                performVibration()
                                lastAddRequestTime = System.currentTimeMillis()
                                viewModel.addNewHouse()
                            }
                        }
                    }
                }

                val fabText = when {
                    hasErrors -> "CORRIGIR ERROS"
                    isGoalReached -> "FECHAR PRODUÇÃO"
                    else -> "ADICIONAR"
                }
                
                val fabIcon = when {
                    hasErrors -> Icons.Default.Warning
                    isGoalReached -> Icons.Default.Check
                    else -> Icons.Default.Add
                }

                ExtendedFloatingActionButton(
                    onClick = fabOnClick,
                    containerColor = fabColor,
                    contentColor = fabContentColor,
                    shape = RoundedCornerShape(if (isEasyMode) 28.dp else 16.dp),
                    expanded = isFabExpanded,
                    icon = { 
                        Icon(
                            imageVector = fabIcon,
                            contentDescription = fabText
                        )
                    },
                    text = {
                        Text(
                            text = fabText,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isEasyMode) 16.sp else 14.sp
                        )
                    }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        
        fun checkForOverScroll(viewportY: Float) {
            val distFromTop = viewportY
            val viewportHeight = listState.layoutInfo.viewportSize.height
            val distFromBottom = viewportHeight - viewportY
            
            if (distFromTop < 200f) {
                if (overscrollJob?.isActive != true) {
                    overscrollJob = scope.launch {
                        while (true) {
                            listState.scrollBy(-30f)
                            kotlinx.coroutines.delay(16)
                        }
                    }
                }
            } else if (distFromBottom < 200f && distFromBottom > 0) {
                 if (overscrollJob?.isActive != true) {
                    overscrollJob = scope.launch {
                        while (true) {
                            listState.scrollBy(30f)
                            kotlinx.coroutines.delay(16)
                        }
                    }
                }
            } else {
                overscrollJob?.cancel()
            }
        }   

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            MeshGradient(modifier = Modifier.fillMaxSize())
            Column(modifier = Modifier.fillMaxSize()) {
                // Item 1: Minimalist Progress Line
                if (!isSearchActive && !isReorderMode) {
                    val productionCount = pendingCount // Situation.NONE (Normal Visits) matches the Meta logic
                    val progress = (productionCount.toFloat() / (if (maxOpenHouses > 0) maxOpenHouses else 25).toFloat()).coerceIn(0f, 1f)
                    val isGoalReached = productionCount >= maxOpenHouses && maxOpenHouses > 0
                    
                    ProductionProgressBar(
                        current = productionCount,
                        total = if (maxOpenHouses > 0) maxOpenHouses else 25,
                        isEasyMode = isEasyMode,
                        focusCount = dashboardTotals.comFoco,
                        modifier = Modifier.padding(bottom = 0.dp)
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 80.dp), // Space for FAB
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                item(key = "header") {
                    Column {
                        ProductionStatsBar(
                            totals = dashboardTotals,
                            isEasyMode = isEasyMode,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        val agentNames by viewModel.agentNames.collectAsState()

                        HomeHeader(
                            municipio = municipio,
                            data = data,
                            bairro = bairro,
                            zona = zona,
                            ciclo = ciclo,
                            tipo = tipo,
                            atividade = atividade,
                            agentName = agentName,
                            agentNamesList = agentNames,
                            isDayClosed = isDayClosed,
                            onUpdateHeader = { m, b, c, z, t, d, ci, a ->
                                viewModel.updateHeader(m, b, c, z, t, d, ci, a)
                            },
                            onUpdateBairro = { viewModel.updateHeader(municipio, it, "BRR", zona, tipo, data, ciclo, atividade) },
                            onUpdateAgentName = { viewModel.updateAgentName(it) },
                            onUpdateMunicipio = { viewModel.updateHeader(it, bairro, "BRR", zona, tipo, data, ciclo, atividade) },
                            onUpdateZona = { viewModel.updateHeader(municipio, bairro, "BRR", it, tipo, data, ciclo, atividade) },
                            onUpdateCategoria = { viewModel.updateHeader(municipio, bairro, it, zona, tipo, data, ciclo, atividade) },
                            onSelectDate = { datePickerDialog.show() },
                            onMoveDateBackward = { viewModel.moveDateBackward() },
                            onMoveDateForward = { viewModel.moveDateForward() },
                            isEasyMode = isEasyMode
                        )
                    }
                }
                
                // Empty State
                if (uiHouses.isEmpty()) {
                    item {
                        com.antigravity.healthagent.ui.components.EmptyStateView(
                            message = "Nenhum imóvel adicionado",
                            subMessage = "Toque no + para iniciar a produção de hoje",
                            icon = Icons.Default.Assignment
                        )
                    }
                }

                // Item 2..N: Houses
                itemsIndexed(
                    items = uiHouses, 
                    key = { _, state -> state.house.id },
                    contentType = { _, _ -> "house" }
                ) { index, uiState ->
                    val house = uiState.house
                    val isDragging = house.id == draggingHouse?.house?.id
                    
                    Box(
                        modifier = Modifier
                            .let {
                                if (isReorderMode) it.animateItemPlacement() else it
                            }
                            .graphicsLayer {
                                alpha = if (isDragging) 0f else 1f
                            }
                            .let {
                                if (!isEasyMode) {
                                    it.pointerInput(house.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { offset ->
                                                val visibleItems = listState.layoutInfo.visibleItemsInfo
                                                val currentHouse = uiHouses.find { it.house.id == house.id } ?: uiState
                                                val index = uiHouses.indexOf(currentHouse) + 1
                                                val itemInfo = visibleItems.find { it.index == index }
                                                
                                                if (itemInfo != null) {
                                                    draggingHouse = currentHouse
                                                    isReorderMode = true
                                                    initialTouchY = offset.y
                                                    ghostY = itemInfo.offset.toFloat()
                                                }
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                ghostY += dragAmount.y
                                                checkForOverScroll(ghostY)
                                                val visibleItems = listState.layoutInfo.visibleItemsInfo
                                                visibleItems.forEach { candidate ->
                                                    if (candidate.index == 0) return@forEach
                                                    val fingerY = ghostY + initialTouchY
                                                    val triggerZoneTop = candidate.offset
                                                    val triggerZoneBottom = candidate.offset + candidate.size
                                                    if (fingerY > triggerZoneTop && fingerY < triggerZoneBottom) {
                                                        val candidateIndexInList = candidate.index - 1
                                                        val currentHouse = uiHouses.find { it.house.id == house.id }
                                                        if (currentHouse != null) {
                                                            val currentIndex = uiHouses.indexOf(currentHouse)
                                                            if (currentIndex != -1 && candidateIndexInList != currentIndex && candidateIndexInList in uiHouses.indices) {
                                                                uiHouses.removeAt(currentIndex)
                                                                uiHouses.add(candidateIndexInList, currentHouse)
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                draggingHouse = null
                                                ghostY = 0f
                                                overscrollJob?.cancel()
                                                viewModel.persistListOrder(uiHouses.map { it.house }.toList())
                                            },
                                            onDragCancel = {
                                                draggingHouse = null
                                                ghostY = 0f
                                                overscrollJob?.cancel()
                                                uiHouses.clear()
                                                uiHouses.addAll(housesUiStates)
                                            }
                                        )
                                    }
                                } else it
                            }
                    ) {
                    Box {
                    val onUpdate = remember(viewModel, house.id) { { h: House -> viewModel.updateHouse(h); Unit } }
                    val onDelete = remember(viewModel, house.id) {
                        { h: House ->
                            performVibration()
                            viewModel.deleteHouse(h)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "Imóvel removido",
                                    actionLabel = "Desfazer",
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.restoreDeletedHouse()
                                }
                            }
                            Unit
                        }
                    }
                    val onMoveUp = remember(viewModel, house.id) { { viewModel.moveHouse(house, moveUp = true); Unit } }
                    val onMoveDown = remember(viewModel, house.id) { { viewModel.moveHouse(house, moveUp = false); Unit } }
                    val onEnableReorder = remember(viewModel, isEasyMode, house.id) {
                        {
                            isReorderMode = !isReorderMode
                            if (isReorderMode) performVibration()
                            Unit
                        }
                    }
                    val onMoveDate = remember(viewModel, house.id) {
                        {
                            houseToMove = house
                            showMoveDatePicker = true
                            Unit
                        }
                    }
                    
                    val focusRequester = remember(house.id) { focusRequesters.getOrPut(house.id) { androidx.compose.ui.focus.FocusRequester() } }
                    HouseRowItem(
                        uiState = uiState,
                        onUpdate = onUpdate,
                        onDelete = onDelete,
                        isReorderMode = isReorderMode,
                        onMoveUp = onMoveUp,
                        onMoveDown = onMoveDown,
                        onEnableReorder = onEnableReorder,
                        onMoveDate = onMoveDate,
                        streetSuggestions = streetSuggestions,
                        enabled = !isDayClosed,
                        isEasyMode = isEasyMode,
                        isSolarMode = isSolarMode,
                        focusRequester = focusRequester
                    )
                    }
                }
                }
            }
        }
            
            // Ghost Overlay
            draggingHouse?.let { uiState ->
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationY = ghostY
                            shadowElevation = 10f
                            scaleX = 1.05f
                            scaleY = 1.05f
                        }
                        .fillMaxWidth()
                ) {
                     HouseRowItem(
                        uiState = uiState,
                        onUpdate = {},
                        onDelete = {},
                        isReorderMode = true,
                        onMoveUp = {},
                        onMoveDown = {},
                        onEnableReorder = {},
                        onMoveDate = {},
                        streetSuggestions = emptyList(),
                        enabled = true,
                        isEasyMode = isEasyMode,
                        focusRequester = null
                    )
                }
            }
            
            // Haptic Feedback for Warnings
            LaunchedEffect(integrityDialogMessage) {
                if (integrityDialogMessage != null) {
                    performVibration()
                }
            }


        }
    }
}

