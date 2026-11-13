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
 * Location, in order of preference (the folder is the app's name without spaces, e.g. MetroNavigator):
 *  - Android 10+: public Download/<AppName>/ via MediaStore (no permission needed)
 *  - Android 9 and below: public Download/<AppName>/ when WRITE_EXTERNAL_STORAGE is granted
 *  - otherwise (or when the above fails): the app-specific external files dir
 *
 * One file per logging session: [startSession] (when logging starts) opens
 * `<App>_telemetry_<yyyy-MM-dd_HH-mm-ss>.txt` and [endSession] (when it stops) closes it, so every
 * start gives a new file. Writes outside a session go to a daily `<App>_telemetry_<yyyy-MM-dd>.txt`.
 * All writes happen on a single background thread, in call order. A failed write never
 * throws; the reason is kept in [lastError] for the UI.
 */
object TelemetryLogger {
    private const val TAG = "TelemetryLogger"
    private const val LOG_FILE_PREFIX = "telemetry"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val sessionFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "telemetry-writer") }

    /** [key]: the session's start time, or the day for writes outside a session. */
    private class Target(val uri: Uri?, val file: File?, val description: String, val key: String)

    @Volatile
    private var target: Target? = null

    /** Start time of the current session (changed on the writer thread, in order with the writes). */
    @Volatile
    private var sessionKey: String? = null

    /** File name of the current logging session (null when not logging), for display. */
    @Volatile
    @JvmStatic
    var currentFileName: String? = null
        private set

    /** Last write failure (null after a successful write). */
    @Volatile
    @JvmStatic
    var lastError: String? = null
        private set

    private fun today(): String = synchronized(dayFormat) { dayFormat.format(Date()) }

    /** The app's name as shown on the phone ("Metro Navigator"). */
    private fun appLabel(context: Context): String = try {
        context.applicationInfo.loadLabel(context.packageManager).toString().takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    } ?: context.packageName.substringAfterLast('.')

    private fun fileName(context: Context, key: String): String =
        "${appLabel(context).replace(Regex("[^a-zA-Z0-9_.-]"), "_")}_${LOG_FILE_PREFIX}_$key.txt"

    /** Each app's own folder under Download: its name without spaces ("MetroNavigator"). */
    private fun folder(context: Context): String = appLabel(context).replace(Regex("[^a-zA-Z0-9]"), "")

    /** Folder the log files go to, for display. */
    @JvmStatic
    fun folderDescription(context: Context): String = "Download/${folder(context)}"

    /**
     * Starts a new log file for the writes that follow (lines already queued still go to the
     * previous file). Returns its name.
     */
    @JvmStatic
    fun startSession(context: Context): String {
        val stamp = synchronized(sessionFormat) { sessionFormat.format(Date()) }
        val name = fileName(context.applicationContext, stamp)
        currentFileName = name
        writer.execute { sessionKey = stamp; target = null }
        return name
    }

    /** Ends the session after the lines already queued (call after the last "stopped" line). */
    @JvmStatic
    fun endSession() {
        currentFileName = null
        writer.execute { sessionKey = null; target = null }
    }

    /** One position row: time, latitude, longitude and speed, nothing else. */
    @JvmStatic
    fun log(context: Context, timestamp: Long, latitude: Double, longitude: Double, speedMps: Double) {
        val appContext = context.applicationContext
        writer.execute {
            writeLine(appContext, String.format(Locale.US, "[%s] Lat: %.6f, Lon: %.6f | Speed: %.2f m/s",
                dateFormat.format(Date(timestamp)), latitude, longitude, speedMps))
        }
    }

    /** Appends [line] exactly as given (a newline is added). */
    @JvmStatic
    fun appendLine(context: Context, line: String) {
        val appContext = context.applicationContext
        writer.execute { writeLine(appContext, line) }
    }

    /** Truncates the current session's file (nothing outside a session). Runs after pending writes. */
    @JvmStatic
    fun clearLogs(context: Context, onDone: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext
        writer.execute {
            if (sessionKey == null) { onDone?.invoke(true); return@execute }
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
            if (t.uri == null && t.file == appSpecificFile(context, t.key)) {
                Log.e(TAG, "Write failed", e)
                lastError = "Cannot write log: ${e.message}"
                return
            }
            Log.w(TAG, "Write to ${t.description} failed, falling back to app storage", e)
            val fallback = appSpecificTarget(context, t.key)
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
        val key = sessionKey ?: today()
        target?.takeIf { it.key == key }?.let { return it }
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaStoreTarget(context, key) else legacyPublicTarget(context, key)
        } catch (e: Exception) {
            Log.w(TAG, "Public Download folder unavailable", e)
            null
        } ?: appSpecificTarget(context, key)
        target = resolved
        return resolved
    }

    private fun mediaStoreTarget(context: Context, key: String): Target? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val name = fileName(context, key)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/${folder(context)}/"
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
        return Target(uri, null, "Download/${folder(context)}/$name", key)
    }

    @Suppress("DEPRECATION")
    private fun legacyPublicTarget(context: Context, key: String): Target? {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return null
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder(context))
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, fileName(context, key))
        return Target(null, file, file.absolutePath, key)
    }

    private fun appSpecificFile(context: Context, key: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName(context, key))
    }

    private fun appSpecificTarget(context: Context, key: String): Target {
        val file = appSpecificFile(context, key)
        return Target(null, file, file.absolutePath, key)
    }
}
