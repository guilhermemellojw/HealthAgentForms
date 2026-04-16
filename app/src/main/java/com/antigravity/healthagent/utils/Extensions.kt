package com.antigravity.healthagent.utils

import android.content.Context
import android.widget.Toast

fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

fun String.toDashDate(): String = this.replace("/", "-")
fun String.toSlashDate(): String = this.replace("-", "/")

/**
 * Executes a Room operation with a retry mechanism to handle [android.database.sqlite.SQLiteDatabaseLockedException]
 * which often occurs during concurrent sync and UI usage.
 */
suspend fun <T> androidx.room.RoomDatabase.withRetry(
    maxAttempts: Int = 3,
    initialDelay: Long = 100,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: android.database.sqlite.SQLiteDatabaseLockedException) {
            lastException = e
            kotlinx.coroutines.delay(initialDelay * (attempt + 1))
        } catch (e: Exception) {
            throw e
        }
    }
    throw lastException ?: Exception("Database operation failed after $maxAttempts attempts")
}
