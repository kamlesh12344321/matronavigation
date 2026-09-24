package com.example.gpslogger

import android.content.Context
import com.example.testgpslocation.utils.TelemetryLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory list of logged lines (newest first), mirrored to the log file. */
object LocationLog {
    private const val MAX_ITEMS = 1000
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    val latest = MutableStateFlow<String?>(null)
    val isLogging = MutableStateFlow(false)
    private var loaded = false

    /** Writes "time, latitude, longitude, accuracy" to the file and the list. Main thread. */
    fun add(context: Context, timestamp: Long, lat: Double, lon: Double, accuracy: Float) {
        val line = String.format(Locale.US, "%s, %.6f, %.6f, ±%.0f m", format.format(Date(timestamp)), lat, lon, accuracy)
        TelemetryLogger.appendLine(context, line)
        latest.value = line
        _lines.value = (listOf(line) + _lines.value).take(MAX_ITEMS)
    }

    /** Loads earlier lines from the file once per process (e.g. after the app was killed). */
    suspend fun loadFromFile(context: Context) {
        if (loaded) return
        loaded = true
        val fromFile = withContext(Dispatchers.IO) { TelemetryLogger.readRecentLines(context, MAX_ITEMS) }.asReversed()
        _lines.value = (_lines.value + fromFile).distinct().take(MAX_ITEMS)
    }

    fun clear(context: Context) {
        TelemetryLogger.clearLogs(context)
        _lines.value = emptyList()
        latest.value = null
    }
}
