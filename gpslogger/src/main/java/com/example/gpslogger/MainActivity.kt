package com.example.gpslogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.location.LocationManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.testgpslocation.utils.TelemetryLogger
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val adapter = LineAdapter()
    private lateinit var tvWarning: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvCurrent = findViewById<TextView>(R.id.tvCurrent)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvWarning = findViewById(R.id.tvWarning)
        tvWarning.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
        }
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val list = findViewById<RecyclerView>(R.id.list)
        val layout = LinearLayoutManager(this)
        list.layoutManager = layout
        list.adapter = adapter
        findViewById<TextView>(R.id.tvLogFilePath).text = "File: ${TelemetryLogger.getLogLocation(this)}"

        btnStart.setOnClickListener {
            if (hasLocationPermission()) {
                ContextCompat.startForegroundService(this, Intent(this, LoggerService::class.java))
            } else {
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
                requestPermissions()
            }
        }
        btnStop.setOnClickListener { stopService(Intent(this, LoggerService::class.java)) }
        findViewById<Button>(R.id.btnClear).setOnClickListener { LocationLog.clear(this) }

        lifecycleScope.launch {
            LocationLog.loadFromFile(this@MainActivity)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    LocationLog.lines.collect {
                        val atTop = layout.findFirstVisibleItemPosition() <= 0
                        adapter.submit(it)
                        if (atTop) list.scrollToPosition(0)
                    }
                }
                launch { LocationLog.latest.collect { tvCurrent.text = it ?: getString(R.string.no_fix) } }
                launch {
                    combine(LocationLog.isLogging, LocationLog.lines) { logging, lines -> logging to lines.size }.collect { (logging, count) ->
                        tvStatus.text = (if (logging) "Logging" else "Stopped") + " · $count points"
                        btnStart.isEnabled = !logging
                        btnStop.isEnabled = logging
                    }
                }
            }
        }

        if (!hasLocationPermission()) requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        tvWarning.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
        TelemetryLogger.lastError?.let { tvWarning.text = it; tvWarning.visibility = android.view.View.VISIBLE }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        requestPermissions(perms.toTypedArray(), 1)
    }

    private class LineAdapter : RecyclerView.Adapter<LineAdapter.Holder>() {
        private var items: List<String> = emptyList()

        fun submit(newItems: List<String>) {
            val added = newItems.size - items.size
            val prependedOnly = added > 0 && newItems.size > added && newItems[added] == items.firstOrNull()
            items = newItems
            if (prependedOnly) notifyItemRangeInserted(0, added) else @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_location, parent, false) as TextView)

        override fun onBindViewHolder(holder: Holder, position: Int) { holder.text.text = items[position] }

        override fun getItemCount() = items.size

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    }
}
