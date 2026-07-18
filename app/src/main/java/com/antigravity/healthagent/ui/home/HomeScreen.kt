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
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.antigravity.healthagent.ui.components.CustomSyncPullIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.ui.components.*
import com.antigravity.healthagent.ui.home.components.*
import com.antigravity.healthagent.ui.components.ProductionProgressBar
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    user: com.antigravity.healthagent.domain.repository.AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSyncPullActive: (Boolean) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var locationPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        locationPermissionGranted = isGranted
    }

    val onGetLocation: (callback: (LatLng) -> Unit) -> Unit = { callback ->
        if (locationPermissionGranted) {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        callback(LatLng(location.latitude, location.longitude))
                    } else {
                        Toast.makeText(context, "Buscando localização...", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SecurityException) {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    val uiEvent by viewModel.uiEvent.collectAsState()
    val maxOpenHouses = uiState.maxOpenHouses
    val streetSuggestions by viewModel.streetSuggestions.collectAsState()
    val daysWithErrors by viewModel.daysWithErrors.collectAsState() // This should probably be in uiState too, but for now ok
    val showMultiDayErrorDialog by viewModel.showMultiDayErrorDialog.collectAsState()
    var showUnlockDialog by remember { mutableStateOf(false) }
    val integrityDialogMessage by viewModel.integrityDialogMessage.collectAsState()
    val validationErrors by viewModel.validationErrorDetails.collectAsState()
    val scrollToHouseId by viewModel.scrollToHouseId.collectAsState()
    val reorderHouses by viewModel.reorderHouses.collectAsState()
    val treatmentDialogState by viewModel.treatmentDialogState.collectAsState()
    val contextDialogState by viewModel.contextDialogState.collectAsState()
    
  
    if (integrityDialogMessage != null) {
        ValidationErrorsDialog(
            errors = validationErrors,
            onHouseClick = { id -> viewModel.onHouseClick(id) },
            onDismiss = { viewModel.dismissIntegrityDialog() },
            isEasyMode = uiState.isEasyMode
        )
    }
 
    if (showMultiDayErrorDialog) {
        MultiDayErrorDialog(
            daysWithErrors = daysWithErrors,
            onNavigateToDay = { viewModel.navigateToErroneousDay(it) },
            onDismiss = { viewModel.dismissMultiDayErrorDialog() },
            isEasyMode = uiState.isEasyMode
        )
    }
    
    LaunchedEffect(uiEvent) {
        uiEvent?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUiEvent()
        }
    }

    UnlockDayDialog(
        show = showUnlockDialog,
        isEasyMode = uiState.isEasyMode,
        onConfirm = {
            viewModel.toggleDayLock()
            showUnlockDialog = false
        },
        onDismiss = { showUnlockDialog = false }
    )

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

    HistoryUnlockDialog(
        show = showHistoryConfirmation,
        isEasyMode = uiState.isEasyMode,
        onConfirm = { viewModel.confirmUnlockHistory() },
        onDismiss = { viewModel.dismissHistoryUnlockConfirmation() }
    )

    val showGoalReached by viewModel.showGoalReached.collectAsState()
    if (showGoalReached) {
        LaunchedEffect(Unit) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            kotlinx.coroutines.delay(100)
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        }
        GoalReachedOverlay(
            onDismiss = { viewModel.dismissGoalReached() },
            onNextDay = { viewModel.advanceToNextDay() }
        )
    }

    val showClosingAudit by viewModel.showClosingAudit.collectAsState()
    showClosingAudit?.let { audit ->
        ClosingAuditDialog(
            audit = audit,
            onConfirm = { viewModel.confirmAndCloseDay(it) },
            onDismiss = { viewModel.dismissClosingAudit() },
            isEasyMode = uiState.isEasyMode
        )
    }


    val situationLimitHouse by viewModel.situationLimitConfirmation.collectAsState()
    if (situationLimitHouse != null) {
        SituationLimitDialog(
            house = situationLimitHouse,
            onDismiss = { viewModel.dismissSituationLimitConfirmation() },
            onHouseClick = { id -> viewModel.onHouseClick(id) },
            onUnlockClick = { viewModel.toggleDayLock() },
            showUnlockOption = !uiState.isSupervisor,
            isEasyMode = uiState.isEasyMode
        )
    }

    treatmentDialogState?.let { state ->
        val house = uiState.houses.find { it.house.id == state.houseId }?.house
        if (house != null) {
            TreatmentDialog(
                house = house,
                onDismiss = { viewModel.closeTreatmentDialog() },
                onConfirm = { treatment, geo ->
                    viewModel.updateHouseField(state.houseId) { h ->
                        h.copy(treatment = treatment, geo = geo)
                    }
                    viewModel.closeTreatmentDialog()
                },
                isEasyMode = uiState.isEasyMode,
                onGetLocation = onGetLocation
            )
        }
    }

    contextDialogState?.let { state ->
        ContextDialog(
            currentBlock = state.block,
            currentBlockSequence = state.blockSequence,
            currentStreet = state.street,
            currentBairro = state.bairro,
            currentQuarteiraoConcluido = state.quarteiraoConcluido,
            currentLocalidadeConcluida = state.localidadeConcluida,
            onDismiss = { viewModel.closeContextDialog() },
            onConfirm = { block, blockSeq, street, bairro, qConcluido, lConcluido ->
                viewModel.confirmContextDialog(state.houseId, block, blockSeq, street, bairro, qConcluido, lConcluido)
            },
            isEasyMode = uiState.isEasyMode
        )
    }

    
    
    




    // Auto-scroll when new house added (if not searching)
    // Auto-Scroll Logic
    var lastAddRequestTime by remember { mutableLongStateOf(0L) }
    var previousHouseCount by remember { mutableIntStateOf(uiState.houses.size) }

    LaunchedEffect(uiState.houses.size) {
        val prevCount = previousHouseCount
        previousHouseCount = uiState.houses.size
        
        if (uiState.houses.size > prevCount && System.currentTimeMillis() - lastAddRequestTime < 2000) {
            // Delay ligeiramente maior para garantir estabilização do layout
            kotlinx.coroutines.delay(150)
            if (uiState.houses.isNotEmpty()) {
                val lastIndex = uiState.houses.size
                val isAlreadyVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == lastIndex }
                
                if (!isAlreadyVisible && !listState.isScrollInProgress) {
                    listState.animateScrollToItem(lastIndex)
                }
            }
        }
    }

    // Auto-scroll to validation error house
    LaunchedEffect(scrollToHouseId) {
        scrollToHouseId?.let { id ->
            val indexInUi = uiState.houses.indexOfFirst { it.house.id == id }
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
            val formattedDate = String.format(java.util.Locale("pt", "BR"), "%02d-%02d-%04d", dayOfMonth, month + 1, year)
            viewModel.onDateSelected(formattedDate)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )

    // Move House Date Picker
    val moveDatePickerDialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val formattedDate = String.format(java.util.Locale("pt", "BR"), "%02d-%02d-%04d", dayOfMonth, month + 1, year)
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

    HouseOptionsDialog(
        show = showLongPressMenu && houseToMove != null && (!uiState.isSupervisor || uiState.isAdmin),
        onMoveToDate = {
            showLongPressMenu = false
            showMoveDatePicker = true
        },
        onReorderList = {
            showLongPressMenu = false
            isReorderMode = true
        },
        onDismiss = {
            showLongPressMenu = false
            houseToMove = null
        }
    )

    DashboardSummaryDialog(
        show = isDashboardOpen,
        dashboardTotals = uiState.dashboardTotals,
        isDayClosed = uiState.isDayClosed,
        isSupervisor = uiState.isSupervisor,
        isAdmin = uiState.isAdmin,
        showDeduplicate = uiState.isDuplicateIds.isNotEmpty(),
        onDeduplicate = {
            viewModel.deduplicateCurrentDay()
            isDashboardOpen = false
        },
        onCloseProduction = {
            isDashboardOpen = false
            viewModel.startDayClosingFlow()
        },
        onDismiss = { isDashboardOpen = false }
    )

    val moveConfirmationData by viewModel.moveConfirmationData.collectAsState()
    MoveHouseGoalReachedDialog(
        show = moveConfirmationData != null,
        onConfirm = { viewModel.confirmMoveHouse() },
        onDismiss = { viewModel.dismissMoveConfirmation() }
    )

    val duplicateHouseConfirmation by viewModel.duplicateHouseConfirmation.collectAsState()
    DuplicateHouseDialog(
        show = duplicateHouseConfirmation != null,
        onConfirm = { viewModel.confirmDuplicateMerge() },
        onDismiss = { viewModel.dismissDuplicateConfirmation() },
        isEasyMode = uiState.isEasyMode
    )

    val strictPendingHousesCount = uiState.strictPendingCount

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    CompositionLocalProvider(LocalHomeUiState provides uiState) {
        Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HomeTopBar(
                uiState = uiState,
                isSearchActive = isSearchActive,
                isReorderMode = isReorderMode,
                onSearchActiveChange = { isSearchActive = it },
                onReorderModeChange = { isReorderMode = it },
                strictPendingHousesCount = strictPendingHousesCount,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                onLockClick = {
                    if (uiState.isDayClosed) {
                        showUnlockDialog = true
                    } else {
                        viewModel.toggleDayLock()
                    }
                },
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                scrollBehavior = scrollBehavior,
                onOpenSettings = onOpenSettings
            )
        },

        floatingActionButton = {
            HomeFab(
                uiState = uiState,
                listState = listState,
                strictPendingHousesCount = strictPendingHousesCount,
                uiHouses = uiState.houses,
                maxOpenHouses = maxOpenHouses,
                onAddHouse = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    lastAddRequestTime = System.currentTimeMillis()
                    viewModel.addNewHouse()
                },
                onClosedDayClick = {
                    scope.launch {
                        snackbarHostState.showSnackbar("Este dia já foi fechado.")
                    }
                },
                onScrollToFirstError = { indexInUi ->
                    scope.launch {
                        listState.animateScrollToItem(indexInUi + 1) // +1 for header
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                },
                onCloseProduction = {
                    viewModel.startDayClosingFlow()
                },
                onShowHeaderAlert = {
                    scope.launch {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        snackbarHostState.showSnackbar("Preencha ao menos o Município e Bairro")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        val isSyncing by viewModel.isSyncing.collectAsState()
        val isRefreshing = uiState.syncStatus is SyncUiState.Syncing || isSyncing
        val pullToRefreshState = rememberPullToRefreshState()

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
                    isSolarMode = uiState.isSolarMode,
                    syncStatus = uiState.syncStatus
                )
            },
            modifier = Modifier.padding(paddingValues).fillMaxSize()
        ) {
            // Indentation and content will be below
        
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            MeshGradient(modifier = Modifier.fillMaxSize())
            
            // Repositioned SnackbarHost to the TOP
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp) // Subtle gap below the TopAppBar
                    .zIndex(10f), // Ensure it stays above other content
                snackbar = { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            )
            Column(modifier = Modifier.fillMaxSize()) {
                // Item 1: Minimalist Progress Line
                if (!isSearchActive && !isReorderMode) {
                    val workedCount = uiState.dashboardTotals.worked
                    
                    ProductionProgressBar(
                        current = workedCount,
                        total = if (maxOpenHouses > 0) maxOpenHouses else 25,
                        isEasyMode = uiState.isEasyMode,
                        focusCount = uiState.dashboardTotals.totalFocos,
                        modifier = Modifier.padding(bottom = 0.dp)
                    )
                }

                ReorderableHouseList(
                    uiState = uiState,
                    reorderHouses = reorderHouses,
                    listState = listState,
                    isSearchActive = isSearchActive,
                    isReorderMode = isReorderMode,
                    onReorderModeChange = { isReorderMode = it },
                    streetSuggestions = streetSuggestions,
                    snackbarHostState = snackbarHostState,
                    onUpdateHeader = { m, b, c, z, t, d, ci, a ->
                        viewModel.updateHeader(m, b, c, z, t, d, ci, a)
                    },
                    onUpdateBairro = { viewModel.updateHeader(uiState.municipality, it, "BRR", uiState.zone, uiState.type, uiState.data, uiState.cycle, uiState.activity) },
                    onUpdateMunicipio = { viewModel.updateHeader(it, uiState.neighborhood, "BRR", uiState.zone, uiState.type, uiState.data, uiState.cycle, uiState.activity) },
                    onUpdateZona = { viewModel.updateHeader(uiState.municipality, uiState.neighborhood, "BRR", it, uiState.type, uiState.data, uiState.cycle, uiState.activity) },
                    onUpdateCategoria = { viewModel.updateHeader(uiState.municipality, uiState.neighborhood, it, uiState.zone, uiState.type, uiState.data, uiState.cycle, uiState.activity) },
                    onSelectDate = { datePickerDialog.show() },
                    onMoveDateBackward = { viewModel.moveDateBackward() },
                    onMoveDateForward = { viewModel.moveDateForward() },
                    onHouseUpdate = { id, updater -> viewModel.updateHouseField(id, updater) },
                    onHouseDelete = { viewModel.deleteHouse(it) },
                    onHouseRestore = { viewModel.restoreDeletedHouse() },
                    onMoveHouse = { house, moveUp -> viewModel.moveHouse(house, moveUp) },
                    onMoveHouseDate = {
                        houseToMove = it
                        showMoveDatePicker = true
                    },
                    onAddNewHouseAt = { viewModel.addNewHouseAt(it) },
                    onPersistListOrder = { viewModel.persistListOrder(it) },
                    onStartReorder = { viewModel.startReorderMode(it) },
                    onUpdateReorder = { viewModel.updateReorderList(it) },
                    onCancelReorder = { viewModel.cancelReorderMode() },
                    onShowTreatment = { viewModel.openTreatmentDialog(it) },
                    onShowContext = { viewModel.openContextDialog(it) },
                    modifier = Modifier.weight(1f)
                )
            }
            
            // Haptic Feedback for Warnings
            LaunchedEffect(integrityDialogMessage) {
                if (integrityDialogMessage != null) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                }
            }
        }
    }
}
}
}

@Composable
fun AddBetweenButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = "Inserir Imóvel Aqui",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
    }
}


