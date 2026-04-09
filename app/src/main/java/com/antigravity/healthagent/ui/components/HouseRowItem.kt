package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
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
                                    text = houseState.formattedStreet,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isMissingStreet) FontWeight.Bold else FontWeight.Black,
                                    color = if (highlightErrors && (isMissingStreet || houseState.invalidFields.isEmpty() && houseState.treatmentShortSummary.isEmpty() && houseState.errorLabels.contains("DUPLICADO"))) MaterialTheme.colorScheme.error
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
    val haptic = LocalHapticFeedback.current
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSolarMode) animatedBgColor else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = when {
            highlightErrors -> BorderStroke(3.dp, MaterialTheme.colorScheme.error)
            isTreated -> BorderStroke(1.2.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f))
            else -> BorderStroke(1.dp, if (isSolarMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // --- HEADER ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = house.streetName.formatStreetName().ifBlank { "NOME DA RUA" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Quarteirão ${house.blockNumber} • ${house.bairro.uppercase()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                // Date Icon
                Surface(
                    onClick = onMoveDate,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(44.dp),
                    enabled = enabled
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.CalendarToday,
                            contentDescription = "Mover Data",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // --- INFORMATION GRID ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                EasyGridItem(
                    label = "NÚMERO",
                    modifier = Modifier.weight(1f),
                    isError = invalidFields.contains("number") && highlightErrors
                ) {
                    DebouncedCompactInputBox(
                        label = "Número",
                        initialValue = house.number,
                        onValueChange = { onUpdate(house.copy(number = it)) },
                        enabled = enabled,
                        isEasyMode = true,
                        focusRequester = focusRequester,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                EasyGridItem(
                    label = "TIPO DE IMÓVEL",
                    modifier = Modifier.weight(1.3f),
                    isError = invalidFields.contains("propertyType") && highlightErrors
                ) {
                    CompactDropdown(
                        label = "Tipo de Imóvel",
                        currentValue = house.propertyType.code.ifBlank { "—" },
                        options = propertyTypeOptions,
                        displayOptions = propertyTypeDisplayOptions,
                        onOptionSelected = { selected ->
                            PropertyType.entries.find { it.code == selected }?.let {
                                onUpdate(house.copy(propertyType = it))
                            }
                        },
                        enabled = enabled,
                        isEasyMode = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                EasyGridItem(
                    label = "SEQUÊNCIA",
                    modifier = Modifier.weight(1f)
                ) {
                    DebouncedCompactInputBox(
                        label = "Sequência",
                        initialValue = if (house.sequence == 0) "" else house.sequence.toString(),
                        onValueChange = { onUpdate(house.copy(sequence = it.trim().toIntOrNull() ?: 0)) },
                        enabled = enabled,
                        isEasyMode = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                }
                EasyGridItem(
                    label = "COMPL.",
                    modifier = Modifier.weight(1f)
                ) {
                    DebouncedCompactInputBox(
                        label = "Compl.",
                        initialValue = if (house.complement == 0) "" else house.complement.toString(),
                        onValueChange = { onUpdate(house.copy(complement = it.trim().toIntOrNull() ?: 0)) },
                        enabled = enabled,
                        isEasyMode = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                }
                EasyGridItem(
                    label = "SITUAÇÃO",
                    modifier = Modifier.weight(1.4f),
                    isError = invalidFields.contains("situation") && highlightErrors
                ) {
                    CompactDropdown(
                        label = "Situação",
                        currentValue = house.situation.code.ifBlank { "—" },
                        options = situationOptions,
                        displayOptions = situationDisplayOptions,
                        onOptionSelected = { selected ->
                            Situation.entries.find { it.code == selected }?.let {
                                onUpdate(house.copy(situation = it))
                            }
                        },
                        enabled = enabled,
                        isEasyMode = true
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            // --- ACTIONS & FOOTER ---
            Column(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ROW 1: Registrar Tratamento (1.6) + Notas (1.0)
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onShowTreatment,
                        color = if (isTreated) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f) else Color.Transparent,
                        enabled = enabled,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.5.dp, if (isTreated) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                        modifier = Modifier.weight(1.6f).fillMaxHeight()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                color = if (isTreated) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                shape = CircleShape,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (isTreated) Icons.Default.Opacity else Icons.Default.Add,
                                        contentDescription = null,
                                        tint = if (isTreated) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isTreated) "Tratado" else "Tratamento",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isTreated) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }

                        Surface(
                            onClick = onShowObservation,
                            color = Color.Transparent,
                            enabled = enabled,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)),
                            modifier = Modifier.weight(1.0f).fillMaxHeight()
                        ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                if (house.observation.isNotBlank()) Icons.Default.NoteAlt else Icons.Default.EditNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Notas",
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }

                // ROW 2: Editar Local (1.6) + Mover (0.5) + Excluir (0.5)
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isReorderMode) {
                        Surface(
                            onClick = onMoveUp,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            enabled = enabled,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                            modifier = Modifier.weight(0.9f).fillMaxHeight()
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.width(4.dp))
                                Text("Subir", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Surface(
                            onClick = onMoveDown,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            enabled = enabled,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                            modifier = Modifier.weight(0.9f).fillMaxHeight()
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.width(4.dp))
                                Text("Descer", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Surface(
                            onClick = onToggleReorder,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(0.8f).fillMaxHeight()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Check, contentDescription = "Confirmar", tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    } else {
                        Surface(
                            onClick = onShowContext,
                            color = Color.Transparent,
                            enabled = enabled,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                            modifier = Modifier.weight(1.6f).fillMaxHeight()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Editar Local", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Surface(
                            onClick = onToggleReorder,
                            color = Color.Transparent,
                            enabled = enabled,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)),
                            modifier = Modifier.weight(0.5f).fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Mover", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Surface(
                            onClick = { onDelete(house) },
                            color = Color.Transparent,
                            enabled = enabled,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                            modifier = Modifier.weight(0.5f).fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                                Text("Excluir", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            if (house.comFoco) {
                Spacer(Modifier.height(12.dp))
                val hasCoords = house.latitude != null
                Surface(
                    color = if (hasCoords) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (hasCoords) Icons.Default.LocationOn else Icons.Default.GpsOff, 
                            contentDescription = null, 
                            tint = if (hasCoords) Color(0xFF388E3C) else Color(0xFFD32F2F), 
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (hasCoords) "Coordenadas registradas" else "Foco sem localização GPS", 
                            style = MaterialTheme.typography.labelMedium, 
                            color = if (hasCoords) Color(0xFF2E7D32) else Color(0xFFC62828),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EasyGridItem(
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .background(Color.Transparent, RoundedCornerShape(20.dp))
            .let { if (isError) it.border(2.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(20.dp)) else it }
            .fillMaxWidth()
            .height(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        content()
    }
}




