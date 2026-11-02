package com.example.testgpslocation.engine

import org.json.JSONObject

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
