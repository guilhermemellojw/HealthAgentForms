package com.antigravity.healthagent.ui.home.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.ui.components.GlassTopAppBar
import com.antigravity.healthagent.ui.home.HomeUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    uiState: HomeUiState,
    isSearchActive: Boolean,
    isReorderMode: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    onReorderModeChange: (Boolean) -> Unit,
    strictPendingHousesCount: Int,
    onSearchQueryChange: (String) -> Unit,
    onLockClick: () -> Unit,
    user: AuthUser?,
    onLogout: () -> Unit,
    onSwitchAccount: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    onOpenSettings: () -> Unit
) {
    val titleText = if (isSearchActive) "Buscar Logradouro"
                    else if (isReorderMode) "Reordenar Imóveis"
                    else "Produção Diária"

    val navIcon: @Composable () -> Unit = {
        if (isSearchActive || isReorderMode) {
            IconButton(onClick = {
                if (isSearchActive) {
                    onSearchActiveChange(false)
                    onSearchQueryChange("")
                } else {
                    onReorderModeChange(false)
                }
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
            }
        }
    }

    GlassTopAppBar(
        title = {
            if (isSearchActive) {
                TextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Buscar Logradouro") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onPrimary),
                    singleLine = true
                )
            } else {
                Column {
                    Text(
                        titleText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    if (!isReorderMode) {
                        Text(
                            text = uiState.data.ifEmpty { "Selecione a Data" },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        },
        navigationIcon = navIcon,
        actions = {
            if (!isSearchActive && !isReorderMode) {
                // Pending houses badge
                val hasStrictPending = strictPendingHousesCount > 0
                Surface(
                    color = if (hasStrictPending) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
                            else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = "Pendentes: $strictPendingHousesCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Locker Icon
                IconButton(
                    onClick = {
                        if (uiState.isAdmin || !uiState.isSupervisor) {
                            onLockClick()
                        }
                    }
                ) {
                    val lockerColor = when {
                        uiState.isDayClosed -> MaterialTheme.colorScheme.error
                        uiState.isManualUnlock -> Color(0xFFFF9800) // Orange/Amber for manual override
                        else -> MaterialTheme.colorScheme.onPrimary
                    }
                    Icon(
                        if (uiState.isDayClosed) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = when {
                            uiState.isDayClosed -> "Dia Fechado"
                            uiState.isManualUnlock -> "Edição Extra Habilitada"
                            else -> "Dia Aberto"
                        },
                        tint = lockerColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        user = user,
        onLogout = onLogout,
        onSwitchAccount = onSwitchAccount,
        scrollBehavior = scrollBehavior,
        onOpenSettings = onOpenSettings
    )
}
