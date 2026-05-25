package com.antigravity.healthagent.ui.home.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.healthagent.ui.home.HomeUiState
import com.antigravity.healthagent.ui.home.HouseUiState

@Composable
fun HomeFab(
    uiState: HomeUiState,
    listState: LazyListState,
    strictPendingHousesCount: Int,
    uiHouses: List<HouseUiState>,
    maxOpenHouses: Int,
    onAddHouse: () -> Unit,
    onClosedDayClick: () -> Unit,
    onScrollToFirstError: (Int) -> Unit,
    onCloseProduction: () -> Unit,
    onShowHeaderAlert: () -> Unit
) {
    if (uiState.isSupervisor && !uiState.isAdmin) return

    val isGoalReached = uiState.pendingCount >= maxOpenHouses && maxOpenHouses > 0
    val hasErrors = strictPendingHousesCount > 0

    // Scroll Logic for FAB expansion
    val lastFirstVisibleItemIndex = remember { mutableIntStateOf(0) }
    val lastFirstVisibleItemScrollOffset = remember { mutableIntStateOf(0) }
    var isFabExpanded by remember { mutableStateOf(true) }

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

    val fabColor = when {
        uiState.isDayClosed && !uiState.isAdmin -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
        hasErrors -> MaterialTheme.colorScheme.errorContainer
        isGoalReached && !uiState.isManualUnlock -> Color(0xFF00C853) // Emerald Green 700
        uiState.isManualUnlock -> Color(0xFFFF9800) // Orange/Amber for manual override
        else -> MaterialTheme.colorScheme.primary
    }

    val fabContentColor = when {
        uiState.isDayClosed && !uiState.isAdmin -> Color.White
        hasErrors -> MaterialTheme.colorScheme.onErrorContainer
        uiState.isManualUnlock -> Color.White
        else -> MaterialTheme.colorScheme.onPrimary
    }

    val fabOnClick: () -> Unit = {
        if (uiState.isDayClosed && !uiState.isAdmin) {
            onClosedDayClick()
        } else if (isGoalReached && hasErrors) {
            val firstErrorId = uiState.validationErrorHouseIds.firstOrNull()
            if (firstErrorId != null) {
                val indexInUi = uiHouses.indexOfFirst { it.house.id == firstErrorId }
                if (indexInUi != -1) {
                    onScrollToFirstError(indexInUi)
                }
            }
        } else if (isGoalReached) {
            onCloseProduction()
        } else {
            val isHeaderValid = uiState.municipality.isNotBlank() && uiState.neighborhood.isNotBlank()
            val skipHeaderCheck = uiState.houses.isEmpty()

            if (!isHeaderValid && !skipHeaderCheck && !uiState.isEasyMode) {
                onShowHeaderAlert()
            } else {
                onAddHouse()
            }
        }
    }

    val fabText = when {
        uiState.isDayClosed -> "DIA FECHADO"
        hasErrors -> "CORRIGIR ERROS"
        isGoalReached -> "FECHAR PRODUÇÃO"
        else -> "ADICIONAR"
    }

    val fabIcon = when {
        uiState.isDayClosed -> Icons.Default.Lock
        hasErrors -> Icons.Default.Warning
        isGoalReached -> Icons.Default.Check
        else -> Icons.Default.Add
    }

    ExtendedFloatingActionButton(
        onClick = fabOnClick,
        containerColor = fabColor,
        contentColor = fabContentColor,
        shape = RoundedCornerShape(if (uiState.isEasyMode) 28.dp else 16.dp),
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
                fontSize = if (uiState.isEasyMode) 16.sp else 14.sp
            )
        }
    )
}
