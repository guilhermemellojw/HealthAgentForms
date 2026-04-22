package com.antigravity.healthagent.data.backup
import android.content.Context
import android.net.Uri
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.google.gson.annotations.SerializedName
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject

data class BackupData(
    @SerializedName("houses")
    val houses: List<House>,
    @SerializedName("dayActivities", alternate = ["day_activities"])
    val dayActivities: List<DayActivity> = emptyList(),
    @SerializedName("sourceAgentUid")
    val sourceAgentUid: String? = null,
    @SerializedName("sourceAgentName")
    val sourceAgentName: String? = null
)

class BackupManager @Inject constructor() {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    val UTF8 = java.nio.charset.StandardCharsets.UTF_8

    fun exportData(context: Context, uri: Uri, data: BackupData) {
        try {
            val outputStream = context.contentResolver.openOutputStream(uri) 
                ?: throw java.io.IOException("Failed to open output stream for URI: $uri")

            outputStream.use { stream ->
                OutputStreamWriter(stream, UTF8).use { writer ->
                    gson.toJson(data, writer)
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e // Re-throw to be caught by ViewModel
        }
    }

    fun exportToFile(file: java.io.File, data: BackupData) {
        try {
            java.io.FileOutputStream(file).use { outputStream ->
                OutputStreamWriter(outputStream, UTF8).use { writer ->
                    gson.toJson(data, writer)
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    // Overload for backward compatibility (Exporting only houses - though we should migrate to BackupData everywhere)
    fun exportData(context: Context, uri: Uri, houses: List<House>) {
         exportData(context, uri, BackupData(houses, emptyList()))
    }

    fun importData(context: Context, uri: Uri): BackupData {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) 
                ?: throw java.io.IOException("Falha ao abrir o arquivo: $uri")

            inputStream.use { stream ->
                // Use PushbackReader to peek at the first char without consuming it
                val reader = java.io.PushbackReader(InputStreamReader(stream, UTF8), 1)
                
                // Skip whitespace to find start char
                var firstCharInt = reader.read()
                while (firstCharInt != -1 && Character.isWhitespace(firstCharInt)) {
                    firstCharInt = reader.read()
                }
                
                if (firstCharInt == -1) throw Exception("O arquivo de backup está vazio.")
                
                // Push back the non-whitespace char so Gson can read it
                reader.unread(firstCharInt)
                
                val firstChar = firstCharInt.toChar()

                return when (firstChar) {
                    '{' -> {
                        val type = object : TypeToken<BackupData>() {}.type
                        val backupData: BackupData? = gson.fromJson(reader, type)
                        
                        // Sanitize with null safety (JSON might have missing fields)
                        val rawHouses = backupData?.houses ?: emptyList()
                        val rawActivities = backupData?.dayActivities ?: emptyList()
                        
                        val sanitizedHouses = sanitizeHouses(rawHouses)
                        val sanitizedActivities = sanitizeActivities(rawActivities)
                        
                        BackupData(sanitizedHouses, sanitizedActivities, backupData?.sourceAgentUid, backupData?.sourceAgentName)
                    }
                    '[' -> {
                        val typeList = object : TypeToken<List<House>>() {}.type
                        val houses: List<House> = gson.fromJson(reader, typeList)
                        
                        val sanitizedHouses = sanitizeHouses(houses ?: emptyList())
                        
                        // Generate closed DayActivities for legacy data
                        val distinctDates = sanitizedHouses.map { it.data }.distinct()
                        val closedActivities = distinctDates.map { date ->
                            DayActivity(date, "NORMAL", true)
                        }
                        BackupData(sanitizedHouses, closedActivities)
                    }
                    else -> throw Exception("Formato de arquivo desconhecido. O arquivo deve começar com '{' ou '['.")
                }
            }
        } catch (e: JsonSyntaxException) {
            e.printStackTrace()
            throw Exception("Erro de formato no arquivo: JSON inválido.", e)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    private fun sanitizeHouses(houses: List<House>): List<House> {
        return houses.mapIndexed { index, house ->
            val stableOrder = if (house.listOrder == 0L) index.toLong() else house.listOrder
            
            // Bug Fix: Normalize Date Formats
            var normalizedData = try { house.data?.replace("/", "-")?.trim() ?: "" } catch(e: Exception) { "" }
            if (normalizedData.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                val parts = normalizedData.split("-")
                normalizedData = "${parts[2]}-${parts[1]}-${parts[0]}"
            }

            // CRITICAL SAFETY: GSON can result in null values for non-nullable Kotlin fields 
            // if fields are missing in JSON. We must check for null even if Kotlin says it's impossible.
            @Suppress("SENSELESS_COMPARISON")
            var finalSituation = if (house.situation == null) com.antigravity.healthagent.data.local.model.Situation.EMPTY else house.situation
            
            @Suppress("SENSELESS_COMPARISON")
            val finalPropertyType = if (house.propertyType == null) com.antigravity.healthagent.data.local.model.PropertyType.EMPTY else house.propertyType

            // Healing logic: If situation is EMPTY, force to NONE (Worked/Aberto)
            // Since these houses are part of a workday production list, they should at least be "Aberto".
            if (finalSituation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) {
                finalSituation = com.antigravity.healthagent.data.local.model.Situation.NONE
            }

            // Fallback for missing or malformed names
            @Suppress("SENSELESS_COMPARISON")
            val safeAgentName = if (house.agentName == null) "" else house.agentName.trim().uppercase()
            @Suppress("SENSELESS_COMPARISON")
            val safeStreet = if (house.streetName == null) "" else house.streetName.trim().uppercase()
            @Suppress("SENSELESS_COMPARISON")
            val safeNum = if (house.number == null || house.number.trim() == "0") "" else house.number.trim().uppercase()

            house.copy(
                agentName = safeAgentName,
                municipio = house.municipio?.trim()?.uppercase() ?: "BOM JARDIM",
                bairro = house.bairro?.trim()?.uppercase() ?: "",
                blockNumber = house.blockNumber?.trim() ?: "",
                blockSequence = house.blockSequence?.trim() ?: "",
                streetName = safeStreet,
                number = safeNum,
                sequence = house.sequence,
                complement = house.complement,
                data = normalizedData,
                listOrder = stableOrder,
                createdAt = if (house.createdAt == 0L) stableOrder else house.createdAt,
                ciclo = house.ciclo?.trim() ?: "1º",
                situation = finalSituation,
                propertyType = finalPropertyType,
                categoria = house.categoria?.trim() ?: "BRR",
                zona = house.zona?.trim() ?: "URB",
                tipo = house.tipo,
                isSynced = false,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    private fun sanitizeActivities(activities: List<DayActivity>): List<DayActivity> {
        return activities.map { activity ->
            var normalizedDate = try { activity.date?.replace("/", "-")?.trim() ?: "" } catch(e: Exception) { "" }
            if (normalizedDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                val parts = normalizedDate.split("-")
                normalizedDate = "${parts[2]}-${parts[1]}-${parts[0]}"
            }

            @Suppress("SENSELESS_COMPARISON")
            val safeAgentName = if (activity.agentName == null) "" else activity.agentName.trim().uppercase()
            @Suppress("SENSELESS_COMPARISON")
            val safeStatus = if (activity.status == null) "NORMAL" else activity.status.trim().uppercase()

            activity.copy(
                agentName = safeAgentName,
                date = normalizedDate,
                status = safeStatus,
                isClosed = activity.isClosed,
                isSynced = false,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }
}
