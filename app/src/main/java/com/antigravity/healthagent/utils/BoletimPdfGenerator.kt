package com.antigravity.healthagent.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.withRotation
import com.antigravity.healthagent.R
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.utils.pdf.PdfComponents
import com.antigravity.healthagent.utils.pdf.PdfTableBuilder
import com.antigravity.healthagent.utils.pdf.PdfHeaderFooter
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
        
        val logoBitmap = com.antigravity.healthagent.utils.BitmapCache.getLogo(context, R.drawable.governo_rj_logo)

        val chunksToProcess = BoletimDataMapper.createChunks(houses)
        val totalFolhas = chunksToProcess.size
        
        try {
            chunksToProcess.forEachIndexed { index, chunk ->
                val folhaNumber = index + 1
                val stats = BoletimDataMapper.calculateBlockStats(houses, chunk)

                 // --- Page 1: Frente (List of Houses) ---
                val page1 = pdfDocument.startPage(pageInfo)
                drawFrontPage(page1.canvas, chunk, date, agentName, folhaNumber, totalFolhas, logoBitmap)
                pdfDocument.finishPage(page1)
        
                // --- Page 2: Verso (Summary) ---
                val page2 = pdfDocument.startPage(pageInfo)
                drawBackPage(page2.canvas, chunk, date, stats.quarteiraoConcluido, stats.localidadeConcluida, stats.workedBlocks, stats.completedBlocks)
                pdfDocument.finishPage(page2)
            }
        } finally {
            // Don't recycle — managed by BitmapCache
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
        
        val logoBitmap = com.antigravity.healthagent.utils.BitmapCache.getLogo(context, R.drawable.governo_rj_logo)

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
            BoletimDataMapper.createChunks(weeklyData[date] ?: emptyList())
        }

        try {
            // --- Pass 1: All Front Pages ---
            sortedDates.forEach { date ->
                val chunks = dailyChunks[date] ?: emptyList()
                val totalFolhas = chunks.size
                chunks.forEachIndexed { index, chunk ->
                    val page = pdfDocument.startPage(pageInfo)
                    drawFrontPage(page.canvas, chunk, date, agentName, index + 1, totalFolhas, logoBitmap)
                    pdfDocument.finishPage(page)
                }
            }

            // --- Pass 2: All Back Pages ---
            sortedDates.forEach { date ->
                val houses = weeklyData[date] ?: emptyList()
                val chunks = dailyChunks[date] ?: emptyList()
                chunks.forEach { chunk ->
                    val stats = BoletimDataMapper.calculateBlockStats(houses, chunk)
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
            val logoVigilancia = com.antigravity.healthagent.utils.BitmapCache.getLogo(context, com.antigravity.healthagent.R.drawable.logo_vigilancia)
            
            try {
                SemanalPdfGenerator.drawSemanalPage(semanalPage.canvas, weekDates, allWeekHouses, activities, agentName, logoVigilancia, logoBitmap)
            } finally {
                // Don't recycle — managed by BitmapCache
            }
            pdfDocument.finishPage(semanalPage)
        } finally {
            // Don't recycle — managed by BitmapCache
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



    private fun drawFrontPage(
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
        val linePaint = PdfComponents.linePaint
        val headerBgPaint = PdfComponents.headerBgPaint

        var cursorY = MARGIN

        // 1. Logo (Left)
        PdfHeaderFooter.drawHeaderLogo(canvas, logoBitmap, MARGIN, cursorY, 40f)

        // 2. Titles (Center) - Aligned with Logo
        val titlePaint = Paint(boldPaint).apply { textSize = 12f }
        val centerX = PAGE_WIDTH / 2f
        val t1 = "SECRETARIA DE ESTADO DE SAÚDE E DEFESA CIVIL"
        val bounds1 = Rect(); titlePaint.getTextBounds(t1, 0, t1.length, bounds1)
        canvas.drawText(t1, centerX - bounds1.width()/2, cursorY + 15f, titlePaint)
        
        val t2 = "REGISTRO DIÁRIO DO SERVIÇO ANTIVETORIAL"
        val bounds2 = Rect(); titlePaint.getTextBounds(t2, 0, t2.length, bounds2)
        canvas.drawText(t2, centerX - bounds2.width()/2, cursorY + 32f, titlePaint)
        
        // 3. Folha Counter (Right)
        val folhaPaint = Paint(textPaint).apply { textSize = 12f }
        val folhaW = 100f
        PdfHeaderFooter.drawPageCounter(canvas, folhaPaint, folhaNumber, totalFolhas, PAGE_WIDTH - MARGIN - folhaW, cursorY, folhaW, 20f)
        
        val headerBlockHeight = 40f
        cursorY += headerBlockHeight + 5f 

        // Metadata Header Rows
        val rowH = 35f 
        val gap = 40f 
        
        val wMunic = 160f
        val wBairro = 260f
        val wCat = 90f
        val wZona = 60f
        val wTipo = PAGE_WIDTH - 2*MARGIN - wMunic - wBairro - wCat - wZona - (gap * 4)

        val lastHouse = houses.lastOrNull() 
        val municipio = lastHouse?.context?.municipio ?: "Bom Jardim"
        val bairro = lastHouse?.address?.bairro?.trim()?.uppercase() ?: ""
        val categoria = lastHouse?.context?.categoria ?: "BRR"
        val zona = lastHouse?.context?.zona ?: "URB"
        val tipo = lastHouse?.context?.tipo?.toString() ?: "2"

        var cx = MARGIN
        val labelSize = 8f
        val dataSize = 10f

        fun dBox(w: Float, label: String, valText: String) {
             PdfComponents.drawHeaderBox(canvas, linePaint, textPaint, headerBgPaint, cx, cursorY, w, rowH, label, valText, 
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
        
        canvas.drawRect(tipoX, cursorY, tipoX + wTipo, cursorY + labelH, headerBgPaint)
        canvas.drawRect(tipoX, cursorY, tipoX + wTipo, cursorY + rowH, linePaint)
        canvas.drawLine(tipoX, cursorY + labelH, tipoX + wTipo, cursorY + labelH, linePaint)
        PdfComponents.drawCenteredTextWithBounds(canvas, Paint(textPaint).apply { textSize = labelSize }, "Tipo", tipoX, cursorY, wTipo, labelH)
        
        PdfComponents.drawCenteredTextWithBounds(canvas, Paint(boldPaint).apply{textSize=dataSize}, tipo, tipoX, cursorY + labelH, wTipo * 0.25f, bottomH)
        canvas.drawLine(tipoX + (wTipo * 0.25f), cursorY + labelH, tipoX + (wTipo * 0.25f), cursorY + rowH, linePaint)
        
        val legPaint = Paint(textPaint).apply { textSize = 7f }
        val legX = tipoX + (wTipo * 0.25f) + 2
        val midY = cursorY + labelH + bottomH/2
        
        canvas.drawLine(tipoX + (wTipo * 0.25f), midY, tipoX + wTipo, midY, linePaint)
        canvas.drawText("1-Sede", legX, midY - 4, legPaint)
        canvas.drawText("2-Outros", legX, midY + 9, legPaint)
        
        cursorY += rowH + 5f
        
        // Row 2: Data | Ciclo | Atividade
        val wData = 200f 
        val wCiclo = 200f 
        val wAtiv = PAGE_WIDTH - 2*MARGIN - wData - wCiclo - (gap * 2) 
        
        val year = date.split("-").lastOrNull() ?: ""
        val cicloRaw = if (date.isNotBlank()) PdfComponents.calculateCicloFromDate(date) else ""
        val ciclo = if (cicloRaw.isNotBlank() && year.isNotBlank()) "$cicloRaw / $year" else cicloRaw
        val atividadeCode = lastHouse?.context?.atividade?.toString() ?: "4" 
        
        cx = MARGIN
        dBox(wData, "Data da atividade", date)
        dBox(wCiclo, "Ciclo/Ano", ciclo)
        
        // Custom Atividade Box
        val ativX = cx
        canvas.drawRect(ativX, cursorY, ativX + wAtiv, cursorY + labelH, headerBgPaint)
        canvas.drawRect(ativX, cursorY, ativX + wAtiv, cursorY + rowH, linePaint)
        canvas.drawLine(ativX, cursorY + labelH, ativX + wAtiv, cursorY + labelH, linePaint)
        PdfComponents.drawCenteredTextWithBounds(canvas, Paint(textPaint).apply { textSize = labelSize }, "Atividade", ativX, cursorY, wAtiv, labelH)
        
        val ativValW = wAtiv * 0.1f 
        val ativLegW = wAtiv - ativValW
        
        PdfComponents.drawCenteredTextWithBounds(canvas, Paint(boldPaint).apply{textSize=dataSize}, atividadeCode, ativX, cursorY + labelH, ativValW, bottomH)
        canvas.drawLine(ativX + ativValW, cursorY + labelH, ativX + ativValW, cursorY + rowH, linePaint)
        
        // Legend Grid
        val gridX = ativX + ativValW
        val col1W = ativLegW * 0.28f
        val col2W = ativLegW * 0.44f
        val col3W = ativLegW * 0.28f
        val gRowH = bottomH / 2
        
        canvas.drawLine(gridX, cursorY + labelH + gRowH, gridX + ativLegW, cursorY + labelH + gRowH, linePaint)
        canvas.drawLine(gridX + col1W, cursorY + labelH, gridX + col1W, cursorY + rowH, linePaint)
        canvas.drawLine(gridX + col1W + col2W, cursorY + labelH, gridX + col1W + col2W, cursorY + rowH, linePaint)
        
        val pGrid = Paint(textPaint).apply { textSize = 5f }
        val items = listOf(
            "1-LI-Levantamento de Índice", "2-LI+T-Levantamento/Índice + Tratamento", "3-PE-Ponto Estratégico",
            "4-T-Tratamento", "5-DF-Delimitação de Foco", "6-PVE-Pesquisa Vetorial Espacial"
        )
        
        fun dText(txt: String, x: Float, y: Float, w: Float) {
            PdfComponents.drawTextInBoxWithBounds(canvas, pGrid, txt, x, y, w, gRowH)
        }
        
        // Row 1
        dText(items[0], gridX, cursorY + labelH, col1W)
        dText(items[1], gridX + col1W, cursorY + labelH, col2W)
        dText(items[2], gridX + col1W + col2W, cursorY + labelH, col3W)
        
        // Row 2
        dText(items[3], gridX, cursorY + labelH + gRowH, col1W)
        dText(items[4], gridX + col1W, cursorY + labelH + gRowH, col2W)
        dText(items[5], gridX + col1W + col2W, cursorY + labelH + gRowH, col3W)
        
        cursorY += rowH + 5f

        // --- Main Table Header ---
        val grayBarH = 20f
        PdfComponents.drawRectBoxWithBounds(canvas, MARGIN, cursorY, PAGE_WIDTH - 2*MARGIN, grayBarH, "PESQUISA ENTOMOLÓGICA / TRATAMENTO", boldPaint, headerBgPaint)
        cursorY += grayBarH

        val ch = 80f
        val headerY = cursorY
        
        val cwQuart = 25f
        val cwNum = 25f
        val cwSeq = 20f
        val cwComp = 20f
        val cwTipo = 20f
        val cwHora = 35f 
        val cwSit = 25f 
        val cwDa = 20f
        val cwDepSingle = 20f 
        val cwDepTotal = cwDepSingle * 7
        val cwElim = 25f
        val cwAmostraSingle = 29f 
        val cwAmostraTotal = cwAmostraSingle * 3
        val cwInsp = 20f
        val cwImovTrat = 20f 
        val cwLarvItem = 28f 
        val cwLarv1Total = cwLarvItem * 2 
        val cwLarv2Total = cwLarvItem * 2 
        val cwAdultTotal = cwLarvItem * 2 
        val cwTratTotal = cwImovTrat + cwLarv1Total + cwLarv2Total + cwAdultTotal
        
        val fixedWidths = cwQuart + cwNum + cwSeq + cwComp + cwTipo + cwHora + cwSit + cwDa + cwDepTotal + cwElim + cwAmostraTotal + cwInsp + cwTratTotal
        val finalCwLog = PAGE_WIDTH - 2*MARGIN - fixedWidths
        
        canvas.drawRect(MARGIN, headerY, PAGE_WIDTH - MARGIN, headerY + ch, headerBgPaint)
        
        var tx = MARGIN
        
        fun dVert(label: String, w: Float) {
            PdfComponents.drawVerticalHeaderWithBounds(canvas, linePaint, textPaint, null, label, tx, headerY, w, ch)
            tx += w
        }

        dVert("Nº do quarteirão", cwQuart)
        PdfComponents.drawRectBoxWithBounds(canvas, tx, headerY, finalCwLog, ch, "Logradouro", boldPaint, null)
        tx += finalCwLog
        
        PdfComponents.drawRectBoxWithBounds(canvas, tx, headerY, cwNum, ch, "Nº", boldPaint, null)
        tx += cwNum
        
        dVert("Sequência", cwSeq)
        dVert("Complemento", cwComp)
        dVert("Tipo do Imóvel", cwTipo)
        dVert("Hora de Entrada", cwHora)
        dVert("Situação", cwSit)
        
        val wDepGroup = cwDa + cwDepTotal + cwElim
        PdfComponents.drawRectBoxWithBounds(canvas, tx, headerY, wDepGroup, ch/3, "Nº DE DEPÓSITOS", boldPaint, null)
        
        val r2Y = headerY + ch/3
        val subH = ch/3
        val tallH = (ch/3) * 2
        
        PdfComponents.drawVerticalHeaderWithBounds(canvas, linePaint, textPaint, null, "D.A.", tx, r2Y, cwDa, tallH)
        PdfComponents.drawRectBoxWithBounds(canvas, tx + cwDa, r2Y, cwDepTotal, subH, "Tipos de Depósitos", textPaint, null)
        
        val elimXBase = tx + cwDa + cwDepTotal
        PdfComponents.drawRectBoxWithBounds(canvas, elimXBase, r2Y, cwElim, tallH, "", textPaint, null)
        
        val eCenterX = elimXBase + cwElim/2
        val eCenterY = r2Y + tallH/2
        
        canvas.withRotation(-90f, eCenterX, eCenterY) {
            val label1 = "Depósitos"
            val label2 = "Eliminados"
            val eb1 = Rect(); textPaint.getTextBounds(label1, 0, label1.length, eb1)
            val eP = Paint(textPaint)
            drawText(label1, eCenterX - eP.measureText(label1)/2, eCenterY - 1, eP)
            drawText(label2, eCenterX - eP.measureText(label2)/2, eCenterY + eb1.height() + 1, eP)
        }
        
        var depX = tx + cwDa
        listOf("A1", "A2", "B", "C", "D1", "D2", "E").forEach {
            PdfComponents.drawRectBoxWithBounds(canvas, depX, headerY + (ch/3)*2, cwDepSingle, subH, it, textPaint, null)
            depX += cwDepSingle
        }
        tx += wDepGroup
        
        PdfComponents.drawRectBoxWithBounds(canvas, tx, headerY, cwAmostraTotal, subH, "Coleta Amostra", boldPaint, null)
        
        val wNumAmostra = cwAmostraSingle * 2
        val wTubitos = cwAmostraSingle
        
        PdfComponents.drawRectBoxWithBounds(canvas, tx, r2Y, wNumAmostra, subH, "Nº da Amostra", textPaint, null)
        PdfComponents.drawVerticalHeaderWithBounds(canvas, linePaint, textPaint, null, "Qtde. Tubitos", tx + wNumAmostra, r2Y, wTubitos, tallH)
        
        var amX = tx
        listOf("Inicial", "Final").forEach {
            PdfComponents.drawVerticalHeaderWithBounds(canvas, linePaint, textPaint, null, it, amX, headerY + (ch/3)*2, cwAmostraSingle, subH)
            amX += cwAmostraSingle
        }
        tx += cwAmostraTotal

        dVert("Imóv. Inspec.", cwInsp)

        val tratY1 = headerY
        val trH1 = 15f
        val trH2 = 15f
        val trH3 = 15f
        val trH4 = ch - trH1 - trH2 - trH3
        
        PdfComponents.drawRectBoxWithBounds(canvas, tx, tratY1, cwTratTotal, trH1, "Tratamento", boldPaint, null)
        
        val yR2 = tratY1 + trH1
        PdfComponents.drawRectBoxWithBounds(canvas, tx, yR2, cwImovTrat + cwLarv1Total + cwLarv2Total, trH2, "Focal", textPaint, null)
        PdfComponents.drawRectBoxWithBounds(canvas, tx + cwImovTrat + cwLarv1Total + cwLarv2Total, yR2, cwAdultTotal, trH2, "Perifocal", textPaint, null)
        
        val yR3 = yR2 + trH2
        PdfComponents.drawVerticalHeaderWithBounds(canvas, linePaint, textPaint, null, "Imóv. Trat.", tx, yR3, cwImovTrat, trH3 + trH4)
        
        PdfComponents.drawRectBoxWithBounds(canvas, tx+cwImovTrat, yR3, cwLarv1Total, trH3, "Larvicida 1", textPaint, null)
        PdfComponents.drawRectBoxWithBounds(canvas, tx+cwImovTrat+cwLarv1Total, yR3, cwLarv2Total, trH3, "Larvicida 2", textPaint, null)
        PdfComponents.drawRectBoxWithBounds(canvas, tx+cwImovTrat+cwLarv1Total+cwLarv2Total, yR3, cwAdultTotal, trH3, "Adulticida", textPaint, null)
        
        val yR4 = yR3 + trH3
        var lX = tx + cwImovTrat
        
        listOf("Qtde.\n(gramas)", "Qtde.\ndep.\nTrat.").forEach { 
            PdfComponents.drawVerticalHeaderWithBounds(canvas, linePaint, textPaint, null, it, lX, yR4, cwLarvItem, trH4)
            lX += cwLarvItem
        }
        listOf("Qtde.\n(gramas)", "Qtde.\ndep.\nTrat.").forEach { 
             PdfComponents.drawVerticalHeaderWithBounds(canvas, linePaint, textPaint, null, it, lX, yR4, cwLarvItem, trH4)
            lX += cwLarvItem
        }
        listOf("Tipo", "Qtde.\nCargas").forEach { 
             PdfComponents.drawVerticalHeaderWithBounds(canvas, linePaint, textPaint, null, it, lX, yR4, cwLarvItem, trH4)
             lX += cwLarvItem
        }
        
        cursorY += ch
        
        // --- Rows ---
        val gridRowH = 12f
        val maxRows = 20
        
        val widths = listOf(
            cwQuart, finalCwLog, cwNum, cwSeq, cwComp, cwTipo, cwHora, cwSit, cwDa,
            cwDepSingle, cwDepSingle, cwDepSingle, cwDepSingle, cwDepSingle, cwDepSingle, cwDepSingle,
            cwElim, cwAmostraSingle, cwAmostraSingle, cwAmostraSingle, cwInsp,
            cwImovTrat, cwLarvItem, cwLarvItem, cwLarvItem, cwLarvItem, cwLarvItem, cwLarvItem
        )
        val alignLefts = List(widths.size) { it == 1 }

        for (i in 0 until maxRows) {
            val house = houses.getOrNull(i)
            
            val blockText = if (house != null && house.address.blockSequence.isNotBlank()) {
                "${house.address.blockNumber} / ${house.address.blockSequence}"
            } else {
                house?.address?.blockNumber ?: ""
            }
            val logPaint = Paint(textPaint).apply { textSize = 10f }
            
            val streetNameRaw = house?.address?.streetName?.formatStreetName() ?: ""
            val availableDescWidth = finalCwLog - 7f 
            val streetName = streetNameRaw.fitToWidth(logPaint, availableDescWidth)
            
            fun chk(v: String?): String = if (streetName.isNotBlank() && v.isNullOrBlank()) "—" else v ?: ""
            
            val numStr = chk(house?.address?.number)
            val seqStr = house?.address?.sequence?.let { if (it == 0) "" else it.toString() } ?: ""
            val complStr = house?.address?.complement?.let { if (it == 0) "" else it.toString() } ?: ""
            val propType = house?.propertyType?.code ?: ""
            val horaStr = ""
            val sit = if(house?.situation == Situation.NONE || house?.situation == Situation.EMPTY) "—" else house?.situation?.code ?: ""
            val isOpen = sit == "—" 
            val daStr = ""
            
            val depValues = if (house != null && isOpen) listOf(house.treatment.a1, house.treatment.a2, house.treatment.b, house.treatment.c, house.treatment.d1, house.treatment.d2, house.treatment.e) else List(7){0}
            val depStrs = depValues.map { if (it > 0) it.toString() else "" }.map { if (isOpen) chk(it) else "" }
            
            val elim = if ((house?.treatment?.eliminados ?: 0) > 0 && isOpen) house?.treatment?.eliminados.toString() else ""
            val elimStr = if(isOpen) chk(elim) else ""
            
            val samples = listOf("", "", "")
            val inspected = ""
            
            val larvG = if (house != null && (house.treatment.larvicida > 0.0) && isOpen) {
                if (house.treatment.larvicida % 1.0 == 0.0) house.treatment.larvicida.toInt().toString() else house.treatment.larvicida.toString()
            } else ""
            val treated = if (larvG.isNotEmpty() || (house != null && house.treatment.eliminados > 0)) "X" else "" 
            val treatedStr = if(isOpen) chk(treated) else ""
            val larvGStr = if(isOpen) chk(larvG) else ""
            
            val sumDeps = if (house != null && isOpen) (house.treatment.a1 + house.treatment.a2 + house.treatment.b + house.treatment.c + house.treatment.d1 + house.treatment.d2 + house.treatment.e) else 0
            val sumDepsStr = if (sumDeps > 0) sumDeps.toString() else ""
            val sumDepsStrFinal = if(isOpen) chk(sumDepsStr) else ""

            val rowValues = mutableListOf(
                blockText, streetName, numStr, chk(seqStr), chk(complStr), propType, horaStr, chk(sit), daStr
            )
            rowValues.addAll(depStrs)
            rowValues.add(elimStr)
            rowValues.addAll(samples)
            rowValues.add(inspected)
            rowValues.add(treatedStr)
            rowValues.add(larvGStr)
            rowValues.add(sumDepsStrFinal)

            if (house != null && house.treatment.comFoco && isOpen) {
                val v26 = rowValues + listOf("", "")
                PdfTableBuilder.drawRow(canvas, linePaint, textPaint, MARGIN, cursorY, gridRowH, widths.take(26), v26, alignLefts.take(26))
                
                val mergedX = MARGIN + widths.take(26).sum()
                val mergedW = cwLarvItem * 2
                PdfComponents.drawCellWithBounds(canvas, linePaint, boldPaint, "Com Foco", mergedX, cursorY, mergedW, gridRowH)
            } else {
                val v28 = rowValues + listOf("", "", "", "")
                PdfTableBuilder.drawRow(canvas, linePaint, textPaint, MARGIN, cursorY, gridRowH, widths, v28, alignLefts)
            }

            cursorY += gridRowH
        }
        
        // --- Totais Row ---
        val totalRowH = 15f
        val wLabel = cwQuart + finalCwLog + cwNum + cwSeq + cwComp + cwTipo + cwHora + cwSit + cwDa
        
        val workedHouses = houses.filter { it.situation == Situation.NONE || it.situation == Situation.EMPTY }
        val sums = mutableListOf<Int>()
        if (workedHouses.isNotEmpty()) {
             sums.add(workedHouses.sumOf { it.treatment.a1 }); sums.add(workedHouses.sumOf { it.treatment.a2 }); sums.add(workedHouses.sumOf { it.treatment.b })
             sums.add(workedHouses.sumOf { it.treatment.c }); sums.add(workedHouses.sumOf { it.treatment.d1 }); sums.add(workedHouses.sumOf { it.treatment.d2 }); sums.add(workedHouses.sumOf { it.treatment.e })
        } else { repeat(7) { sums.add(0) } }
        
        val totalElim = workedHouses.sumOf { it.treatment.eliminados }
        val totalTreated = workedHouses.count { it.treatment.larvicida > 0 || it.treatment.eliminados > 0 }

        val totalLarv = workedHouses.sumOf { it.treatment.larvicida }
        val totalLarvStr = if (totalLarv % 1.0 == 0.0) totalLarv.toInt().toString() else "%.1f".format(java.util.Locale.US, totalLarv)
        
        val totalDepsTreated = workedHouses.sumOf { it.treatment.a1 + it.treatment.a2 + it.treatment.b + it.treatment.c + it.treatment.d1 + it.treatment.d2 + it.treatment.e }

        val totalWidths = listOf(
            wLabel,
            cwDepSingle, cwDepSingle, cwDepSingle, cwDepSingle, cwDepSingle, cwDepSingle, cwDepSingle,
            cwElim,
            cwAmostraSingle, cwAmostraSingle, cwAmostraSingle,
            cwInsp,
            cwImovTrat,
            cwLarvItem,
            cwLarvItem,
            cwLarvItem, cwLarvItem, cwLarvItem, cwLarvItem
        )
        val totalValues = mutableListOf("TOTAIS")
        totalValues.addAll(sums.map { it.toString() })
        totalValues.add(totalElim.toString())
        totalValues.addAll(listOf("", "", "", ""))
        totalValues.add(totalTreated.toString())
        totalValues.add(totalLarvStr)
        totalValues.add(totalDepsTreated.toString())
        totalValues.addAll(listOf("", "", "", ""))

        val totalBolds = List(totalWidths.size) { true }
        PdfTableBuilder.drawRow(canvas, linePaint, boldPaint, MARGIN, cursorY, totalRowH, totalWidths, totalValues, bolds = totalBolds, bgPaint = headerBgPaint)
        
        cursorY += totalRowH + 10f
        
        // Footer (Conventions & Situation)
        val footerRowH = 15f
        val wConv = PAGE_WIDTH / 2 - MARGIN - 10
        val convX = MARGIN
        PdfComponents.drawRectBoxWithBounds(canvas, convX, cursorY, wConv, footerRowH, "CONVENÇÕES", boldPaint, headerBgPaint)
        
        // Situacao
        val startSit = MARGIN + wConv + 20
        PdfComponents.drawRectBoxWithBounds(canvas, startSit, cursorY, wConv, footerRowH, "SITUAÇÃO", boldPaint, headerBgPaint)
        PdfComponents.drawRectBoxWithBounds(canvas, startSit, cursorY + footerRowH, wConv, footerRowH, "F-Fechado  REC-Recusado  A-Abandonado  V-Vazio", Paint(textPaint).apply{textSize=7f}, null)
        
        cursorY += footerRowH * 2 + 15
        
        if (cursorY + 30 > PAGE_HEIGHT) {
            cursorY = PAGE_HEIGHT - 30f
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
        
        PdfComponents.drawTextInRectWithBounds(canvas, textPaint, agLabel, sigX, cursorY, labelW, 20f, alignLeft=true)
        val lineStart = sigX + labelW
        val lineEnd = sigX + sigW - 5
        canvas.drawLine(lineStart, cursorY + 12, lineEnd, cursorY + 12, linePaint)
        
        if (agentName.isNotBlank()) {
             val namePaint = Paint(boldPaint).apply { textSize = 10f }
             PdfComponents.drawTextInRectWithBounds(canvas, namePaint, agentName, lineStart, cursorY - 3, (lineEnd - lineStart), 20f, alignLeft=true)
        }
        sigX += sigW + gapSig
        
        // Supervisor
        PdfComponents.drawTextInRectWithBounds(canvas, textPaint, "Supervisor: ___________________", sigX, cursorY, sigW, 20f, alignLeft=true)
        sigX += sigW + gapSig
          
        // Sup. Geral
        PdfComponents.drawTextInRectWithBounds(canvas, textPaint, "Sup. Geral: ___________________", sigX, cursorY, sigW, 20f, alignLeft=true)
        sigX += sigW + gapSig
        
        // Laboratório
        PdfComponents.drawTextInRectWithBounds(canvas, textPaint, "Laboratório: __________________", sigX, cursorY, sigW, 20f, alignLeft=true)
        
        PdfComponents.drawTextInRectWithBounds(canvas, Paint(textPaint).apply{textSize=6f}, "FAD-01(Frente)", MARGIN, cursorY + 12f, 100f, 15f, alignLeft=true)
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
            textSize = 8f
            isAntiAlias = true
        }
        val smallPaint = Paint(textPaint).apply { textSize = 7f }
        val boldPaint = Paint(textPaint).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val headerBgPaint = PdfComponents.headerBgPaint
        val linePaint = PdfComponents.linePaint
        val dataPaint = Paint(boldPaint).apply { textSize = 10f }
        
        var cursorY = MARGIN
        
        // --- Top Info Blocks ---
        // Block 1: Visita (Left)
        val wB1 = 130f
        val hRowB1 = 14f
        PdfComponents.drawRectBoxWithBounds(canvas, MARGIN, cursorY, wB1, hRowB1, "VISITA", boldPaint, headerBgPaint)
        
        val yR1Visita = cursorY + hRowB1
        val wLabel = wB1 - 25f
        val wCheck = 25f
        
        PdfComponents.drawRectBoxWithBounds(canvas, MARGIN, yR1Visita, wLabel, hRowB1, "NORMAL", textPaint, null, alignLeft=true)
        PdfComponents.drawRectBoxWithBounds(canvas, MARGIN + wLabel, yR1Visita, wCheck, hRowB1, "X", boldPaint, null)
        
        val yR2Visita = yR1Visita + hRowB1
        PdfComponents.drawRectBoxWithBounds(canvas, MARGIN, yR2Visita, wLabel, hRowB1, "RECUPERAÇÃO", textPaint, null, alignLeft=true)
        PdfComponents.drawRectBoxWithBounds(canvas, MARGIN + wLabel, yR2Visita, wCheck, hRowB1, "", boldPaint, null)
        
        // Block 2: Equipe / Agente (Middle)
        val gap = 60f
        val xB2 = MARGIN + wB1 + gap
        val wB2 = 250f
        val wLabelB2 = 90f
        val wValB2 = wB2 - wLabelB2
        val hRowB2 = 14f
        
        PdfComponents.drawRectBoxWithBounds(canvas, xB2, cursorY, wLabelB2, hRowB2, "Equipe / Agente", textPaint, null, alignLeft=true)
        val teamName = chunkHouses.lastOrNull()?.agentName?.uppercase() ?: "PMBJ"
        PdfComponents.drawRectBoxWithBounds(canvas, xB2 + wLabelB2, cursorY, wValB2, hRowB2, teamName, boldPaint, null)
        
        val yR1Equipe = cursorY + hRowB2
        PdfComponents.drawRectBoxWithBounds(canvas, xB2, yR1Equipe, wLabelB2, hRowB2, "Data", textPaint, null, alignLeft=true)
        PdfComponents.drawRectBoxWithBounds(canvas, xB2 + wLabelB2, yR1Equipe, wValB2, hRowB2, date, boldPaint, null)
        
        val yR2Equipe = yR1Equipe + hRowB2
        PdfComponents.drawRectBoxWithBounds(canvas, xB2, yR2Equipe, wLabelB2, hRowB2, "Localidade Concluída", textPaint, null, alignLeft=true)
        val locLabel = if (localidadeConcluida) "SIM" else "NÃO"
        PdfComponents.drawRectBoxWithBounds(canvas, xB2 + wLabelB2, yR2Equipe, wValB2, hRowB2, locLabel, boldPaint, null)
        
        // Block 3: Quarteirão (Right)
        val xB3 = xB2 + wB2 + gap
        val wB3 = PAGE_WIDTH - MARGIN - xB3
        val wCommonLabel = 145f 
        val wRestR1 = wB3 - wCommonLabel
        val wBoxR1 = wRestR1 / 5 
        val hRowB3 = 14f
        
        PdfComponents.drawRectBoxWithBounds(canvas, xB3, cursorY, wCommonLabel, hRowB3, "Nº e sequência dos quarteirões", textPaint, null, alignLeft=true)
        var qxTop = xB3 + wCommonLabel
        
        repeat(5) { i ->
            val pair = workedPairs.getOrNull(i)
            val qStr = if (pair != null) {
                if (pair.second.isNotBlank()) "${pair.first} / ${pair.second}" else pair.first
            } else ""
            PdfComponents.drawRectBoxWithBounds(canvas, qxTop, cursorY, wBoxR1, hRowB3, qStr, boldPaint, null)
            qxTop += wBoxR1
        }
        
        val wRestR2 = wB3 - wCommonLabel
        val wBoxR2 = wRestR2 / 4 
        PdfComponents.drawRectBoxWithBounds(canvas, xB3, cursorY + hRowB3, wCommonLabel, hRowB3, "Quarteirão Concluído?", textPaint, null, alignLeft=true)
        
        var qxBot = xB3 + wCommonLabel
        val yR2Right = cursorY + hRowB3
        val qSim = if (quarteiraoConcluido) "X" else ""
        val qNao = if (!quarteiraoConcluido) "X" else ""
        
        PdfComponents.drawRectBoxWithBounds(canvas, qxBot, yR2Right, wBoxR2, hRowB3, "SIM", smallPaint, null); qxBot += wBoxR2
        PdfComponents.drawRectBoxWithBounds(canvas, qxBot, yR2Right, wBoxR2, hRowB3, qSim, boldPaint, null); qxBot += wBoxR2
        PdfComponents.drawRectBoxWithBounds(canvas, qxBot, yR2Right, wBoxR2, hRowB3, "NÃO", smallPaint, null); qxBot += wBoxR2
        PdfComponents.drawRectBoxWithBounds(canvas, qxBot, yR2Right, wBoxR2, hRowB3, qNao, boldPaint, null)
        
        cursorY += (hRowB1 * 3) + 8f
        
        // --- Gray Header: Resumo ---
        val gh = 15f
        PdfComponents.drawRectBoxWithBounds(canvas, MARGIN, cursorY, PAGE_WIDTH - 2*MARGIN, gh, "RESUMO DIÁRIO DO TRABALHO DE CAMPO", boldPaint, headerBgPaint)
        cursorY += gh + 5f

        // --- Table Row 1 ---
        val typeR = chunkHouses.count { it.propertyType == PropertyType.R && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
        val typeC = chunkHouses.count { it.propertyType == PropertyType.C && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
        val typeTB = chunkHouses.count { it.propertyType == PropertyType.TB && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
        val typePE = chunkHouses.count { it.propertyType == PropertyType.PE && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
        val typeO = chunkHouses.count { it.propertyType == PropertyType.O && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
        val totalTypes = typeR + typeC + typeTB + typePE + typeO
        
        val t1Labels = listOf("Residência", "Comércio", "TB", "PE", "Outros", "Total")
        val t1Vals = listOf(typeR, typeC, typeTB, typePE, typeO, totalTypes)
        val colW1 = 35f
        val wT1 = colW1 * 6
        var cx = MARGIN
        val hRowT = 15f
        
        PdfComponents.drawRectBoxWithBounds(canvas, cx, cursorY, wT1, hRowT, "Nº de Imóveis Trabalhados por tipo", boldPaint, headerBgPaint)
        val t1Widths = List(6) { colW1 }
        PdfTableBuilder.drawRow(canvas, linePaint, smallPaint, cx, cursorY + hRowT, hRowT, t1Widths, t1Labels, bgPaint = headerBgPaint)
        val t1ValStrs = t1Vals.map { if (it == 0) "—" else it.toString() }
        PdfTableBuilder.drawRow(canvas, linePaint, dataPaint, cx, cursorY + hRowT * 2, hRowT * 2, t1Widths, t1ValStrs)
        
        cx += wT1 + 10f 
        
        // Table 2: Nº de Imóveis
        val t2Labels = listOf("Trat. Focal", "Trat. Perifocal", "Inspecionados")
        val tratFocal = chunkHouses.count { it.treatment.larvicida > 0 }
        val t2Vals = listOf(tratFocal.toString(), "—", "—")
        val colW2 = 60f
        val wT2 = colW2 * 3
        
        PdfComponents.drawRectBoxWithBounds(canvas, cx, cursorY, wT2, hRowT, "Nº de Imóveis", boldPaint, headerBgPaint)
        val t2Widths = List(3) { colW2 }
        PdfTableBuilder.drawRow(canvas, linePaint, smallPaint, cx, cursorY + hRowT, hRowT, t2Widths, t2Labels, bgPaint = headerBgPaint)
        val t2ValStrs = t2Vals.map { if (it == "0") "—" else it }
        PdfTableBuilder.drawRow(canvas, linePaint, dataPaint, cx, cursorY + hRowT * 2, hRowT * 2, t2Widths, t2ValStrs)
        
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
        
        PdfComponents.drawRectBoxWithBounds(canvas, cx, cursorY, wT3, hRowT, "Pendência", boldPaint, headerBgPaint)
        val t3Widths = List(4) { colW3 }
        PdfTableBuilder.drawRow(canvas, linePaint, smallPaint, cx, cursorY + hRowT, hRowT, t3Widths, t3Labels, bgPaint = headerBgPaint)
        val t3ValStrs = t3Vals.map { if (it == 0) "—" else it.toString() }
        PdfTableBuilder.drawRow(canvas, linePaint, dataPaint, cx, cursorY + hRowT * 2, hRowT * 2, t3Widths, t3ValStrs)
        
        cx += wT3 + 10f
        
        // Table 4: Depósitos
        val t4Labels = listOf("A1", "A2", "B", "C", "D1", "D2", "E", "Total")
        val sA1 = chunkHouses.sumOf { it.treatment.a1 }
        val sA2 = chunkHouses.sumOf { it.treatment.a2 }
        val sB = chunkHouses.sumOf { it.treatment.b }
        val sC = chunkHouses.sumOf { it.treatment.c }
        val sD1 = chunkHouses.sumOf { it.treatment.d1 }
        val sD2 = chunkHouses.sumOf { it.treatment.d2 }
        val sE = chunkHouses.sumOf { it.treatment.e }
        val sTotal = sA1 + sA2 + sB + sC + sD1 + sD2 + sE
        val t4Vals = listOf(sA1, sA2, sB, sC, sD1, sD2, sE, sTotal)
        val colW4 = 28f
        val wT4 = colW4 * 8
        
        PdfComponents.drawRectBoxWithBounds(canvas, cx, cursorY, wT4, hRowT, "Nº de depósito por tipo", boldPaint, headerBgPaint)
        val t4Widths = List(8) { colW4 }
        PdfTableBuilder.drawRow(canvas, linePaint, smallPaint, cx, cursorY + hRowT, hRowT, t4Widths, t4Labels, bgPaint = headerBgPaint)
        val t4ValStrs = t4Vals.map { if (it == 0) "—" else it.toString() }
        PdfTableBuilder.drawRow(canvas, linePaint, dataPaint, cx, cursorY + hRowT * 2, hRowT * 2, t4Widths, t4ValStrs)
        
        cursorY += hRowT*3 + 25f 
        
        // --- Row 2: Depósitos & Quarteirões ---
        val r2Y = cursorY
        
        val wElim = 50f
        val wTratItem = 45f 
        val wTrat = wTratItem * 4 
        val wAdult = wTratItem * 2 
        val wTubitos = 60f
        
        var dx = MARGIN
        val hHeader = 15f
        val hData = 18f
        
        val wTratGroup = wTrat
        val wDepBlock = wElim + wTratGroup
        
        PdfComponents.drawRectBoxWithBounds(canvas, dx, r2Y, wDepBlock, hHeader, "Depósitos", boldPaint, headerBgPaint)
        PdfComponents.drawRectBoxWithBounds(canvas, dx, r2Y + hHeader, wElim, hHeader * 3, "Eliminados", smallPaint, null)
        PdfComponents.drawRectBoxWithBounds(canvas, dx, r2Y + hHeader * 4, wElim, hData, chunkHouses.sumOf { it.treatment.eliminados }.toString(), dataPaint, null)
        
        dx += wElim
        
        PdfComponents.drawRectBoxWithBounds(canvas, dx, r2Y + hHeader, wTratGroup, hHeader, "Tratados", smallPaint, null)
        
        val wBTI = wTratGroup / 2
        PdfComponents.drawRectBoxWithBounds(canvas, dx, r2Y + hHeader * 2, wBTI, hHeader, "BTI WDG", smallPaint, null)
        PdfComponents.drawRectBoxWithBounds(canvas, dx + wBTI, r2Y + hHeader * 2, wBTI, hHeader, "BTI G", smallPaint, null)
        
        val wColTrat = wBTI / 2
        val labelsTrat = listOf("Qtde. (g)", "Dep. Trat.", "Qtde. (g)", "Dep. Trat.") 
        val larvOutput = chunkHouses.sumOf { it.treatment.larvicida }
        val larvOutputStr = if (larvOutput > 0) String.format(java.util.Locale.US, "%.1f", larvOutput).replace(".", ",") else "—"
        val larvDep = chunkHouses.count { it.treatment.larvicida > 0 }
        val larvDepStr = if (larvDep > 0) larvDep.toString() else "—"
        
        val tratWidths = List(4) { wColTrat }
        PdfTableBuilder.drawRow(canvas, linePaint, Paint(smallPaint).apply{textSize=6f}, dx, r2Y + hHeader * 3, hHeader, tratWidths, labelsTrat)
        PdfTableBuilder.drawRow(canvas, linePaint, dataPaint, dx, r2Y + hHeader * 4, hData, tratWidths, listOf(larvOutputStr, larvDepStr, "—", "—"))
        
        dx += wTratGroup
        
        val hAdSubLabel = 45f
        PdfComponents.drawRectBoxWithBounds(canvas, dx, r2Y, wAdult, hHeader, "Adulticida", boldPaint, headerBgPaint)
        
        val wAdCol = wAdult / 2
        PdfComponents.drawRectBoxWithBounds(canvas, dx, r2Y + hHeader, wAdCol, hAdSubLabel, "Tipo", smallPaint, null)
        PdfComponents.drawRectBoxWithBounds(canvas, dx + wAdCol, r2Y + hHeader, wAdCol, hAdSubLabel, "Cargas", smallPaint, null)
        
        PdfComponents.drawRectBoxWithBounds(canvas, dx, r2Y + hHeader + hAdSubLabel, wAdCol, hData, "—", dataPaint, null)
        PdfComponents.drawRectBoxWithBounds(canvas, dx + wAdCol, r2Y + hHeader + hAdSubLabel, wAdCol, hData, "—", dataPaint, null)
        
        dx += wAdult
        
        val hTubHeader = 60f
        PdfComponents.drawRectBoxWithBounds(canvas, dx, r2Y, wTubitos, hTubHeader, "", boldPaint, headerBgPaint)
        
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
        
        PdfComponents.drawRectBoxWithBounds(canvas, dx, r2Y + hTubHeader, wTubitos, hData, "—", dataPaint, null)
        
        dx += wTubitos
        
        val qGap = 15f
        val qx = dx + qGap
        val wRightSection = PAGE_WIDTH - MARGIN - qx
        val hGrid = hHeader + (hData * 2) 
        
        PdfComponents.drawRectBoxWithBounds(canvas, qx, r2Y, wRightSection, hHeader, "Nº e sequência dos quarteirões trabalhados", boldPaint, headerBgPaint)
        
        val qColW = wRightSection / 6
        
        repeat(2) { r ->
            var curQx = qx
            repeat(6) { c ->
                val idx = r * 6 + c
                val pair = workedPairs.getOrNull(idx)
                PdfComponents.drawBlockCell(canvas, linePaint, dataPaint, pair?.first, pair?.second, curQx, r2Y + hHeader + (r * hData), qColW, hData)
                curQx += qColW
            }
        }
        
        val q2Y = r2Y + hGrid
        PdfComponents.drawRectBoxWithBounds(canvas, qx, q2Y, wRightSection, hHeader, "Nº e sequência dos quarteirões concluídos", boldPaint, headerBgPaint)
        
        repeat(2) { r ->
            var curQx = qx
            repeat(6) { c ->
                val idx = r * 6 + c
                val pair = completedPairs.getOrNull(idx)
                PdfComponents.drawBlockCell(canvas, linePaint, dataPaint, pair?.first, pair?.second, curQx, q2Y + hHeader + (r * hData), qColW, hData)
                curQx += qColW
            }
        }
        
        cursorY = r2Y + (hGrid * 2) + 15f
        
        // --- Resumo do Laboratório ---
        val hLabHeader = 15f
        PdfComponents.drawRectBoxWithBounds(canvas, MARGIN, cursorY, PAGE_WIDTH - 2*MARGIN, hLabHeader, "RESUMO DO LABORATÓRIO", boldPaint, headerBgPaint)
        cursorY += hLabHeader + 5f
        
        val wAes = (PAGE_WIDTH - 2*MARGIN - 10f) / 2
        val hAesRow = 18f
        
        PdfComponents.drawRectBoxWithBounds(canvas, MARGIN, cursorY, wAes, hAesRow, "Nº e sequência dos quarteirões com Aedes aegypti", smallPaint, headerBgPaint)
        val wColAe = wAes / 8
        repeat(2) { r ->
             var ax = MARGIN
             repeat(8) {
                 PdfComponents.drawRectBoxWithBounds(canvas, ax, cursorY + hAesRow*(r+1), wColAe, hAesRow, " /", textPaint, null)
                 ax += wColAe
             }
        }
        
        val xRight = MARGIN + wAes + 10f
        PdfComponents.drawRectBoxWithBounds(canvas, xRight, cursorY, wAes, hAesRow, "Nº e sequência dos quarteirões com Aedes albopictus", smallPaint, headerBgPaint)
        repeat(2) { r ->
             var ax = xRight
             repeat(8) {
                 PdfComponents.drawRectBoxWithBounds(canvas, ax, cursorY + hAesRow*(r+1), wColAe, hAesRow, " /", textPaint, null)
                 ax += wColAe
             }
        }
        
        cursorY += hAesRow * 3 + 10f
        
        val wStatsLabel = 120f 
        val wStatsCol = 42f
        val wStatsTotal = wStatsLabel + (wStatsCol * 8)
        val hStHeader = 18f 
        val hStRow = 22f 
        
        var sx = MARGIN
        PdfComponents.drawRectBoxWithBounds(canvas, sx, cursorY, wStatsLabel, hStHeader, "", textPaint, headerBgPaint); sx += wStatsLabel
        listOf("A1", "A2", "B", "C", "D1", "D2", "E", "Total").forEach { 
            PdfComponents.drawRectBoxWithBounds(canvas, sx, cursorY, wStatsCol, hStHeader, it, smallPaint, headerBgPaint)
            sx += wStatsCol
        }
        
        sx = MARGIN
        val yR1Stats = cursorY + hStHeader
        PdfComponents.drawRectBoxWithBounds(canvas, sx, yR1Stats, wStatsLabel, hStRow, "Com Aedes aegypti", smallPaint, null, alignLeft=true); sx += wStatsLabel
        repeat(8) { PdfComponents.drawRectBoxWithBounds(canvas, sx, yR1Stats, wStatsCol, hStRow, "—", textPaint, null); sx += wStatsCol }
        
        sx = MARGIN
        val yR2Stats = cursorY + hStHeader + hStRow
        PdfComponents.drawRectBoxWithBounds(canvas, sx, yR2Stats, wStatsLabel, hStRow, "Com Aedes albopictus", smallPaint, null, alignLeft=true); sx += wStatsLabel
        repeat(8) { PdfComponents.drawRectBoxWithBounds(canvas, sx, yR2Stats, wStatsCol, hStRow, "—", textPaint, null); sx += wStatsCol }
        
        val xStage = 533f
        val wStageSection = PAGE_WIDTH - MARGIN - xStage
        val wStageCol = wStageSection / 4
        val stageLabels = listOf("Larvas", "Pupas", "Exúvia de Pupa", "Adultos")
        
        var stX = xStage
        stageLabels.forEach {
            PdfComponents.drawRectBoxWithBounds(canvas, stX, cursorY, wStageCol, hStHeader + hStRow - 5f, it, Paint(smallPaint).apply{textSize=6.5f}, headerBgPaint) 
            stX += wStageCol
        }
        
        val sRH = 22f 
        var currStY = cursorY + hStHeader + hStRow - 5f
        repeat(3) {
             stX = xStage
             repeat(4) {
                 PdfComponents.drawRectBoxWithBounds(canvas, stX, currStY, wStageCol, sRH, "", textPaint, null)
                 stX += wStageCol
             }
        currStY += sRH
        }
        
        cursorY = yR2Stats + hStRow + 25f
        
        // Footer Legends
        val colW = wStatsTotal / 4f
        val legH = 10f
        val lP = Paint(smallPaint).apply { textSize = 5.5f }
        
        PdfComponents.drawTextInBoxWithBounds(canvas, lP, "A1 - Caixa d'água (elevado)", MARGIN, cursorY, colW, legH)
        PdfComponents.drawTextInBoxWithBounds(canvas, lP, "A2 - Outros depósitos de armazenamento de água (baixo)", MARGIN + colW, cursorY, colW, legH)
        PdfComponents.drawTextInBoxWithBounds(canvas, lP, "B - Pequenos depósitos móveis", MARGIN + 2*colW + 50f, cursorY, colW, legH)
        PdfComponents.drawTextInBoxWithBounds(canvas, lP, "C - Depósitos fixos", MARGIN + 3*colW +50f, cursorY, colW, legH)
        
        cursorY += legH
        PdfComponents.drawTextInBoxWithBounds(canvas, lP, "D1 - Pneus e outros materiais rodantes", MARGIN, cursorY, colW, legH)
        PdfComponents.drawTextInBoxWithBounds(canvas, lP, "D2 - Lixo (recipientes plásticos, latas), sucatas, entulhos", MARGIN + colW, cursorY, colW, legH)
        PdfComponents.drawTextInBoxWithBounds(canvas, lP, "E - Depósitos naturais", MARGIN + 2*colW + 50f, cursorY, colW, legH)
        
        cursorY += legH + 20f
        
        val sigH = 30f
        val boxGap = 10f
        val w5 = (PAGE_WIDTH - 2*MARGIN - (boxGap*4)) / 5
        var sigX = MARGIN
        val footLabels = listOf("Data da Entrada", "Data da Conclusão", "Laboratório", "Laboratorista", "Assinatura")
        
        footLabels.forEach { 
             PdfComponents.drawRectBoxWithBounds(canvas, sigX, cursorY, w5, 15f, it, smallPaint, headerBgPaint)
             PdfComponents.drawRectBoxWithBounds(canvas, sigX, cursorY+15f, w5, sigH, "", textPaint, null)
             sigX += w5 + boxGap
        }
    }
}
