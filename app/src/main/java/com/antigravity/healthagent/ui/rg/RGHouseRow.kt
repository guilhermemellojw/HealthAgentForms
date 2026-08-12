package com.antigravity.healthagent.ui.rg

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.utils.formatStreetName

@Composable
fun RGHouseRow(
    house: House,
    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false
) {
    val basicStyle = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    val paddingVertical = if (isEasyMode) 16.dp else 12.dp

    val numberWidth = if (isEasyMode) 55.dp else 50.dp
    val seqWidth = if (isEasyMode) 40.dp else 35.dp
    val typeWidth = if (isEasyMode) 35.dp else 30.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = paddingVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = house.address.streetName.formatStreetName(),
            style = basicStyle,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = if (house.address.number.isBlank() || house.address.number == "0") "—" else house.address.number,
            style = basicStyle,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(numberWidth)
        )

        RGSeparator(isEasyMode, isSolarMode)

        Text(
            text = if (house.address.sequence == 0) "—" else house.address.sequence.toString(),
            style = basicStyle,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(seqWidth)
        )

        RGSeparator(isEasyMode, isSolarMode)

        Text(
            text = if (house.address.complement == 0) "—" else house.address.complement.toString(),
            style = basicStyle,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(seqWidth)
        )

        RGSeparator(isEasyMode, isSolarMode)

        Text(
            text = house.propertyType.code,
            style = basicStyle,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(typeWidth)
        )

        RGSeparator(isEasyMode, isSolarMode)

        Text(
            text = house.situation.code.ifBlank { "—" },
            style = basicStyle,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(typeWidth)
        )
    }

    Divider(
        color = if (isSolarMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
fun AgentGroupHeader(
    agentName: String,
    isSolarMode: Boolean
) {
    Text(
        text = agentName.ifBlank { "Agente" }.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = if (isSolarMode) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    )
}

@Composable
fun AgentGroupCard(
    agentName: String,
    houses: List<House>,
    isSolarMode: Boolean,
    isEasyMode: Boolean
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            1.dp,
            if (isSolarMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else Color.White.copy(alpha = 0.15f)
        ),
        color = if (isSolarMode) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column {
            AgentGroupHeader(agentName = agentName, isSolarMode = isSolarMode)
            Divider(
                color = if (isSolarMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            houses.forEach { house ->
                RGHouseRow(
                    house = house,
                    isEasyMode = isEasyMode,
                    isSolarMode = isSolarMode
                )
            }
        }
    }
}

@Composable
private fun RGSeparator(isEasyMode: Boolean = false, isSolarMode: Boolean = false) {
    VerticalDivider(
        modifier = Modifier
            .height(if (isEasyMode) 28.dp else 20.dp)
            .padding(horizontal = if (isEasyMode) 6.dp else 4.dp),
        thickness = 1.dp,
        color = if (isSolarMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    )
}
