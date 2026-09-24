package com.example.trainbox.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class SpatialiteDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "SpatialiteDbHelper"
        private const val DATABASE_NAME = "train_telemetry.sqlite"
        private const val DATABASE_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "Initializing SpatiaLite database schema...")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS train_telemetry (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                speed REAL NOT NULL,
                is_station_stop INTEGER NOT NULL
            )
        """)
        try {
            db.execSQL("SELECT InitSpatialMetadata(1);")
        } catch (e: Exception) {
            Log.w(TAG, "Spatial metadata initialization stub note: ${e.message}")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS train_telemetry")
        onCreate(db)
    }

    fun insertTelemetry(timestamp: Long, lat: Double, lon: Double, speed: Double, isStationStop: Boolean) {
        val db = writableDatabase
        db.execSQL(
            "INSERT INTO train_telemetry (timestamp, latitude, longitude, speed, is_station_stop) VALUES (?, ?, ?, ?, ?)",
            arrayOf(timestamp, lat, lon, speed, if (isStationStop) 1 else 0)
        )
    }

    fun getTelemetryCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM train_telemetry", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return 0
    }
}
