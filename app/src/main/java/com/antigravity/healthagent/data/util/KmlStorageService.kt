package com.antigravity.healthagent.data.util

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KmlStorageService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun copyKmlToInternal(uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val internalFile = File(context.filesDir, "selected_map_layers.kml")
            
            FileOutputStream(internalFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            internalFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("KmlStorageService", "Error copying KML", e)
            null
        }
    }

    fun takePersistablePermission(uri: Uri) {
        try {
            val takeFlags: Int = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: SecurityException) {
            android.util.Log.e("KmlStorageService", "SecurityException taking permission", e)
        }
    }
}
