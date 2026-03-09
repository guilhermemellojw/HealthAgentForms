package com.antigravity.healthagent.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

@Composable
fun IsometricNeighborhoodView(
    modifier: Modifier = Modifier,
    houseCount: Int = 10
) {
    val infiniteTransition = rememberInfiniteTransition(label = "isoRotation")
    val floatAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )

    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2 + floatAnim
        
        val isoW = 32.dp.toPx()
        val isoH = 16.dp.toPx()
        
        // Draw Grid
        drawIsometricGrid(centerX, centerY, 5, 5, isoW, isoH)
        
        // Draw Houses
        for (i in 0 until houseCount.coerceAtMost(25)) {
            val gx = (i % 5) - 2
            val gy = (i / 5) - 2
            
            val dx = centerX + (gx - gy) * isoW
            val dy = centerY + (gx + gy) * isoH
            
            drawIsometricHouse(dx, dy, isoW * 0.7f, isoH * 0.7f)
        }
    }
}

private fun DrawScope.drawIsometricGrid(cx: Float, cy: Float, rows: Int, cols: Int, w: Float, h: Float) {
    val gridColor = Color.White.copy(alpha = 0.1f)
    for (i in -rows/2..rows/2) {
        val startX = cx + (i - (-cols/2)) * w
        val startY = cy + (i + (-cols/2)) * h
        val endX = cx + (i - (cols/2)) * w
        val endY = cy + (i + (cols/2)) * h
        drawLine(gridColor, Offset(startX, startY), Offset(endX, endY), strokeWidth = 1.dp.toPx())
    }
    for (j in -cols/2..cols/2) {
        val startX = cx + ((-rows/2) - j) * w
        val startY = cy + ((-rows/2) + j) * h
        val endX = cx + ((rows/2) - j) * w
        val endY = cy + ((rows/2) + j) * h
        drawLine(gridColor, Offset(startX, startY), Offset(endX, endY), strokeWidth = 1.dp.toPx())
    }
}

private fun DrawScope.drawIsometricHouse(x: Float, y: Float, w: Float, h: Float) {
    val houseColor = Color(0xFF4DB6AC) // Teal
    val roofColor = Color(0xFF00796B)
    val sideColor = Color(0xFF80CBC4)

    // Base/Sides
    val leftFace = Path().apply {
        moveTo(x, y)
        lineTo(x - w, y - h / 2)
        lineTo(x - w, y - h * 1.5f)
        lineTo(x, y - h)
        close()
    }
    drawPath(leftFace, color = sideColor)

    val rightFace = Path().apply {
        moveTo(x, y)
        lineTo(x + w, y - h / 2)
        lineTo(x + w, y - h * 1.5f)
        lineTo(x, y - h)
        close()
    }
    drawPath(rightFace, color = houseColor)

    // Roof
    val roof = Path().apply {
        moveTo(x, y - h)
        lineTo(x - w, y - h * 1.5f)
        lineTo(x, y - h * 2f)
        lineTo(x + w, y - h * 1.5f)
        close()
    }
    drawPath(roof, color = roofColor)
}
