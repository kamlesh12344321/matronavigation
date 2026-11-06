package com.example.testgpslocation.engine

import android.content.Context
import android.util.Log
import com.example.testgpslocation.utils.GeoToolbox
import com.example.testgpslocation.utils.NetworkQuality
import com.example.testgpslocation.utils.NetworkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed class DownloadResult {
    data class Saved(val lines: Int) : DownloadResult()
    object AlreadyDownloaded : DownloadResult()
    object NothingHere : DownloadResult()
    object NoNetwork : DownloadResult()
    data class Failed(val reason: String) : DownloadResult()
}

/**
 * Metro line data, stored on the phone for offline use. Sources, in order:
 *  1. lines bundled inside the APK (assets/metro/<city>.json.gz, made at build time by
 *     scripts/build_metro_bundle.py): available on first launch with no network at all;
 *  2. lines downloaded earlier (phone cache);
 *  3. a live Overpass (OpenStreetMap) download — only an optional refresh / new-city path,
 *     never required: navigation works with 1 or 2 alone, and without any line at all.
 *
 * Offline lookups ([cachedNear]) never touch the network and never wait for a download, so a
 * slow or stalled download on a weak network cannot delay navigation underground.
 */
class MetroRouteService(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, CACHE_DIR)
    private val cacheMutex = Mutex()
    private val downloadMutex = Mutex()
    private var cached: MutableList<MetroRoute>? = null
    private var areas: List<Area>? = null

    private data class Area(val lat: Double, val lon: Double, val radius: Int, val time: Long)

    /** Loads the offline cache; returns the number of saved lines. */
    suspend fun preload(): Int = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val routes = loadCache()
            importBundledLocked(routes)
            routes.size
        }
    }

    /**
     * Copies the lines shipped in the APK into the cache, once per app install / update.
     * Lines already on the phone (e.g. a newer download) are kept.
     */
    private fun importBundledLocked(routes: MutableList<MetroRoute>) {
        val names = try { appContext.assets.list(BUNDLE_DIR)?.filter { it.endsWith(".json.gz") } } catch (e: Exception) { null }
        if (names.isNullOrEmpty()) return
        val stamp = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime.toString()
        } catch (e: Exception) { "0" }
        val marker = File(cacheDir, BUNDLE_MARKER)
        val done = try { if (marker.exists()) marker.readLines().toMutableSet() else mutableSetOf() } catch (e: Exception) { mutableSetOf() }
        var changed = false
        for (name in names) {
            val key = "$name@$stamp"
            if (key in done) continue
            try {
                val json = GZIPInputStream(appContext.assets.open("$BUNDLE_DIR/$name")).bufferedReader().use { it.readText() }
                val lines = OverpassParser.parseAll(json)
                lines.forEach { route ->
                    if (routes.none { it.id == route.id }) { routes.add(route); save(route) }
                }
                JSONObject(json).optJSONObject("bundle")?.let { b ->
                    val lat = b.optDouble("lat"); val lon = b.optDouble("lon")
                    if (!lat.isNaN() && !lon.isNaN()) markAreaDownloaded(lat, lon, b.optInt("radius", CITY_RADIUS_METERS))
                }
                Log.i(TAG, "Imported ${lines.size} bundled lines from $name")
            } catch (e: Exception) {
                Log.e(TAG, "Bundled line file $name is unreadable", e) // keep going: other cities / cache still work
            }
            done.removeAll { it.startsWith("$name@") }
            done.add(key)
            changed = true
        }
        if (changed) {
            try {
                if (!cacheDir.exists()) cacheDir.mkdirs()
                marker.writeText(done.joinToString("\n"))
            } catch (e: Exception) {
                Log.w(TAG, "Could not record bundled import", e)
            }
        }
    }

    /** Saved lines within [maxOffsetMeters] of the location, nearest first. Offline only. */
    suspend fun cachedNear(lat: Double, lon: Double, maxOffsetMeters: Double = MAX_ROUTE_DISTANCE_METERS): List<MetroRoute> =
        withContext(Dispatchers.Default) {
            val routes = cacheMutex.withLock { loadCache().toList() }
            routes.map { it to it.project(lat, lon).offsetMeters }
                .filter { it.second <= maxOffsetMeters }
                .sortedBy { it.second }
                .map { it.first }
        }

    /** True when a recent download already covered this spot. */
    suspend fun isAreaDownloaded(lat: Double, lon: Double): Boolean = withContext(Dispatchers.IO) {
        cacheMutex.withLock { isAreaDownloadedLocked(lat, lon) }
    }

    private fun isAreaDownloadedLocked(lat: Double, lon: Double): Boolean {
        val cutoff = System.currentTimeMillis() - AREA_MAX_AGE_MS
        return loadAreas().any { it.time > cutoff && GeoToolbox.calculateDistance(lat, lon, it.lat, it.lon) < it.radius - AREA_EDGE_MARGIN_METERS }
    }

    /**
     * Downloads every metro line within [radiusMeters] and saves it. [force] ignores the
     * "already downloaded" memory. Tries several Overpass servers in turn.
     */
    suspend fun download(lat: Double, lon: Double, radiusMeters: Int = SEARCH_RADIUS_METERS, force: Boolean = false): DownloadResult =
        withContext(Dispatchers.IO) {
            downloadMutex.withLock {
                if (!force && isAreaDownloaded(lat, lon)) return@withLock DownloadResult.AlreadyDownloaded
                if (NetworkStatus.quality(appContext) == NetworkQuality.NONE) return@withLock DownloadResult.NoNetwork

                val city = radiusMeters > SEARCH_RADIUS_METERS
                val query = buildOverpassQuery(lat, lon, radiusMeters, relationsOnly = city)
                var lastError = "no server answered"
                var json: String? = null
                for (endpoint in OVERPASS_URLS) {
                    val (body, error) = fetchJson(endpoint, query, if (city) CITY_READ_TIMEOUT_MS else READ_TIMEOUT_MS)
                    if (body != null) { json = body; break }
                    lastError = error ?: lastError
                    if (NetworkStatus.quality(appContext) == NetworkQuality.NONE) return@withLock DownloadResult.NoNetwork
                }
                if (json == null) return@withLock DownloadResult.Failed(lastError)

                val fetched = try {
                    OverpassParser.parseAll(json)
                } catch (e: Exception) {
                    Log.e(TAG, "Parse failed", e)
                    return@withLock DownloadResult.Failed("bad data from server (${e.javaClass.simpleName})")
                }
                cacheMutex.withLock {
                    val routes = loadCache()
                    fetched.forEach { route ->
                        routes.removeAll { it.id == route.id }
                        routes.add(route)
                        save(route)
                    }
                    markAreaDownloaded(lat, lon, radiusMeters)
                }
                if (fetched.isEmpty()) DownloadResult.NothingHere else DownloadResult.Saved(fetched.size)
            }
        }

    /** Number of saved lines (after [preload]). */
    val cachedLineCount: Int get() = cached?.size ?: 0

    /**
     * Route relations with inline member geometry (no recursion into every track node, which
     * made responses huge and timed out), the named stop nodes of those relations, and, for
     * the normal (non-city) query, loose subway ways and stations as a fallback for areas
     * without route relations.
     */
    private fun buildOverpassQuery(lat: Double, lon: Double, radius: Int, relationsOnly: Boolean): String {
        val around = "(around:$radius,$lat,$lon)"
        val timeout = if (relationsOnly) 180 else 60
        val base = "[out:json][timeout:$timeout];" +
            "rel[\"route\"~\"^(subway|light_rail|monorail)$\"]$around->.r;.r out geom;" +
            "node(r.r)[\"name\"];out body;"
        if (relationsOnly) return base
        return base +
            "way[\"railway\"~\"^(subway|light_rail|monorail)$\"]$around;out geom;" +
            "node[\"railway\"~\"^(station|halt)$\"]$around;out body;"
    }

    /** Returns (body, null) on success or (null, reason) on failure. */
    private fun fetchJson(endpoint: String, query: String, readTimeoutMs: Int): Pair<String?, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Overpass HTTP $code from $endpoint")
                return null to "server busy (HTTP $code)"
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // Overpass reports query timeouts inside a 200 response.
            if (body.contains("\"remark\"") && body.contains("runtime error")) return null to "server timeout"
            body to null
        } catch (e: Exception) {
            Log.w(TAG, "Fetch from $endpoint failed: $e")
            null to (e.javaClass.simpleName.removeSuffix("Exception").ifEmpty { "network error" })
        } finally {
            conn?.disconnect()
        }
    }

    // ---- Offline storage (call with cacheMutex held) -------------------------------------------

    private fun loadCache(): MutableList<MetroRoute> {
        cached?.let { return it }
        val list = mutableListOf<MetroRoute>()
        cacheDir.listFiles { f -> f.extension == "json" && f.name != AREAS_FILE }?.forEach { f ->
            try {
                val route = read(f)
                if (route != null) list.add(route) else f.delete() // old format / invalid
            } catch (e: Exception) {
                Log.w(TAG, "Bad cache file ${f.name}, deleting", e)
                f.delete()
            }
        }
        cacheDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        cached = list
        return list
    }

    private fun save(route: MetroRoute) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            // Write to a temp file then rename, so a crash / full disk never leaves a half-written line.
            val tmp = File(cacheDir, "${route.id}.json.tmp")
            tmp.writeText(RouteJson.write(route))
            if (!tmp.renameTo(File(cacheDir, "${route.id}.json"))) tmp.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Cache save failed", e)
        }
    }

    private fun read(file: File): MetroRoute? = RouteJson.read(file.readText())

    private fun markAreaDownloaded(lat: Double, lon: Double, radius: Int) {
        val cutoff = System.currentTimeMillis() - AREA_MAX_AGE_MS
        val list = loadAreas().filter { it.time > cutoff } + Area(lat, lon, radius, System.currentTimeMillis())
        areas = list
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            File(cacheDir, AREAS_FILE).writeText(JSONArray().apply {
                list.forEach { put(JSONObject().put("lat", it.lat).put("lon", it.lon).put("radius", it.radius).put("time", it.time)) }
            }.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Could not save downloaded areas", e)
        }
    }

    private fun loadAreas(): List<Area> {
        areas?.let { return it }
        val list = try {
            val f = File(cacheDir, AREAS_FILE)
            if (!f.exists()) emptyList() else JSONArray(f.readText()).let { a ->
                (0 until a.length()).map {
                    a.getJSONObject(it).let { o -> Area(o.getDouble("lat"), o.getDouble("lon"), o.optInt("radius", SEARCH_RADIUS_METERS), o.getLong("time")) }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        areas = list
        return list
    }

    companion object {
        private const val TAG = "MetroRouteService"
        /** This app's identity for every OpenStreetMap service (line data and map tiles). */
        const val USER_AGENT = "TestGPSLocation-MetroNavigator/1.0"
        private val OVERPASS_URLS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
        )
        private const val CACHE_DIR = "metro_routes"
        private const val AREAS_FILE = "downloaded_areas.json"
        private const val BUNDLE_DIR = "metro"
        private const val BUNDLE_MARKER = "bundled_imported.txt"
        private const val AREA_MAX_AGE_MS = 7L * 24 * 3600 * 1000
        private const val AREA_EDGE_MARGIN_METERS = 500
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 70_000
        private const val CITY_READ_TIMEOUT_MS = 190_000
        const val SEARCH_RADIUS_METERS = 2000
        const val CITY_RADIUS_METERS = 30_000
        const val MAX_ROUTE_DISTANCE_METERS = 400.0
    }
}

/** The saved form of a [MetroRoute] (offline metro lines, test-drive routes) and of a [RideState]. */
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

    fun writeRide(s: RideState): String = JSONObject().apply {
        s.routeId?.let { put("route", it) }
        put("lat", s.latitude); put("lon", s.longitude); put("along", s.distanceAlongLine)
        put("direction", s.direction); put("directionKnown", s.directionKnown); put("directionUnconfirmed", s.directionUnconfirmed)
        put("heading", s.headingDeg); put("speed", s.speedMps); put("cruise", s.cruiseSpeedMps); put("error", s.uncertaintyMeters)
        s.lastStation?.let { put("lastStation", it) }
        put("savedAt", s.savedAtMs)
        put("inVehicle", s.inVehicle); put("headingKnown", s.headingKnown)
    }.toString()

    /** Throws on malformed JSON. */
    fun readRide(text: String): RideState = JSONObject(text).run {
        RideState(optStringOrNull("route"), getDouble("lat"), getDouble("lon"), getDouble("along"), getInt("direction"),
            getBoolean("directionKnown"), getBoolean("directionUnconfirmed"), getDouble("heading"), getDouble("speed"),
            getDouble("cruise"), getDouble("error"), optStringOrNull("lastStation"), getLong("savedAt"),
            optBoolean("inVehicle", true), optBoolean("headingKnown", true))
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() } else null
}

