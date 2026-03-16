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
    val dayActivities: List<DayActivity> = emptyList()
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
                        val backupData: BackupData = gson.fromJson(reader, type)
                        
                        // Sanitize with null safety (JSON might have missing fields)
                        val rawHouses = backupData.houses ?: emptyList()
                        val rawActivities = backupData.dayActivities ?: emptyList()
                        
                        val sanitizedHouses = sanitizeHouses(rawHouses)
                        val sanitizedActivities = sanitizeActivities(rawActivities)
                        
                        BackupData(sanitizedHouses, sanitizedActivities)
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
        val mappedHouses = houses.map { house ->
            house.copy(
                agentName = house.agentName?.trim()?.uppercase() ?: "",
                municipio = house.municipio?.trim()?.uppercase() ?: "BOM JARDIM",
                bairro = house.bairro?.trim()?.uppercase() ?: "",
                blockNumber = house.blockNumber?.trim() ?: "",
                blockSequence = house.blockSequence?.trim() ?: "",
                streetName = house.streetName?.trim() ?: "",
                number = house.number?.trim() ?: "",
                sequence = house.sequence ?: 0,
                complement = house.complement ?: 0,
                data = house.data?.replace("/", "-")?.trim() ?: "",
                listOrder = house.listOrder,
                // Legacy Support: If createdAt is missing or 0, use listOrder as a stable differentiator 
                // for this specific backup file. This preserves collisions that were separate in the JSON.
                createdAt = if (house.createdAt == 0L) house.listOrder else house.createdAt,
                ciclo = house.ciclo?.trim() ?: "1º",
                categoria = house.categoria?.trim() ?: "BRR",
                zona = house.zona?.trim() ?: "URB",
                tipo = house.tipo
            )
        }
        return com.antigravity.healthagent.utils.HouseNormalizationUtils.normalizeHouses(mappedHouses)
    }

    private fun sanitizeActivities(activities: List<DayActivity>): List<DayActivity> {
        return activities.map { activity ->
            activity.copy(
                agentName = activity.agentName?.trim()?.uppercase() ?: "",
                date = activity.date?.replace("/", "-")?.trim() ?: "",
                status = activity.status?.trim()?.uppercase() ?: "NORMAL",
                isClosed = activity.isClosed
            )
        }
    }
}
