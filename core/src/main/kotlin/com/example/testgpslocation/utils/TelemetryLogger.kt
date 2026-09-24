package com.example.testgpslocation.utils

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TelemetryLogger {
    private const val LOG_FILE_NAME = "telemetry_log.txt"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @JvmStatic
    fun getLogFile(context: Context): File {
        val appName = try {
            val label = context.applicationInfo.loadLabel(context.packageManager).toString()
            if (label.isNotBlank()) label.replace(Regex("[^a-zA-Z0-9_.-]"), "_") else context.packageName.substringAfterLast('.')
        } catch (_: Exception) {
            context.packageName.substringAfterLast('.')
        }

        val fileName = "${appName}_$LOG_FILE_NAME"

        // 1. Try public Download folder
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir != null) {
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }
                if (downloadDir.exists()) {
                    return File(downloadDir, fileName)
                }
            }
        } catch (_: Exception) {
        }

        // 2. Fallback to app's external files Download folder
        try {
            val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (extDir != null) {
                if (!extDir.exists()) {
                    extDir.mkdirs()
                }
                return File(extDir, fileName)
            }
        } catch (_: Exception) {
        }

        // 3. Fallback to internal filesDir
        return File(context.filesDir, fileName)
    }

    @JvmStatic
    @Synchronized
    fun log(
        context: Context,
        timestamp: Long,
        latitude: Double,
        longitude: Double,
        source: String, // "GPS" / "DR" / "Cell"
        speed: Float,
        gpsStatus: String, // "Acquired" / "Lost"
        networkStatus: String // "Connected" / "Lost"
    ) {
        val timeStr = dateFormat.format(Date(timestamp))
        val logEntry = String.format(
            Locale.US,
            "[%s] Lat: %.6f, Lon: %.6f | Source: %s | Speed: %.2f m/s | GPS: %s | Network: %s%n",
            timeStr, latitude, longitude, source, speed, gpsStatus, networkStatus
        )
        try {
            val file = getLogFile(context)
            FileWriter(file, true).use { writer ->
                writer.append(logEntry)
            }
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    @Synchronized
    fun log(
        context: Context,
        latitude: Double,
        longitude: Double,
        source: String,
        speed: Float,
        gpsStatus: String,
        networkStatus: String
    ) {
        log(context, System.currentTimeMillis(), latitude, longitude, source, speed, gpsStatus, networkStatus)
    }

    @JvmStatic
    @Synchronized
    fun logCustom(context: Context, message: String) {
        val timeStr = dateFormat.format(Date())
        val logEntry = "[$timeStr] $message%n"
        try {
            val file = getLogFile(context)
            FileWriter(file, true).use { writer ->
                writer.append(logEntry)
            }
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    @Synchronized
    fun clearLogs(context: Context): Boolean {
        try {
            val file = getLogFile(context)
            if (file.exists()) {
                return file.delete()
            }
        } catch (_: Exception) {
        }
        return false
    }

    @JvmStatic
    fun readLogs(context: Context): String {
        try {
            val file = getLogFile(context)
            if (file.exists()) {
                return file.readText()
            }
        } catch (_: Exception) {
        }
        return ""
    }
}
