package com.antigravity.healthagent.ui.components.house

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.ui.components.action.ActionIcon

@Composable
fun HouseRowActions(
    enabled: Boolean,
    isReorderMode: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onShowContext: () -> Unit,
    onShowDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .wrapContentWidth()
            .padding(start = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (isReorderMode) {
            IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Subir",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Descer",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            ActionIcon.Standard(
                icon = Icons.Default.Edit,
                onClick = onShowContext,
                contentDescription = "Editar Local",
                tint = MaterialTheme.colorScheme.primary,
                enabled = enabled,
            )
            if (enabled) {
                ActionIcon.Standard(
                    icon = Icons.Default.Delete,
                    onClick = onShowDelete,
                    contentDescription = "Excluir",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
