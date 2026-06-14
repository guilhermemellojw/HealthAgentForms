package com.antigravity.healthagent.utils.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.withRotation
import com.antigravity.healthagent.domain.logger.AppLogger
import java.util.Calendar
import java.util.Locale

object PdfComponents {

    // --- Base Paints and Colors ---
    val linePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 0.5f
        style = Paint.Style.STROKE
    }

    val headerBgPaint = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
    }

    val barBgPaintLight = Paint().apply {
        color = Color.parseColor("#F2F2F2")
        style = Paint.Style.FILL
    }

    // --- Drawing primitives for BoletimPdfGenerator (using getTextBounds) ---

    fun drawCenteredTextWithBounds(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float) {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val tx = x + (w - bounds.width()) / 2
        val ty = y + (h + bounds.height()) / 2 - bounds.bottom
        canvas.drawText(text, tx, ty, paint)
    }

    fun drawTextInRectWithBounds(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
        if (alignLeft) {
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val ty = y + (h + bounds.height()) / 2 - bounds.bottom
            canvas.drawText(text, x + 2, ty, paint)
        } else {
            drawCenteredTextWithBounds(canvas, paint, text, x, y, w, h)
        }
    }

    fun drawRectBoxWithBounds(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        paint: Paint,
        bgPaint: Paint? = null,
        alignLeft: Boolean = false
    ) {
        if (bgPaint != null) {
            canvas.drawRect(x, y, x + w, y + h, bgPaint)
        }
        canvas.drawRect(x, y, x + w, y + h, linePaint)
        drawTextInRectWithBounds(canvas, paint, text, x, y, w, h, alignLeft)
    }

    fun drawCellWithBounds(canvas: Canvas, line: Paint, text: Paint, value: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
        canvas.drawRect(x, y, x + w, y + h, line)
        if (alignLeft) {
            val bounds = Rect()
            text.getTextBounds(value, 0, value.length, bounds)
            val ty = y + (h + bounds.height()) / 2 - bounds.bottom
            canvas.drawText(value, x + 2, ty, text)
        } else {
            drawCenteredTextWithBounds(canvas, text, value, x, y, w, h)
        }
    }

    fun drawHeaderBox(
        canvas: Canvas,
        line: Paint,
        textP: Paint,
        bgPaint: Paint?,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        label: String,
        value: String,
        labelSize: Float = 6f,
        valueSize: Float? = null
    ) {
        val stripH = if (h > 15) 12f else h / 2
        
        if (bgPaint != null) {
            canvas.drawRect(x, y, x + w, y + stripH, bgPaint)
        }
        canvas.drawRect(x, y, x + w, y + h, line)
        canvas.drawLine(x, y + stripH, x + w, y + stripH, line)

        val labelPaint = Paint(textP).apply { textSize = labelSize }
        drawCenteredTextWithBounds(canvas, labelPaint, label, x, y, w, stripH)
        
        val valP = Paint(textP).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            if (valueSize != null) textSize = valueSize
        }
        drawCenteredTextWithBounds(canvas, valP, value, x, y + stripH, w, h - stripH)
    }

    fun drawVerticalHeaderWithBounds(
        canvas: Canvas,
        line: Paint,
        text: Paint,
        bgPaint: Paint?,
        label: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float
    ) {
        if (bgPaint != null) {
            canvas.drawRect(x, y, x + w, y + h, bgPaint)
        }
        canvas.drawRect(x, y, x + w, y + h, line)
        
        canvas.withRotation(-90f, x + w / 2, y + h / 2) {
            val lines = label.split("\n")
            if (lines.size == 1) {
                drawCenteredTextWithBounds(this, text, label, x + w / 2 - h / 2, y + h / 2 - w / 2, h, w)
            } else {
                val rotX = x + w / 2 - h / 2
                val rotY = y + h / 2 - w / 2
                val rotW = h
                val rotH = w
                
                val fm = text.fontMetrics
                val lineHeight = fm.descent - fm.ascent
                val totalTextH = lines.size * lineHeight
                
                val blockTop = rotY + (rotH - totalTextH) / 2
                
                lines.forEachIndexed { i, lineStr ->
                    val bounds = Rect()
                    text.getTextBounds(lineStr, 0, lineStr.length, bounds)
                    val lineX = rotX + (rotW - bounds.width()) / 2
                    val lineBaseY = blockTop + (i * lineHeight) - fm.ascent
                    drawText(lineStr, lineX, lineBaseY, text)
                }
            }
        }
    }

    fun drawTextInBoxWithBounds(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
        if (alignLeft) {
            val bounds = Rect()
            paint.getTextBounds("A", 0, 1, bounds)
            val ty = y + (h + bounds.height()) / 2 - bounds.bottom
            canvas.drawText(text, x + 2, ty, paint)
        } else {
            val safeText = if (text.length > 25) text.take(23) + ".." else text
            drawCenteredTextWithBounds(canvas, paint, safeText, x, y, w, h)
        }
    }

    fun drawBlockCell(
        canvas: Canvas,
        linePaint: Paint,
        textPaint: Paint,
        blockNum: String?,
        blockSeq: String?,
        x: Float,
        y: Float,
        w: Float,
        h: Float
    ) {
        // Draw frame
        canvas.drawRect(x, y, x + w, y + h, linePaint)
        
        // Draw central separator "/"
        val sep = " / "
        val sepBounds = Rect()
        textPaint.getTextBounds(sep, 0, sep.length, sepBounds)
        
        val centerX = x + w / 2
        val centerY = y + (h + sepBounds.height()) / 2 - sepBounds.bottom
        
        // Center the "/"
        val sepX = centerX - (sepBounds.width() / 2f)
        canvas.drawText(sep, sepX, centerY, textPaint)
        
        // Draw blockNum to the left of "/"
        if (!blockNum.isNullOrBlank()) {
            val bBounds = Rect()
            textPaint.getTextBounds(blockNum, 0, blockNum.length, bBounds)
            val bx = sepX - bBounds.width() - 2f // 2f gap
            canvas.drawText(blockNum, bx, centerY, textPaint)
        }
        
        // Draw blockSeq to the right of "/"
        if (!blockSeq.isNullOrBlank()) {
            val sBounds = Rect()
            textPaint.getTextBounds(blockSeq, 0, blockSeq.length, sBounds)
            val sx = sepX + sepBounds.width() + 2f // 2f gap
            canvas.drawText(blockSeq, sx, centerY, textPaint)
        }
    }

    // --- Drawing primitives for SemanalPdfGenerator (using FontMetrics) ---

    fun drawCenteredTextWithMetrics(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val textX = if (alignLeft) x + 5f else x + (w - bounds.width()) / 2f
        val textY = y + h / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, textX, textY, paint)
    }

    fun drawTextInRectWithMetrics(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val textX = if (alignLeft) x else x + (w - bounds.width()) / 2f
        val textY = y + h / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, textX, textY, paint)
    }

    fun drawRectBoxWithMetrics(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, text: String, paint: Paint, bgPaint: Paint?, alignLeft: Boolean = false) {
        if (bgPaint != null) {
            canvas.drawRect(x, y, x + w, y + h, bgPaint)
        }
        canvas.drawRect(x, y, x + w, y + h, linePaint)
        if (text.isNotBlank()) {
            drawCenteredTextWithMetrics(canvas, paint, text, x, y, w, h, alignLeft)
        }
    }

    fun drawCellWithMetrics(canvas: Canvas, linePaint: Paint, textPaint: Paint, text: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
        canvas.drawRect(x, y, x + w, y + h, linePaint)
        if (text.isNotBlank()) {
            drawCenteredTextWithMetrics(canvas, textPaint, text, x, y, w, h, alignLeft)
        }
    }

    fun drawVerticalHeaderWithMetrics(canvas: Canvas, linePaint: Paint, textPaint: Paint, bgPaint: Paint?, label: String, x: Float, y: Float, w: Float, h: Float) {
        if (bgPaint != null) {
            canvas.drawRect(x, y, x + w, y + h, bgPaint)
        }
        canvas.drawRect(x, y, x + w, y + h, linePaint)
        
        val lines = label.split("\n")
        val paint = Paint(textPaint).apply { textSize = 7f }
        val lineHeight = paint.textSize + 2f
        val totalH = lines.size * lineHeight
        
        var curY = y + (h - totalH) / 2f + paint.textSize
        lines.forEach { line ->
            val textW = paint.measureText(line)
            canvas.drawText(line, x + w / 2f - textW / 2f, curY, paint)
            curY += lineHeight
        }
    }

    fun drawVerticalTextInBox(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float) {
        canvas.drawRect(x, y, x + w, y + h, linePaint)
        val centerX = x + w / 2f
        val centerY = y + h / 2f
        canvas.withRotation(-90f, centerX, centerY) {
            val textW = paint.measureText(text)
            drawText(text, centerX - textW / 2f, centerY + paint.textSize / 3f, paint)
        }
    }

    // --- Common Utility Methods ---

    fun calculateCicloFromDate(dateStr: String): String {
        return try {
            val sdf = com.antigravity.healthagent.utils.DateUtils.DASH_DATE.get()
            val dateObj = sdf.parse(dateStr)
            if (dateObj != null) {
                val cal = Calendar.getInstance()
                cal.time = dateObj
                val month = cal.get(Calendar.MONTH)
                when (month) {
                    Calendar.JANUARY, Calendar.FEBRUARY -> "1º"
                    Calendar.MARCH, Calendar.APRIL -> "2º"
                    Calendar.MAY, Calendar.JUNE -> "3º"
                    Calendar.JULY, Calendar.AUGUST -> "4º"
                    Calendar.SEPTEMBER, Calendar.OCTOBER -> "5º"
                    Calendar.NOVEMBER, Calendar.DECEMBER -> "6º"
                    else -> "1º"
                }
            } else "1º"
        } catch (e: Exception) {
            "1º"
        }
    }

    fun calculateCicloSemanal(date: String): String {
        try {
            val parts = date.split("-")
            if (parts.size == 3) {
                val month = parts[1].toInt()
                val cicloNum = ((month - 1) / 2) + 1
                return "${cicloNum}º"
            }
        } catch (e: Exception) {
            AppLogger.e("PdfComponents", "Erro ao calcular ciclo semanal", e)
        }
        return ""
    }

    fun dsh(v: Int): String = if (v == 0) "—" else v.toString()

    fun formatDouble(v: Double): String {
        if (v == 0.0) return "—"
        return if (v % 1.0 == 0.0) v.toInt().toString()
        else String.format(Locale("pt", "BR"), "%.1f", v)
    }

    fun formatDoubleUS(v: Double): String {
        if (v == 0.0) return ""
        return if (v % 1.0 == 0.0) v.toInt().toString()
        else String.format(Locale.US, "%.1f", v)
    }
}
