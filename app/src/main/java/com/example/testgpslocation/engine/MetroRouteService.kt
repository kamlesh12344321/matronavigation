package com.example.testgpslocation.engine

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.example.testgpslocation.models.*
import com.example.testgpslocation.utils.GeoToolbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.Math.abs
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MetroRouteService(private val context: Context) {

    private companion object {
        const val TAG = "MetroRouteService"
        const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        const val CACHE_DIR = "metro_routes"
        const val MAX_ROUTE_DISTANCE_THRESHOLD = 400.0 // Reject if further than 400m from start
    }

    suspend fun getRouteByLocation(
        latitude: Double,
        longitude: Double,
        radiusMeter: Int
    ): MetroRoute? = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "route_${(latitude * 100).toInt()}_${(longitude * 100).toInt()}"
            readFromCache(cacheKey)?.let { return@withContext it }

            if (!isNetworkAvailable()) return@withContext null

            val query = buildOverpassQuery(latitude, longitude, radiusMeter)
            val json = fetchJson(query) ?: return@withContext null

            val route = parse(json, latitude, longitude)
            
            // VALIDITY CHECK: Only accept if the nearest point is actually close to the search coord
            if (route != null) {
                val nearestPointDist = route.trackPoints.minOf { GeoToolbox.calculateDistance(latitude, longitude, it.lat, it.lon) }
                if (nearestPointDist <= MAX_ROUTE_DISTANCE_THRESHOLD) {
                    saveToCache(cacheKey, route)
                    return@withContext route
                } else {
                    Log.w(TAG, "Fetched route rejected: Too far from starting point ($nearestPointDist m)")
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get route", e)
            null
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(network) ?: return false
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected ?: false
        }
    }

    private fun buildOverpassQuery(lat: Double, lon: Double, radius: Int): String {
        return "[out:json][timeout:90];(" +
                "rel[\"route\"~\"subway|light_rail|monorail\"](around:$radius,$lat,$lon);" +
                "way[\"railway\"~\"subway|light_rail|monorail\"](around:$radius,$lat,$lon);" +
                "node[\"railway\"~\"station|halt\"](around:$radius,$lat,$lon);" +
                ");(._; >;);out body geom;"
    }

    private fun fetchJson(query: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(OVERPASS_URL)
            conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            conn.outputStream.use { os ->
                os.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray())
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null

            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(json: String, targetLat: Double, targetLon: Double): MetroRoute? {
        val root = JSONObject(json)
        val elements = root.optJSONArray("elements") ?: return null

        val nodes = mutableMapOf<Long, JSONObject>()
        val ways = mutableMapOf<Long, JSONObject>()
        val relations = mutableListOf<JSONObject>()

        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val id = el.optLong("id")
            when (el.optString("type")) {
                "node" -> nodes[id] = el
                "way" -> ways[id] = el
                "relation" -> relations.add(el)
            }
        }

        var bestLine: JSONObject? = null
        var minDistance = Double.MAX_VALUE

        // NEAREST DISTANCE SELECTION (Not average)
        for (rel in relations) {
            if (!isMetro(rel.optJSONObject("tags"))) continue
            val dist = calculateMinRelationDistance(rel, ways, nodes, targetLat, targetLon)
            if (dist < minDistance) {
                minDistance = dist
                bestLine = rel
            }
        }

        if (bestLine == null) {
            for (way in ways.values) {
                if (!isMetro(way.optJSONObject("tags"))) continue
                val dist = calculateMinWayDistance(way, targetLat, targetLon)
                if (dist < minDistance) {
                    minDistance = dist
                    bestLine = way
                }
            }
        }

        val best = bestLine ?: return null
        val route = MetroRoute().apply {
            createdAtMillis = System.currentTimeMillis()
            val tags = best.optJSONObject("tags")
            lineName = tags?.optString("name", tags?.optString("ref", "Unknown Line"))
            fromStation = tags?.optString("from")
            toStation = tags?.optString("to")
        }

        if (best.optString("type") == "relation") {
            extractFromRelation(route, best, ways, nodes)
        } else {
            extractFromWay(route, best)
        }

        computeDistances(route)
        return if (route.isValid) route else null
    }

    private fun isMetro(tags: JSONObject?): Boolean {
        if (tags == null) return false
        val r = tags.optString("route", "")
        val rw = tags.optString("railway", "")
        return r.contains("subway", true) || r.contains("light_rail", true) || r.contains("monorail", true) ||
                rw.contains("subway", true) || rw.contains("light_rail", true) || rw.contains("monorail", true)
    }

    private fun calculateMinRelationDistance(rel: JSONObject, ways: Map<Long, JSONObject>, nodes: Map<Long, JSONObject>, tLat: Double, tLon: Double): Double {
        val members = rel.optJSONArray("members") ?: return Double.MAX_VALUE
        var minD = Double.MAX_VALUE
        for (i in 0 until members.length()) {
            val m = members.getJSONObject(i)
            val ref = m.optLong("ref")
            val d = when (m.optString("type")) {
                "way" -> ways[ref]?.let { calculateMinWayDistance(it, tLat, tLon) } ?: Double.MAX_VALUE
                "node" -> nodes[ref]?.let { GeoToolbox.calculateDistance(tLat, tLon, it.optDouble("lat"), it.optDouble("lon")) } ?: Double.MAX_VALUE
                else -> Double.MAX_VALUE
            }
            if (d < minD) minD = d
        }
        return minD
    }

    private fun calculateMinWayDistance(way: JSONObject, tLat: Double, tLon: Double): Double {
        val geom = way.optJSONArray("geometry")
        if (geom != null) {
            var minD = Double.MAX_VALUE
            for (i in 0 until geom.length()) {
                val pt = geom.getJSONObject(i)
                minD = minOf(minD, GeoToolbox.calculateDistance(tLat, tLon, pt.optDouble("lat"), pt.optDouble("lon")))
            }
            return minD
        }
        return Double.MAX_VALUE
    }

    private fun extractFromRelation(route: MetroRoute, rel: JSONObject, ways: Map<Long, JSONObject>, nodes: Map<Long, JSONObject>) {
        val members = rel.optJSONArray("members") ?: return
        for (i in 0 until members.length()) {
            val m = members.getJSONObject(i)
            val ref = m.optLong("ref")
            when (m.optString("type")) {
                "way" -> ways[ref]?.let { extractFromWay(route, it) }
                "node" -> nodes[ref]?.let { node ->
                    if (isStation(node.optJSONObject("tags"))) {
                        val name = node.optJSONObject("tags")?.optString("name", "Unknown") ?: "Unknown"
                        if (route.stations.none { it.name == name }) {
                            route.stations.add(MetroStation(name, node.optDouble("lat"), node.optDouble("lon")))
                        }
                    }
                }
            }
        }
    }

    private fun extractFromWay(route: MetroRoute, way: JSONObject) {
        val geom = way.optJSONArray("geometry")
        if (geom != null) {
            for (i in 0 until geom.length()) {
                val pt = geom.getJSONObject(i)
                addPoint(route, pt.optDouble("lat"), pt.optDouble("lon"))
            }
        }
    }

    private fun isStation(tags: JSONObject?): Boolean {
        if (tags == null) return false
        val rw = tags.optString("railway", "")
        return rw.equals("station", true) || rw.equals("halt", true)
    }

    private fun addPoint(route: MetroRoute, lat: Double, lon: Double) {
        if (route.trackPoints.isNotEmpty()) {
            val last = route.trackPoints.last()
            if (abs(last.lat - lat) < 1e-7 && abs(last.lon - lon) < 1e-7) return
        }
        route.trackPoints.add(MetroPoint(lat, lon))
    }

    private fun computeDistances(route: MetroRoute) {
        if (route.trackPoints.size < 2) return
        var total = 0.0
        route.trackPoints[0].distanceFromStartMeters = 0.0
        for (i in 1 until route.trackPoints.size) {
            val p1 = route.trackPoints[i - 1]
            val p2 = route.trackPoints[i]
            total += GeoToolbox.calculateDistance(p1.lat, p1.lon, p2.lat, p2.lon)
            p2.distanceFromStartMeters = total
        }
    }

    private fun saveToCache(key: String, route: MetroRoute) {
        try {
            val dir = File(context.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }
            val file = File(dir, "$key.json")
            val root = JSONObject().apply {
                put("lineName", route.lineName)
                put("track", JSONArray().apply {
                    route.trackPoints.forEach { p ->
                        put(JSONObject().apply { put("lat", p.lat); put("lon", p.lon); put("dist", p.distanceFromStartMeters) })
                    }
                })
                put("stations", JSONArray().apply {
                    route.stations.forEach { s ->
                        put(JSONObject().apply { put("name", s.name); put("lat", s.lat); put("lon", s.lon) })
                    }
                })
            }
            file.writeText(root.toString())
        } catch (e: Exception) { Log.e(TAG, "Cache save failed", e) }
    }

    private fun readFromCache(key: String): MetroRoute? {
        return try {
            val file = File(File(context.cacheDir, CACHE_DIR), "$key.json")
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            MetroRoute().apply {
                lineName = root.optString("lineName")
                root.optJSONArray("track")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        trackPoints.add(MetroPoint(o.getDouble("lat"), o.getDouble("lon"), o.getDouble("dist")))
                    }
                }
                root.optJSONArray("stations")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        stations.add(MetroStation(o.getString("name"), o.getDouble("lat"), o.getDouble("lon")))
                    }
                }
            }
        } catch (e: Exception) { null }
    }
}
