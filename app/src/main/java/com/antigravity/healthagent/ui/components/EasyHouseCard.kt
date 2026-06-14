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
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.ui.components.LockBanner
import com.antigravity.healthagent.ui.components.LockBannerStyle
import com.antigravity.healthagent.ui.components.action.ActionButton
import com.antigravity.healthagent.ui.components.action.ActionIcon
import com.antigravity.healthagent.ui.components.action.ActionSize
import com.antigravity.healthagent.utils.formatStreetName

private val propertyTypeOptions = PropertyType.entries.filter { it != PropertyType.EMPTY }.map { it.code }
private val propertyTypeDisplayOptions = PropertyType.entries.filter { it != PropertyType.EMPTY }.map { it.displayValue }
private val situationOptions = Situation.entries.filter { it != Situation.EMPTY }.map { it.code }
private val situationDisplayOptions = Situation.entries.filter { it != Situation.EMPTY }.map { it.displayValue }

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
    onGetLocation: (callback: (com.google.android.gms.maps.model.LatLng) -> Unit) -> Unit = {},
    isHighlighted: Boolean = false
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
            isHighlighted -> BorderStroke(3.dp, MaterialTheme.colorScheme.tertiary)
            highlightErrors -> BorderStroke(3.dp, MaterialTheme.colorScheme.error)
            isTreated -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            else -> BorderStroke(1.dp, if (isSolarMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            if (!enabled) {
                LockBanner(
                    enabled = enabled,
                    style = LockBannerStyle.FULL,
                )
            }

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
                        text = house.address.streetName.formatStreetName().ifBlank { "NOME DA RUA" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Quarteirão ${house.address.blockNumber} • ${house.address.bairro.uppercase()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                ActionIcon.Outlined(
                    icon = Icons.Default.CalendarToday,
                    onClick = onMoveDate,
                    contentDescription = "Mover Data",
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    enabled = enabled,
                    touchSize = ActionSize.touchMd,
                )
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
                        initialValue = house.address.number,
                        onValueChange = { onUpdate(house.copy(address = house.address.copy(number = it))) },
                        enabled = enabled,
                        isEasyMode = true,
                        focusRequester = focusRequester,
                        modifier = Modifier.fillMaxWidth(),
                        key = house.createdAt
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
                        initialValue = if (house.address.sequence == 0) "" else house.address.sequence.toString(),
                        onValueChange = { onUpdate(house.copy(address = house.address.copy(sequence = it.trim().toIntOrNull() ?: 0))) },
                        enabled = enabled,
                        isEasyMode = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        key = house.createdAt
                    )
                }
                EasyGridItem(
                    label = "COMPL.",
                    modifier = Modifier.weight(1f)
                ) {
                    DebouncedCompactInputBox(
                        label = "Compl.",
                        initialValue = if (house.address.complement == 0) "" else house.address.complement.toString(),
                        onValueChange = { onUpdate(house.copy(address = house.address.copy(complement = it.trim().toIntOrNull() ?: 0))) },
                        enabled = enabled,
                        isEasyMode = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        key = house.createdAt
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
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionButton.Primary(
                        icon = if (isTreated) Icons.Default.Opacity else Icons.Default.Add,
                        label = if (isTreated) "Tratado" else "Tratamento",
                        onClick = onShowTreatment,
                        enabled = enabled,
                        isActive = isTreated,
                        modifier = Modifier.weight(1.6f),
                    )
                    ActionButton.Secondary(
                        icon = if (house.observation.isNotBlank()) Icons.Default.NoteAlt else Icons.Default.EditNote,
                        label = "Notas",
                        onClick = onShowObservation,
                        enabled = enabled,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1.0f),
                    )
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
                        ActionButton.Secondary(
                            icon = Icons.Default.Edit,
                            label = "Editar Local",
                            onClick = onShowContext,
                            enabled = enabled,
                            modifier = Modifier.weight(1.6f),
                        )
                        ActionButton.IconLabel(
                            icon = Icons.Default.SwapVert,
                            label = "Mover",
                            onClick = onToggleReorder,
                            enabled = enabled,
                            modifier = Modifier.weight(0.5f),
                        )
                        ActionButton.Destructive(
                            icon = Icons.Default.Delete,
                            label = "Excluir",
                            onClick = { onDelete(house) },
                            enabled = enabled,
                            modifier = Modifier.weight(0.5f),
                        )
                    }
                }
            }

            if (house.treatment.comFoco) {
                Spacer(Modifier.height(12.dp))
                val hasCoords = house.geo.latitude != null
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


