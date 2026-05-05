package com.antigravity.healthagent.data.backup

import android.content.Context
import android.net.Uri
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
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
    val sourceAgentName: String? = null,
    @SerializedName("isComplete")
    val isComplete: Boolean = true,
    @Transient
    val isPartial: Boolean = false
)

class BackupManager @Inject constructor() {
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .setLenient()
        .serializeSpecialFloatingPointValues()
        // Legacy Compatibility Adapters
        .registerTypeAdapter(Boolean::class.java, SafeBooleanAdapter())
        .registerTypeAdapter(java.lang.Boolean::class.java, SafeBooleanAdapter())
        .registerTypeAdapter(Int::class.java, SafeIntAdapter())
        .registerTypeAdapter(java.lang.Integer::class.java, SafeIntAdapter())
        .registerTypeAdapter(Long::class.java, SafeLongAdapter())
        .registerTypeAdapter(java.lang.Long::class.java, SafeLongAdapter())
        .registerTypeAdapter(Double::class.java, SafeDoubleAdapter())
        .registerTypeAdapter(java.lang.Double::class.java, SafeDoubleAdapter())
        .create()

    val UTF8 = java.nio.charset.StandardCharsets.UTF_8

    fun exportData(context: Context, uri: Uri, data: BackupData) {
        val tempFile = java.io.File(context.cacheDir, "backup_export_temp.json")
        try {
            // 1. Serialize to a temporary file first
            java.io.FileOutputStream(tempFile).use { outputStream ->
                OutputStreamWriter(outputStream, UTF8).use { writer ->
                    gson.toJson(data.copy(isComplete = true), writer)
                    writer.flush()
                }
            }

            // 2. Only if successful, copy to the final destination URI
            val outputStream = context.contentResolver.openOutputStream(uri) 
                ?: throw java.io.IOException("Failed to open output stream for URI: $uri")

            outputStream.use { stream ->
                tempFile.inputStream().use { input ->
                    input.copyTo(stream)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e 
        } finally {
            if (tempFile.exists()) try { tempFile.delete() } catch(e: Exception) {}
        }
    }

    fun exportToFile(file: java.io.File, data: BackupData) {
        val tempFile = java.io.File(file.parent, "${file.name}.tmp")
        try {
            java.io.FileOutputStream(tempFile).use { outputStream ->
                OutputStreamWriter(outputStream, UTF8).use { writer ->
                    gson.toJson(data.copy(isComplete = true), writer)
                    writer.flush()
                }
            }
            if (file.exists()) file.delete()
            tempFile.renameTo(file)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        } finally {
            if (tempFile.exists()) try { tempFile.delete() } catch(e: Exception) {}
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

            return inputStream.use { stream ->
                // 1. Detect and skip BOM via PushbackInputStream
                val pushbackStream = java.io.PushbackInputStream(stream, 3)
                val bom = ByteArray(3)
                val readCount = pushbackStream.read(bom, 0, 3)
                
                if (readCount == -1) throw Exception("O arquivo de backup está vazio.")

                var charset = java.nio.charset.StandardCharsets.UTF_8
                if (readCount >= 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte()) {
                    // UTF-8 BOM, do not unread these 3 bytes
                } else if (readCount >= 2 && bom[0] == 0xFE.toByte() && bom[1] == 0xFF.toByte()) {
                    charset = java.nio.charset.StandardCharsets.UTF_16BE
                    if (readCount == 3) pushbackStream.unread(bom[2].toInt())
                } else if (readCount >= 2 && bom[0] == 0xFF.toByte() && bom[1] == 0xFE.toByte()) {
                    charset = java.nio.charset.StandardCharsets.UTF_16LE
                    if (readCount == 3) pushbackStream.unread(bom[2].toInt())
                } else if (readCount > 0) {
                    pushbackStream.unread(bom, 0, readCount)
                }

                // 2. Wrap in InputStreamReader. We will use default replacement char for invalid UTF-8
                // which is fine since we are looking for JSON structure, and GSON handles it safely.
                val baseReader = java.io.InputStreamReader(pushbackStream, charset)
                
                // 3. Skip leading garbage using PushbackReader
                val pushbackReader = java.io.PushbackReader(baseReader, 1)
                var firstChar = ' '
                while (true) {
                    val firstCharInt = pushbackReader.read()
                    if (firstCharInt == -1) {
                        throw Exception("Formato de arquivo inválido. O arquivo deve conter um objeto '{' ou lista '['.")
                    }
                    val c = firstCharInt.toChar()
                    if (c == '{' || c == '[') {
                        firstChar = c
                        pushbackReader.unread(firstCharInt)
                        break
                    }
                }

                // 4. Parse using a resilient, item-by-item approach
                val jsonReader = JsonReader(pushbackReader)
                jsonReader.isLenient = true

                var parsedHouses = mutableListOf<House>()
                var parsedActivities = mutableListOf<DayActivity>()
                var sourceAgentUid: String? = null
                var sourceAgentName: String? = null
                var isCompleteInFile = false
                var wasTruncated = false

                try {
                    if (firstChar == '{') {
                        jsonReader.beginObject()
                        while (jsonReader.hasNext()) {
                            val name = try { jsonReader.nextName() } catch (e: Exception) { 
                                android.util.Log.e("BackupManager", "JSON Truncated at root name: ${e.message}")
                                wasTruncated = true
                                break 
                            }
                            android.util.Log.i("BackupManager", "Importing section: $name")
                            when (name) {
                                "houses" -> {
                                    jsonReader.beginArray()
                                    try {
                                        while (jsonReader.hasNext()) {
                                            val house: House = gson.fromJson(jsonReader, House::class.java)
                                            parsedHouses.add(house)
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("BackupManager", "JSON Truncated in houses array: ${e.message}")
                                        wasTruncated = true
                                    }
                                    try { if (jsonReader.peek() != JsonToken.END_ARRAY) wasTruncated = true else jsonReader.endArray() } catch (e: Exception) { wasTruncated = true }
                                }
                                "dayActivities", "day_activities" -> {
                                    jsonReader.beginArray()
                                    try {
                                        while (jsonReader.hasNext()) {
                                            val activity: DayActivity = gson.fromJson(jsonReader, DayActivity::class.java)
                                            parsedActivities.add(activity)
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("BackupManager", "JSON Truncated in activities array: ${e.message}")
                                        wasTruncated = true
                                    }
                                    try { if (jsonReader.peek() != JsonToken.END_ARRAY) wasTruncated = true else jsonReader.endArray() } catch (e: Exception) { wasTruncated = true }
                                }
                                "sourceAgentUid" -> sourceAgentUid = try { jsonReader.nextString() } catch(e: Exception) { wasTruncated = true; null }
                                "sourceAgentName" -> sourceAgentName = try { jsonReader.nextString() } catch(e: Exception) { wasTruncated = true; null }
                                "isComplete" -> isCompleteInFile = try { jsonReader.nextBoolean() } catch(e: Exception) { wasTruncated = true; false }
                                else -> try { jsonReader.skipValue() } catch(e: Exception) { wasTruncated = true }
                            }
                        }
                        try { jsonReader.endObject() } catch (e: Exception) { wasTruncated = true }
                    } else if (firstChar == '[') {
                        jsonReader.beginArray()
                        try {
                            while (jsonReader.hasNext()) {
                                val house: House = gson.fromJson(jsonReader, House::class.java)
                                parsedHouses.add(house)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("BackupManager", "JSON Truncated in legacy houses array: ${e.message}")
                            wasTruncated = true
                        }
                        try { if (jsonReader.peek() != JsonToken.END_ARRAY) wasTruncated = true else jsonReader.endArray() } catch (e: Exception) { wasTruncated = true }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    wasTruncated = true
                }

                if (parsedHouses.isEmpty() && parsedActivities.isEmpty() && wasTruncated) {
                    throw Exception("O arquivo de backup está corrompido e nenhum dado pôde ser recuperado.")
                }

                val sanitizedHouses = sanitizeHouses(parsedHouses)
                val sanitizedActivities = sanitizeActivities(parsedActivities)

                if (firstChar == '{') {
                    BackupData(sanitizedHouses, sanitizedActivities, sourceAgentUid, sourceAgentName, isCompleteInFile, wasTruncated)
                } else {
                    val distinctDates = sanitizedHouses.map { it.data }.distinct()
                    val closedActivities = distinctDates.map { date ->
                        DayActivity(date, "NORMAL", true)
                    }
                    BackupData(sanitizedHouses, closedActivities, isPartial = wasTruncated)
                }
            }
        } catch (e: Exception) {
            if (e is java.io.IOException || e.message?.contains("corrompido") == true || e.message?.contains("inválido") == true) {
                throw e
            }
            e.printStackTrace()
            val errorMessage = e.message ?: "Erro desconhecido"
            throw Exception("Erro de formato no arquivo: JSON inválido.\nDetalhes: $errorMessage", e)
        }
    }

    private fun sanitizeHouses(houses: List<House>): List<House> {
        return houses.mapIndexed { index, house ->
            @Suppress("SENSELESS_COMPARISON")
            val stableOrder = if (house == null || house.listOrder == null || house.listOrder == 0L) index.toLong() else house.listOrder
            
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
            if (finalSituation == com.antigravity.healthagent.data.local.model.Situation.EMPTY) {
                finalSituation = com.antigravity.healthagent.data.local.model.Situation.NONE
            }

            // Fallback for missing or malformed names
            @Suppress("SENSELESS_COMPARISON")
            val safeAgentName = if (house.agentName == null) "" else house.agentName.trim().uppercase()
            @Suppress("SENSELESS_COMPARISON")
            val safeAgentUid = if (house.agentUid == null) "" else house.agentUid.trim()
            @Suppress("SENSELESS_COMPARISON")
            val safeStreet = if (house.streetName == null) "" else house.streetName.trim().uppercase()
            @Suppress("SENSELESS_COMPARISON")
            val safeNum = if (house.number == null || house.number.trim() == "0") "" else house.number.trim().uppercase()

            house.copy(
                agentName = safeAgentName,
                agentUid = safeAgentUid,
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
                lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
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
            val safeAgentUid = if (activity.agentUid == null) "" else activity.agentUid.trim()
            
            // Fix for blank or null status
            @Suppress("SENSELESS_COMPARISON")
            val rawStatus = if (activity.status == null) "" else activity.status.trim().uppercase()
            val safeStatus = if (rawStatus.isBlank()) "NORMAL" else rawStatus

            activity.copy(
                agentName = safeAgentName,
                agentUid = safeAgentUid,
                date = normalizedDate,
                status = safeStatus,
                isClosed = activity.isClosed,
                isManualUnlock = activity.isManualUnlock,
                editedByAdmin = activity.editedByAdmin,
                isSynced = false,
                lastUpdated = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
            )
        }
    }

    // --- Custom Type Adapters for Legacy Compatibility ---

    private class SafeBooleanAdapter : TypeAdapter<Boolean>() {
        override fun write(out: JsonWriter, value: Boolean?) {
            out.value(value)
        }
        override fun read(reader: JsonReader): Boolean? {
            return when (reader.peek()) {
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NUMBER -> reader.nextInt() != 0
                JsonToken.STRING -> {
                    val s = reader.nextString().lowercase()
                    s == "true" || s == "1"
                }
                JsonToken.NULL -> {
                    reader.nextNull()
                    false
                }
                else -> {
                    reader.skipValue()
                    false
                }
            }
        }
    }

    private class SafeIntAdapter : TypeAdapter<Int>() {
        override fun write(out: JsonWriter, value: Int?) { out.value(value) }
        override fun read(reader: JsonReader): Int? {
            return when (reader.peek()) {
                JsonToken.NUMBER -> reader.nextInt()
                JsonToken.BOOLEAN -> if (reader.nextBoolean()) 1 else 0
                JsonToken.STRING -> {
                    val s = reader.nextString()
                    if (s.isEmpty()) 0 else try { s.toDouble().toInt() } catch(e: Exception) { 0 }
                }
                JsonToken.NULL -> { reader.nextNull(); 0 }
                else -> { reader.skipValue(); 0 }
            }
        }
    }

    private class SafeLongAdapter : TypeAdapter<Long>() {
        override fun write(out: JsonWriter, value: Long?) { out.value(value) }
        override fun read(reader: JsonReader): Long? {
            return when (reader.peek()) {
                JsonToken.NUMBER -> reader.nextLong()
                JsonToken.STRING -> {
                    val s = reader.nextString()
                    if (s.isEmpty()) 0L else try { s.toDouble().toLong() } catch(e: Exception) { 0L }
                }
                JsonToken.NULL -> { reader.nextNull(); 0L }
                else -> { reader.skipValue(); 0L }
            }
        }
    }

    private class SafeDoubleAdapter : TypeAdapter<Double>() {
        override fun write(out: JsonWriter, value: Double?) { out.value(value) }
        override fun read(reader: JsonReader): Double? {
            return when (reader.peek()) {
                JsonToken.NUMBER -> reader.nextDouble()
                JsonToken.STRING -> {
                    val s = reader.nextString()
                    if (s.isEmpty() || s == "NaN" || s == "Infinity") 0.0 else try { s.toDouble() } catch(e: Exception) { 0.0 }
                }
                JsonToken.NULL -> { reader.nextNull(); 0.0 }
                else -> { reader.skipValue(); 0.0 }
            }
        }
    }
}
