package com.antigravity.healthagent.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.withRotation
import com.antigravity.healthagent.R // Fixed Import
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation

import java.io.File
import java.io.FileOutputStream


object BoletimPdfGenerator {

    private const val PAGE_WIDTH = 842 // A4 Landscape width (595 * 1.414)
    private const val PAGE_HEIGHT = 595 // A4 Landscape height
    private const val MARGIN = 20f

    fun generatePdf(
        context: Context,
        houses: List<House>,
        date: String,
        agentName: String
    ): File {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        
        // Hoist bitmap decoding to avoid redundant work in loops
        val logoBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.governo_rj_logo)

        val chunksToProcess = createChunks(houses)
        val totalFolhas = chunksToProcess.size
        
        try {
            chunksToProcess.forEachIndexed { index, chunk ->
                val folhaNumber = index + 1
                val stats = calculateBlockStats(houses, chunk)

                 // --- Page 1: Frente (List of Houses) ---
                val page1 = pdfDocument.startPage(pageInfo)
                drawFrontPage(context, page1.canvas, chunk, date, agentName, folhaNumber, totalFolhas, logoBitmap)
                pdfDocument.finishPage(page1)
        
                // --- Page 2: Verso (Summary) ---
                val page2 = pdfDocument.startPage(pageInfo)
                drawBackPage(page2.canvas, chunk, date, stats.quarteiraoConcluido, stats.localidadeConcluida, stats.workedBlocks, stats.completedBlocks)
                pdfDocument.finishPage(page2)
            }
        } finally {
            logoBitmap?.recycle()
        }

        val sanitizedAgent = agentName.trim().replace(" ", "_").replace("/", "-")
        val fileName = if (sanitizedAgent.isNotBlank()) "Boletim_${date}_$sanitizedAgent.pdf" else "Boletim_$date.pdf"
        val file = File(context.cacheDir, fileName)
        
        try {
            FileOutputStream(file).use { out ->
                pdfDocument.writeTo(out)
            }
        } finally {
            pdfDocument.close()
        }

