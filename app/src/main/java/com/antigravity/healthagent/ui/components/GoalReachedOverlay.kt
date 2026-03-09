package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun GoalReachedOverlay(onDismiss: () -> Unit) {
    // Confetti State
    val particles = remember {
        List(50) {
            Confetto(
                color = Color(
                    red = kotlin.random.Random.nextFloat(),
                    green = kotlin.random.Random.nextFloat(),
                    blue = kotlin.random.Random.nextFloat(),
                    alpha = 1f
                ),
                x = kotlin.random.Random.nextDouble(-100.0, 1100.0).toFloat(), // Wide start to drift in
                y = kotlin.random.Random.nextDouble(-500.0, -50.0).toFloat(), // Start above screen
                speedX = kotlin.random.Random.nextDouble(-2.0, 2.0).toFloat(),
                speedY = kotlin.random.Random.nextDouble(5.0, 15.0).toFloat(),
                rotation = kotlin.random.Random.nextFloat() * 360f,
                rotationSpeed = kotlin.random.Random.nextDouble(-5.0, 5.0).toFloat()
            )
        }
    }
    
    var time by remember { mutableStateOf(0L) }
    
    LaunchedEffect(Unit) {
        val startTime = System.nanoTime()
        while(true) {
            time = System.nanoTime() - startTime
            kotlinx.coroutines.delay(16) // ~60fps
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
         Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { onDismiss() } // Dismiss on outside click
        ) {
            // Confetti
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                
                particles.forEach { p ->
                    val elapsedSeconds = time / 1_000_000_000.0
                    val x = (p.x + p.speedX * (elapsedSeconds * 60)).toFloat()
                    val y = (p.y + p.speedY * (elapsedSeconds * 60)).toFloat()
                    
                    val wrappedY = y % (canvasHeight + 100) - 50 // Loop
                    val wrappedX = (x % canvasWidth + canvasWidth) % canvasWidth
                    
                    drawCircle(
                        color = p.color,
                        radius = 8.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(wrappedX, wrappedY)
                    )
                }
            }

            // Card
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Trophy / Star
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Success",
                        tint = Color(0xFFFFD700), // Gold
                        modifier = Modifier
                            .size(64.dp)
                    )

                    // 3D Isometric View
                    IsometricNeighborhoodView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        houseCount = 15 // Or count from ViewModel if passed
                    )
                    
                    Text(
                        text = "Meta Diária Atingida!",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Text(
                        text = "Você concluiu a produção diária. Inicie um novo dia para continuar adicionando imóveis.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Continuar", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

data class Confetto(
    val color: Color,
    val x: Float,
    val y: Float,
    val speedX: Float,
    val speedY: Float,
    val rotation: Float,
    val rotationSpeed: Float
)
