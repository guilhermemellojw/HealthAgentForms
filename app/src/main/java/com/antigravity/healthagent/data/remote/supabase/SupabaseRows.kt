package com.antigravity.healthagent.data.remote.supabase

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Linha PostgREST (JsonObject) -> Map backend-neutro consumido pelos mappers
 * da F2.1 (mesma forma de DocumentSnapshot.data).
 */
fun JsonObject.toAnyMap(): Map<String, Any?> {
    return entries.associate { (k, v) -> k to v.toAny() }
}

fun JsonElement.toAny(): Any? {
    return when (this) {
        is JsonNull -> null
        is JsonObject -> toAnyMap()
        is JsonArray -> map { it.toAny() }
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> content
        }
    }
}
