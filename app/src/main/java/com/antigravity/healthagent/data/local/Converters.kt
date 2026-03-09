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
}
