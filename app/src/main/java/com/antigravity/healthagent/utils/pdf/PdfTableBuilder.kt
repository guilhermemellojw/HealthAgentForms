package com.antigravity.healthagent.utils.pdf

import android.graphics.Canvas
import android.graphics.Paint

object PdfTableBuilder {

    fun drawRow(
        canvas: Canvas,
        linePaint: Paint,
        textPaint: Paint,
        x: Float,
        y: Float,
        h: Float,
        widths: List<Float>,
        values: List<String>,
        alignLefts: List<Boolean> = emptyList(),
        bolds: List<Boolean> = emptyList(),
        bgPaint: Paint? = null
    ) {
        var cx = x
        widths.forEachIndexed { i, w ->
            val value = values.getOrNull(i) ?: ""
            val isAlignLeft = alignLefts.getOrNull(i) ?: false
            val isBold = bolds.getOrNull(i) ?: false
            val paint = if (isBold) {
                Paint(textPaint).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
            } else textPaint

            if (bgPaint != null) {
                canvas.drawRect(cx, y, cx + w, y + h, bgPaint)
            }
            canvas.drawRect(cx, y, cx + w, y + h, linePaint)
            
            // Render text
            if (value.isNotBlank()) {
                PdfComponents.drawTextInRectWithBounds(canvas, paint, value, cx, y, w, h, isAlignLeft)
            }
            cx += w
        }
    }
}
