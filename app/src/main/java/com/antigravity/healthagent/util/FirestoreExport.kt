package com.antigravity.healthagent.util

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileWriter

object FirestoreExport {
    private const val TAG = "FirestoreExport"
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun exportAll(context: Context) {
        val outputDir = File(context.filesDir, "firestore_export")
        if (!outputDir.exists()) outputDir.mkdirs()
        exportCollection("houses", outputDir, "cloud_houses.csv")
        exportCollection("day_activities", outputDir, "cloud_activities.csv")
        Log.i(TAG, "Export completed: ${outputDir.absolutePath}")
    }

    private suspend fun exportCollection(name: String, dir: File, fileName: String) {
        try {
            val snapshot = firestore.collection(name).get().await()
            if (snapshot.isEmpty) {
                Log.w(TAG, "Collection $name is empty")
                return
            }
            val csv = File(dir, fileName)
            FileWriter(csv).use { writer ->
                // header
                val headers = snapshot.documents.first().data?.keys?.joinToString(",") ?: ""
                writer.appendLine(headers)
                // rows
                for (doc in snapshot.documents) {
                    val row = doc.data?.values?.joinToString(",") { v ->
                        val s = v?.toString() ?: ""
                        if (s.contains(",") || s.contains('"')) "\"${s.replace("\"", "\\\"")}\"" else s
                    } ?: ""
                    writer.appendLine(row)
                }
            }
            Log.i(TAG, "Exported $name to ${csv.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed export $name: ${e.message}", e)
        }
    }
}