        return file
    }

    fun generateWeeklyBatchPdf(
        context: Context,
        weeklyData: Map<String, List<House>>,
        agentName: String,
        activities: Map<String, String>,
        weekDates: List<String>
    ): File {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        
        // Hoist bitmap decoding to avoid redundant work in loops
        val logoBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.governo_rj_logo)

        // Sort dates for daily pages (only days with data)
        val dateComparator = Comparator<String> { d1, d2 ->
            try {
                val p1 = d1.replace("/", "-").split("-").reversed().joinToString("")
                val p2 = d2.replace("/", "-").split("-").reversed().joinToString("")
                p1.compareTo(p2)
            } catch (_: Exception) { 0 }
        }
        val sortedDates = weeklyData.keys.sortedWith(dateComparator)

        // Pre-calculate chunks
        val dailyChunks = sortedDates.associateWith { date ->
            createChunks(weeklyData[date] ?: emptyList())
        }

        try {
            // --- Pass 1: All Front Pages ---
            sortedDates.forEach { date ->
                val chunks = dailyChunks[date] ?: emptyList()
                val totalFolhas = chunks.size
                chunks.forEachIndexed { index, chunk ->
                    val page = pdfDocument.startPage(pageInfo)
                    drawFrontPage(context, page.canvas, chunk, date, agentName, index + 1, totalFolhas, logoBitmap)
                    pdfDocument.finishPage(page)
                }
            }

            // --- Pass 2: All Back Pages ---
            sortedDates.forEach { date ->
                val houses = weeklyData[date] ?: emptyList()
                val chunks = dailyChunks[date] ?: emptyList()
                chunks.forEachIndexed { index, chunk ->
                    val stats = calculateBlockStats(houses, chunk)
                    val page = pdfDocument.startPage(pageInfo)
                    drawBackPage(page.canvas, chunk, date, stats.quarteiraoConcluido, stats.localidadeConcluida, stats.workedBlocks, stats.completedBlocks)
                    pdfDocument.finishPage(page)
                }
            }

            // --- Final Page: Resumo Semanal ---
            val semanalPage = pdfDocument.startPage(pageInfo)
            // Flatten all houses for the weekly summary
            val allWeekHouses = weeklyData.values.flatten()
            // Use the FULL weekDates ensuring we print all days including those with status but no houses
            // Hoist bitmap decoding for summary (using the same pre-decoded logos if possible, but Semanal uses logo_vigilancia)
            val logoVigilancia = try { BitmapFactory.decodeResource(context.resources, com.antigravity.healthagent.R.drawable.logo_vigilancia) } catch(e: Exception) { null }
            
            try {
                SemanalPdfGenerator.drawSemanalPage(context, semanalPage.canvas, weekDates, allWeekHouses, activities, agentName, logoVigilancia, logoBitmap)
            } finally {
                logoVigilancia?.recycle()
            }
            pdfDocument.finishPage(semanalPage)
        } finally {
            logoBitmap?.recycle()
        }



        val rangeStart = weekDates.firstOrNull() ?: ""
        val rangeEnd = weekDates.lastOrNull() ?: ""
        val sanitizedAgent = agentName.trim().replace(" ", "_").replace("/", "-")
        val fileName = "Produção_da_Semana_${rangeStart}_a_${rangeEnd}_${sanitizedAgent}.pdf"
        val file = File(context.cacheDir, fileName)

        try {
            FileOutputStream(file).use { out ->
                pdfDocument.writeTo(out)
            }
        } finally {
            pdfDocument.close()
        }

        return file
    }

    private data class BlockStats(
        val workedBlocks: List<Pair<String, String>>,
        val completedBlocks: List<Pair<String, String>>,
        val quarteiraoConcluido: Boolean,
        val localidadeConcluida: Boolean
    )

    private fun createChunks(houses: List<House>): List<List<House>> {
        val sortedHouses = houses // Already sorted by listOrder
        val houseChunks = mutableListOf<List<House>>()
        
        if (sortedHouses.isNotEmpty()) {
            var currentBairro = sortedHouses.first().bairro.trim().uppercase()
            var currentGroup = mutableListOf<House>()
            
            for (house in sortedHouses) {
                val houseBairro = house.bairro.trim().uppercase()
                val messageChanged = !houseBairro.equals(currentBairro, ignoreCase = true)
                val groupFull = currentGroup.size >= 20
                
                if (messageChanged || groupFull) {
                    houseChunks.add(currentGroup)
                    currentGroup = mutableListOf()
                    currentBairro = houseBairro
                }
                currentGroup.add(house)
            }
            if (currentGroup.isNotEmpty()) {
                houseChunks.add(currentGroup)
            }
        }
        
        return if (houseChunks.isEmpty()) listOf(emptyList()) else houseChunks
    }

    private fun calculateBlockStats(allHouses: List<House>, chunk: List<House>): BlockStats {
        if (chunk.isEmpty()) return BlockStats(emptyList(), emptyList(), false, false)
        
        // O(N) Pre-calculation: Map each block to its last house index in the full list
        // Block key: blockNumber|blockSequence|bairro
        val blockToLastIndex = mutableMapOf<String, Int>()
        allHouses.forEachIndexed { index, h ->
            val key = "${h.blockNumber}|${h.blockSequence}|${h.bairro.trim().uppercase()}"
            blockToLastIndex[key] = index
        }

        val chunkHouseIds = chunk.map { it.id }.toSet()
        val workedBlocks = chunk.map { Pair(it.blockNumber, it.blockSequence) }.distinct()
        val currentBairro = chunk.firstOrNull()?.bairro ?: ""
        val completedBlocks = mutableListOf<Pair<String, String>>()
        
        workedBlocks.forEach { (bNum, bSeq) ->
            val blockHousesInChunk = chunk.filter { it.blockNumber == bNum && it.blockSequence == bSeq }
            val hasManual = blockHousesInChunk.any { it.quarteiraoConcluido }
            
            val key = "$bNum|$bSeq|${currentBairro.trim().uppercase()}"
            val lastIndexInFull = blockToLastIndex[key] ?: -1
            
            // Auto-concluded if the LAST house of this block in the WHOLE list is in this chunk
            // AND it's not the absolute last house of the whole list (meaning it has a successor)
            val lastHouseInFull = if (lastIndexInFull != -1) allHouses[lastIndexInFull] else null
            val isLastHouseInChunk = lastHouseInFull != null && chunkHouseIds.contains(lastHouseInFull.id)
            val hasSuccessor = lastIndexInFull != -1 && lastIndexInFull < allHouses.size - 1
            
            val autoConcluido = isLastHouseInChunk && hasSuccessor

            if (hasManual || autoConcluido) {
                completedBlocks.add(Pair(bNum, bSeq))
            }
        }
        
        val quarteiraoConcluido = completedBlocks.isNotEmpty()
        val localidadeConcluida = chunk.any { it.localidadeConcluida }
        
        return BlockStats(workedBlocks, completedBlocks, quarteiraoConcluido, localidadeConcluida)
    }

    private fun drawFrontPage(
        context: Context,
        canvas: Canvas,
        houses: List<House>, // This is the chunk
        date: String,
        agentName: String,
        folhaNumber: Int,
        totalFolhas: Int,
        logoBitmap: android.graphics.Bitmap?
    ) {
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
        // Background for headers (Main Table Header & Sub-headers)
        val headerBgPaint = Paint().apply {
            color = Color.LTGRAY 
            style = Paint.Style.FILL
        }

        var cursorY = MARGIN

        // 1. Logo (Left)
        // Logo is pre-decoded and passed as an argument to avoid OOM
        
        if (logoBitmap != null) {
            val logoH = 40f
            val logoW = (logoBitmap.width.toFloat() / logoBitmap.height.toFloat() * logoH)
            val destRect = Rect(MARGIN.toInt(), cursorY.toInt(), (MARGIN + logoW).toInt(), (cursorY + logoH).toInt())
            canvas.drawBitmap(logoBitmap, null, destRect, null)
        } else {
             // Fallback if logo invalid, though normally we expect a logo
             drawTextInRect(canvas, boldPaint, "LOGO", MARGIN, cursorY, 60f, 40f)
        }

        // 2. Titles (Center) - Aligned with Logo
        // Both same font size 12f
        val titlePaint = Paint(boldPaint).apply { textSize = 12f }
        
        // Center of page
        val centerX = PAGE_WIDTH / 2f
        // We can draw centered text around centerX.
        // Title 1
        val t1 = "SECRETARIA DE ESTADO DE SAÚDE E DEFESA CIVIL"
        val bounds1 = Rect(); titlePaint.getTextBounds(t1, 0, t1.length, bounds1)
        canvas.drawText(t1, centerX - bounds1.width()/2, cursorY + 15f, titlePaint)
        
        // Title 2
        val t2 = "REGISTRO DIÁRIO DO SERVIÇO ANTIVETORIAL"
        val bounds2 = Rect(); titlePaint.getTextBounds(t2, 0, t2.length, bounds2)
        canvas.drawText(t2, centerX - bounds2.width()/2, cursorY + 32f, titlePaint) // Slightly below
        
        // 3. Folha Counter (Right)
        val folhaText = "Folha:   $folhaNumber   /   $totalFolhas"
        val folhaPaint = Paint(textPaint).apply { textSize = 12f }
        val folhaW = 100f
        // Draw at same vertical level, roughly aligned with top title or centered
        drawCenteredText(canvas, folhaPaint, folhaText, PAGE_WIDTH - MARGIN - folhaW, cursorY, folhaW, 20f)
        
        // Underline for Folha

        // We can just draw the text, the user didn't strictly require the underlines but the sample has them. 
        // Simple text is fine for now as per previous iterations.

        // Advance cursor PAST the entire header block
        val headerBlockHeight = 40f
        cursorY += headerBlockHeight + 5f 

        // Metadata Header Rows (Municipio, Data, etc.)
        // Metadata Header Rows (Municipio, Data, etc.)
        // Metadata Header Rows (Municipio, Data, etc.)
        // Metadata Header Rows (Municipio, Data, etc.)
        // Metadata Header Rows (Municipio, Data, etc.)
        // Row 1
        val rowH = 35f 
        val gap = 40f 
        
        // Revised widths
        val wMunic = 160f
        val wBairro = 260f
        val wCat = 90f
        val wZona = 60f
        
        val wTipo = PAGE_WIDTH - 2*MARGIN - wMunic - wBairro - wCat - wZona - (gap * 4)

        // Data from last house of the chunk (most recent context for this page)
        val lastHouse = houses.lastOrNull() 
        val municipio = lastHouse?.municipio ?: "Bom Jardim"
        val bairro = lastHouse?.bairro?.trim()?.uppercase() ?: ""
        val categoria = lastHouse?.categoria ?: "BRR"
        val zona = lastHouse?.zona ?: "URB"
        val tipo = lastHouse?.tipo?.toString() ?: "2"

        var cx = MARGIN
        // Use uniform Large Font helper or apply inline
        val labelSize = 8f // Increased from 6f
        val dataSize = 10f // Increased/Bold

        // Row 1: Stacked Boxes
        fun dBox(w: Float, label: String, valText: String) {
             drawHeaderBox(canvas, linePaint, textPaint, headerBgPaint, cx, cursorY, w, rowH, label, valText, 
                 labelSize=labelSize, valueSize=dataSize)
             cx += w + gap
        }
        
        dBox(wMunic, "Município", municipio)
        dBox(wBairro, "Código e Nome do Bairro", bairro)
        dBox(wCat, "Categoria / Bairro", categoria)
        dBox(wZona, "Zona", zona)
        
        // Custom Tipo Box
        val tipoX = cx
        val labelH = 12f 
        val bottomH = rowH - labelH
        
        // 1. Top Label
        canvas.drawRect(tipoX, cursorY, tipoX + wTipo, cursorY + labelH, headerBgPaint)
        canvas.drawRect(tipoX, cursorY, tipoX + wTipo, cursorY + rowH, linePaint)
        canvas.drawLine(tipoX, cursorY + labelH, tipoX + wTipo, cursorY + labelH, linePaint)
        drawCenteredText(canvas, Paint(textPaint).apply { textSize = labelSize }, "Tipo", tipoX, cursorY, wTipo, labelH)
        
        // 2. Bottom Content
        val typeValW = wTipo * 0.25f 

        
        // Value
        drawCenteredText(canvas, Paint(boldPaint).apply{textSize=dataSize}, tipo, tipoX, cursorY + labelH, typeValW, bottomH)
        canvas.drawLine(tipoX + typeValW, cursorY + labelH, tipoX + typeValW, cursorY + rowH, linePaint)
        
        // Legend
        val legPaint = Paint(textPaint).apply { textSize = 7f } // Increased a bit (was 6f)
        val legX = tipoX + typeValW + 2
        val midY = cursorY + labelH + bottomH/2
        
        // Horizontal divider line for Tipo Legend
        canvas.drawLine(tipoX + typeValW, midY, tipoX + wTipo, midY, linePaint)
        
        canvas.drawText("1-Sede", legX, midY - 4, legPaint)
        canvas.drawText("2-Outros", legX, midY + 9, legPaint)
        
        cursorY += rowH + 5f // Redec vertical padding (was 15f)
        
        // Row 2: Data | Ciclo | Atividade
        val wData = 200f 
        val wCiclo = 200f 
        val wAtiv = PAGE_WIDTH - 2*MARGIN - wData - wCiclo - (gap * 2) 
        
        val year = date.split("-").lastOrNull() ?: ""
        val cicloRaw = if (date.isNotBlank()) calculateCicloFromDate(date) else ""
        val ciclo = if (cicloRaw.isNotBlank() && year.isNotBlank()) "$cicloRaw / $year" else cicloRaw
        val atividadeCode = lastHouse?.atividade?.toString() ?: "4" 
        
        cx = MARGIN
        // Data & Ciclo 
        dBox(wData, "Data da atividade", date)
        dBox(wCiclo, "Ciclo/Ano", ciclo)
        
        // Custom Atividade Box
        val ativX = cx
        // 1. Top Label
        canvas.drawRect(ativX, cursorY, ativX + wAtiv, cursorY + labelH, headerBgPaint)
        canvas.drawRect(ativX, cursorY, ativX + wAtiv, cursorY + rowH, linePaint)
        canvas.drawLine(ativX, cursorY + labelH, ativX + wAtiv, cursorY + labelH, linePaint)
        drawCenteredText(canvas, Paint(textPaint).apply { textSize = labelSize }, "Atividade", ativX, cursorY, wAtiv, labelH)
        
        // 2. Bottom Content
        val ativValW = wAtiv * 0.1f 
        val ativLegW = wAtiv - ativValW
        
        // Value
        drawCenteredText(canvas, Paint(boldPaint).apply{textSize=dataSize}, atividadeCode, ativX, cursorY + labelH, ativValW, bottomH)
        canvas.drawLine(ativX + ativValW, cursorY + labelH, ativX + ativValW, cursorY + rowH, linePaint)
        
        // Legend Grid
        val gridX = ativX + ativValW
        // Grid: 3 cols x 2 rows
        // Unequal widths to fit long middle text
        val col1W = ativLegW * 0.28f
        val col2W = ativLegW * 0.44f // Widest for long text
        val col3W = ativLegW * 0.28f
        val gRowH = bottomH / 2
        
        // Lines
        canvas.drawLine(gridX, cursorY + labelH + gRowH, gridX + ativLegW, cursorY + labelH + gRowH, linePaint) // Horiz mid
        canvas.drawLine(gridX + col1W, cursorY + labelH, gridX + col1W, cursorY + rowH, linePaint) // Vert 1
        canvas.drawLine(gridX + col1W + col2W, cursorY + labelH, gridX + col1W + col2W, cursorY + rowH, linePaint) // Vert 2
        
        val pGrid = Paint(textPaint).apply { textSize = 5f } // Unchanged as requested
        val items = listOf(
            "1-LI-Levantamento de Índice", "2-LI+T-Levantamento/Índice + Tratamento", "3-PE-Ponto Estratégico",
            "4-T-Tratamento", "5-DF-Delimitação de Foco", "6-PVE-Pesquisa Vetorial Espacial"
        )
        
        fun dText(txt: String, x: Float, y: Float, w: Float) {
            drawTextInBox(canvas, pGrid, txt, x, y, w, gRowH)
        }
        
        // Row 1
        dText(items[0], gridX, cursorY + labelH, col1W)
        dText(items[1], gridX + col1W, cursorY + labelH, col2W)
        dText(items[2], gridX + col1W + col2W, cursorY + labelH, col3W)
        
        // Row 2
        dText(items[3], gridX, cursorY + labelH + gRowH, col1W)
        dText(items[4], gridX + col1W, cursorY + labelH + gRowH, col2W)
        dText(items[5], gridX + col1W + col2W, cursorY + labelH + gRowH, col3W)
        
        cursorY += rowH + 5f // Reduc vertical padding

        // --- Main Table Header ---
        
        // 1. Gray Bar Top
        val grayBarH = 20f // Increased padding
        drawRectBox(canvas, MARGIN, cursorY, PAGE_WIDTH - 2*MARGIN, grayBarH, "PESQUISA ENTOMOLÓGICA / TRATAMENTO", boldPaint, headerBgPaint)
        cursorY += grayBarH

        // 2. Column Headers
        val ch = 80f // Total header height (Increased to 80f for Qtde. (gramas))
        val headerY = cursorY
        
        // Define Widths
        val cwQuart = 25f // Increased from 15f
        val cwNum = 25f
        val cwSeq = 20f // Increased to 20f
        val cwComp = 20f // Increased to 20f
        val cwTipo = 20f // Increased to 20f
        val cwHora = 35f 
        val cwSit = 25f 
        val cwDa = 20f // Increased to 20f
        val cwDepSingle = 20f // Increased to 20f 
        val cwDepTotal = cwDepSingle * 7
        val cwElim = 25f // Increased from 20f
        val cwAmostraSingle = 29f 
        val cwAmostraTotal = cwAmostraSingle * 3
        val cwInsp = 20f // User requested 20f
        val cwImovTrat = 20f // User requested 20f 
        val cwLarvItem = 28f 
        val cwLarv1Total = cwLarvItem * 2 
        val cwLarv2Total = cwLarvItem * 2 
        val cwAdultTotal = cwLarvItem * 2 
        val cwTratTotal = cwImovTrat + cwLarv1Total + cwLarv2Total + cwAdultTotal
        
        val fixedWidths = cwQuart + cwNum + cwSeq + cwComp + cwTipo + cwHora + cwSit + cwDa + cwDepTotal + cwElim + cwAmostraTotal + cwInsp + cwTratTotal
        val finalCwLog = PAGE_WIDTH - 2*MARGIN - fixedWidths
        
        // Draw Header Background
        canvas.drawRect(MARGIN, headerY, PAGE_WIDTH - MARGIN, headerY + ch, headerBgPaint)
        
        var tx = MARGIN
        
        // Vertical Headers Helper
        fun dVert(label: String, w: Float) {
            drawVerticalHeader(canvas, linePaint, textPaint, null, label, tx, headerY, w, ch)
            tx += w
        }

        // 1. Quarteirão
        dVert("Nº do quarteirão", cwQuart)
        
        // 2. Logradouro
        drawRectBox(canvas, tx, headerY, finalCwLog, ch, "Logradouro", boldPaint, null)
        tx += finalCwLog
        
        // 3. Nº
        drawRectBox(canvas, tx, headerY, cwNum, ch, "Nº", boldPaint, null)
        tx += cwNum
        
        // 4. Seq, Comp, Tipo, Hora, Sit, DA
        dVert("Sequência", cwSeq)
        dVert("Complemento", cwComp) // vertical
        dVert("Tipo do Imóvel", cwTipo)
        dVert("Hora de Entrada", cwHora)
        dVert("Situação", cwSit)
        // 5. Depósitos & D.A. & Eliminados
        // Group Header: Nº DE DEPÓSITOS (Now includes D.A. + Dep Columns + Eliminados)
        val wDepGroup = cwDa + cwDepTotal + cwElim
        drawRectBox(canvas, tx, headerY, wDepGroup, ch/3, "Nº DE DEPÓSITOS", boldPaint, null)
        
        // Row 2 Y
        val r2Y = headerY + ch/3
        val subH = ch/3
        val tallH = (ch/3) * 2
        
        // 1. D.A. (Left, Tall)
        drawVerticalHeader(canvas, linePaint, textPaint, null, "D.A.", tx, r2Y, cwDa, tallH)
        
        // 2. Tipos de Depósitos (Middle, Short Header)
        drawRectBox(canvas, tx + cwDa, r2Y, cwDepTotal, subH, "Tipos de Depósitos", textPaint, null)
        
        // 3. Depósitos Eliminados (Right, Tall, Split Text)
        // Manual draw for split text
        val elimXBase = tx + cwDa + cwDepTotal
        drawRectBox(canvas, elimXBase, r2Y, cwElim, tallH, "", textPaint, null) // Box border
        
        val eCenterX = elimXBase + cwElim/2
        val eCenterY = r2Y + tallH/2
        
        canvas.withRotation(-90f, eCenterX, eCenterY) {
            val label1 = "Depósitos"
            val label2 = "Eliminados"
            val eb1 = Rect(); textPaint.getTextBounds(label1, 0, label1.length, eb1)
            val eGap = 0f
            
            // Draw centered
            val eP = Paint(textPaint)
            drawText(label1, eCenterX - eP.measureText(label1)/2, eCenterY - 1, eP)
            drawText(label2, eCenterX - eP.measureText(label2)/2, eCenterY + eb1.height() + eGap + 1, eP)
        }
        
        // Row 3 (A1..E) under Tipos
        var depX = tx + cwDa
        listOf("A1", "A2", "B", "C", "D1", "D2", "E").forEach {
            drawRectBox(canvas, depX, headerY + (ch/3)*2, cwDepSingle, subH, it, textPaint, null)
            depX += cwDepSingle
        }
        tx += wDepGroup // Advance past D.A., Deps, Eliminados
        
        // 7. Amostra
        // Group: Coleta Amostra
        drawRectBox(canvas, tx, headerY, cwAmostraTotal, subH, "Coleta Amostra", boldPaint, null)
        
        // Sub-layout: Nº da Amostra (Left, 2 cols width) | Qtde Tubitos (Right, 1 col width, Tall)
        val wNumAmostra = cwAmostraSingle * 2
        val wTubitos = cwAmostraSingle
        
        // Nº da Amostra Header
        drawRectBox(canvas, tx, r2Y, wNumAmostra, subH, "Nº da Amostra", textPaint, null)
        
        // Qtde Tubitos (Tall)
        drawVerticalHeader(canvas, linePaint, textPaint, null, "Qtde. Tubitos", tx + wNumAmostra, r2Y, wTubitos, tallH)
        
        // Inicial / Final (Under Nº da Amostra)
        var amX = tx
        listOf("Inicial", "Final").forEach {
            drawVerticalHeader(canvas, linePaint, textPaint, null, it, amX, headerY + (ch/3)*2, cwAmostraSingle, subH)
            amX += cwAmostraSingle
        }
        tx += cwAmostraTotal

        // 8. Imóv. Inspec.
        dVert("Imóv. Inspec.", cwInsp)

        // 9. Tratamento
        // Top: Tratamento
        val tratY1 = headerY
        val tH = ch // Total height for treatment section
        
        // Vertical distribution for Tratamento
        val trH1 = 15f
        val trH2 = 15f
        val trH3 = 15f
        val trH4 = tH - trH1 - trH2 - trH3 // Main vertical space (approx 30f if ch=75)
        
        // R1
        drawRectBox(canvas, tx, tratY1, cwTratTotal, trH1, "Tratamento", boldPaint, null)
        // R2
        // R2
        val yR2 = tratY1 + trH1
        drawRectBox(canvas, tx, yR2, cwImovTrat + cwLarv1Total + cwLarv2Total, trH2, "Focal", textPaint, null)
        drawRectBox(canvas, tx + cwImovTrat + cwLarv1Total + cwLarv2Total, yR2, cwAdultTotal, trH2, "Perifocal", textPaint, null)
        // R3
        val yR3 = yR2 + trH2
        // Imov Trat (starts at R3, goes down to end)
        drawVerticalHeader(canvas, linePaint, textPaint, null, "Imóv. Trat.", tx, yR3, cwImovTrat, trH3 + trH4)
        
        drawRectBox(canvas, tx+cwImovTrat, yR3, cwLarv1Total, trH3, "Larvicida 1", textPaint, null)
        drawRectBox(canvas, tx+cwImovTrat+cwLarv1Total, yR3, cwLarv2Total, trH3, "Larvicida 2", textPaint, null)
        drawRectBox(canvas, tx+cwImovTrat+cwLarv1Total+cwLarv2Total, yR3, cwAdultTotal, trH3, "Adulticida", textPaint, null)
        // R4
        val yR4 = yR3 + trH3
        var lX = tx + cwImovTrat
        // Larv 1 Cols
        listOf("Qtde.\n(gramas)", "Qtde.\ndep.\nTrat.").forEach { 
            drawVerticalHeader(canvas, linePaint, textPaint, null, it, lX, yR4, cwLarvItem, trH4)
            lX += cwLarvItem
        }
        // Larv 2 Cols
        listOf("Qtde.\n(gramas)", "Qtde.\ndep.\nTrat.").forEach { 
             drawVerticalHeader(canvas, linePaint, textPaint, null, it, lX, yR4, cwLarvItem, trH4)
            lX += cwLarvItem
        }
        // Adult Cols
        listOf("Tipo", "Qtde.\nCargas").forEach { 
             drawVerticalHeader(canvas, linePaint, textPaint, null, it, lX, yR4, cwLarvItem, trH4)
             lX += cwLarvItem
        }
        
        cursorY += ch
        
        // --- Rows ---
        val gridRowH = 12f // Reduced from 15f to 12f to SAVE SPACE
        val maxRows = 20
        
        for (i in 0 until maxRows) {
            val house = houses.getOrNull(i)
            var curX = MARGIN
            
            val blockText = if (house != null && house.blockSequence.isNotBlank()) {
                "${house.blockNumber} / ${house.blockSequence}"
            } else {
                house?.blockNumber ?: ""
            }
            drawCell(canvas, linePaint, textPaint, blockText, curX, cursorY, cwQuart, gridRowH); curX += cwQuart
            val logPaint = Paint(textPaint).apply { textSize = 10f } // Increased logradouro font size
            
            val streetNameRaw = house?.streetName?.formatStreetName() ?: ""

            
            // Smart Abbreviation Logic
            // 5f padding is used in drawCell/drawTextInRect (alignLeft adds 5f)
            // We give a bit more safety margin (e.g. 7f)
            val availableDescWidth = finalCwLog - 7f 
            
            val streetName = streetNameRaw.fitToWidth(logPaint, availableDescWidth)

            drawCell(canvas, linePaint, logPaint, streetName, curX, cursorY, finalCwLog, gridRowH, alignLeft=true); curX += finalCwLog
            
            // Logic to fill with dash if street exists but value is empty
            fun chk(v: String?): String = if (streetName.isNotBlank() && v.isNullOrBlank()) "—" else v ?: ""
            
            drawCell(canvas, linePaint, textPaint, chk(house?.number), curX, cursorY, cwNum, gridRowH); curX += cwNum
            val seqStr = house?.sequence?.let { if (it == 0) "" else it.toString() } ?: ""
            drawCell(canvas, linePaint, textPaint, chk(seqStr), curX, cursorY, cwSeq, gridRowH); curX += cwSeq
            val complStr = house?.complement?.let { if (it == 0) "" else it.toString() } ?: ""
            drawCell(canvas, linePaint, textPaint, chk(complStr), curX, cursorY, cwComp, gridRowH); curX += cwComp
            drawCell(canvas, linePaint, textPaint, house?.propertyType?.code ?: "", curX, cursorY, cwTipo, gridRowH); curX += cwTipo
            drawCell(canvas, linePaint, textPaint, "", curX, cursorY, cwHora, gridRowH); curX += cwHora
            val sit = if(house?.situation == Situation.NONE) "—" else house?.situation?.code ?: ""
            // User Logic: "—" means OPEN. If situation != "—" (meaning it is NOT open, e.g. Closed/Refused/etc), 
            // then ALL subsequent cells must be empty.
            // Note: In code model, Situation.NONE usually maps to "—" display or empty. 
            // Confirmed via previous snippet: list.count { it.situation.code == "—" }
            
            // However, "—" IS the symbol for "Open" (Pending) in this context based on user prompt.
            // "everytime Situação is different of open( diffent of "—")"
            // So if sit != "—", we clear subsequent data.
            val isOpen = sit == "—" 

            drawCell(canvas, linePaint, textPaint, chk(sit), curX, cursorY, cwSit, gridRowH); curX += cwSit
            drawCell(canvas, linePaint, textPaint, "", curX, cursorY, cwDa, gridRowH); curX += cwDa
            
            val depValues = if (house != null && isOpen) listOf(house.a1, house.a2, house.b, house.c, house.d1, house.d2, house.e) else List(7){0}
            for (v in depValues) {
                // Determine value logic: if > 0 then number string, else (if street exists -> dash, else empty)
                val vStr = if (v > 0) v.toString() else ""
                drawCell(canvas, linePaint, textPaint, if(isOpen) chk(vStr) else "", curX, cursorY, cwDepSingle, gridRowH)
                curX += cwDepSingle
            }
            
            val elim = if ((house?.eliminados ?: 0) > 0 && isOpen) house?.eliminados.toString() else ""
            drawCell(canvas, linePaint, textPaint, if(isOpen) chk(elim) else "", curX, cursorY, cwElim, gridRowH); curX += cwElim
            
            // Amostras & Insp
             repeat(3) { drawCell(canvas, linePaint, textPaint, "", curX, cursorY, cwAmostraSingle, gridRowH); curX += cwAmostraSingle }
            val inspected = "" // User requested to leave this column empty
            drawCell(canvas, linePaint, textPaint, inspected, curX, cursorY, cwInsp, gridRowH); curX += cwInsp
            
            // Tratamento: Imov Trat + Larv1 (2 cols) + Larv2 (2 cols) + Adult (2 cols) -> 7 cols total
             // Only show treatment if Open
             val larvG = if (house != null && (house.larvicida > 0.0) && isOpen) {
                 if (house.larvicida % 1.0 == 0.0) house.larvicida.toInt().toString() else house.larvicida.toString()
             } else ""
             val treated = if (larvG.isNotEmpty() || (house != null && house.eliminados > 0)) "X" else "" 
             
             // Imov. Trat. (X)
             drawCell(canvas, linePaint, textPaint, if(isOpen) chk(treated) else "", curX, cursorY, cwImovTrat, gridRowH); curX += cwImovTrat
             
             // Larv 1
             drawCell(canvas, linePaint, textPaint, if(isOpen) chk(larvG) else "", curX, cursorY, cwLarvItem, gridRowH); curX += cwLarvItem
             
             // Qtde dep Trat (Sum of A1..E)
             val sumDeps = if (house != null && isOpen) (house.a1 + house.a2 + house.b + house.c + house.d1 + house.d2 + house.e) else 0
             val sumDepsStr = if (sumDeps > 0) sumDeps.toString() else ""
             drawCell(canvas, linePaint, textPaint, if(isOpen) chk(sumDepsStr) else "", curX, cursorY, cwLarvItem, gridRowH); curX += cwLarvItem
             
             // Larv 2 & Adult (Merged if Com Foco)
             // User requested to use only the last 2 cells (Adulticida columns) for indication of "Com Foco"
             if (house != null && house.comFoco && isOpen) {
                 // Larv 2 (Empty)
                 drawCell(canvas, linePaint, textPaint, "", curX, cursorY, cwLarvItem, gridRowH); curX += cwLarvItem
                 drawCell(canvas, linePaint, textPaint, "", curX, cursorY, cwLarvItem, gridRowH); curX += cwLarvItem
                 
                 // Com Foco (Merged Adult columns)
                 val mergedW = cwLarvItem * 2
                 drawCell(canvas, linePaint, boldPaint, "Com Foco", curX, cursorY, mergedW, gridRowH)
             } else {
                 // Larv 2 (Empty)
                 drawCell(canvas, linePaint, textPaint, "", curX, cursorY, cwLarvItem, gridRowH); curX += cwLarvItem
                 drawCell(canvas, linePaint, textPaint, "", curX, cursorY, cwLarvItem, gridRowH); curX += cwLarvItem
                 
                 // Adult (Empty)
                 drawCell(canvas, linePaint, textPaint, "", curX, cursorY, cwLarvItem, gridRowH); curX += cwLarvItem
                 drawCell(canvas, linePaint, textPaint, "", curX, cursorY, cwLarvItem, gridRowH)
             }

            cursorY += gridRowH
        }
        
        // --- Totais Row ---
        val totalRowH = 15f // Increased specifically for Totais to add padding
        val wLabel = cwQuart + finalCwLog + cwNum + cwSeq + cwComp + cwTipo + cwHora + cwSit + cwDa
        drawRectBox(canvas, MARGIN, cursorY, wLabel, totalRowH, "TOTAIS", boldPaint, headerBgPaint)
        var totX = MARGIN + wLabel
        
        // Sums (Filter by Situation.NONE to match row logic)
        val workedHouses = houses.filter { it.situation == Situation.NONE }
        val sums = mutableListOf<Int>()
        if (workedHouses.isNotEmpty()) {
             sums.add(workedHouses.sumOf { it.a1 }); sums.add(workedHouses.sumOf { it.a2 }); sums.add(workedHouses.sumOf { it.b })
             sums.add(workedHouses.sumOf { it.c }); sums.add(workedHouses.sumOf { it.d1 }); sums.add(workedHouses.sumOf { it.d2 }); sums.add(workedHouses.sumOf { it.e })
        } else { repeat(7) { sums.add(0) } }
        sums.forEach { drawCell(canvas, linePaint, boldPaint, it.toString(), totX, cursorY, cwDepSingle, totalRowH); totX += cwDepSingle }
        
        val totalElim = workedHouses.sumOf { it.eliminados }
        drawCell(canvas, linePaint, boldPaint, totalElim.toString(), totX, cursorY, cwElim, totalRowH); totX += cwElim
        
        repeat(3) { drawCell(canvas, linePaint, textPaint, "", totX, cursorY, cwAmostraSingle, totalRowH); totX += cwAmostraSingle }
        
        // Imov. Inspec. (Left empty per user request)
        drawCell(canvas, linePaint, boldPaint, "", totX, cursorY, cwInsp, totalRowH); totX += cwInsp
        
        // Imov. Trat. (Houses where any treatment was performed: larvicide or eliminated items)
        val totalTreated = workedHouses.count { it.larvicida > 0 || it.eliminados > 0 }
        drawCell(canvas, linePaint, boldPaint, totalTreated.toString(), totX, cursorY, cwImovTrat, totalRowH); totX += cwImovTrat

        // Larv 1 Gramas Sum (BPU)
        val totalLarv = workedHouses.sumOf { it.larvicida }
        val totalLarvStr = if (totalLarv % 1.0 == 0.0) totalLarv.toInt().toString() else "%.1f".format(java.util.Locale.US, totalLarv)
        drawCell(canvas, linePaint, boldPaint, totalLarvStr, totX, cursorY, cwLarvItem, totalRowH); totX += cwLarvItem
        
        // Larv 1 Qtde Dep Trat Sum (New)
        val totalDepsTreated = workedHouses.sumOf { it.a1 + it.a2 + it.b + it.c + it.d1 + it.d2 + it.e }
        drawCell(canvas, linePaint, boldPaint, totalDepsTreated.toString(), totX, cursorY, cwLarvItem, totalRowH); totX += cwLarvItem
        
        // Remaining 4 columns (Larv2 and Adult)
        repeat(4) { drawCell(canvas, linePaint, textPaint, "", totX, cursorY, cwLarvItem, totalRowH); totX += cwLarvItem }
        
        cursorY += totalRowH + 10f // Gap between Totais and Conventions
        
        // --- Footer (Conventions & Situation) ---
        val footerRowH = 15f
        
        // Convencoes
        val wConv = PAGE_WIDTH / 2 - MARGIN - 10
        val convX = MARGIN
        drawRectBox(canvas, convX, cursorY, wConv, footerRowH, "CONVENÇÕES", boldPaint, headerBgPaint)
        drawRectBox(canvas, convX, cursorY + footerRowH, wConv, footerRowH, "R-Residência  C-Comércio  TB-Terreno Baldio  PE-Ponto Estratégico  O-Outros  D.A.-Difícil Acesso", Paint(textPaint).apply{textSize=7f}, null)
        
        // Situacao
        val startSit = MARGIN + wConv + 20
        drawRectBox(canvas, startSit, cursorY, wConv, footerRowH, "SITUAÇÃO", boldPaint, headerBgPaint)
        drawRectBox(canvas, startSit, cursorY + footerRowH, wConv, footerRowH, "F-Fechado  REC-Recusado  A-Abandonado  V-Vazio", Paint(textPaint).apply{textSize=7f}, null)
        
        cursorY += footerRowH * 2 + 15 // Reduced spacing
        
        // Signatures
        // Ensure we are not off page 
        if (cursorY + 30 > PAGE_HEIGHT) {
            cursorY = PAGE_HEIGHT - 30f // Force stick to bottom if overflow
        }
        
        val sigW = 160f
        val totalWidth = PAGE_WIDTH - 2 * MARGIN
        val gapSig = (totalWidth - (4 * sigW)) / 3
        
        var sigX = MARGIN
        
        // Agente
        val agLabel = "Agente: "
        val agP = Paint(textPaint)
        val wb = Rect(); agP.getTextBounds(agLabel, 0, agLabel.length, wb)
        val labelW = wb.width() + 5f
        
        // Draw Label
        drawTextInRect(canvas, textPaint, agLabel, sigX, cursorY, labelW, 20f, alignLeft=true)
        
        // Draw Line
        val lineStart = sigX + labelW
        val lineEnd = sigX + sigW - 5
        canvas.drawLine(lineStart, cursorY + 12, lineEnd, cursorY + 12, linePaint)
        
        // Draw Name on Line
        if (agentName.isNotBlank()) {
             val namePaint = Paint(boldPaint).apply { textSize = 10f }
             drawTextInRect(canvas, namePaint, agentName, lineStart, cursorY - 3, (lineEnd - lineStart), 20f, alignLeft=true)
        }
        
        sigX += sigW + gapSig // Advance for next signature
        
        // Supervisor
        drawTextInRect(canvas, textPaint, "Supervisor: ___________________", sigX, cursorY, sigW, 20f, alignLeft=true)
         sigX += sigW + gapSig
         
        // Sup. Geral
        drawTextInRect(canvas, textPaint, "Sup. Geral: ___________________", sigX, cursorY, sigW, 20f, alignLeft=true)
        sigX += sigW + gapSig
        
        // Laboratório
        drawTextInRect(canvas, textPaint, "Laboratório: __________________", sigX, cursorY, sigW, 20f, alignLeft=true)
        
        // Missing Reference
        drawTextInRect(canvas, Paint(textPaint).apply{textSize=6f}, "FAD-01(Frente)", MARGIN, cursorY + 12f, 100f, 15f, alignLeft=true)
    }

    private fun drawBackPage(
        canvas: Canvas,
        chunkHouses: List<House>,
        date: String,
        quarteiraoConcluido: Boolean,
        localidadeConcluida: Boolean,
        workedPairs: List<Pair<String, String>>,
        completedPairs: List<Pair<String, String>>
    ) {
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 8f // Reduced base size
            isAntiAlias = true
        }
        val smallPaint = Paint(textPaint).apply { textSize = 7f }
        val boldPaint = Paint(textPaint).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val headerBgPaint = Paint().apply { color = Color.LTGRAY; style = Paint.Style.FILL }
        val linePaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        val dataPaint = Paint(boldPaint).apply { textSize = 10f }
        
        var cursorY = MARGIN
        
        // --- Top Info Blocks ---
        // Block 1: Visita (Left)
        val wB1 = 130f
        val hRowB1 = 14f // Reset to 14f (Synchronized with Box 2)
        
        // Header Visita
        drawRectBox(canvas, MARGIN, cursorY, wB1, hRowB1, "VISITA", boldPaint, headerBgPaint)
        
        // Rows
        val yR1Visita = cursorY + hRowB1
        val wLabel = wB1 - 25f
        val wCheck = 25f
        
        // Normal
        drawRectBox(canvas, MARGIN, yR1Visita, wLabel, hRowB1, "NORMAL", textPaint, null, alignLeft=true)
        drawRectBox(canvas, MARGIN + wLabel, yR1Visita, wCheck, hRowB1, "X", boldPaint, null)
        
        // Recuperação
        val yR2Visita = yR1Visita + hRowB1
        drawRectBox(canvas, MARGIN, yR2Visita, wLabel, hRowB1, "RECUPERAÇÃO", textPaint, null, alignLeft=true)
        drawRectBox(canvas, MARGIN + wLabel, yR2Visita, wCheck, hRowB1, "", boldPaint, null)
        
        // Block 2: Equipe / Agente (Middle)
        val gap = 60f // Increased again to provide more space
        val xB2 = MARGIN + wB1 + gap
        val wB2 = 250f
        val wLabelB2 = 90f
        val wValB2 = wB2 - wLabelB2
        
        // R1: Equipe / Agente
        val hRowB2 = 14f
        drawRectBox(canvas, xB2, cursorY, wLabelB2, hRowB2, "Equipe / Agente", textPaint, null, alignLeft=true)
        drawRectBox(canvas, xB2 + wLabelB2, cursorY, wValB2, hRowB2, "PMBJ", boldPaint, null)
        
        // R2: Data
        val yR1Equipe = cursorY + hRowB2
        drawRectBox(canvas, xB2, yR1Equipe, wLabelB2, hRowB2, "Data", textPaint, null, alignLeft=true)
        drawRectBox(canvas, xB2 + wLabelB2, yR1Equipe, wValB2, hRowB2, date, boldPaint, null)
        
        // R3: Localidade Concluída
        val yR2Equipe = yR1Equipe + hRowB2
        drawRectBox(canvas, xB2, yR2Equipe, wLabelB2, hRowB2, "Localidade Concluída", textPaint, null, alignLeft=true)
        val locLabel = if (localidadeConcluida) "SIM" else "NÃO"
        drawRectBox(canvas, xB2 + wLabelB2, yR2Equipe, wValB2, hRowB2, locLabel, boldPaint, null)
        
        // Block 3: Quarteirão (Right)
        val xB3 = xB2 + wB2 + gap
        val wB3 = PAGE_WIDTH - MARGIN - xB3
        
        // Align labels to same width for clean grid start
        val wCommonLabel = 145f 
        
        // R1: Nº do Quarteirão
        val wRestR1 = wB3 - wCommonLabel
        val wBoxR1 = wRestR1 / 5 
        
        // Block 3: Quarteirão (Right) aligned to match Left/Middle height
        val hRowB3 = 14f // Total 28f (shorter than others)
        
        drawRectBox(canvas, xB3, cursorY, wCommonLabel, hRowB3, "Nº e sequência dos quarteirões", textPaint, null, alignLeft=true)
        var qxTop = xB3 + wCommonLabel
        
        // Populate with up to 5 unique blocks from this chunk
        repeat(5) { i ->
            val pair = workedPairs.getOrNull(i)
            val qStr = if (pair != null) {
                if (pair.second.isNotBlank()) "${pair.first} / ${pair.second}" else pair.first
            } else ""
            
            drawRectBox(canvas, qxTop, cursorY, wBoxR1, hRowB3, qStr, boldPaint, null)
            qxTop += wBoxR1
        }
        
        // R2: Quarteirão Concluído?
        val wRestR2 = wB3 - wCommonLabel
        val wBoxR2 = wRestR2 / 4 
        
        drawRectBox(canvas, xB3, cursorY + hRowB3, wCommonLabel, hRowB3, "Quarteirão Concluído?", textPaint, null, alignLeft=true)
        
        var qxBot = xB3 + wCommonLabel
        val yR2Right = cursorY + hRowB3
        val qSim = if (quarteiraoConcluido) "X" else ""
        val qNao = if (!quarteiraoConcluido) "X" else ""
        
        drawRectBox(canvas, qxBot, yR2Right, wBoxR2, hRowB3, "SIM", smallPaint, null); qxBot += wBoxR2
        drawRectBox(canvas, qxBot, yR2Right, wBoxR2, hRowB3, qSim, boldPaint, null); qxBot += wBoxR2
        drawRectBox(canvas, qxBot, yR2Right, wBoxR2, hRowB3, "NÃO", smallPaint, null); qxBot += wBoxR2
        drawRectBox(canvas, qxBot, yR2Right, wBoxR2, hRowB3, qNao, boldPaint, null)
        
        // IMPORTANT: Move cursorY PAST the tallest block (Box 1 is 48f)
        cursorY += (hRowB1 * 3) + 8f
        

        
        // --- Gray Header: Resumo ---
        val gh = 15f
        drawRectBox(canvas, MARGIN, cursorY, PAGE_WIDTH - 2*MARGIN, gh, "RESUMO DIÁRIO DO TRABALHO DE CAMPO", boldPaint, headerBgPaint)
        cursorY += gh + 5f


        
        // --- Table Row 1 ---
        val typeR = chunkHouses.count { it.propertyType == PropertyType.R && it.situation == Situation.NONE }
        val typeC = chunkHouses.count { it.propertyType == PropertyType.C && it.situation == Situation.NONE }
        val typeTB = chunkHouses.count { it.propertyType == PropertyType.TB && it.situation == Situation.NONE }
        val typePE = chunkHouses.count { it.propertyType == PropertyType.PE && it.situation == Situation.NONE }
        val typeO = chunkHouses.count { it.propertyType == PropertyType.O && it.situation == Situation.NONE }
        val totalTypes = typeR + typeC + typeTB + typePE + typeO
        
        // Table 1: Imoveis Tipo
        val t1Labels = listOf("Residência", "Comércio", "TB", "PE", "Outros", "Total")
        val t1Vals = listOf(typeR, typeC, typeTB, typePE, typeO, totalTypes)
        val colW1 = 35f
        val wT1 = colW1 * 6
        var cx = MARGIN
        val hRowT = 15f // Standardized Row height for tables
        
        drawRectBox(canvas, cx, cursorY, wT1, hRowT, "Nº de Imóveis Trabalhados por tipo", boldPaint, headerBgPaint)
        var tx = cx
        t1Labels.forEach { drawRectBox(canvas, tx, cursorY + hRowT, colW1, hRowT, it, smallPaint, headerBgPaint); tx += colW1 }
        tx = cx
        t1Vals.forEach { 
            val vStr = if(it == 0) "—" else it.toString()
            drawRectBox(canvas, tx, cursorY + hRowT*2, colW1, hRowT*2, vStr, dataPaint, null)
            tx += colW1 
        }
        
        cx += wT1 + 10f 
        
        // Table 2: Nº de Imóveis
        val t2Labels = listOf("Trat. Focal", "Trat. Perifocal", "Inspecionados")
        val tratFocal = chunkHouses.count { it.larvicida > 0 }
        val inspec = chunkHouses.count { it.situation == Situation.NONE }
        val t2Vals = listOf(tratFocal.toString(), "—", "—")
        
        val colW2 = 60f
        val wT2 = colW2 * 3
        
        drawRectBox(canvas, cx, cursorY, wT2, hRowT, "Nº de Imóveis", boldPaint, headerBgPaint)
        tx = cx
        t2Labels.forEach { drawRectBox(canvas, tx, cursorY + hRowT, colW2, hRowT, it, smallPaint, headerBgPaint); tx += colW2 }
        tx = cx
        t2Vals.forEachIndexed { _, v -> 
            val vStr = if(v == "0") "—" else v
            drawRectBox(canvas, tx, cursorY + hRowT*2, colW2, hRowT*2, vStr, dataPaint, null)
            tx += colW2 
        }
        
        cx += wT2 + 10f
        
        // Table 3: Pendencia
        val t3Labels = listOf("Fechados", "Recusas", "Aband.", "Vazios")
        val pendF = chunkHouses.count { it.situation == Situation.F }
        val pendR = chunkHouses.count { it.situation == Situation.REC }
        val pendA = chunkHouses.count { it.situation == Situation.A }
        val pendV = chunkHouses.count { it.situation == Situation.V }
        val t3Vals = listOf(pendF, pendR, pendA, pendV)
        
        val colW3 = 40f
        val wT3 = colW3 * 4
        
        drawRectBox(canvas, cx, cursorY, wT3, hRowT, "Pendência", boldPaint, headerBgPaint)
        tx = cx
        t3Labels.forEach { drawRectBox(canvas, tx, cursorY + hRowT, colW3, hRowT, it, smallPaint, headerBgPaint); tx += colW3 }
        tx = cx
        t3Vals.forEach { 
            val vStr = if(it == 0) "—" else it.toString()
            drawRectBox(canvas, tx, cursorY + hRowT*2, colW3, hRowT*2, vStr, dataPaint, null)
            tx += colW3 
        }
        
        cx += wT3 + 10f
        
        // Table 4: Depósitos
        val t4Labels = listOf("A1", "A2", "B", "C", "D1", "D2", "E", "Total")
        val sA1 = chunkHouses.sumOf { it.a1 }
        val sA2 = chunkHouses.sumOf { it.a2 }
        val sB = chunkHouses.sumOf { it.b }
        val sC = chunkHouses.sumOf { it.c }
        val sD1 = chunkHouses.sumOf { it.d1 }
        val sD2 = chunkHouses.sumOf { it.d2 }
        val sE = chunkHouses.sumOf { it.e }
        val sTotal = sA1 + sA2 + sB + sC + sD1 + sD2 + sE
        val t4Vals = listOf(sA1, sA2, sB, sC, sD1, sD2, sE, sTotal)
        
        val colW4 = 28f
        val wT4 = colW4 * 8
        
        drawRectBox(canvas, cx, cursorY, wT4, hRowT, "Nº de depósito por tipo", boldPaint, headerBgPaint)
        tx = cx
        t4Labels.forEach { drawRectBox(canvas, tx, cursorY + hRowT, colW4, hRowT, it, smallPaint, headerBgPaint); tx += colW4 }
        tx = cx
        t4Vals.forEach { 
             val vStr = if(it == 0) "—" else it.toString()
             drawRectBox(canvas, tx, cursorY + hRowT*2, colW4, hRowT*2, vStr, dataPaint, null)
             tx += colW4 
        }
        
        cursorY += hRowT*3 + 25f 
        
        
        // --- Row 2: Depósitos (Left) & Quarteirões (Right) ---
        val r2Y = cursorY
        
        // Widths for Depósitos Section
        val wElim = 50f
        val wTratItem = 45f 
        val wTrat = wTratItem * 4 
        val wAdult = wTratItem * 2 
        val wTubitos = 60f
        
        var dx = MARGIN
        
        // Standardized Weights for Precision Layout
        val hHeader = 15f // Matching Pendência top labels
        val hData = 18f   // Matching Aedes data rows (Row 5 level)
        
        // A. Depósitos Block (Elim + Tratados) - 78f total (4x15 header + 18 data)
        val wTratGroup = wTrat
        val wDepBlock = wElim + wTratGroup
        
        // R1: Depósitos (Top Gray Header)
        drawRectBox(canvas, dx, r2Y, wDepBlock, hHeader, "Depósitos", boldPaint, headerBgPaint)
        
        // R2-R4: Eliminados (Labels)
        drawRectBox(canvas, dx, r2Y + hHeader, wElim, hHeader * 3, "Eliminados", smallPaint, null)
        // R5: Eliminados (Value Row)
        drawRectBox(canvas, dx, r2Y + hHeader * 4, wElim, hData, chunkHouses.sumOf { it.eliminados }.toString(), dataPaint, null)
        
        dx += wElim
        
        // R2: Tratados (White Sub-Header)
        drawRectBox(canvas, dx, r2Y + hHeader, wTratGroup, hHeader, "Tratados", smallPaint, null)
        
        // R3: BTI WDG / BTI G
        val wBTI = wTratGroup / 2
        drawRectBox(canvas, dx, r2Y + hHeader * 2, wBTI, hHeader, "BTI WDG", smallPaint, null)
        drawRectBox(canvas, dx + wBTI, r2Y + hHeader * 2, wBTI, hHeader, "BTI G", smallPaint, null)
        
        // R4: Row 4 Labels (Qtde / Dep)
        val wColTrat = wBTI / 2
        val labelsTrat = listOf("Qtde. (g)", "Dep. Trat.", "Qtde. (g)", "Dep. Trat.") 
        tx = dx
        labelsTrat.forEach { 
             drawRectBox(canvas, tx, r2Y + hHeader * 3, wColTrat, hHeader, it, Paint(smallPaint).apply{textSize=6f}, null)
             tx += wColTrat
        }
        
        // R5: Value Row (Data)
        val larvOutput = chunkHouses.sumOf { it.larvicida }
        val larvOutputStr = if (larvOutput > 0) String.format(java.util.Locale.US, "%.1f", larvOutput).replace(".", ",") else "—"
        val larvDep = chunkHouses.count { it.larvicida > 0 }
        val larvDepStr = if (larvDep > 0) larvDep.toString() else "—"
        
        tx = dx
        listOf(larvOutputStr, larvDepStr, "—", "—").forEach {
             drawRectBox(canvas, tx, r2Y + hHeader * 4, wColTrat, hData, it, dataPaint, null)
             tx += wColTrat
        }
        
        dx += wTratGroup
        
        // B. Adulticida Block - 78f total (15 gray + 45 white + 18 data)
        val hAdSubLabel = 45f
        // R1: Adulticida Header (Top Gray - Matching Depósitos)
        drawRectBox(canvas, dx, r2Y, wAdult, hHeader, "Adulticida", boldPaint, headerBgPaint)
        
        // R2: Tipo / Cargas Labels (Internal Sub-headers - White)
        val wAdCol = wAdult / 2
        drawRectBox(canvas, dx, r2Y + hHeader, wAdCol, hAdSubLabel, "Tipo", smallPaint, null)
        drawRectBox(canvas, dx + wAdCol, r2Y + hHeader, wAdCol, hAdSubLabel, "Cargas", smallPaint, null)
        
        // R3: Value Row (Data)
        drawRectBox(canvas, dx, r2Y + hHeader + hAdSubLabel, wAdCol, hData, "—", dataPaint, null)
        drawRectBox(canvas, dx + wAdCol, r2Y + hHeader + hAdSubLabel, wAdCol, hData, "—", dataPaint, null)
        
        dx += wAdult
        
        // C. Tubitos/Amostras Section - 78f total (60 gray + 18 data)
        val hTubHeader = 60f
        
        // R1: Tall Label Box (Gray)
        drawRectBox(canvas, dx, r2Y, wTubitos, hTubHeader, "", boldPaint, headerBgPaint)
        
        val line1 = "Nº Tubitos/"
        val line2 = "Amostras"
        val line3 = "Coletadas"
        
        val b1 = Rect(); boldPaint.getTextBounds(line1, 0, line1.length, b1)
        val b2 = Rect(); boldPaint.getTextBounds(line2, 0, line2.length, b2)
        val b3 = Rect(); boldPaint.getTextBounds(line3, 0, line3.length, b3)
        
        val ttH = b1.height() + b2.height() + b3.height() + 10f
        var curTY = r2Y + (hTubHeader - ttH) / 2f + b1.height()
        
        canvas.drawText(line1, dx + (wTubitos - b1.width()) / 2, curTY, boldPaint)
        curTY += b2.height() + 4f
        canvas.drawText(line2, dx + (wTubitos - b2.width()) / 2, curTY, boldPaint)
        curTY += b3.height() + 4f
        canvas.drawText(line3, dx + (wTubitos - b3.width()) / 2, curTY, boldPaint)
        
        // R2: Value Row (Data)
        drawRectBox(canvas, dx, r2Y + hTubHeader, wTubitos, hData, "—", dataPaint, null)
        
        dx += wTubitos
        
        // --- Quarteirões Grids (Right) ---
        val qGap = 15f
        val qx = dx + qGap
        val wRightSection = PAGE_WIDTH - MARGIN - qx
        
        // Each grid: Header (15f) + 2 Data rows (2 * 18f) = 51f. Total 102f combined.
        val hGrid = hHeader + (hData * 2) 
        
        // Grid 1: Nº e sequência dos quarteirões trabalhados
        drawRectBox(canvas, qx, r2Y, wRightSection, hHeader, "Nº e sequência dos quarteirões trabalhados", boldPaint, headerBgPaint)
        
        val qColW = wRightSection / 6
        
        // Populate Grid 1 (Worked)
        repeat(2) { r ->
            var curQx = qx
            repeat(6) { c ->
                val idx = r * 6 + c
                val pair = workedPairs.getOrNull(idx)
                drawBlockCell(canvas, linePaint, dataPaint, pair?.first, pair?.second, curQx, r2Y + hHeader + (r * hData), qColW, hData)
                curQx += qColW
            }
        }
        
        // Grid 2: Nº e sequência dos quarteirões concluídos
        val q2Y = r2Y + hGrid
        drawRectBox(canvas, qx, q2Y, wRightSection, hHeader, "Nº e sequência dos quarteirões concluídos", boldPaint, headerBgPaint)
        
        // Populate Grid 2 (Completed)
        repeat(2) { r ->
            var curQx = qx
            repeat(6) { c ->
                val idx = r * 6 + c
                val pair = completedPairs.getOrNull(idx)
                drawBlockCell(canvas, linePaint, dataPaint, pair?.first, pair?.second, curQx, q2Y + hHeader + (r * hData), qColW, hData)
                curQx += qColW
            }
        }
        
        // Update CursorY to be below the tallest block (102f + cushion)
        cursorY = r2Y + (hGrid * 2) + 15f
        
        // --- Resumo do Laboratório ---
        val hLabHeader = 15f
        drawRectBox(canvas, MARGIN, cursorY, PAGE_WIDTH - 2*MARGIN, hLabHeader, "RESUMO DO LABORATÓRIO", boldPaint, headerBgPaint)
        cursorY += hLabHeader + 5f
        
        // 1. Aedes Grids
        val wAes = (PAGE_WIDTH - 2*MARGIN - 10f) / 2
        val hAesRow = 18f // Slightly reduced for fit
        
        drawRectBox(canvas, MARGIN, cursorY, wAes, hAesRow, "Nº e sequência dos quarteirões com Aedes aegypti", smallPaint, headerBgPaint)
        val wColAe = wAes / 8
        repeat(2) { r ->
             var ax = MARGIN
             repeat(8) {
                 drawRectBox(canvas, ax, cursorY + hAesRow*(r+1), wColAe, hAesRow, " /", textPaint, null)
                 ax += wColAe
             }
        }
        
        val xRight = MARGIN + wAes + 10f
        drawRectBox(canvas, xRight, cursorY, wAes, hAesRow, "Nº e sequência dos quarteirões com Aedes albopictus", smallPaint, headerBgPaint)
        repeat(2) { r ->
             var ax = xRight
             repeat(8) {
                 drawRectBox(canvas, ax, cursorY + hAesRow*(r+1), wColAe, hAesRow, " /", textPaint, null)
                 ax += wColAe
             }
        }
        
        cursorY += hAesRow * 3 + 10f
        
        // 2. Stats & Stages
        val wStatsLabel = 120f 
        val wStatsCol = 42f // Reduced from 48f to increase gap
        val wStatsTotal = wStatsLabel + (wStatsCol * 8)
        
        val hStHeader = 18f 
        val hStRow = 22f 

        
        var sx = MARGIN
        drawRectBox(canvas, sx, cursorY, wStatsLabel, hStHeader, "", textPaint, headerBgPaint); sx += wStatsLabel
        listOf("A1", "A2", "B", "C", "D1", "D2", "E", "Total").forEach { 
            drawRectBox(canvas, sx, cursorY, wStatsCol, hStHeader, it, smallPaint, headerBgPaint)
            sx += wStatsCol
        }
        
        
        sx = MARGIN
        val yR1Stats = cursorY + hStHeader
        drawRectBox(canvas, sx, yR1Stats, wStatsLabel, hStRow, "Com Aedes aegypti", smallPaint, null, alignLeft=true); sx += wStatsLabel
        repeat(8) { drawRectBox(canvas, sx, yR1Stats, wStatsCol, hStRow, "—", textPaint, null); sx += wStatsCol }
        
        
        sx = MARGIN
        val yR2Stats = cursorY + hStHeader + hStRow
        drawRectBox(canvas, sx, yR2Stats, wStatsLabel, hStRow, "Com Aedes albopictus", smallPaint, null, alignLeft=true); sx += wStatsLabel
        repeat(8) { drawRectBox(canvas, sx, yR2Stats, wStatsCol, hStRow, "—", textPaint, null); sx += wStatsCol }
        
        val xStage = 533f // Fixed starting position to increase gap and preserve width
        val wStageSection = PAGE_WIDTH - MARGIN - xStage
        val wStageCol = wStageSection / 4
        
        val stageLabels = listOf("Larvas", "Pupas", "Exúvia de Pupa", "Adultos")
        
        var stX = xStage
        stageLabels.forEach {
            drawRectBox(canvas, stX, cursorY, wStageCol, hStHeader + hStRow - 5f, it, Paint(smallPaint).apply{textSize=6.5f}, headerBgPaint) 
            stX += wStageCol
        }
        
        val sRH = 22f 
        var currStY = cursorY + hStHeader + hStRow - 5f
        repeat(3) {
             stX = xStage
             repeat(4) {
                 drawRectBox(canvas, stX, currStY, wStageCol, sRH, "", textPaint, null)
                 stX += wStageCol
             }
        currStY += sRH
        }
        
        cursorY = yR2Stats + hStRow + 25f
        
        // Footer Legends
        // Draw Conventions constrained to follow the width of the Stats table above
        val colW = wStatsTotal / 4f // ~114f (Stay aligned with narrowed grid)
        val legH = 10f
        val lP = Paint(smallPaint).apply { textSize = 5.5f } // Slightly smaller to ensure fit
        
        // Row 1
        drawTextInBox(canvas, lP, "A1 - Caixa d'água (elevado)", MARGIN, cursorY, colW, legH)
        drawTextInBox(canvas, lP, "A2 - Outros depósitos de armazenamento de água (baixo)", MARGIN + colW, cursorY, colW, legH)
        drawTextInBox(canvas, lP, "B - Pequenos depósitos móveis", MARGIN + 2*colW + 50f, cursorY, colW, legH)
        drawTextInBox(canvas, lP, "C - Depósitos fixos", MARGIN + 3*colW +50f, cursorY, colW, legH)
        
        cursorY += legH
        // Row 2
        drawTextInBox(canvas, lP, "D1 - Pneus e outros materiais rodantes", MARGIN, cursorY, colW, legH)
        drawTextInBox(canvas, lP, "D2 - Lixo (recipientes plásticos, latas), sucatas, entulhos", MARGIN + colW, cursorY, colW, legH)
        drawTextInBox(canvas, lP, "E - Depósitos naturais", MARGIN + 2*colW + 50f, cursorY, colW, legH)
        
        cursorY += legH + 20f
        
        val sigH = 30f
        val boxGap = 10f
        val w5 = (PAGE_WIDTH - 2*MARGIN - (boxGap*4)) / 5
        
        var sigX = MARGIN
        val footLabels = listOf("Data da Entrada", "Data da Conclusão", "Laboratório", "Laboratorista", "Assinatura")
        
        footLabels.forEach { 
             drawRectBox(canvas, sigX, cursorY, w5, 15f, it, smallPaint, headerBgPaint)
             drawRectBox(canvas, sigX, cursorY+15f, w5, sigH, "", textPaint, null)
             sigX += w5 + boxGap
        }
    }

    // --- Helpers ---

    private fun drawRectBox(
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
        val linePaint = Paint().apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 0.5f }
        
        if (bgPaint != null) {
            canvas.drawRect(x, y, x + w, y + h, bgPaint)
        }
        canvas.drawRect(x, y, x + w, y + h, linePaint)
        drawTextInRect(canvas, paint, text, x, y, w, h, alignLeft)
    }

    private fun drawHeaderBox(
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
        val stripH = if (h > 15) 12f else h/2 // Increased to 12f to match Atividade label
        
        if (bgPaint != null) {
             canvas.drawRect(x, y, x + w, y + stripH, bgPaint)
        }
        canvas.drawRect(x, y, x + w, y + h, line)
        canvas.drawLine(x, y + stripH, x + w, y + stripH, line)

        val labelPaint = Paint(textP).apply { textSize = labelSize }
        drawCenteredText(canvas, labelPaint, label, x, y, w, stripH)
        
        val valP = Paint(textP).apply { 
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            if(valueSize != null) textSize = valueSize 
        }
        drawCenteredText(canvas, valP, value, x, y + stripH, w, h - stripH)
    }

    private fun drawTextInBox(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
         if (alignLeft) {
             val bounds = Rect()
             paint.getTextBounds("A", 0, 1, bounds)
             val ty = y + (h + bounds.height()) / 2 - bounds.bottom
             // Minimal padding
             canvas.drawText(text, x + 2, ty, paint)
         } else {
             // Basic truncation for now to be safe
             val safeText = if (text.length > 25) text.take(23) + ".." else text
             drawCenteredText(canvas, paint, safeText, x, y, w, h)
         }
    }

    private fun drawVerticalHeader(
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
        
        canvas.withRotation(-90f, x + w/2, y + h/2) {
             val lines = label.split("\n")
             if (lines.size == 1) {
                  drawCenteredText(this, text, label, x + w/2 - h/2, y + h/2 - w/2, h, w)
             } else {
                  // Multi-line logic for vertical header
                  val rotX = x + w/2 - h/2
                  val rotY = y + h/2 - w/2
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
    
    private fun drawCell(canvas: Canvas, line: Paint, text: Paint, value: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
        canvas.drawRect(x, y, x + w, y + h, line)
        if (alignLeft) {
            val bounds = Rect()
            text.getTextBounds(value, 0, value.length, bounds)
             val ty = y + (h + bounds.height()) / 2 - bounds.bottom
            canvas.drawText(value, x + 2, ty, text)
        } else {
            drawCenteredText(canvas, text, value, x, y, w, h)
        }
    }

    private fun drawCenteredText(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float) {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val tx = x + (w - bounds.width()) / 2
        val ty = y + (h + bounds.height()) / 2 - bounds.bottom
        canvas.drawText(text, tx, ty, paint)
    }

    private fun drawTextInRect(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
          if (alignLeft) {
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
             val ty = y + (h + bounds.height()) / 2 - bounds.bottom
            canvas.drawText(text, x + 2, ty, paint)
        } else {
            drawCenteredText(canvas, paint, text, x, y, w, h)
        }
    }
    


    private fun drawBlockCell(
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
    
    private fun calculateCicloFromDate(dateStr: String): String {
        return try {
            val sdf = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US)
            val dateObj = sdf.parse(dateStr)
            if (dateObj != null) {
                val cal = java.util.Calendar.getInstance()
                cal.time = dateObj
                val month = cal.get(java.util.Calendar.MONTH)
                when (month) {
                    java.util.Calendar.JANUARY, java.util.Calendar.FEBRUARY -> "1º"
                    java.util.Calendar.MARCH, java.util.Calendar.APRIL -> "2º"
                    java.util.Calendar.MAY, java.util.Calendar.JUNE -> "3º"
                    java.util.Calendar.JULY, java.util.Calendar.AUGUST -> "4º"
                    java.util.Calendar.SEPTEMBER, java.util.Calendar.OCTOBER -> "5º"
                    java.util.Calendar.NOVEMBER, java.util.Calendar.DECEMBER -> "6º"
                    else -> "1º"
                }
            } else "1º"
        } catch (e: Exception) {
            "1º"
        }
    }
}
