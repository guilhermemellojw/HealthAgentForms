package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.antigravity.healthagent.domain.repository.AuthUser

@Composable
fun GoogleIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val width = size.width
        val height = size.height
        val strokeWidth = width * 0.18f
        
        // Red - Top
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 140f,
            sweepAngle = 180f,
            useCenter = false,
            style = Stroke(width = strokeWidth),
            size = size
        )
        // Yellow - Left
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 140f,
            sweepAngle = -60f,
            useCenter = false,
            style = Stroke(width = strokeWidth),
            size = size
        )
        // Green - Bottom
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 40f,
            sweepAngle = 100f,
            useCenter = false,
            style = Stroke(width = strokeWidth),
            size = size
        )
        // Blue - Right and Bar
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = 0f,
            sweepAngle = 40f,
            useCenter = false,
            style = Stroke(width = strokeWidth),
            size = size
        )
        
        // Horizontal bar for 'G'
        val barWidth = width * 0.45f
        drawRect(
            color = Color(0xFF4285F4),
            topLeft = Offset(width * 0.5f, height * 0.41f),
            size = Size(barWidth, height * 0.18f)
        )
    }
}

@Composable
fun UserIconMenu(
    user: AuthUser,
    onLogout: () -> Unit,
    onSwitchAccount: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onPrimary
) {
    var showUserMenu by remember { mutableStateOf(false) }
    
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable { showUserMenu = true },
            contentAlignment = Alignment.Center
        ) {
            UserAvatar(
                user = user,
                size = 34.dp
            )
        }
        DropdownMenu(
            expanded = showUserMenu,
            onDismissRequest = { showUserMenu = false }
        ) {
            DropdownMenuItem(
                text = { 
                    Column {
                        Text(user.standardName, fontWeight = FontWeight.Bold)
                        Text(user.email ?: "", style = MaterialTheme.typography.bodySmall)
                        if (!user.agentName.isNullOrBlank() && user.displayName != null && user.agentName != user.displayName) {
                            Text("Perfil Google: ${user.displayName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                onClick = { },
                enabled = false
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Configurações") },
                onClick = { 
                    showUserMenu = false
                    onOpenSettings()
                },
                leadingIcon = { Icon(Icons.Default.Settings, null) }
            )
            DropdownMenuItem(
                text = { Text("Trocar Conta") },
                onClick = { 
                    showUserMenu = false
                    onSwitchAccount()
                },
                leadingIcon = { Icon(Icons.Default.SwapHoriz, null) }
            )
        }
    }
}

@Composable
fun UserAvatar(
    user: AuthUser,
    size: Dp,
    modifier: Modifier = Modifier
) {
    UserAvatar(
        uid = user.uid,
        displayName = user.displayName,
        email = user.email,
        photoUrl = user.photoUrl,
        size = size,
        modifier = modifier
    )
}

@Composable
fun UserAvatar(
    uid: String,
    displayName: String?,
    email: String?,
    photoUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val googleColors = listOf(
        Color(0xFF4285F4),
        Color(0xFF34A853),
        Color(0xFFFBBC04),
        Color(0xFFEA4335),
        Color(0xFF4285F4)
    )

    val avatarColors = listOf(
        Color(0xFF4285F4),
        Color(0xFFEA4335),
        Color(0xFFFBBC04),
        Color(0xFF34A853),
        Color(0xFF673AB7),
        Color(0xFFF44336),
        Color(0xFF2196F3),
        Color(0xFF4CAF50)
    )

    val backgroundColor = remember(uid) {
        avatarColors[uid.hashCode().let { if (it < 0) -it else it } % avatarColors.size]
    }

    var hasImageFailed by remember { mutableStateOf(false) }
    
    val initials = remember(displayName, email) {
        (displayName ?: email ?: "?").take(1).uppercase()
    }

    Surface(
        modifier = modifier
            .size(size)
            .border(
                BorderStroke(2.5.dp, Brush.sweepGradient(googleColors)),
                CircleShape
            )
            .padding(2.5.dp)
            .clip(CircleShape),
        color = backgroundColor,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp
    ) {
        if (!photoUrl.isNullOrEmpty() && !hasImageFailed) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) {
                        hasImageFailed = true
                    }
                }
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.5).sp,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
