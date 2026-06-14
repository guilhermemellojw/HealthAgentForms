package com.antigravity.healthagent.ui.components.house

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.ui.components.CompactDropdown
import com.antigravity.healthagent.ui.components.DebouncedCompactInputBox
import com.antigravity.healthagent.ui.home.HouseUiState

private val propertyTypeOptions = PropertyType.entries.filter { it != PropertyType.EMPTY }.map { it.code }
private val propertyTypeDisplayOptions = PropertyType.entries.filter { it != PropertyType.EMPTY }.map { it.displayValue }

private val situationOptions = Situation.entries.filter { it != Situation.EMPTY }.map { it.code }
private val situationDisplayOptions = Situation.entries.filter { it != Situation.EMPTY }.map { it.displayValue }

@Composable
fun HouseRowInputs(
    houseState: HouseUiState,
    onUpdate: (House) -> Unit,
    enabled: Boolean,
    focusRequester: androidx.compose.ui.focus.FocusRequester?,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val house = houseState.house
    val highlightErrors = houseState.highlightErrors
    val invalidFields = houseState.invalidFields

    val isMissingNumbers = remember(invalidFields) { invalidFields.contains("number") }
    val isMissingType = remember(invalidFields) { invalidFields.contains("propertyType") }
    val isMissingSituation = remember(invalidFields) { invalidFields.contains("situation") }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Caixa de Número
            DebouncedCompactInputBox(
                label = "NÚMERO",
                initialValue = house.address.number,
                onValueChange = { onUpdate(house.copy(address = house.address.copy(number = it))) },
                modifier = Modifier.weight(1.2f),
                isError = highlightErrors && isMissingNumbers,
                enabled = enabled,
                focusRequester = focusRequester,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Next
                ),
                key = house.createdAt
            )

            // Caixa de Sequência
            DebouncedCompactInputBox(
                label = "SEQUÊNCIA",
                initialValue = if (house.address.sequence == 0) "" else house.address.sequence.toString(),
                onValueChange = { onUpdate(house.copy(address = house.address.copy(sequence = it.trim().toIntOrNull() ?: 0))) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.weight(0.5f),
                isError = highlightErrors && isMissingNumbers,
                enabled = enabled,
                key = house.createdAt
            )

            // Caixa de Complemento
            DebouncedCompactInputBox(
                label = "COMPLEMENTO",
                initialValue = if (house.address.complement == 0) "" else house.address.complement.toString(),
                onValueChange = { onUpdate(house.copy(address = house.address.copy(complement = it.trim().toIntOrNull() ?: 0))) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.weight(0.5f),
                enabled = enabled,
                key = house.createdAt
            )

            // Dropdown Tipo
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

            // Dropdown Situação
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

        // Resumo do Tratamento (se houver)
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
                    tint = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (enabled) 1f else 0.5f)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = treatmentParts,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (enabled) 1f else 0.5f),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
