package com.antigravity.healthagent.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object TimeManager {
    private const val PREFS_NAME = "TimeManagerPrefs"
    private const val KEY_OFFSET = "time_offset_ms"
    
    @Volatile
    private var offsetMs: Long = 0L

    /**
     * Initializes the offset from local storage.
     * Should be called synchronously in Application onCreate to ensure models get correct time.
     */
    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        offsetMs = prefs.getLong(KEY_OFFSET, 0L)
        Log.i("TimeManager", "Initialized with offset: ${offsetMs}ms")
    }

    /**
     * Returns the current time adjusted by the network offset.
     * Use this instead of System.currentTimeMillis() for data persistence.
     */
    fun currentTimeMillis(): Long {
        return System.currentTimeMillis() + offsetMs
    }

    /**
     * Fetches current network time via an HTTP HEAD request and updates the offset.
     * Lightweight and doesn't require NTP dependencies.
     */
    suspend fun synchronizeTime(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                // We use google.com as it's highly available and returns an accurate 'Date' header
                val url = URL("https://google.com")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                
                connection.connect()
                
                val dateHeader = connection.getHeaderField("Date")
                if (dateHeader != null) {
                    // Date header format: EEE, dd MMM yyyy HH:mm:ss z
                    val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                    format.timeZone = TimeZone.getTimeZone("GMT")
                    
                    val networkDate = format.parse(dateHeader)
                    if (networkDate != null) {
                        val networkTime = networkDate.time
                        val deviceTime = System.currentTimeMillis()
                        
                        val newOffset = networkTime - deviceTime
                        
                        // Only update if difference is significant (e.g. > 5 seconds) to avoid jitter
                        if (kotlin.math.abs(newOffset - offsetMs) > 5000) {
                            offsetMs = newOffset
                            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.edit().putLong(KEY_OFFSET, offsetMs).apply()
                            Log.i("TimeManager", "Time synchronized. New offset: ${offsetMs}ms")
                        } else {
                            Log.d("TimeManager", "Time is relatively accurate. Offset unchanged.")
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.w("TimeManager", "Failed to synchronize network time: ${e.message}")
            }
        }
    }
}
