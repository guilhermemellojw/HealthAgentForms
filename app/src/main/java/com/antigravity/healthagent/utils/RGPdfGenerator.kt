package com.antigravity.healthagent.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Environment
import com.antigravity.healthagent.R
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.fitToWidth
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object RGPdfGenerator {

    private const val PAGE_WIDTH = 595 // A4 width in points (approx)
    private const val PAGE_HEIGHT = 842 // A4 height in points (approx)
    private const val MARGIN = 20f

    fun generatePdf(
        context: Context,
        houses: List<House>,
        bairro: String,
        block: String,
        municipio: String = "Bom Jardim",
        supervisor: String = "",
        gerente: String = "",
        participatingAgents: List<String> = emptyList()
    ): File {
        val pdfDocument = PdfDocument()
        val paint = Paint()

        // Page info
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()

        // We might need multiple pages if the list is long, but for now let's assume one or implement pagination logic
        // The sample shows one page with extensive rows. Let's see how many fit.
        // Left column ~25 rows, Right column ~25 rows -> ~50 houses per page.

        val housesPerPage = 52
        val totalPages = if (houses.isEmpty()) 1 else (houses.size + housesPerPage - 1) / housesPerPage

        val safeBairro = bairro.trim().uppercase()

        for (i in 0 until totalPages) {
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas
            val startIndex = i * housesPerPage
            val endIndex = minOf(startIndex + housesPerPage, houses.size)
            val pageHouses = houses.subList(startIndex, endIndex)

            drawPage(
                context = context,
                canvas = canvas,
                paint = paint,
                pageHouses = pageHouses,
                allHouses = houses, // Pass all houses for cumulative totals
                bairro = safeBairro,
                block = block,
                municipio = municipio,
                supervisor = supervisor,
                gerente = gerente,
                participatingAgents = participatingAgents,
                pageNumber = i + 1,
                totalPages = totalPages
            )
            pdfDocument.finishPage(page)
        }

        val safeBlockName = block.replace("/", "_")
        val file = File(context.cacheDir, "RG_${safeBairro}_Q${safeBlockName}.pdf")
        
        try {
            pdfDocument.writeTo(FileOutputStream(file))
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            pdfDocument.close()
        }

        return file
    }

    private fun drawHeaderSection(
        context: Context,
        canvas: Canvas,
        boldPaint: Paint,
        textPaint: Paint,
        headerAreaWidth: Float,
        headerY: Float,
        cursorY: Float
    ): Float {
        val prefText = "PREFEITURA MUNICIPAL DE BOM JARDIM"
        val secText = "SECRETARIA MUNICIPAL DE SAÚDE"
        
        val prefWidth = boldPaint.measureText(prefText)
        val secWidth = textPaint.measureText(secText)
        
        drawTextInRect(canvas, boldPaint, prefText, MARGIN, headerY, headerAreaWidth, 10f, alignLeft = true)
        
        val secBounds = Rect()
        textPaint.getTextBounds(secText, 0, secText.length, secBounds)
        val secTyCalculated = headerY + 10 + (10f + secBounds.height()) / 2 - secBounds.bottom
        
        val centerPref = MARGIN + 4f + (prefWidth / 2)
        val secX = centerPref - (secWidth / 2)
        val finalSecX = if (secX < MARGIN) MARGIN else secX
        
        canvas.drawText(secText, finalSecX, secTyCalculated, textPaint)
        
        val nextY = headerY + 10 + 10.2f
        val logoBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.logo_vigilancia_ambiental)
        if (logoBitmap != null) {
            val logoWidth = 140
            val finalLogoWidth = if (logoWidth > headerAreaWidth) headerAreaWidth.toInt() else logoWidth
            val finalLogoHeight = (logoBitmap.height.toFloat() / logoBitmap.width.toFloat() * finalLogoWidth).toInt()

            val destRect = Rect((MARGIN + 5).toInt(), nextY.toInt(), (MARGIN + 5 + finalLogoWidth).toInt(), nextY.toInt() + finalLogoHeight)
            canvas.drawBitmap(logoBitmap, null, destRect, null)
            
            val logoBottom = nextY + finalLogoHeight
            return if (logoBottom > cursorY + 60) logoBottom else cursorY + 60
        }
        return cursorY + 50
    }

    private fun drawResponsavelTable(
        canvas: Canvas,
        linePaint: Paint,
        textPaint: Paint,
        boldPaint: Paint,
        tableX: Float,
        tableY: Float,
        tableWidth: Float,
        responsavelRowH: Float,
        rowH: Float
    ): Float {
        drawRect(canvas, linePaint, tableX, tableY, tableWidth, responsavelRowH)
        drawCenteredText(canvas, boldPaint, "RESPONSÁVEL", tableX, tableY, tableWidth, responsavelRowH)
        
        val r1y = tableY + responsavelRowH
        val c1w = tableWidth * 0.25f
        val c2w = tableWidth * 0.25f
        val c3w = tableWidth * 0.35f
        val c4w = tableWidth * 0.15f
        
        drawRect(canvas, linePaint, tableX, r1y, c1w, rowH)
        drawTextInRect(canvas, textPaint, "Gerente", tableX, r1y, c1w, rowH)
        drawRect(canvas, linePaint, tableX + c1w, r1y, c2w, rowH) 
        drawRect(canvas, linePaint, tableX + c1w + c2w, r1y, c3w, rowH)
        drawTextInRect(canvas, textPaint, "Supervisor de Turma", tableX + c1w + c2w, r1y, c3w, rowH)
        drawRect(canvas, linePaint, tableX + c1w + c2w + c3w, r1y, c4w, rowH) 
        
        val r2y = r1y + rowH
        drawRect(canvas, linePaint, tableX, r2y, c1w, rowH)
        drawTextInRect(canvas, textPaint, "Supervisor", tableX, r2y, c1w, rowH)
        drawRect(canvas, linePaint, tableX + c1w, r2y, c2w, rowH) 
        drawRect(canvas, linePaint, tableX + c1w + c2w, r2y, c3w, rowH)
        drawTextInRect(canvas, textPaint, "Agente", tableX + c1w + c2w, r2y, c3w, rowH)
        drawRect(canvas, linePaint, tableX + c1w + c2w + c3w, r2y, c4w, rowH)
        drawCenteredText(canvas, textPaint, "X", tableX + c1w + c2w + c3w, r2y, c4w, rowH)
        
        return r2y + rowH
    }

    private fun drawLocationHeaderTable(
        canvas: Canvas,
        linePaint: Paint,
        textPaint: Paint,
        boldPaint: Paint,
        cursorY: Float,
        municipio: String,
        bairro: String,
        block: String,
        rowHeight: Float
    ) {
        val totalRowWidth = PAGE_WIDTH - 2 * MARGIN
        val halfWidth = totalRowWidth / 2
        val w1 = halfWidth / 2
        val w2 = halfWidth / 2
        val w3 = halfWidth / 2
        val w4 = halfWidth / 2
        
        var curX = MARGIN
        drawRect(canvas, linePaint, curX, cursorY, w1, rowHeight)
        drawTextInRect(canvas, textPaint, "MUNICÍPIO", curX, cursorY, w1, rowHeight, alignLeft = true)
        curX += w1
        drawRect(canvas, linePaint, curX, cursorY, w2, rowHeight)
        drawCenteredText(canvas, boldPaint, municipio, curX, cursorY, w2, rowHeight)
        curX += w2
        drawRect(canvas, linePaint, curX, cursorY, w3, rowHeight)
        drawTextInRect(canvas, textPaint, "CATEGORIA", curX, cursorY, w3, rowHeight, alignLeft = true)
        curX += w3
        drawRect(canvas, linePaint, curX, cursorY, w4, rowHeight)
        drawCenteredText(canvas, boldPaint, "BRR", curX, cursorY, w4, rowHeight)
        
        val line2y = cursorY + rowHeight
        curX = MARGIN
        drawRect(canvas, linePaint, curX, line2y, w1, rowHeight)
        drawTextInRect(canvas, textPaint, "BAIRRO", curX, line2y, w1, rowHeight, alignLeft = true)
        curX += w1
        drawRect(canvas, linePaint, curX, line2y, w2, rowHeight)
        drawCenteredText(canvas, boldPaint, bairro, curX, line2y, w2, rowHeight)
        curX += w2
        drawRect(canvas, linePaint, curX, line2y, w3, rowHeight)
        drawTextInRect(canvas, textPaint, "QUART. Nº", curX, line2y, w3, rowHeight, alignLeft = true)
        curX += w3
        drawRect(canvas, linePaint, curX, line2y, w4, rowHeight)
        drawCenteredText(canvas, boldPaint, block, curX, line2y, w4, rowHeight)
    }

    private fun drawPage(
        context: Context,
        canvas: Canvas,
        paint: Paint,
        pageHouses: List<House>,
        allHouses: List<House>, // New parameter for global context
        bairro: String,
        block: String,
        municipio: String,
        supervisor: String,
        gerente: String,
        participatingAgents: List<String>,
        pageNumber: Int,
        totalPages: Int
    ) {
        // --- Paints ---
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 8f
            isAntiAlias = true
        }
        val boldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 8f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }


        var cursorY = MARGIN

        val totalWidth = PAGE_WIDTH - 2 * MARGIN
        val colGap = 10f
        val listWidth = (totalWidth - colGap) / 2
        
        val cmN = 35f
        val cmSeq = 25f
        val cmComp = 25f
        val cmTipo = 30f
        val cmPend = 25f
        val cmLog = listWidth - cmN - cmSeq - cmComp - cmTipo - cmPend
        
        val alignX = MARGIN + cmLog + cmN + cmSeq + cmComp
        
        val tableX = alignX
        val tableWidth = PAGE_WIDTH - MARGIN - tableX
        val tableY = MARGIN
        val rowH = 25f
        val responsavelRowH = 16f
        
        val headerAreaWidth = tableX - MARGIN - 10f
        
        val headerBottomLogo = drawHeaderSection(
            context = context,
            canvas = canvas,
            boldPaint = boldPaint,
            textPaint = textPaint,
            headerAreaWidth = headerAreaWidth,
            headerY = cursorY,
            cursorY = cursorY
        )
        
        val tableBottomY = drawResponsavelTable(
            canvas = canvas,
            linePaint = linePaint,
            textPaint = textPaint,
            boldPaint = boldPaint,
            tableX = tableX,
            tableY = tableY,
            tableWidth = tableWidth,
            responsavelRowH = responsavelRowH,
            rowH = rowH
        )

        val headerBottom = maxOf(headerBottomLogo, tableBottomY)
        
        val folhaY = headerBottom
        drawCenteredText(canvas, textPaint, "FOLHA $pageNumber/$totalPages", MARGIN, folhaY, PAGE_WIDTH - 2 * MARGIN, 10f)
        
        cursorY = folhaY + 12f
        
        val rowHeight = 20f
        
        drawLocationHeaderTable(
            canvas = canvas,
            linePaint = linePaint,
            textPaint = textPaint,
            boldPaint = boldPaint,
            cursorY = cursorY,
            municipio = municipio,
            bairro = bairro,
            block = block,
            rowHeight = rowHeight
        )
        
        cursorY += (2 * rowHeight) + 10
        
        // --- Lists (Two Columns) ---
        // Total width available parameters defined at top of function for alignment calculations
        
        // Column headers
        // | Logradouro | Nº | Seq. | Comp. | Tipo | Pend. |
        // relative weights:
        // Logradouro: large
        // N: small
        // Seq: small
        // Comp: small
        // Tipo: small
        // Pend: small
        
        // Let's define column widths for the inner table
        // Let's define column widths for the inner table
        val cwN = 35f
        val cwSeq = 25f
        val cwComp = 25f
        val cwTipo = 30f
        val cwPend = 25f
        val cwLog = listWidth - cwN - cwSeq - cwComp - cwTipo - cwPend
        
        val listHeaderHeight = 60f
        val itemHeight = 15f
        
        val leftX = MARGIN
        val rightX = MARGIN + listWidth + colGap
        
        // Draw headers for both columns
        drawListHeader(canvas, linePaint, textPaint, leftX, cursorY, cwLog, cwN, cwSeq, cwComp, cwTipo, cwPend, listHeaderHeight)
        drawListHeader(canvas, linePaint, textPaint, rightX, cursorY, cwLog, cwN, cwSeq, cwComp, cwTipo, cwPend, listHeaderHeight)
        
        var listY = cursorY + listHeaderHeight
        
        // Split houses into left and right
        val maxRows = 26
        val housesLeft = pageHouses.take(minOf(pageHouses.size, maxRows))
        val housesRight = if (pageHouses.size > maxRows) pageHouses.drop(maxRows) else emptyList()
        
        // Draw Left List
        for (house in housesLeft) {
            drawHouseRow(canvas, linePaint, textPaint, house, leftX, listY, cwLog, cwN, cwSeq, cwComp, cwTipo, cwPend, itemHeight)
            listY += itemHeight
        }
        // Fill remaining rows if needed to match height? Not strictly necessary but looks better.
        // For now just draw the items.
        
        // Draw Right List
        var rightListY = cursorY + listHeaderHeight
        for (house in housesRight) {
             drawHouseRow(canvas, linePaint, textPaint, house, rightX, rightListY, cwLog, cwN, cwSeq, cwComp, cwTipo, cwPend, itemHeight)
             rightListY += itemHeight
        }
        
        // Draw vertical lines for empty slots to fill page? 
        // The user wants "exact layout". The sample has fixed rows.
        // Let's calculate how many rows fit in the space (~25 rows per col)
        // val maxRows = 26 // Already defined above
        val filledLeft = housesLeft.size
        for (k in filledLeft until maxRows) {
             drawEmptyRow(canvas, linePaint, leftX, listY, cwLog, cwN, cwSeq, cwComp, cwTipo, cwPend, itemHeight)
             listY += itemHeight
        }
        val filledRight = housesRight.size
        for (k in filledRight until maxRows) {
             drawEmptyRow(canvas, linePaint, rightX, rightListY, cwLog, cwN, cwSeq, cwComp, cwTipo, cwPend, itemHeight)
             rightListY += itemHeight
        }

        // --- Footer (Totals & Signatures) ---
        // Drawn on EVERY page of the report
        if (true) {
            val footerY = cursorY + listHeaderHeight + (maxRows * itemHeight) + 10
            
            // Calculate Totals for the houses on THIS page
            val totalResidencial = pageHouses.count { it.propertyType == PropertyType.R && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
            val totalComercial = pageHouses.count { it.propertyType == PropertyType.C && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
            val totalTerreno = pageHouses.count { it.propertyType == PropertyType.TB && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
            val totalPonto = pageHouses.count { it.propertyType == PropertyType.PE && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
            val totalOutros = pageHouses.count { it.propertyType == PropertyType.O && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
            val totalPendentes = pageHouses.count { it.situation != Situation.NONE && it.situation != Situation.EMPTY } 
            val totalGeral = pageHouses.size
            
            val gap = 0f
            val totalTableWidth = PAGE_WIDTH - 2 * MARGIN
            val blockWidth = (totalTableWidth - gap) / 2
            
            val fw2 = 35f // Code width
            val fw3 = 80f // Value width
            val fw1 = blockWidth - fw2 - fw3 // Label width
            val midX = MARGIN + blockWidth + gap
            
            // Header "FECHAMENTO"
            val fh = 15f
            drawRect(canvas, linePaint, MARGIN, footerY, PAGE_WIDTH - 2*MARGIN, fh)
            drawCenteredText(canvas, boldPaint, "FECHAMENTO", MARGIN, footerY, PAGE_WIDTH - 2*MARGIN, fh)
            
            var fy = footerY + fh
            
            // Row 1
            drawFooterCell(canvas, linePaint, textPaint, "Residencial", "R", totalResidencial.toString(), MARGIN, fy, fw1, fw2, fw3, fh)
            drawFooterCell(canvas, linePaint, textPaint, "Ponto Estratégico", "PE", totalPonto.toString(), midX, fy, fw1, fw2, fw3, fh)
            
            fy += fh
            // Row 2
            drawFooterCell(canvas, linePaint, textPaint, "Comercial", "C", totalComercial.toString(), MARGIN, fy, fw1, fw2, fw3, fh)
            drawFooterCell(canvas, linePaint, textPaint, "Outros", "O", totalOutros.toString(), midX, fy, fw1, fw2, fw3, fh)
            
            fy += fh
            // Row 3
            drawFooterCell(canvas, linePaint, textPaint, "Terreno Baldio", "TB", totalTerreno.toString(), MARGIN, fy, fw1, fw2, fw3, fh)
            drawRect(canvas, linePaint, midX, fy, fw1 + fw2, fh)
            drawTextInRect(canvas, textPaint, "Total Geral", midX, fy, fw1 + fw2, fh, alignLeft = true)
            drawRect(canvas, linePaint, midX+fw1+fw2, fy, fw3, fh)
            drawCenteredText(canvas, boldPaint, totalGeral.toString(), midX+fw1+fw2, fy, fw3, fh)
            
            fy += fh
            // Row 4
            drawRect(canvas, linePaint, MARGIN, fy, fw1, fh); drawTextInRect(canvas, textPaint, "Pendentes", MARGIN, fy, fw1, fh, alignLeft=true)
            drawRect(canvas, linePaint, MARGIN+fw1, fy, fw2, fh); drawCenteredText(canvas, textPaint, "P", MARGIN+fw1, fy, fw2, fh)
            val startValueX = MARGIN + fw1 + fw2
            val fullValueWidth = PAGE_WIDTH - MARGIN - startValueX
            drawRect(canvas, linePaint, startValueX, fy, fullValueWidth, fh)
            drawCenteredText(canvas, textPaint, totalPendentes.toString(), startValueX, fy, fw3, fh)

            fy += fh + 12
            
            // --- Signatures ---
            // Use the provided participatingAgents list (TEAMWORK EVOLUTION)
            // If empty, fallback to local page discovery for compatibility
            val rawAgentName = if (participatingAgents.isNotEmpty()) {
                participatingAgents.joinToString(" / ").uppercase()
            } else {
                pageHouses
                    .mapNotNull { it.agentName }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" / ").uppercase()
            }
            
            val sigH = 20f
            val totalSigWidth = PAGE_WIDTH - 2 * MARGIN
            val maxSigNameWidth = totalSigWidth - (textPaint.measureText("ASSINATURA:") + 15f)
            val agentNameDisplay = rawAgentName.fitToWidth(textPaint, maxSigNameWidth)
            val dateValue = pageHouses.lastOrNull()?.data ?: ""

            // Row 1: NOME
            drawRect(canvas, linePaint, MARGIN, fy, totalSigWidth, sigH)
            val nomeLabel = "NOME:"
            val nomeLabelWidth = textPaint.measureText(nomeLabel)
            drawTextInRect(canvas, textPaint, nomeLabel, MARGIN, fy, nomeLabelWidth + 10, sigH, alignLeft = true)
            drawTextInRect(canvas, textPaint, agentNameDisplay, MARGIN + nomeLabelWidth + 5f, fy, totalSigWidth - nomeLabelWidth - 5f, sigH, alignLeft = true)
            
            fy += sigH
            
            // Row 2: ASSINATURA | DATA
            val dateColWidth = totalSigWidth * 0.25f
            val sigColWidth = totalSigWidth - dateColWidth
            drawRect(canvas, linePaint, MARGIN, fy, sigColWidth, sigH)
            val sigLabel = "ASSINATURA:"
            val sigLabelWidth = textPaint.measureText(sigLabel)
            drawTextInRect(canvas, textPaint, sigLabel, MARGIN, fy, sigLabelWidth + 10, sigH, alignLeft = true)
            drawTextInRect(canvas, textPaint, agentNameDisplay, MARGIN + sigLabelWidth + 5f, fy, sigColWidth - sigLabelWidth - 5f, sigH, alignLeft = true)
            
            val dataX = MARGIN + sigColWidth
            drawRect(canvas, linePaint, dataX, fy, dateColWidth, sigH)
            val dataLabel = "DATA"
            val dataLabelWidth = textPaint.measureText(dataLabel)
            drawTextInRect(canvas, textPaint, dataLabel, dataX, fy, dataLabelWidth + 10, sigH, alignLeft = true)
            drawTextInRect(canvas, textPaint, dateValue, dataX + dataLabelWidth + 15f, fy, dateColWidth - dataLabelWidth - 15f, sigH, alignLeft = true)
        }
    }
    
    // --- Helper Functions ---
    
    private fun drawRect(canvas: Canvas, paint: Paint, x: Float, y: Float, w: Float, h: Float) {
        canvas.drawRect(x, y, x + w, y + h, paint)
    }

    private fun drawCenteredText(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float) {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val tx = x + (w - bounds.width()) / 2
        val ty = y + (h + bounds.height()) / 2 - bounds.bottom // vertical center
        canvas.drawText(text, tx, ty, paint)
    }
    
    private fun drawTextInRect(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
        if (alignLeft) {
             val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val ty = y + (h + bounds.height()) / 2 - bounds.bottom
            canvas.drawText(text, x + 4f, ty, paint)
        } else {
            drawCenteredText(canvas, paint, text, x, y, w, h)
        }
    }

    private fun drawListHeader(canvas: Canvas, line: Paint, text: Paint, x: Float, y: Float, wLog: Float, wN: Float, wSeq: Float, wComp: Float, wTipo: Float, wPend: Float, h: Float) {
        // Logradouro
        drawRect(canvas, line, x, y, wLog, h); drawCenteredText(canvas, text, "Logradouro", x, y, wLog, h)
        // Nº
        drawRect(canvas, line, x+wLog, y, wN, h); drawCenteredText(canvas, text, "Nº", x+wLog, y, wN, h)
        
        // Vertical text headers for others?
        // Seq, Comp, Tipo, Pend are vertical in the image
        drawVerticalText(canvas, text, "Sequência", x+wLog+wN, y, wSeq, h)
        drawVerticalText(canvas, text, "Complemento", x+wLog+wN+wSeq, y, wComp, h)
        drawVerticalText(canvas, text, "Tipo do Imóvel", x+wLog+wN+wSeq+wComp, y, wTipo, h)
        drawVerticalText(canvas, text, "Pendência", x+wLog+wN+wSeq+wComp+wTipo, y, wPend, h)
        
        // Draw boxes
        drawRect(canvas, line, x+wLog+wN, y, wSeq, h)
        drawRect(canvas, line, x+wLog+wN+wSeq, y, wComp, h)
        drawRect(canvas, line, x+wLog+wN+wSeq+wComp, y, wTipo, h)
        drawRect(canvas, line, x+wLog+wN+wSeq+wComp+wTipo, y, wPend, h)
    }

    private fun drawVerticalText(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float) {
        canvas.save()
        canvas.rotate(-90f, x + w / 2, y + h / 2)
        drawCenteredText(canvas, paint, text, x + w/2 - h/2, y + h/2 - w/2, h, w) // swap dims for rotated
        canvas.restore()
    }

    private fun drawHouseRow(canvas: Canvas, line: Paint, text: Paint, house: House, x: Float, y: Float, wLog: Float, wN: Float, wSeq: Float, wComp: Float, wTipo: Float, wPend: Float, h: Float) {
        var cur = x
        
        // Smart Abbreviation Logic for Street Name
        val streetNameRaw = house.address.streetName.formatStreetName()
        
        // 4f padding is used in drawTextInRect (alignLeft adds 4f)
        // We give a bit more safety margin (e.g. 6f)
        val availableDescWidth = wLog - 6f 
        
        val streetName = streetNameRaw.fitToWidth(text, availableDescWidth)

        drawRect(canvas, line, cur, y, wLog, h); drawTextInRect(canvas, text, streetName, cur, y, wLog, h, alignLeft=true)
        cur += wLog
        val nText = house.address.number.ifEmpty { "—" }
        drawRect(canvas, line, cur, y, wN, h); drawCenteredText(canvas, text, nText, cur, y, wN, h)
        cur += wN
        val seqText = house.address.sequence.let { if (it == 0) "—" else it.toString() }
        drawRect(canvas, line, cur, y, wSeq, h); drawCenteredText(canvas, text, seqText, cur, y, wSeq, h)
        cur += wSeq
        val compText = house.address.complement.let { if (it == 0) "—" else it.toString() }
        drawRect(canvas, line, cur, y, wComp, h); drawCenteredText(canvas, text, compText, cur, y, wComp, h)
        cur += wComp
        drawRect(canvas, line, cur, y, wTipo, h); drawCenteredText(canvas, text, house.propertyType.code, cur, y, wTipo, h)
        cur += wTipo
        val pendText = if(house.situation == Situation.NONE || house.situation == Situation.EMPTY) "—" else house.situation.code
        drawRect(canvas, line, cur, y, wPend, h); drawCenteredText(canvas, text, pendText, cur, y, wPend, h)
    }
    
    private fun drawEmptyRow(canvas: Canvas, line: Paint, x: Float, y: Float, wLog: Float, wN: Float, wSeq: Float, wComp: Float, wTipo: Float, wPend: Float, h: Float) {
        var cur = x
        drawRect(canvas, line, cur, y, wLog, h)
        cur += wLog
        drawRect(canvas, line, cur, y, wN, h)
        cur += wN
        drawRect(canvas, line, cur, y, wSeq, h)
        cur += wSeq
        drawRect(canvas, line, cur, y, wComp, h)
        cur += wComp
        drawRect(canvas, line, cur, y, wTipo, h)
        cur += wTipo
        drawRect(canvas, line, cur, y, wPend, h)
    }
    
    private fun drawFooterCell(canvas: Canvas, line: Paint, text: Paint, label: String, code: String, value: String, x: Float, y: Float, w1: Float, w2: Float, w3: Float, h: Float) {
        drawRect(canvas, line, x, y, w1, h); drawTextInRect(canvas, text, label, x, y, w1, h, alignLeft=true)
        drawRect(canvas, line, x+w1, y, w2, h); drawCenteredText(canvas, text, code, x+w1, y, w2, h)
        drawRect(canvas, line, x+w1+w2, y, w3, h); drawCenteredText(canvas, text, value, x+w1+w2, y, w3, h)
    }
    
    // minOf for integers
    private fun minOf(a: Int, b: Int): Int {
        return if (a <= b) a else b
    }
    
    private fun maxOf(a: Float, b: Float): Float {
        return if (a >= b) a else b
    }
}
