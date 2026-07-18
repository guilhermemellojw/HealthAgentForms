package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.ui.home.HouseUiState
import com.antigravity.healthagent.ui.components.house.*
import androidx.compose.ui.draw.drawBehind

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HouseRowItem(
    houseState: HouseUiState,
    onUpdate: ((House) -> House) -> Unit,
    onDelete: (House) -> Unit,
    isReorderMode: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onEnableReorder: () -> Unit = {},
    onMoveDate: () -> Unit = {},
    getStreetSuggestions: () -> List<String> = { emptyList() },
    enabled: Boolean = true,

    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    isAdmin: Boolean = false,
    onShowTreatment: (House) -> Unit = {},
    onShowContext: (House) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val house = houseState.house
    
    // Using pre-calculated UI state from the ViewModel for better performance
    val invalidFields = houseState.invalidFields
    val highlightErrors = houseState.highlightErrors
    val isTreated = houseState.isTreated
    
    val isMissingNumbers = remember(invalidFields) { invalidFields.contains("number") }
    val isMissingType = remember(invalidFields) { invalidFields.contains("propertyType") }
    val isMissingSituation = remember(invalidFields) { invalidFields.contains("situation") }
    val isMissingBlock = remember(invalidFields) { invalidFields.contains("blockNumber") }
    val isMissingStreet = remember(invalidFields) { invalidFields.contains("streetName") }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showObservationDialog by remember { mutableStateOf(false) }
    
    val animatedBgColor by animateColorAsState(
        targetValue = when {
            highlightErrors -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) 
            else -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(durationMillis = 150),
        label = "animatedBgColor"
    )

    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            onConfirm = {
                onDelete(house)
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
            isEasyMode = isEasyMode
        )
    }

    if (showObservationDialog) {
        ObservationDialog(
            currentObservation = house.observation,
            onDismiss = { showObservationDialog = false },
            onConfirm = { 
                onUpdate { h -> h.copy(observation = it) }
                showObservationDialog = false
            }
        )
    }

    if (isEasyMode) {
        // Easy Mode doesn't need SwipeToDismissBox overhead
        EasyHouseCard(
            house = house,
            onUpdate = onUpdate,
            onDelete = { showDeleteDialog = true },
            onMoveDate = onMoveDate,
            onShowTreatment = { onShowTreatment(house) },
            onShowContext = { onShowContext(house) },
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onToggleReorder = onEnableReorder,
            onShowObservation = { showObservationDialog = true },
            isReorderMode = isReorderMode,
            highlightErrors = highlightErrors,
            invalidFields = invalidFields,
            enabled = enabled,
            isTreated = isTreated,
            animatedBgColor = animatedBgColor,
            isSolarMode = isSolarMode,
            focusRequester = focusRequester,
            isHighlighted = houseState.isHighlighted
        )
    } else {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
    
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isSolarMode) animatedBgColor else animatedBgColor.copy(alpha = 0.7f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(
                width = if (houseState.isHighlighted) 3.dp else if (isTreated) 1.5.dp else 1.dp,
                color = when {
                    houseState.isHighlighted -> MaterialTheme.colorScheme.tertiary
                    highlightErrors -> MaterialTheme.colorScheme.error
                    isSolarMode -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    else -> Color.White.copy(alpha = 0.15f)
                }
            )
        ) {
            val colors = MaterialTheme.colorScheme
            val indicatorColor = remember(highlightErrors, isTreated, colors) {
                when {
                    highlightErrors -> colors.error
                    isTreated -> colors.tertiaryContainer
                    else -> Color.Transparent
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        if (indicatorColor != Color.Transparent) {
                            drawRect(
                                color = indicatorColor,
                                size = this.size.copy(width = 6.dp.toPx())
                            )
                        }
                    }
            ) {
                HouseRowHeader(
                    houseState = houseState,
                    enabled = enabled,
                    isReorderMode = isReorderMode,
                    isAdmin = isAdmin,
                    onShowObservation = { showObservationDialog = true },
                    onMoveDate = onMoveDate,
                    onShowTreatment = { onShowTreatment(house) }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 12.dp, bottom = 6.dp, top = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    HouseRowInputs(
                        houseState = houseState,
                        onUpdate = onUpdate,
                        enabled = enabled,
                        focusRequester = focusRequester,
                        modifier = Modifier.weight(1f)
                    )

                    HouseRowActions(
                        enabled = enabled,
                        isReorderMode = isReorderMode,
                        onMoveUp = onMoveUp,
                        onMoveDown = onMoveDown,
                    onShowContext = { onShowContext(house) },
                    onShowDelete = { showDeleteDialog = true }
                    )
                }
            }
        }
    }
}
