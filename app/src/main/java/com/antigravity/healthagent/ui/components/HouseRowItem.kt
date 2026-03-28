package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.ui.home.HouseUiState
import com.antigravity.healthagent.utils.formatStreetName
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val propertyTypeOptions = PropertyType.entries.filter { it != PropertyType.EMPTY }.map { it.code }
private val propertyTypeDisplayOptions = PropertyType.entries.filter { it != PropertyType.EMPTY }.map { it.displayValue }

private val situationOptions = Situation.entries.filter { it != Situation.EMPTY }.map { it.code }
private val situationDisplayOptions = Situation.entries.filter { it != Situation.EMPTY }.map { it.displayValue }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HouseRowItem(
    houseState: HouseUiState,
    onUpdate: (House) -> Unit,
    onDelete: (House) -> Unit,
    isReorderMode: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onEnableReorder: () -> Unit = {},
    onMoveDate: () -> Unit = {},
    streetSuggestions: List<String> = emptyList(),
    enabled: Boolean = true,

    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onGetLocation: (callback: (com.google.android.gms.maps.model.LatLng) -> Unit) -> Unit = {}
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

    var showContextDialog by remember { mutableStateOf(false) }
    var showTreatmentDialog by remember { mutableStateOf(false) }
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

    if (showContextDialog) {
        ContextDialog(
            currentBlock = house.blockNumber,
            currentBlockSequence = house.blockSequence,
            currentStreet = house.streetName,
            currentBairro = house.bairro,
            currentQuarteiraoConcluido = house.quarteiraoConcluido,
            currentLocalidadeConcluida = house.localidadeConcluida,
            invalidFields = invalidFields,
            streetSuggestions = streetSuggestions,
            onDismiss = { showContextDialog = false },
            onConfirm = { block, blockSeq, street, bairro, qConcluido, lConcluido ->
                onUpdate(house.copy(
                    blockNumber = block, 
                    blockSequence = blockSeq,
                    streetName = street, 
                    bairro = bairro,
                    quarteiraoConcluido = qConcluido,
                    localidadeConcluida = lConcluido
                ))
                showContextDialog = false
            },
            isEasyMode = isEasyMode
        )
    }

    if (showTreatmentDialog) {
        TreatmentDialog(
            house = house,
            onDismiss = { showTreatmentDialog = false },
            onConfirm = { updatedHouse ->
                onUpdate(updatedHouse)
                showTreatmentDialog = false
            },
            isEasyMode = isEasyMode,
            onGetLocation = onGetLocation
        )
    }

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
                onUpdate(house.copy(observation = it))
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
            onShowTreatment = { showTreatmentDialog = true },
            onShowContext = { showContextDialog = true },
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
            onGetLocation = onGetLocation
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
                width = if (isTreated) 1.5.dp else 1.dp,
                color = when {
                    highlightErrors -> MaterialTheme.colorScheme.error
                    else -> Color.White.copy(alpha = 0.15f)
                }
            )
        ) {
            val colors = MaterialTheme.colorScheme
            val indicatorColor = remember(highlightErrors, isTreated, colors) {
                when {
                    highlightErrors -> colors.error
                    isTreated -> colors.tertiaryContainer
                    else -> Color.Transparent // No bar for untreated/non-error houses
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        if (indicatorColor != Color.Transparent) { // Only draw if not transparent
                            drawRect(
                                color = indicatorColor,
                                size = this.size.copy(width = 6.dp.toPx())
                            )
                        }
                    }
                    .padding(start = 18.dp, end = 12.dp, bottom = 12.dp, top = 6.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header: Location Info
                    if (!isReorderMode) {
                        val blockDisplay = houseState.blockDisplay
                        val formattedStreet = houseState.formattedStreet
                        Row(
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Location Info (Left)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = if (highlightErrors && isMissingBlock) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                            else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp),
                                    border = if (highlightErrors && isMissingBlock) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null
                                ) {
                                    Text(
                                        text = " QUARTEIRÃO $blockDisplay ",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Black,
                                        color = if (highlightErrors && isMissingBlock) MaterialTheme.colorScheme.error 
                                                else MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.5f),
                                        fontSize = 9.sp
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                houseState.errorLabels.forEach { label ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.error,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = " $label ",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White,
                                            fontSize = 8.sp
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    text = formattedStreet,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isMissingStreet) FontWeight.Bold else FontWeight.Black,
                                    color = if (highlightErrors && isMissingStreet) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.5f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
    
                            // Move Date & Observation Icons (Right)
                            if (enabled) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { showObservationDialog = true },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            if (house.observation.isNotBlank()) Icons.Default.NoteAlt else Icons.Default.EditNote,
                                            contentDescription = "Ver Observação",
                                            modifier = Modifier.size(20.dp),
                                            tint = if (house.observation.isNotBlank()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary 
                                        )
                                    }
                                    IconButton(
                                        onClick = onMoveDate,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.DateRange,
                                            contentDescription = "Mover Data",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary 
                                        )
                                    }
                                }
                            }
                        }
                    }
    
                    // Input Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DebouncedCompactInputBox(
                            label = "NÚMERO",
                            initialValue = house.number,
                            onValueChange = { onUpdate(house.copy(number = it)) },
                            modifier = Modifier.weight(0.7f),
                            isError = highlightErrors && isMissingNumbers,
                            enabled = enabled,
                            focusRequester = focusRequester,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Next
                            )
                        )
    
                        DebouncedCompactInputBox(
                            label = "SEQUÊNCIA",
                            initialValue = if (house.sequence == 0) "" else house.sequence.toString(),
                            onValueChange = { onUpdate(house.copy(sequence = it.trim().toIntOrNull() ?: 0)) },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Next
                            ),
                            modifier = Modifier.weight(0.7f),
                            isError = highlightErrors && isMissingNumbers,
                            enabled = enabled
                        )
    
                        DebouncedCompactInputBox(
                            label = "COMPLEMENTO",
                            initialValue = if (house.complement == 0) "" else house.complement.toString(),
                            onValueChange = { onUpdate(house.copy(complement = it.trim().toIntOrNull() ?: 0)) },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done
                            ),
                            modifier = Modifier.weight(0.6f),
                            enabled = enabled
                        )
    
                        CompactDropdown(
                            label = "TIPO",
                            currentValue = house.propertyType.code,
                            options = propertyTypeOptions,
                            displayOptions = propertyTypeDisplayOptions,
                            onOptionSelected = { selected ->
                                PropertyType.entries.find { it.code == selected }?.let {
                                    onUpdate(house.copy(propertyType = it))
                                }
                            },
                            modifier = Modifier.weight(1f),
                            isError = highlightErrors && isMissingType,
                            enabled = enabled
                        )
    
                        // Situation
                        CompactDropdown(
                            label = "SITUAÇÃO",
                            currentValue = house.situation.code,
                            options = situationOptions,
                            displayOptions = situationDisplayOptions,
                            onOptionSelected = { selected ->
                                Situation.entries.find { it.code == selected }?.let {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onUpdate(house.copy(situation = it))
                                }
                            },
                            modifier = Modifier.weight(1f),
                            isError = highlightErrors && isMissingSituation,
                            enabled = enabled
                        )
                    }
    
                    val treatmentParts = houseState.treatmentShortSummary
    
                    if (treatmentParts.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Opacity, 
                                contentDescription = null, 
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if(enabled) 1f else 0.5f)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = treatmentParts,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if(enabled) 1f else 0.5f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
    
                // Right side action column
                Column(
                    modifier = Modifier
                        .wrapContentWidth()
                        .padding(start = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isReorderMode) {
                        IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Descer", tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        IconButton(
                            onClick = { showTreatmentDialog = true },
                            modifier = Modifier.size(32.dp),
                            enabled = enabled && house.situation == Situation.NONE
                        ) {
                            val iconColor = if (isTreated) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primary
                            val isLocked = !enabled || house.situation != Situation.NONE
                            Icon(
                                Icons.Default.Opacity, 
                                contentDescription = "Tratamento", 
                                modifier = Modifier.size(20.dp),
                                tint = iconColor.copy(alpha = if(!isLocked) 1f else 0.3f)
                            )
                        }
                        IconButton(
                            onClick = { showContextDialog = true },
                            modifier = Modifier.size(32.dp),
                            enabled = enabled
                        ) {
                            Icon(
                                Icons.Default.Edit, 
                                contentDescription = "Editar Local", 
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = if(enabled) 1f else 0.5f)
                            )
                        }
                        if (enabled) {
                            IconButton(
                                onClick = { showDeleteDialog = true },
                                modifier = Modifier.size(32.dp),
                                enabled = enabled
                            ) {
                                Icon(
                                    Icons.Default.Delete, 
                                    contentDescription = "Excluir", 
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = if(enabled) 1f else 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EasyHouseCard(
    house: House,
    onUpdate: (House) -> Unit,
    onDelete: (House) -> Unit,
    onMoveDate: () -> Unit,
    onShowTreatment: () -> Unit,
    onShowContext: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleReorder: () -> Unit,
    onShowObservation: () -> Unit,
    isReorderMode: Boolean,
    highlightErrors: Boolean,
    invalidFields: Set<String>,
    enabled: Boolean,
    isTreated: Boolean,
    animatedBgColor: Color,
    isSolarMode: Boolean = false,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onGetLocation: (callback: (com.google.android.gms.maps.model.LatLng) -> Unit) -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSolarMode) animatedBgColor else animatedBgColor.copy(alpha = 0.7f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = if (isTreated) 1.5.dp else 1.dp,
            color = if (isTreated) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f) 
                    else Color.White.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = house.streetName.formatStreetName().ifBlank { "NOME DA RUA" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Quarteirão ${house.blockNumber} • ${house.bairro.uppercase()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = onShowObservation,
                        color = Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (house.observation.isNotBlank()) Icons.Default.NoteAlt else Icons.Default.EditNote,
                                contentDescription = "Observação",
                                tint = if (house.observation.isNotBlank()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onMoveDate,
                        color = Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.DateRange,
                                contentDescription = "Mover Data",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Input Grid Row 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DebouncedCompactInputBox(
                    label = "Nº",
                    initialValue = house.number,
                    onValueChange = { onUpdate(house.copy(number = it)) },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    isEasyMode = true,
                    focusRequester = focusRequester
                )
                CompactDropdown(
                    label = "TIPO DE IMÓVEL",
                    currentValue = house.propertyType.displayValue,
                    options = propertyTypeOptions,
                    displayOptions = propertyTypeDisplayOptions,
                    onOptionSelected = { selected ->
                        PropertyType.entries.find { it.code == selected }?.let {
                            onUpdate(house.copy(propertyType = it))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    isEasyMode = true
                )
            }

            Spacer(Modifier.height(12.dp))

            // Input Grid Row 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DebouncedCompactInputBox(
                    label = "SEQUÊNCIA",
                    initialValue = if (house.sequence == 0) "" else house.sequence.toString(),
                    onValueChange = { onUpdate(house.copy(sequence = it.trim().toIntOrNull() ?: 0)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Next
                    ),
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    isEasyMode = true
                )
                DebouncedCompactInputBox(
                    label = "COMPLEMENTO",
                    initialValue = if (house.complement == 0) "" else house.complement.toString(),
                    onValueChange = { onUpdate(house.copy(complement = it.trim().toIntOrNull() ?: 0)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    modifier = Modifier.weight(1.2f),
                    enabled = enabled,
                    isEasyMode = true
                )
                CompactDropdown(
                    label = "SITUAÇÃO",
                    currentValue = house.situation.displayValue,
                    options = situationOptions,
                    displayOptions = situationDisplayOptions,
                    onOptionSelected = { selected ->
                        Situation.entries.find { it.code == selected }?.let {
                            onUpdate(house.copy(situation = it))
                        }
                    },
                    modifier = Modifier.weight(1.8f),
                    enabled = enabled,
                    isEasyMode = true
                )
            }

            Spacer(Modifier.height(24.dp))

            // Registrar Tratamento Button
            // (isTreated is now passed as a parameter)

            Surface(
                onClick = onShowTreatment,
                color = Color.Transparent,
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (isTreated) MaterialTheme.colorScheme.tertiary.copy(alpha = if(enabled) 0.7f else 0.3f) 
                                           else MaterialTheme.colorScheme.primary.copy(alpha = if(enabled) 0.7f else 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isTreated) Icons.Default.Opacity else Icons.Default.Add,
                                contentDescription = null,
                                tint = if (isTreated) MaterialTheme.colorScheme.tertiary else Color(0xFF00897B),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = if (isTreated) "Ver Tratamento" else "Registrar Tratamento",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isTreated) MaterialTheme.colorScheme.tertiary else Color(0xFF00897B),
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = if (isTreated) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f) 
                               else Color(0xFF00897B).copy(alpha = 0.5f)
                    )
                }
            }

            if (house.comFoco) {
                val hasCoords = house.latitude != null
                Surface(
                    color = if (hasCoords) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (hasCoords) Icons.Default.LocationOn else Icons.Default.GpsOff, 
                            contentDescription = null, 
                            tint = if (hasCoords) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error, 
                            modifier = Modifier.size(16.dp)
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                        Text(
                            if (hasCoords) "Coordenadas registradas" else "Foco sem localização GPS", 
                            style = MaterialTheme.typography.labelMedium, 
                            color = if (hasCoords) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            fontWeight = if (hasCoords) FontWeight.Normal else FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isReorderMode) {
                    Button(
                        onClick = onMoveUp,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Subir")
                    }
                    Button(
                        onClick = onMoveDown,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Descer")
                    }
                } else {
                    Surface(
                        onClick = onShowContext,
                        color = Color.Transparent,
                        enabled = enabled,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if(enabled) 0.7f else 0.3f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = if(enabled) 1f else 0.4f))
                            Spacer(Modifier.width(8.dp))
                            Text("Editar Local", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary.copy(alpha = if(enabled) 1f else 0.4f))
                        }
                    }
                    Surface(
                        onClick = onToggleReorder,
                        color = Color.Transparent,
                        enabled = enabled,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if(enabled) 0.7f else 0.3f)),
                        modifier = Modifier.weight(0.8f)
                    ) {
                        Row(
                            Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = if(enabled) 1f else 0.4f))
                            Spacer(Modifier.width(8.dp))
                            Text("Mover", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary.copy(alpha = if(enabled) 1f else 0.4f))
                        }
                    }
                }

                Spacer(Modifier.weight(0.1f))

                IconButton(
                    onClick = { onDelete(house) },
                    modifier = Modifier.size(48.dp),
                    enabled = enabled
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = MaterialTheme.colorScheme.error.copy(alpha = if(enabled) 1f else 0.4f), modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}



