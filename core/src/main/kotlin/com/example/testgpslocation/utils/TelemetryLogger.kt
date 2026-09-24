package com.example.testgpslocation.utils

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Appends telemetry lines to a per-app text file.
 *
 * Location, in order of preference:
 *  - Android 10+: public Download/TestGPSLocation/ via MediaStore (no permission needed)
 *  - Android 9 and below: public Download/TestGPSLocation/ when WRITE_EXTERNAL_STORAGE is granted
 *  - otherwise (or when the above fails): the app-specific external files dir
 *
 * One file per day (`<App>_telemetry_<yyyy-MM-dd>.txt`) so files stay small and easy to find.
 * All writes happen on a single background thread, in call order. A failed write never
 * throws; the reason is kept in [lastError] for the UI.
 */
object TelemetryLogger {
    private const val TAG = "TelemetryLogger"
    private const val LOG_FILE_PREFIX = "telemetry"
    private const val PUBLIC_SUBDIR = "TestGPSLocation"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "telemetry-writer") }

    private class Target(val uri: Uri?, val file: File?, val description: String, val day: String)

    @Volatile
    private var target: Target? = null

    /** Last write failure (null after a successful write). */
    @Volatile
    @JvmStatic
    var lastError: String? = null
        private set

    private fun today(): String = synchronized(dayFormat) { dayFormat.format(Date()) }

    private fun fileName(context: Context, day: String): String {
        val appName = try {
            val label = context.applicationInfo.loadLabel(context.packageManager).toString()
            label.takeIf { it.isNotBlank() }?.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        } catch (_: Exception) {
            null
        } ?: context.packageName.substringAfterLast('.')
        return "${appName}_${LOG_FILE_PREFIX}_$day.txt"
    }

    /** Human readable location of the log file (for display). */
    @JvmStatic
    fun getLogLocation(context: Context): String = resolveTarget(context.applicationContext).description

    @JvmStatic
    fun log(
        context: Context,
        timestamp: Long,
        latitude: Double,
        longitude: Double,
        source: String, // "GPS" / "TRACK" / "DR"
        speed: Float,
        gpsStatus: String, // "Acquired" / "Lost"
        networkStatus: String, // "Connected" / "Weak" / "Lost"
        extra: String? = null // optional extra columns, appended as "| extra"
    ) {
        val appContext = context.applicationContext
        writer.execute {
            val line = String.format(
                Locale.US,
                "[%s] Lat: %.6f, Lon: %.6f | Source: %s | Speed: %.2f m/s | GPS: %s | Network: %s",
                dateFormat.format(Date(timestamp)), latitude, longitude, source, speed, gpsStatus, networkStatus
            ) + (extra?.let { " | $it" } ?: "")
            writeLine(appContext, line)
        }
    }

    @JvmStatic
    fun log(context: Context, latitude: Double, longitude: Double, source: String, speed: Float, gpsStatus: String, networkStatus: String) {
        log(context, System.currentTimeMillis(), latitude, longitude, source, speed, gpsStatus, networkStatus)
    }

    @JvmStatic
    fun logCustom(context: Context, message: String) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        writer.execute { writeLine(appContext, "[${dateFormat.format(Date(now))}] $message") }
    }

    /** Appends [line] exactly as given (a newline is added). */
    @JvmStatic
    fun appendLine(context: Context, line: String) {
        val appContext = context.applicationContext
        writer.execute { writeLine(appContext, line) }
    }

    /** Truncates the log. Runs on the writer thread, after any pending writes. */
    @JvmStatic
    fun clearLogs(context: Context, onDone: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext
        writer.execute {
            val ok = try {
                openOutput(appContext, resolveTarget(appContext), append = false)?.use { } != null
            } catch (e: Exception) {
                Log.w(TAG, "clearLogs failed", e)
                false
            }
            onDone?.invoke(ok)
        }
    }

    /** Reads the last [maxLines] lines. Blocking — call off the main thread. */
    @JvmStatic
    fun readRecentLines(context: Context, maxLines: Int): List<String> {
        val appContext = context.applicationContext
        return try {
            val t = resolveTarget(appContext)
            val input = when {
                t.uri != null -> appContext.contentResolver.openInputStream(t.uri)
                t.file != null && t.file.exists() -> t.file.inputStream()
                else -> null
            } ?: return emptyList()
            val deque = ArrayDeque<String>()
            input.bufferedReader().useLines { lines ->
                lines.forEach { if (it.isNotBlank()) { deque.addLast(it); if (deque.size > maxLines) deque.removeFirst() } }
            }
            deque.toList()
        } catch (e: Exception) {
            Log.w(TAG, "readRecentLines failed", e)
            emptyList()
        }
    }

    private fun writeLine(context: Context, line: String) {
        val bytes = (line + "\n").toByteArray()
        val t = resolveTarget(context)
        try {
            openOutput(context, t, append = true)?.use { it.write(bytes) } ?: throw IllegalStateException("no output stream")
            lastError = null
        } catch (e: Exception) {
            if (t.uri == null && t.file == appSpecificFile(context, t.day)) {
                Log.e(TAG, "Write failed", e)
                lastError = "Cannot write log: ${e.message}"
                return
            }
            Log.w(TAG, "Write to ${t.description} failed, falling back to app storage", e)
            val fallback = appSpecificTarget(context, t.day)
            target = fallback
            try {
                openOutput(context, fallback, append = true)?.use { it.write(bytes) }
                lastError = null
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback write failed", e2)
                lastError = "Cannot write log (storage full?): ${e2.message}"
            }
        }
    }

    private fun openOutput(context: Context, t: Target, append: Boolean): OutputStream? = when {
        t.uri != null -> context.contentResolver.openOutputStream(t.uri, if (append) "wa" else "wt")
        t.file != null -> FileOutputStream(t.file, append)
        else -> null
    }

    @Synchronized
    private fun resolveTarget(context: Context): Target {
        val day = today()
        target?.takeIf { it.day == day }?.let { return it }
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaStoreTarget(context, day) else legacyPublicTarget(context, day)
        } catch (e: Exception) {
            Log.w(TAG, "Public Download folder unavailable", e)
            null
        } ?: appSpecificTarget(context, day)
        target = resolved
        return resolved
    }

    private fun mediaStoreTarget(context: Context, day: String): Target? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val name = fileName(context, day)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBDIR/"
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver

        // Files this install created are visible to it without any permission.
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, relativePath),
            null
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null }

        val uri = existing ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }) ?: return null
        return Target(uri, null, "Download/$PUBLIC_SUBDIR/$name", day)
    }

    @Suppress("DEPRECATION")
    private fun legacyPublicTarget(context: Context, day: String): Target? {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return null
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, fileName(context, day))
        return Target(null, file, file.absolutePath, day)
    }

    private fun appSpecificFile(context: Context, day: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName(context, day))
    }

    private fun appSpecificTarget(context: Context, day: String): Target {
        val file = appSpecificFile(context, day)
        return Target(null, file, file.absolutePath, day)
    }
}
