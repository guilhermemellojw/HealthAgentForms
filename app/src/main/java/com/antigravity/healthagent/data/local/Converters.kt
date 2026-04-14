package com.antigravity.healthagent.data.local

import androidx.room.TypeConverter
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.local.model.Treatment

class Converters {
    @TypeConverter
    fun fromPropertyType(value: PropertyType): String = value.name

    @TypeConverter
    fun toPropertyType(value: String): PropertyType {
        return try {
            PropertyType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            PropertyType.values().find { it.code == value } ?: PropertyType.EMPTY
        }
    }

    @TypeConverter
    fun fromSituation(value: Situation): String = value.name

    @TypeConverter
    fun toSituation(value: String): Situation {
        return try {
            Situation.valueOf(value)
        } catch (e: IllegalArgumentException) {
            Situation.values().find { it.code == value } ?: Situation.EMPTY
        }
    }

    @TypeConverter
    fun fromTreatment(value: Treatment?): String? = value?.name

    @TypeConverter
    fun toTreatment(value: String?): Treatment? {
        if (value == null) return null
        return try {
            Treatment.valueOf(value)
        } catch (e: IllegalArgumentException) {
            Treatment.values().find { it.code == value }
        }
    }

    @TypeConverter
    fun fromTombstoneType(value: com.antigravity.healthagent.data.local.model.TombstoneType): String = value.name

    @TypeConverter
    fun toTombstoneType(value: String): com.antigravity.healthagent.data.local.model.TombstoneType {
        return try {
            com.antigravity.healthagent.data.local.model.TombstoneType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            com.antigravity.healthagent.data.local.model.TombstoneType.HOUSE
        }
    }

    private val gson = com.google.gson.Gson()

    @TypeConverter
    fun fromMap(value: Map<String, Int>): String = gson.toJson(value)

    @TypeConverter
    fun toMap(value: String): Map<String, Int> {
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Int>>() {}.type
        return gson.fromJson(value, type) ?: emptyMap()
    }
}
