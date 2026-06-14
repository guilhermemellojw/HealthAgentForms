package com.antigravity.healthagent.ui.components

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.domain.repository.AuthUser

@Composable
fun MeshGradient(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    )
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mesh")
    val circleOffset1 by infiniteTransition.animateValue(
        initialValue = Offset(0.1f, 0.1f),
        targetValue = Offset(0.2f, 0.3f),
        typeConverter = Offset.VectorConverter,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset1"
    )
    val circleOffset2 by infiniteTransition.animateValue(
        initialValue = Offset(0.9f, 0.8f),
        targetValue = Offset(0.7f, 0.6f),
        typeConverter = Offset.VectorConverter,
        animationSpec = infiniteRepeatable(
            animation = tween(11000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset2"
    )

    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = colors,
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            )
            .let { 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    it.blur(80.dp)
                } else it
            }
            .drawBehind {
                drawCircle(
                    color = colors[0].copy(alpha = 0.4f),
                    radius = size.width * 0.9f,
                    center = Offset(size.width * circleOffset1.x, size.height * circleOffset1.y)
                )
                drawCircle(
                    color = colors[1].copy(alpha = 0.3f),
                    radius = size.width * 0.7f,
                    center = Offset(size.width * circleOffset2.x, size.height * circleOffset2.y)
                )
            }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    user: AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        MeshGradient(
            modifier = Modifier
                .matchParentSize()
                .let { 
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        it.blur(40.dp)
                    } else it
                },
            colors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
            )
        )
        
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = {
                actions()
                if (user != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    UserIconMenu(
                        user = user,
                        onLogout = onLogout,
                        onSwitchAccount = onSwitchAccount,
                        onOpenSettings = onOpenSettings
                    )
                }
            },
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )
    }
}

@Composable
fun GlassNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxWidth()) {
        MeshGradient(
            modifier = Modifier
                .matchParentSize()
                .let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        it.blur(40.dp)
                    } else it
                },
            colors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
            )
        )

        NavigationBar(
            containerColor = Color.Transparent,
            content = content
        )
    }
}
