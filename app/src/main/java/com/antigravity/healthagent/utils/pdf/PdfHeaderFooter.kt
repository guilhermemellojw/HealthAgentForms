package com.antigravity.healthagent.utils.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

object PdfHeaderFooter {

    fun drawHeaderLogo(
        canvas: Canvas,
        logo: Bitmap?,
        x: Float,
        y: Float,
        height: Float,
        fallbackText: String = "LOGO"
    ): Float {
        if (logo != null) {
            val logoW = (logo.width.toFloat() / logo.height.toFloat() * height)
            val destRect = Rect(x.toInt(), y.toInt(), (x + logoW).toInt(), (y + height).toInt())
            canvas.drawBitmap(logo, null, destRect, null)
            return logoW
        } else {
            val paint = Paint().apply {
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textSize = 10f
            }
            PdfComponents.drawTextInRectWithBounds(canvas, paint, fallbackText, x, y, 60f, height)
            return 60f
        }
    }

    fun drawPageCounter(
        canvas: Canvas,
        paint: Paint,
        currentPage: Int,
        totalPages: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float
    ) {
        val text = "Folha:   $currentPage   /   $totalPages"
        PdfComponents.drawCenteredTextWithBounds(canvas, paint, text, x, y, width, height)
    }
}
