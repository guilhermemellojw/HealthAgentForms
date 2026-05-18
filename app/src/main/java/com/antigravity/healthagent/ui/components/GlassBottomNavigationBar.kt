package com.antigravity.healthagent.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun GlassBottomNavigationBar(
    isSupervisor: Boolean,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassNavigationBar(modifier = modifier) {
        val navItemColors = NavigationBarItemDefaults.colors(
            indicatorColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
            selectedTextColor = MaterialTheme.colorScheme.onPrimary,
            unselectedIconColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
            unselectedTextColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
        )

        if (isSupervisor) {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { onTabSelected(0) },
                icon = { Icon(Icons.Default.Dashboard, contentDescription = "Resumo") },
                label = { Text("Resumo") },
                colors = navItemColors
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                icon = { Icon(Icons.Default.Group, contentDescription = "Agentes") },
                label = { Text("Agentes") },
                colors = navItemColors
            )
        } else {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { onTabSelected(0) },
                icon = { Icon(Icons.Default.EditNote, contentDescription = "Produção") },
                label = { Text("Produção") },
                colors = navItemColors
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                icon = { Icon(Icons.Default.LibraryBooks, contentDescription = "RG") },
                label = { Text("RG") },
                colors = navItemColors
            )
            NavigationBarItem(
                selected = selectedTab == 2,
                onClick = { onTabSelected(2) },
                icon = { Icon(Icons.Default.Assignment, contentDescription = "Boletim") },
                label = { Text("Boletim") },
                colors = navItemColors
            )
            NavigationBarItem(
                selected = selectedTab == 3,
                onClick = { onTabSelected(3) },
                icon = { Icon(Icons.Default.DateRange, contentDescription = "Semanal") },
                label = { Text("Semanal") },
                colors = navItemColors
            )
            NavigationBarItem(
                selected = selectedTab == 4,
                onClick = { onTabSelected(4) },
                icon = { Icon(Icons.Default.LocationOn, contentDescription = "Quarteirões") },
                label = { Text("Mapa") },
                colors = navItemColors
            )
        }
    }
}
