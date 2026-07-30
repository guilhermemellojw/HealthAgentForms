package com.antigravity.healthagent.ui.components.house

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.healthagent.ui.components.LockBanner
import com.antigravity.healthagent.ui.components.LockBannerStyle
import com.antigravity.healthagent.ui.components.action.ActionIcon
import com.antigravity.healthagent.ui.home.HouseUiState

@Composable
fun HouseRowHeader(
    houseState: HouseUiState,
    enabled: Boolean,
    isReorderMode: Boolean,
    isAdmin: Boolean,
    onShowObservation: () -> Unit,
    onMoveDate: () -> Unit,
    onShowTreatment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val house = houseState.house
    val highlightErrors = houseState.highlightErrors
    val invalidFields = houseState.invalidFields

    val isMissingBlock = remember(invalidFields) { invalidFields.contains("blockNumber") }
    val isMissingStreet = remember(invalidFields) { invalidFields.contains("streetName") }

    Column(modifier = modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp)) {
        if (!enabled) {
            LockBanner(
                enabled = enabled,
                isTeamworkProtection = houseState.isMine.not() && !houseState.highlightErrors && !isAdmin,
                isHomologated = house.editedByAdmin,
                style = LockBannerStyle.COMPACT,
            )
        }

        // Cabeçalho de Localização e Ações Rápidas (Notas / Data)
        if (!isReorderMode) {
            val blockDisplay = houseState.blockDisplay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Info de Localização (Esquerda)
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
                    if (houseState.errorLabels.isEmpty()) {
                        val displayStreet = if (houseState.isMine) houseState.formattedStreet
                        else "${houseState.formattedStreet} • ${house.agentName}"
                        Text(
                            text = displayStreet,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isMissingStreet) FontWeight.Bold else FontWeight.Black,
                            color = if (highlightErrors && (isMissingStreet || houseState.invalidFields.isEmpty() && houseState.treatmentShortSummary.isEmpty() && houseState.errorLabels.contains("DUPLICADO"))) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.5f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon.Badge(
                        icon = if (house.observation.isNotBlank()) Icons.Default.NoteAlt else Icons.Default.EditNote,
                        onClick = onShowObservation,
                        contentDescription = "Ver Observação",
                        hasBadge = house.observation.isNotBlank(),
                        badgeColor = MaterialTheme.colorScheme.tertiary,
                        tint = if (house.observation.isNotBlank()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        enabled = enabled,
                    )
                    ActionIcon.Standard(
                        icon = Icons.Default.DateRange,
                        onClick = onMoveDate,
                        contentDescription = "Mover Data",
                        tint = MaterialTheme.colorScheme.primary,
                        enabled = enabled,
                    )
                    ActionIcon.Standard(
                        icon = Icons.Default.Opacity,
                        onClick = onShowTreatment,
                        contentDescription = "Tratamento",
                        tint = MaterialTheme.colorScheme.primary,
                        enabled = enabled,
                    )
                }
            }
        }
    }
}
