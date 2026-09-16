package com.antigravity.healthagent.ui.home.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.ui.components.EmptyStateView
import com.antigravity.healthagent.ui.components.HouseRowItem
import com.antigravity.healthagent.ui.components.PremiumCard
import com.antigravity.healthagent.ui.components.ProductionStatsBar
import com.antigravity.healthagent.ui.home.AddBetweenButton
import com.antigravity.healthagent.ui.home.HomeUiState
import com.antigravity.healthagent.ui.home.HouseUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReorderableHouseList(
    uiState: HomeUiState,
    reorderHouses: List<HouseUiState>,
    listState: LazyListState,
    isSearchActive: Boolean,
    isReorderMode: Boolean,
    onReorderModeChange: (Boolean) -> Unit,
    streetSuggestions: List<String>,
    snackbarHostState: SnackbarHostState,
    onUpdateHeader: (municipio: String, bairro: String, categoria: String, zona: String, tipo: Int, data: String, ciclo: String, atividade: Int) -> Unit,
    onUpdateBairro: (String) -> Unit,
    onUpdateMunicipio: (String) -> Unit,
    onUpdateZona: (String) -> Unit,
    onUpdateCategoria: (String) -> Unit,
    onSelectDate: () -> Unit,
    onMoveDateBackward: () -> Unit,
    onMoveDateForward: () -> Unit,
    onHouseUpdate: (Int, (House) -> House) -> Unit,
    onHouseDelete: (House) -> Unit,
    onHouseRestore: () -> Unit,
    onMoveHouse: (House, moveUp: Boolean) -> Unit,
    onMoveHouseDate: (House) -> Unit,
    onAddNewHouseAt: (Int) -> Unit,
    onPersistListOrder: (List<House>) -> Unit,
    onStartReorder: (List<HouseUiState>) -> Unit,
    onUpdateReorder: (List<HouseUiState>) -> Unit,
    onCancelReorder: () -> Unit,
    onShowTreatment: (House) -> Unit,
    onShowContext: (House) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Local drag working copy (only non-null during active drag)
    var dragWorkingCopy by remember { mutableStateOf<List<HouseUiState>?>(null) }
    val displayHouses = if (isReorderMode) reorderHouses else uiState.houses
    var draggingHouse by remember { mutableStateOf<HouseUiState?>(null) }
    var ghostY by remember { mutableFloatStateOf(0f) }
    var initialTouchY by remember { mutableFloatStateOf(0f) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var dragCompleteVersion by remember { mutableIntStateOf(0) }

    val focusRequesters = remember { mutableMapOf<Int, androidx.compose.ui.focus.FocusRequester>() }

    var overscrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val isAdmin = uiState.isAdmin
    val isEasyMode = uiState.isEasyMode
    val isSolarMode = uiState.isSolarMode
    val isDayClosed = uiState.isDayClosed
    val isManualUnlock = uiState.isManualUnlock
    val isSupervisor = uiState.isSupervisor
    val isEditingToolsEnabled = uiState.isEditingToolsEnabled
    val uiHousesSnapshot = uiState.houses

    fun checkForOverScroll(viewportY: Float) {
        checkForOverScroll(
            viewportY = viewportY,
            listState = listState,
            scope = scope,
            currentJob = overscrollJob,
            onJobUpdated = { overscrollJob = it }
        )
    }

    Box(
        modifier = modifier.fillMaxSize().let {
            if (!isEasyMode && !isDayClosed) {
                it.pointerInput(dragCompleteVersion) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val visibleItems = listState.layoutInfo.visibleItemsInfo
                            val touchedItem = visibleItems.find { candidate ->
                                candidate.index != 0 && offset.y >= candidate.offset && offset.y <= candidate.offset + candidate.size
                            }
                            if (touchedItem != null) {
                                val itemIndex = touchedItem.index - 1
                                val currentList = if (isReorderMode) reorderHouses else uiHousesSnapshot
                                if (itemIndex in currentList.indices) {
                                    val currentHouse = currentList[itemIndex]
                                    val workingCopy = currentList.toMutableList()
                                    dragWorkingCopy = workingCopy
                                    onStartReorder(workingCopy)
                                    draggingHouse = currentHouse
                                    dragTargetIndex = itemIndex
                                    onReorderModeChange(true)
                                    initialTouchY = offset.y - touchedItem.offset
                                    ghostY = touchedItem.offset.toFloat()
                                }
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
                                    dragTargetIndex = candidate.index - 1
                                }
                            }
                        },
                        onDragEnd = {
                            val finalList = if (dragTargetIndex >= 0) {
                                val workCopy = (dragWorkingCopy ?: uiHousesSnapshot).toMutableList()
                                val currentHouse = workCopy.find { it.house.id == draggingHouse?.house?.id }
                                if (currentHouse != null) {
                                    val currentIndex = workCopy.indexOf(currentHouse)
                                    if (currentIndex != -1 && currentIndex != dragTargetIndex && dragTargetIndex in workCopy.indices) {
                                        workCopy.removeAt(currentIndex)
                                        workCopy.add(dragTargetIndex, currentHouse)
                                        workCopy.toList()
                                    } else {
                                        dragWorkingCopy ?: uiHousesSnapshot
                                    }
                                } else {
                                    dragWorkingCopy ?: uiHousesSnapshot
                                }
                            } else {
                                dragWorkingCopy ?: uiHousesSnapshot
                            }
                            onPersistListOrder(finalList.map { it.house }.toList())
                            draggingHouse = null
                            ghostY = 0f
                            overscrollJob?.cancel()
                            dragWorkingCopy = null
                            dragTargetIndex = -1
                            dragCompleteVersion++
                            onCancelReorder()
                            onReorderModeChange(false)
                        },
                        onDragCancel = {
                            draggingHouse = null
                            ghostY = 0f
                            overscrollJob?.cancel()
                            dragWorkingCopy = null
                            dragTargetIndex = -1
                            dragCompleteVersion++
                            onCancelReorder()
                            onReorderModeChange(false)
                        }
                    )
                }
            } else it
        }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = draggingHouse == null,
            contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.Top
        ) {
            item(key = "header") {
                Column {
                    PremiumCard(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        isSolarMode = uiState.isSolarMode
                    ) {
                        ProductionStatsBar(
                            totals = uiState.dashboardTotals,
                            isEasyMode = uiState.isEasyMode,
                            isSolarMode = uiState.isSolarMode
                        )
                    }

                    HomeHeader(
                        municipio = uiState.municipality,
                        data = uiState.data,
                        bairro = uiState.neighborhood,
                        zona = uiState.zone,
                        ciclo = uiState.cycle,
                        tipo = uiState.type,
                        atividade = uiState.activity,
                        agentName = uiState.agentName,
                        isDayClosed = uiState.isDayClosed,
                        onUpdateHeader = onUpdateHeader,
                        onUpdateBairro = onUpdateBairro,
                        onUpdateAgentName = { },
                        onUpdateMunicipio = onUpdateMunicipio,
                        onUpdateZona = onUpdateZona,
                        onUpdateCategoria = onUpdateCategoria,
                        onSelectDate = onSelectDate,
                        onMoveDateBackward = onMoveDateBackward,
                        onMoveDateForward = onMoveDateForward,
                        isEasyMode = uiState.isEasyMode,
                        isSolarMode = uiState.isSolarMode,
                        isBairroEditable = true
                    )
                }
            }

            if (displayHouses.isEmpty()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(16.dp))
                        if (!isSearchActive && isEditingToolsEnabled && (isAdmin || (!isDayClosed || isManualUnlock) && !isSupervisor)) {
                            AddBetweenButton(onClick = { onAddNewHouseAt(-1) })
                            Spacer(Modifier.height(16.dp))
                        }
                        EmptyStateView(
                            message = "Nenhum imóvel adicionado",
                            subMessage = "Toque no + para iniciar a produção de hoje",
                            icon = Icons.AutoMirrored.Filled.Assignment
                        )
                    }
                }
            }

            itemsIndexed(
                items = displayHouses,
                key = { _, state ->
                    val house = state.house
                    if (house.id != 0) {
                        house.id.toString()
                    } else {
                        "in_flight_${house.listOrder}_${house.createdAt}"
                    }
                },
                contentType = { _, _ -> "house" }
            ) { index, houseState ->
                Column {
                    if (index == 0 && !isSearchActive && isEditingToolsEnabled && (isAdmin || (!isDayClosed || isManualUnlock) && !isSupervisor)) {
                        Spacer(Modifier.height(8.dp))
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            AddBetweenButton(
                                onClick = { onAddNewHouseAt(-1) }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    } else if (index == 0) {
                        Spacer(Modifier.height(10.dp))
                    }

                    val house = houseState.house
                    val isDragging = house.id == draggingHouse?.house?.id
                    val isDropTarget = draggingHouse != null && index == dragTargetIndex && !isDragging

                    if (isDropTarget) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 12.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .let {
                                if (isReorderMode && draggingHouse == null) it.animateItem() else it
                            }
                            .graphicsLayer {
                                alpha = if (isDragging) 0f else 1f
                            }
                    ) {
                        val onUpdate = remember(house.id) { { updater: (House) -> House -> onHouseUpdate(house.id, updater); Unit } }
                        val onDelete = remember(house.id) {
                            { h: House ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onHouseDelete(h)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Imóvel removido",
                                        actionLabel = "Desfazer",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        onHouseRestore()
                                    }
                                }
                                Unit
                            }
                        }
                        val onMoveUp = remember(house.id) { { onMoveHouse(house, true); Unit } }
                        val onMoveDown = remember(house.id) { { onMoveHouse(house, false); Unit } }
                        val onEnableReorder = remember(isEasyMode, house.id, isReorderMode) {
                            {
                                val nextMode = !isReorderMode
                                onReorderModeChange(nextMode)
                                if (nextMode) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                Unit
                            }
                        }
                        val onMoveDate = remember(house.id) {
                            {
                                onMoveHouseDate(house)
                                Unit
                            }
                        }

                        val focusRequester = remember(house.id) { focusRequesters.getOrPut(house.id) { androidx.compose.ui.focus.FocusRequester() } }
                        val isBaseEnabled = isAdmin || (!isSupervisor && (!isDayClosed || isManualUnlock))
                        val isLockedByAdmin = house.editedByAdmin && !isAdmin && !isManualUnlock

                        HouseRowItem(
                            houseState = houseState,
                            onUpdate = onUpdate,
                            onDelete = onDelete,
                            isReorderMode = isReorderMode,
                            onMoveUp = onMoveUp,
                            onMoveDown = onMoveDown,
                            onEnableReorder = onEnableReorder,
                            onMoveDate = onMoveDate,
                            getStreetSuggestions = remember(streetSuggestions) { { streetSuggestions } },
                            isEasyMode = isEasyMode,
                            isSolarMode = isSolarMode,
                            focusRequester = focusRequester,
                            enabled = isBaseEnabled && (houseState.isMine || isAdmin) && !isLockedByAdmin,
                            isAdmin = isAdmin,
                            onShowTreatment = { onShowTreatment(house) },
                            onShowContext = { onShowContext(house) }
                        )
                    }

                    if (!isSearchActive && isEditingToolsEnabled && (isAdmin || (!isDayClosed || isManualUnlock) && !isSupervisor) && (houseState.isMine || isAdmin)) {
                        Spacer(Modifier.height(8.dp))
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            AddBetweenButton(
                                onClick = { onAddNewHouseAt(houseState.house.id) }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }

        draggingHouse?.let { ghostHouse ->
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
                    houseState = ghostHouse,
                    onUpdate = {},
                    onDelete = {},
                    isReorderMode = true,
                    onMoveUp = {},
                    onMoveDown = {},
                    onEnableReorder = {},
                    onMoveDate = {},
                    getStreetSuggestions = { emptyList() },
                    enabled = !isDayClosed,
                    isEasyMode = isEasyMode,
                    focusRequester = null,
                    isAdmin = isAdmin,
                    onShowTreatment = {},
                    onShowContext = {}
                )
            }
        }
    }
}

