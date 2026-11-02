package com.example.testgpslocation.engine

import org.json.JSONArray
import org.json.JSONObject

/** The saved form of a [MetroRoute]: offline metro lines and test-drive routes. */
object RouteJson {
    const val VERSION = 2

    fun write(route: MetroRoute): String = JSONObject().apply {
        put("version", VERSION)
        put("id", route.id)
        put("lineName", route.lineName)
        route.fromStation?.let { put("from", it) }
        route.toStation?.let { put("to", it) }
        put("track", JSONArray().apply { route.points.forEach { put(JSONArray().put(it.lat).put(it.lon)) } })
        put("stations", JSONArray().apply {
            route.stations.forEach { s -> put(JSONObject().put("name", s.name).put("lat", s.lat).put("lon", s.lon)) }
        })
    }.toString()

    /** Null for another format version or an unusable line; throws on malformed JSON. */
    fun read(text: String): MetroRoute? {
        val root = JSONObject(text)
        if (root.optInt("version") != VERSION) return null
        val track = root.getJSONArray("track").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> TrackPoint(p.getDouble(0), p.getDouble(1)) } } }
        val stations = root.getJSONArray("stations").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { o -> MetroStation(o.getString("name"), o.getDouble("lat"), o.getDouble("lon")) } }
        }
        return MetroRoute(root.getString("id"), root.getString("lineName"), root.optStringOrNull("from"), root.optStringOrNull("to"), track, stations)
            .takeIf { it.isValid }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() } else null
}
