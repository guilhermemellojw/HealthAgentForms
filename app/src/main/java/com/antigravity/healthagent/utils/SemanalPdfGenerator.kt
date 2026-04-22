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
import com.antigravity.healthagent.utils.formatStreetName
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object SemanalPdfGenerator {

    private const val PAGE_WIDTH = 842 // A4 Landscape width
    private const val PAGE_HEIGHT = 595 // A4 Landscape height
    private const val MARGIN_LEFT = 40f
    private const val MARGIN_RIGHT = 90f // Space for external labels
    private const val MARGIN_Y = 80f

    fun generatePdf(
        context: Context,
        weekDates: List<String>,
        allHouses: List<House>,
        activities: Map<String, String>,
        agentName: String
    ): File {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        // Hoist bitmap decoding
        val logoVigilancia = try { BitmapFactory.decodeResource(context.resources, R.drawable.logo_vigilancia_ambiental) } catch (e: Exception) { null }
        val logoGoverno = try { BitmapFactory.decodeResource(context.resources, R.drawable.governo_rj_logo) } catch (e: Exception) { null }

        try {
            drawSemanalPage(context, canvas, weekDates, allHouses, activities, agentName, logoVigilancia, logoGoverno)
        } finally {
            logoVigilancia?.recycle()
            logoGoverno?.recycle()
        }

        pdfDocument.finishPage(page)

        val sdf = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US)
        val firstDateStr = weekDates.firstOrNull()
        
        var fileNameStr = "Semanal_export"
        
        if (firstDateStr != null) {
            try {
                // Ensure date format is consistent for parsing
                val transformedDate = firstDateStr.replace("/", "-")
                val parsedDate = sdf.parse(transformedDate)
                if (parsedDate != null) {
                    val cal = java.util.Calendar.getInstance()
                    cal.time = parsedDate
                
                    // Set to Sunday (Start of week)
                    val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
                    val daysFromSunday = dayOfWeek - java.util.Calendar.SUNDAY
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -daysFromSunday)
                
                    val startDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    val startMonthIndex = cal.get(java.util.Calendar.MONTH)
                
                    // Set to Saturday (End of week) - add 6 days to Sunday
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 6)
                    val endDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
                
                    val monthNames = arrayOf("Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho", "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro")
                    val monthName = monthNames[startMonthIndex]
                
                    val startDayStr = String.format(java.util.Locale("pt", "BR"), "%02d", startDay)
                    val endDayStr = String.format(java.util.Locale("pt", "BR"), "%02d", endDay)
                    val sanitizedAgent = agentName.trim().replace(" ", "_").replace("/", "-")
                
                    fileNameStr = "Semanal_${startDayStr}_a_${endDayStr}_${monthName}_$sanitizedAgent"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        val fileName = "$fileNameStr.pdf"
        val file = File(context.cacheDir, fileName)

        try {
            FileOutputStream(file).use { out ->
                pdfDocument.writeTo(out)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            throw IOException("Falha ao salvar o arquivo PDF: ${e.message}", e)
        } finally {
            pdfDocument.close()
        }

        return file
    }

    fun drawSemanalPage(
        context: Context,
        canvas: Canvas,
        weekDates: List<String>,
        allHouses: List<House>,
        activities: Map<String, String>,
        agentName: String,
        logoVigilancia: android.graphics.Bitmap?,
        logoGoverno: android.graphics.Bitmap?
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
        val headerBgPaint = Paint().apply {
            color = Color.parseColor("#E0E0E0") // Light gray
            style = Paint.Style.FILL
        }

        var cursorY = MARGIN_Y

        // --- Header Section (Split Design) ---
        val tableWidth = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT
        val rightEdge = MARGIN_LEFT + tableWidth

        // LEFT SIDE: Logo and Municipality Info
        val logoH = 38f
        // Bitmaps are passed as arguments to avoid redundant decoding
        val actualLogo = logoVigilancia ?: logoGoverno
        
        if (actualLogo != null) {
            val logoW = (actualLogo.width.toFloat() / actualLogo.height.toFloat() * logoH)
            val logoX = MARGIN_LEFT // Start at table left edge
            val logoY = cursorY + 12f
            val destRect = Rect(logoX.toInt(), logoY.toInt(), (logoX + logoW).toInt(), (logoY + logoH).toInt())
            canvas.drawBitmap(actualLogo, null, destRect, null)
        }

        val smallBold = Paint(boldPaint).apply { textSize = 7.5f }
        val prefText = "PREFEITURA MUNICIPAL DE BOM JARDIM"
        val secText = "SECRETARIA MUNICIPAL DE SAÚDE"
        canvas.drawText(prefText, MARGIN_LEFT, cursorY + 10f, smallBold)
        canvas.drawText(secText, MARGIN_LEFT, cursorY + 19f, smallBold)

        // RIGHT SIDE: Titles
        val titlePaint = Paint(boldPaint).apply { textSize = 20f }
        val subTitlePaint = Paint(boldPaint).apply { textSize = 26f }
        val headerText1 = "Programa Municipal de Controle da Dengue"
        val headerText2 = "PMCD"
        
        val title1W = titlePaint.measureText(headerText1)
        val title2W = subTitlePaint.measureText(headerText2)
        
        canvas.drawText(headerText1, rightEdge - title1W, cursorY + 25f, titlePaint)
        // Center headerText2 within the width of headerText1
        canvas.drawText(headerText2, (rightEdge - title1W) + (title1W - title2W) / 2f, cursorY + 55f, subTitlePaint)

        cursorY += logoH + 25f

        // Metadata Header
        val uniqueWeekBairros = allHouses.filter { weekDates.contains(it.data) }.map { it.bairro.trim().uppercase() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        val bairro = uniqueWeekBairros.joinToString(" / ")
        val firstHouse = allHouses.find { h -> weekDates.contains(h.data) }
        val categoria = firstHouse?.categoria ?: "BRR"
        // Fix: If no houses (e.g. holiday week), calculate cycle from the first date of the week
        val firstDateOfWeek = weekDates.firstOrNull() ?: ""
        val calculatedCiclo = if (firstDateOfWeek.isNotBlank()) calculateCiclo(firstDateOfWeek) else ""
        val ciclo = calculatedCiclo
        val ano = if (weekDates.isNotEmpty()) weekDates.first().takeLast(4) else ""

        // Gray bar "RESUMO SEMANAL DOS AGENTES"
        val grayBarH = 15f
        val barBgPaint = Paint().apply {
            color = Color.parseColor("#F2F2F2") // Very light gray like sample
            style = Paint.Style.FILL
        }
        drawRectBox(canvas, MARGIN_LEFT, cursorY, tableWidth, grayBarH, "RESUMO SEMANAL DOS AGENTES", boldPaint, barBgPaint)
        cursorY += grayBarH

        // Row with Bairro, Ciclo, Turma, Dates
        val metaRowH = 20f
        var cx = MARGIN_LEFT
        val col2X = MARGIN_LEFT + 480f // Wider gap like sample
        
        val labelCB = "Código/Bairro"
        val wLabelCB = textPaint.measureText(labelCB)
        val wCat = 60f
        val wBName = 290f
        drawMetaFieldSegmented(canvas, textPaint, linePaint, labelCB, listOf(categoria, bairro), listOf(wCat, wBName), cx, cursorY, metaRowH)
        
        // Calculated endpoint for left column to ensure Turma aligns with Bairro
        // drawMetaFieldSegmented adds 5px after label, then segments with 20px separator for Bairro
        val endLeft = MARGIN_LEFT + wLabelCB + 5f + wCat + 20f + wBName

        cx = col2X
        
        // Ano/Ciclo Segments - Align to right edge
        val labelAC = "Ano/Ciclo"
        val wLabelAC = textPaint.measureText(labelAC)
        // rightEdge already defined above
        val availAC = rightEdge - (col2X + wLabelAC + 25f) // 5 (label gap) + 20 (separator)
        val wAno = availAC * 0.55f
        val wCicloVal = availAC * 0.45f
        drawMetaFieldSegmented(canvas, textPaint, linePaint, labelAC, listOf(ano, ciclo), listOf(wAno, wCicloVal), cx, cursorY, metaRowH)

        cursorY += metaRowH + 8f
        cx = MARGIN_LEFT
        
        // Turma (Halved width and centered value)
        val labelTurma = "Turma:"
        val fullTurmaW = endLeft - MARGIN_LEFT
        val wTurma = (fullTurmaW / 2f) 
        drawMetaField(canvas, textPaint, linePaint, labelTurma, "PMBJ", cx, cursorY, wTurma, metaRowH, centerValue = true)
        
        cx = col2X
        
        // Semana de Segments - Align to right edge
        val labelSD = "Semana de"
        val wLabelSD = textPaint.measureText(labelSD)
        // Spacing in drawMetaFieldSegmented for isSemana: 4+8 (/) + 12+18 (a) + 4+8 (/) = 54
        val availSD = rightEdge - (col2X + wLabelSD + 5f + 54f)
        val wSeg = availSD / 4f
        
        // Calculate Sunday (Start) and Saturday (End) based on the first date (Monday)
        val sdf = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US)
        val firstDateStr = weekDates.firstOrNull()
        
        var weekStartDay = ""
        var weekStartMonth = ""
        var weekEndDay = ""
        var weekEndMonth = ""

        if (firstDateStr != null) {
            try {
                val parsedDate = sdf.parse(firstDateStr)
                if (parsedDate != null) {
                    val cal = java.util.Calendar.getInstance()
                    cal.time = parsedDate
                
                // Set to Sunday (Start of week)
                val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
                val daysFromSunday = dayOfWeek - java.util.Calendar.SUNDAY
                cal.add(java.util.Calendar.DAY_OF_YEAR, -daysFromSunday)
                
                weekStartDay = String.format(java.util.Locale("pt", "BR"), "%02d", cal.get(java.util.Calendar.DAY_OF_MONTH))
                weekStartMonth = String.format(java.util.Locale("pt", "BR"), "%02d", cal.get(java.util.Calendar.MONTH) + 1)
                
                // Set to Saturday (End of week) - add 6 days to Sunday
                cal.add(java.util.Calendar.DAY_OF_YEAR, 6)
                weekEndDay = String.format(java.util.Locale("pt", "BR"), "%02d", cal.get(java.util.Calendar.DAY_OF_MONTH))
                weekEndMonth = String.format(java.util.Locale.US, "%02d", cal.get(java.util.Calendar.MONTH) + 1)
                } // End of if (parsedDate != null)
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to existing logic if parsing fails
                weekStartDay = weekDates.firstOrNull()?.take(2) ?: ""
                weekStartMonth = weekDates.firstOrNull()?.substring(3, 5) ?: ""
                weekEndDay = weekDates.lastOrNull()?.take(2) ?: ""
                weekEndMonth = weekDates.lastOrNull()?.substring(3, 5) ?: ""
            }
        }

        drawMetaFieldSegmented(canvas, textPaint, linePaint, labelSD, listOf(weekStartDay, weekStartMonth, weekEndDay, weekEndMonth), listOf(wSeg, wSeg, wSeg, wSeg), cx, cursorY, metaRowH, isSemana = true)
        
        cursorY += metaRowH + 15f

        // --- Table Headers ---
        val th1 = 20f // Top header height
        val th2 = 35f // Middle header height
        // Bottom header height (for dep types) removed unused th3
        val totalHeaderH = th1 + th2

        val colData = 60f
        val colRes = 24f
        val colCom = 24f
        val colTB = 24f
        val colOut = 24f
        val colPE = 24f
        val colTotalVisits = 30f
        val groupVisits = colRes + colCom + colTB + colOut + colPE + colTotalVisits
        
        val colFEC = 24f
        val colREC = 24f
        val colRecup = 24f
        val groupPend = colFEC + colREC + colRecup
        
        val colAmostras = 35f
        
        val colDep = 23f
        val groupDeps = colDep * 7
        
        val colTotalDeps = 52f
        val colElim = 65f
        val colLarv = 57f
        val colQuart = 60f // Standardized width

        // Vertical split headers
        var tx = MARGIN_LEFT
        
        // Column Data
        drawRectBox(canvas, tx, cursorY, colData, totalHeaderH, "Data", boldPaint, null)
        tx += colData
        
        // Group Imóveis Trabalhados
        drawRectBox(canvas, tx, cursorY, groupVisits, th1, "Imóveis Trabalhados", boldPaint, null)
        var stx = tx
        drawVerticalTextInBox(canvas, textPaint, "Res", stx, cursorY + th1, colRes, th2); stx += colRes
        drawVerticalTextInBox(canvas, textPaint, "Com", stx, cursorY + th1, colCom, th2); stx += colCom
        drawVerticalTextInBox(canvas, textPaint, "TB", stx, cursorY + th1, colTB, th2); stx += colTB
        drawVerticalTextInBox(canvas, textPaint, "Out", stx, cursorY + th1, colOut, th2); stx += colOut
        drawVerticalTextInBox(canvas, textPaint, "PE", stx, cursorY + th1, colPE, th2); stx += colPE
        drawVerticalTextInBox(canvas, boldPaint, "Total", stx, cursorY + th1, colTotalVisits, th2)
        tx += groupVisits
        
        // Group Pendências
        drawRectBox(canvas, tx, cursorY, groupPend, th1, "Pendências", boldPaint, null)
        stx = tx
        drawVerticalTextInBox(canvas, textPaint, "FEC", stx, cursorY + th1, colFEC, th2); stx += colFEC
        drawVerticalTextInBox(canvas, textPaint, "REC", stx, cursorY + th1, colREC, th2); stx += colREC
        drawVerticalTextInBox(canvas, textPaint, "Recup", stx, cursorY + th1, colRecup, th2)
        tx += groupPend
        
        // Amostras Coletadas
        drawVerticalHeader(canvas, linePaint, textPaint, null, "Amostras\nColetadas", tx, cursorY, colAmostras, totalHeaderH)
        tx += colAmostras
        
        // Group Quantidades de Depósitos Tratados
        drawRectBox(canvas, tx, cursorY, groupDeps, th1, "Quantidades de Depósitos Tratados", boldPaint, null)
        // Sub-headers A1..E
        stx = tx
        listOf("A1", "A2", "B", "C", "D1", "D2", "E").forEach { label ->
            drawRectBox(canvas, stx, cursorY + th1, colDep, th2, label, textPaint, null)
            stx += colDep
        }
        tx += groupDeps
        
        // Total Depósitos
        drawVerticalHeader(canvas, linePaint, textPaint, null, "Total de\nDepósitos\nTratados", tx, cursorY, colTotalDeps, totalHeaderH)
        tx += colTotalDeps
        
        // Depósitos Eliminados
        val elimLabel = "Depósitos\nEliminados\nCaixas\nd'água Difícil\nacesso"
        drawVerticalHeader(canvas, linePaint, textPaint, null, elimLabel, tx, cursorY, colElim, totalHeaderH)
        tx += colElim
        
        // Larvicida
        drawVerticalHeader(canvas, linePaint, textPaint, null, "Larvicida\nBPU(Gr)", tx, cursorY, colLarv, totalHeaderH)
        tx += colLarv
        
        // Quarteirões Concluídos
        drawVerticalHeader(canvas, linePaint, textPaint, null, "Quarteirões\nConcluídos", tx, cursorY, colQuart, totalHeaderH)
        
        cursorY += totalHeaderH

        // --- Data Rows ---
        val rowH = 15f
        val dash = "—"

        // --- O(N) Pre-calculations ---
        val housesByDate = allHouses.groupBy { it.data }
        val allBlockCompletions = mutableListOf<Pair<String, String>>() // List of (BlockDisplayName, Date)

        weekDates.forEach { date ->
            val dayHouses = housesByDate[date] ?: return@forEach
            
            // 1. Identify all unique blocks worked on this day
            val dayBlocks = dayHouses.map { Triple(it.blockNumber, it.blockSequence, it.bairro.trim().uppercase()) }.distinct()
            
            // 2. Pre-sort day houses by listOrder once
            val dayHousesSorted = dayHouses.sortedBy { it.listOrder }
            val housesByBlock = dayHouses.groupBy { "${it.blockNumber}|${it.blockSequence}|${it.bairro.trim().uppercase()}" }

            dayBlocks.forEach { (bNum, bSeq, bairro) ->
                val blockKey = "$bNum|$bSeq|$bairro"
                val blockHousesInDay = housesByBlock[blockKey] ?: emptyList()
                
                val hasManual = blockHousesInDay.any { it.quarteiraoConcluido }
                val hasBairroManual = blockHousesInDay.any { it.localidadeConcluida }
                
                // Auto-concluded: The LAST house of this block in THIS daily list has a successor in the same daily list
                val lastInBlockOnDay = blockHousesInDay.maxByOrNull { it.listOrder }
                val indexOfLast = if (lastInBlockOnDay != null) dayHousesSorted.indexOfFirst { it.id == lastInBlockOnDay.id } else -1
                val hasSuccessorOnSameDay = indexOfLast != -1 && indexOfLast < dayHousesSorted.size - 1

                if (hasManual || hasBairroManual || hasSuccessorOnSameDay) {
                    val displayName = if (bSeq.isNotBlank()) "$bNum/$bSeq" else bNum
                    allBlockCompletions.add(displayName to date)
                }
            }
        }

        // Totals accumulators
        var totRes = 0; var totCom = 0; var totTB = 0; var totOut = 0; var totPE = 0; var totVisits = 0
        var totFEC = 0; var totREC = 0; var totRecup = 0
        var totAmostras = 0
        val totDeps = IntArray(7) { 0 }
        var totTotalDeps = 0
        var totElim = 0
        var totLarv = 0.0
        val totCompletedBlocks = mutableSetOf<String>()

        weekDates.forEach { date ->
            val dayHouses = housesByDate[date] ?: emptyList()
            val status = activities[date] ?: ""

            tx = MARGIN_LEFT
            
            // Data Cell
            val displayDate = date.replace("-", "/").substring(0, 5) // Format DD/MM
            drawCell(canvas, linePaint, textPaint, displayDate, tx, cursorY, colData, rowH)
            tx += colData
            
            if (status.isNotBlank() && !status.equals("NORMAL", ignoreCase = true)) {
                val statusPaint = Paint(boldPaint).apply { textSize = 9f; letterSpacing = 0.1f }
                val annotationWidth = tableWidth - colData - colQuart
                
                // Annotation area
                drawCell(canvas, linePaint, statusPaint, status, tx, cursorY, annotationWidth, rowH)
                tx += annotationWidth
                
                // Final Quarteirão cell
                drawCell(canvas, linePaint, textPaint, "", tx, cursorY, colQuart, rowH)
            } else {
                if (dayHouses.isEmpty()) {
                    // Empty working day with no specific status (should not happen normally but for safety)
                    val annotationWidth = tableWidth - colData - colQuart
                    drawCell(canvas, linePaint, textPaint, dash, tx, cursorY, annotationWidth, rowH)
                    tx += annotationWidth
                    drawCell(canvas, linePaint, textPaint, dash, tx, cursorY, colQuart, rowH)
                } else {
                    // Property Types - Filter by OPEN houses only
                    val res = dayHouses.count { it.propertyType == PropertyType.R && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
                    val com = dayHouses.count { it.propertyType == PropertyType.C && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
                    val tb = dayHouses.count { it.propertyType == PropertyType.TB && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
                    val out = dayHouses.count { it.propertyType == PropertyType.O && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
                    val pe = dayHouses.count { it.propertyType == PropertyType.PE && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
                    val dayTotalVisits = res + com + tb + out + pe
                    
                    val fec = dayHouses.count { it.situation == Situation.F }
                    val rec = dayHouses.count { it.situation == Situation.REC }
                    val recup = 0 // Placeholder
                    val samples = 0 
                    
                    val workedDayHouses = dayHouses.filter { it.situation == Situation.NONE || it.situation == Situation.EMPTY }
                    val dists = intArrayOf(
                        workedDayHouses.sumOf { it.a1 },
                        workedDayHouses.sumOf { it.a2 },
                        workedDayHouses.sumOf { it.b },
                        workedDayHouses.sumOf { it.c },
                        workedDayHouses.sumOf { it.d1 },
                        workedDayHouses.sumOf { it.d2 },
                        workedDayHouses.sumOf { it.e }
                    )
                    val dayTotalDeps = dists.sum()
                    val elim = workedDayHouses.sumOf { it.eliminados }
                    val larv = workedDayHouses.sumOf { it.larvicida }
                    
                // formatDouble and dsh are now shared helpers below

                    drawCell(canvas, linePaint, textPaint, dsh(res), tx, cursorY, colRes, rowH); tx += colRes
                    drawCell(canvas, linePaint, textPaint, dsh(com), tx, cursorY, colCom, rowH); tx += colCom
                    drawCell(canvas, linePaint, textPaint, dsh(tb), tx, cursorY, colTB, rowH); tx += colTB
                    drawCell(canvas, linePaint, textPaint, dsh(out), tx, cursorY, colOut, rowH); tx += colOut
                    drawCell(canvas, linePaint, textPaint, dsh(pe), tx, cursorY, colPE, rowH); tx += colPE
                    drawCell(canvas, linePaint, boldPaint, dsh(dayTotalVisits), tx, cursorY, colTotalVisits, rowH); tx += colTotalVisits
                    
                    drawCell(canvas, linePaint, textPaint, dsh(fec), tx, cursorY, colFEC, rowH); tx += colFEC
                    drawCell(canvas, linePaint, textPaint, dsh(rec), tx, cursorY, colREC, rowH); tx += colREC
                    drawCell(canvas, linePaint, textPaint, dsh(recup), tx, cursorY, colRecup, rowH); tx += colRecup
                    drawCell(canvas, linePaint, textPaint, dsh(samples), tx, cursorY, colAmostras, rowH); tx += colAmostras
                    
                    for (i in 0..6) {
                        drawCell(canvas, linePaint, textPaint, dsh(dists[i]), tx, cursorY, colDep, rowH)
                        tx += colDep
                        totDeps[i] += dists[i]
                    }
                    
                    val dayCompletedBlocks = allBlockCompletions.filter { it.second == date }
                        .map { it.first }
                        .distinct()
                        .sorted()
                    
                    val blocksStr = if (dayCompletedBlocks.isEmpty()) dash else dayCompletedBlocks.joinToString("   ")
                    totCompletedBlocks.addAll(dayCompletedBlocks)

                    drawCell(canvas, linePaint, boldPaint, dsh(dayTotalDeps), tx, cursorY, colTotalDeps, rowH); tx += colTotalDeps
                    drawCell(canvas, linePaint, textPaint, dsh(elim), tx, cursorY, colElim, rowH); tx += colElim
                    drawCell(canvas, linePaint, textPaint, formatDouble(larv), tx, cursorY, colLarv, rowH); tx += colLarv
                    
                    // Draw Blocks with auto-scale
                    if (blocksStr != dash) {
                        val availableW = colQuart - 4f
                        var currentTextSize = 8f
                        var fitPaint = Paint(textPaint).apply { textSize = currentTextSize }
                        while (fitPaint.measureText(blocksStr) > availableW && currentTextSize > 4f) {
                            currentTextSize -= 0.5f
                            fitPaint = Paint(textPaint).apply { textSize = currentTextSize }
                        }
                        drawCell(canvas, linePaint, fitPaint, blocksStr, tx, cursorY, colQuart, rowH)
                    } else {
                        drawCell(canvas, linePaint, textPaint, blocksStr, tx, cursorY, colQuart, rowH)
                    }
                    
                    // Neighborhoods for completed blocks
                    val concludedBairrosToday = dayHouses.filter { h -> 
                        val matchId = if (h.blockSequence.isNotBlank()) "${h.blockNumber}/${h.blockSequence}" else h.blockNumber
                        dayCompletedBlocks.contains(matchId)
                    }.map { it.bairro.trim().uppercase() }.distinct().sorted()

                    if (concludedBairrosToday.isNotEmpty()) {
                        val labelPaint = Paint(textPaint).apply { textSize = 7f }
                        val labelX = MARGIN_LEFT + tableWidth + 5f
                        if (concludedBairrosToday.size == 1) {
                            canvas.drawText(concludedBairrosToday[0], labelX, cursorY + (rowH / 2f) + (labelPaint.textSize / 2f) - 1f, labelPaint)
                        } else {
                             if (concludedBairrosToday.size > 2) {
                                 canvas.drawText(concludedBairrosToday[0], labelX, cursorY + 6.5f, labelPaint)
                                 canvas.drawText(concludedBairrosToday[1] + "...", labelX, cursorY + 13.5f, labelPaint)
                             } else {
                                 concludedBairrosToday.forEachIndexed { index, name ->
                                     val yPos = if (index == 0) cursorY + 6.5f else cursorY + 13.5f
                                     canvas.drawText(name, labelX, yPos, labelPaint)
                                 }
                             }
                        }
                    }
                    
                    // Accumulate totals
                    totRes += res; totCom += com; totTB += tb; totOut += out; totPE += pe; totVisits += dayTotalVisits
                    totFEC += fec; totREC += rec; totRecup += recup
                    totAmostras += samples
                    totTotalDeps += dayTotalDeps
                    totElim += elim
                    totLarv += larv
                }
            }
            cursorY += rowH
        }

        // --- Totals Row ---
        tx = MARGIN_LEFT
        drawRectBox(canvas, tx, cursorY, colData, rowH, "TOTAIS", boldPaint, headerBgPaint)
        tx += colData
        
        // dshT and formatDouble are now shared helpers below

        drawRectBox(canvas, tx, cursorY, colRes, rowH, dsh(totRes), boldPaint, headerBgPaint); tx += colRes
        drawRectBox(canvas, tx, cursorY, colCom, rowH, dsh(totCom), boldPaint, headerBgPaint); tx += colCom
        drawRectBox(canvas, tx, cursorY, colTB, rowH, dsh(totTB), boldPaint, headerBgPaint); tx += colTB
        drawRectBox(canvas, tx, cursorY, colOut, rowH, dsh(totOut), boldPaint, headerBgPaint); tx += colOut
        drawRectBox(canvas, tx, cursorY, colPE, rowH, dsh(totPE), boldPaint, headerBgPaint); tx += colPE
        drawRectBox(canvas, tx, cursorY, colTotalVisits, rowH, dsh(totVisits), boldPaint, headerBgPaint); tx += colTotalVisits
        
        drawRectBox(canvas, tx, cursorY, colFEC, rowH, dsh(totFEC), boldPaint, headerBgPaint); tx += colFEC
        drawRectBox(canvas, tx, cursorY, colREC, rowH, dsh(totREC), boldPaint, headerBgPaint); tx += colREC
        drawRectBox(canvas, tx, cursorY, colRecup, rowH, dsh(totRecup), boldPaint, headerBgPaint); tx += colRecup
        
        drawRectBox(canvas, tx, cursorY, colAmostras, rowH, dsh(totAmostras), boldPaint, headerBgPaint); tx += colAmostras
        
        for (i in 0..6) {
            drawRectBox(canvas, tx, cursorY, colDep, rowH, dsh(totDeps[i]), boldPaint, headerBgPaint)
            tx += colDep
        }
        
        drawRectBox(canvas, tx, cursorY, colTotalDeps, rowH, dsh(totTotalDeps), boldPaint, headerBgPaint); tx += colTotalDeps
        drawRectBox(canvas, tx, cursorY, colElim, rowH, dsh(totElim), boldPaint, headerBgPaint); tx += colElim
        drawRectBox(canvas, tx, cursorY, colLarv, rowH, formatDouble(totLarv), boldPaint, headerBgPaint); tx += colLarv
        val totBlocksStr = if (totCompletedBlocks.isEmpty()) dash else totCompletedBlocks.sorted().joinToString("   ")
        drawRectBox(canvas, tx, cursorY, colQuart, rowH, totBlocksStr, boldPaint, headerBgPaint)
        
        cursorY += rowH + 5f
        drawTextInRect(canvas, textPaint, "Resumo Semanal dos Agentes / SMS / Município de Bom Jardim / PMCD", MARGIN_LEFT, cursorY, PAGE_WIDTH.toFloat(), 10f, alignLeft = true)

        // --- Footer Section ---
        cursorY += 50f
        
        val footerDate = if (weekDates.isNotEmpty()) weekDates.last().replace("-", "/") else ""
        val cityLabel = "Bom Jardim, "
        val cityLabelW = textPaint.measureText(cityLabel)
        canvas.drawText(cityLabel, MARGIN_LEFT + 10f, cursorY, textPaint)
        
        val dateStartX = MARGIN_LEFT + 10f + cityLabelW
        val dateEndX = dateStartX + 120f
        val valPaint = Paint(textPaint).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = 11f }
        canvas.drawText(footerDate, dateStartX + (120f - textPaint.measureText(footerDate)) / 2f, cursorY - 2, valPaint)
        canvas.drawLine(dateStartX, cursorY + 2, dateEndX, cursorY + 2, linePaint)
        
        val agentLabel = "Agente: "
        val agentLabelW = textPaint.measureText(agentLabel)
        val agentLineW = 160f // Halved width (from 320f)
        val agentStartX = MARGIN_LEFT + tableWidth - agentLineW
        canvas.drawText(agentLabel, agentStartX - agentLabelW - 5f, cursorY, textPaint)
        canvas.drawLine(agentStartX, cursorY + 2, agentStartX + agentLineW, cursorY + 2, linePaint)
        
        if (agentName.isNotBlank()) {
            val namePaint = Paint(boldPaint).apply { 
                textSize = 10f // Matching Boletim
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            // Left-aligned on the line as in Boletim
            canvas.drawText(agentName, agentStartX + 5f, cursorY - 2f, namePaint)
        }
}

    // --- Helper Methods (Similar to BoletimPdfGenerator) ---

    private fun drawRectBox(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, text: String, paint: Paint, bgPaint: Paint?, alignLeft: Boolean = false) {
        if (bgPaint != null) {
            canvas.drawRect(x, y, x + w, y + h, bgPaint)
        }
        canvas.drawRect(x, y, x + w, y + h, Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f })
        if (text.isNotBlank()) {
            drawCenteredText(canvas, paint, text, x, y, w, h, alignLeft)
        }
    }

    private fun drawMetaField(canvas: Canvas, paint: Paint, linePaint: Paint, label: String, value: String, x: Float, y: Float, w: Float, h: Float, centerValue: Boolean = false) {
        canvas.drawText(label, x, y + h - 5, paint)
        val labelW = paint.measureText(label) + 5f
        canvas.drawLine(x + labelW, y + h - 5, x + w, y + h - 5, linePaint)
        val valPaint = Paint(paint).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = 10f }
        
        if (centerValue) {
            val valW = valPaint.measureText(value)
            canvas.drawText(value, x + labelW + (w - labelW - valW) / 2f, y + h - 7, valPaint)
        } else {
            canvas.drawText(value, x + labelW + 5f, y + h - 7, valPaint)
        }
    }

    private fun drawMetaFieldSegmented(
        canvas: Canvas, paint: Paint, linePaint: Paint, label: String, values: List<String>, widths: List<Float>, 
        x: Float, y: Float, h: Float, isSemana: Boolean = false
    ) {
        val valPaint = Paint(paint).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = 10f }
        canvas.drawText(label, x, y + h - 5, paint)
        var curX = x + paint.measureText(label) + 5f
        
        values.forEachIndexed { i, value ->
            val w = widths[i]
            canvas.drawLine(curX, y + h - 5, curX + w, y + h - 5, linePaint)
            if (value.isNotBlank()) {
                val valW = valPaint.measureText(value)
                canvas.drawText(value, curX + (w - valW) / 2f, y + h - 7, valPaint)
            }
            curX += w
            
            if (isSemana) {
                if (i == 0 || i == 2) {
                    curX += 4f
                    canvas.drawText("/", curX, y + h - 5, paint)
                    curX += 8f
                } else if (i == 1) {
                    curX += 12f
                    canvas.drawText("a", curX, y + h - 5, paint)
                    curX += 18f
                }
            } else {
                if (i < values.size - 1) {
                    curX += 8f
                    canvas.drawText("/", curX, y + h - 5, paint)
                    curX += 12f
                }
            }
        }
    }

    private fun drawVerticalHeader(canvas: Canvas, linePaint: Paint, textPaint: Paint, bgPaint: Paint?, label: String, x: Float, y: Float, w: Float, h: Float) {
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

    private fun drawVerticalTextInBox(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float) {
        canvas.drawRect(x, y, x + w, y + h, Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f })
        val centerX = x + w / 2f
        val centerY = y + h / 2f
        canvas.withRotation(-90f, centerX, centerY) {
            val textW = paint.measureText(text)
            drawText(text, centerX - textW / 2f, centerY + paint.textSize / 3f, paint)
        }
    }

    private fun drawCell(canvas: Canvas, linePaint: Paint, textPaint: Paint, text: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
        canvas.drawRect(x, y, x + w, y + h, linePaint)
        if (text.isNotBlank()) {
            drawCenteredText(canvas, textPaint, text, x, y, w, h, alignLeft)
        }
    }

    private fun drawCenteredText(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val textX = if (alignLeft) x + 5f else x + (w - bounds.width()) / 2f
        
        // Use font metrics for consistent vertical alignment
        val textY = y + h / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, textX, textY, paint)
    }

    private fun drawTextInRect(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, w: Float, h: Float, alignLeft: Boolean = false) {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val textX = if (alignLeft) x else x + (w - bounds.width()) / 2f
        
        // Use font metrics for consistent vertical alignment
        val textY = y + h / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, textX, textY, paint)
    }

    private fun calculateCiclo(date: String): String {
        try {
            val parts = date.split("-")
            if (parts.size == 3) {
                val month = parts[1].toInt()
                val cicloNum = ((month - 1) / 2) + 1
                return "${cicloNum}º"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    private val DASH = "—"

    private fun dsh(v: Int): String = if (v == 0) DASH else v.toString()

    private fun formatDouble(v: Double): String {
        if (v == 0.0) return DASH
        return if (v % 1.0 == 0.0) v.toInt().toString() 
        else String.format(java.util.Locale("pt", "BR"), "%.1f", v)
    }
}
