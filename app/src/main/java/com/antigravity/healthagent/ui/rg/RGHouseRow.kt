package com.antigravity.healthagent.ui.rg

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.healthagent.data.local.model.House
@Composable
fun RGHouseRow(
    house: House,
    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false
) {
    val basicStyle = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    val paddingVertical = if (isEasyMode) 16.dp else 12.dp
    val textHeight = if (isEasyMode) 24.sp else 20.sp
    
    // Column widths
    val numberWidth = if (isEasyMode) 55.dp else 50.dp
    val seqWidth = if (isEasyMode) 40.dp else 35.dp
    val typeWidth = if (isEasyMode) 35.dp else 30.dp
    
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = paddingVertical),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Street Name - Weight 1f to take available space
            Text(
                text = house.streetName,
                style = basicStyle,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Number
            Text(
                text = house.number.ifBlank { "—" },
                style = basicStyle,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(numberWidth)
            )
            
            RGSeparator(isEasyMode, isSolarMode)
            
            // Sequence
            Text(
                text = house.sequence?.toString() ?: "—",
                style = basicStyle,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(seqWidth)
            )
            
            RGSeparator(isEasyMode, isSolarMode)
            
            // Complement
            Text(
                text = house.complement?.toString() ?: "—",
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
                text = house.situation.code,
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
}

@Composable
private fun RGSeparator(isEasyMode: Boolean = false, isSolarMode: Boolean = false) {
    Text(
        text = "|",
        style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        color = if (isSolarMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) 
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = if (isEasyMode) 6.dp else 4.dp)
    )
}