/**
 * Turns an Overpass (OpenStreetMap) JSON response into [MetroRoute]s. Used for live downloads
 * and for the line files bundled in the APK, so both go through exactly the same code.
 */
object OverpassParser {

    /** Builds one route per metro relation; falls back to stitched loose ways if there are none. */
    fun parseAll(json: String): List<MetroRoute> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val nodes = mutableMapOf<Long, JSONObject>()
        val ways = mutableMapOf<Long, JSONObject>()
        val relations = mutableListOf<JSONObject>()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            when (el.optString("type")) {
                "node" -> nodes[el.optLong("id")] = el
                "way" -> ways[el.optLong("id")] = el
                "relation" -> relations.add(el)
            }
        }
        val allStations = nodes.values.filter { isStation(it.optJSONObject("tags")) }.mapNotNull { toStation(it) }

        val routes = relations.filter { isMetro(it.optJSONObject("tags")) }.mapNotNull { rel ->
            val members = rel.optJSONArray("members") ?: return@mapNotNull null
            val trackWays = mutableListOf<List<TrackPoint>>()
            val stops = mutableListOf<MetroStation>()
            for (i in 0 until members.length()) {
                val m = members.optJSONObject(i) ?: continue
                val role = m.optString("role", "")
                val ref = m.optLong("ref")
                when (m.optString("type")) {
                    "way" -> if (!role.startsWith("platform")) {
                        val pts = if (m.has("geometry")) geometry(m) else ways[ref]?.let { geometry(it) } ?: emptyList()
                        if (pts.size >= 2) trackWays.add(pts)
                    }
                    "node" -> nodes[ref]?.let { n ->
                        if (role.startsWith("stop") || isStation(n.optJSONObject("tags"))) toStation(n)?.let(stops::add)
                    }
                }
            }
            val tags = rel.optJSONObject("tags")
            MetroRoute(
                id = "rel_${rel.optLong("id")}",
                lineName = lineName(tags),
                fromStation = tags?.optStringOrNull("from"),
                toStation = tags?.optStringOrNull("to"),
                points = TrackStitcher.stitch(trackWays),
                rawStations = stops.ifEmpty { allStations }
            ).takeIf { it.isValid }
        }
        if (routes.isNotEmpty()) return routes

        // No route relations: stitch the loose subway ways into one line.
        val metroWays = ways.values.filter { isMetro(it.optJSONObject("tags")) }
        if (metroWays.isEmpty()) return emptyList()
        val track = TrackStitcher.stitch(metroWays.map { geometry(it) })
        val tags = metroWays.first().optJSONObject("tags")
        return listOfNotNull(MetroRoute("way_${metroWays.first().optLong("id")}", lineName(tags), null, null, track, allStations).takeIf { it.isValid })
    }

    private fun geometry(obj: JSONObject): List<TrackPoint> {
        val geom = obj.optJSONArray("geometry") ?: return emptyList()
        return (0 until geom.length()).mapNotNull { i ->
            val p = geom.optJSONObject(i) ?: return@mapNotNull null // null entries appear for clipped geometry
            TrackPoint(p.optDouble("lat"), p.optDouble("lon")).takeIf { !it.lat.isNaN() && !it.lon.isNaN() }
        }
    }

    private fun toStation(node: JSONObject): MetroStation? {
        val tags = node.optJSONObject("tags")
        val name = tags?.optStringOrNull("name:en") ?: tags?.optStringOrNull("name") ?: return null
        val lat = node.optDouble("lat"); val lon = node.optDouble("lon")
        if (lat.isNaN() || lon.isNaN()) return null
        return MetroStation(name, lat, lon)
    }

    private fun lineName(tags: JSONObject?): String =
        tags?.optStringOrNull("name:en") ?: tags?.optStringOrNull("name") ?: tags?.optStringOrNull("ref")?.let { "Line $it" } ?: "Metro line"

    private fun isMetro(tags: JSONObject?): Boolean {
        if (tags == null) return false
        val kinds = setOf("subway", "light_rail", "monorail")
        return tags.optString("route") in kinds || tags.optString("railway") in kinds
    }

    private fun isStation(tags: JSONObject?): Boolean {
        if (tags == null) return false
        val rw = tags.optString("railway")
        return rw == "station" || rw == "halt" || rw == "stop" || tags.optString("public_transport") == "stop_position"
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() } else null

}
