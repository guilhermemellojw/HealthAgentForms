package com.antigravity.healthagent.ui.rg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.utils.formatStreetName
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person

@Composable
fun RGHouseRow(
    house: House,
    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false,
    currentUserUid: String = ""
) {
    val basicStyle = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    val paddingVertical = if (isEasyMode) 16.dp else 12.dp
    val textHeight = if (isEasyMode) 24.sp else 20.sp
    
    // Column widths
    val numberWidth = if (isEasyMode) 55.dp else 50.dp
    val seqWidth = if (isEasyMode) 40.dp else 35.dp
    val typeWidth = if (isEasyMode) 35.dp else 30.dp
    
    val isTeammate = house.agentUid.isNotBlank() && house.agentUid != currentUserUid
    val backgroundColor = if (isTeammate) {
        if (isSolarMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
        else MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)
    } else {
        Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = paddingVertical),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isTeammate) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(16.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(1.5.dp)
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            // Street Name - Weight 1f to take available space
            Text(
                text = house.address.streetName.formatStreetName(),
                style = basicStyle,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Number
            Text(
                text = if (house.address.number.isBlank() || house.address.number == "0") "—" else house.address.number,
                style = basicStyle,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(numberWidth)
            )
            
            RGSeparator(isEasyMode, isSolarMode)
            
            // Sequence
            Text(
                text = if (house.address.sequence == 0) "—" else house.address.sequence.toString(),
                style = basicStyle,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(seqWidth)
            )
            
            RGSeparator(isEasyMode, isSolarMode)
            
            // Complement
            Text(
                text = if (house.address.complement == 0) "—" else house.address.complement.toString(),
                style = basicStyle,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(seqWidth)
            )
            
            RGSeparator(isEasyMode, isSolarMode)
            
            // Type
            Text(
                text = house.propertyType.code,
                style = basicStyle,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(typeWidth)
            )
            
            RGSeparator(isEasyMode, isSolarMode)
            
            // Situation
            Text(
                text = house.situation.code.ifBlank { "—" },
                style = basicStyle,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(typeWidth)
            )
        }
        
        if (isTeammate) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 25.dp, end = 16.dp, bottom = paddingVertical),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Visita por: ${house.agentName.ifBlank { "Colega" }.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }
        }

        Divider(
            color = if (isSolarMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) 
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
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
