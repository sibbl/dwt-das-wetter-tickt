package net.sibbl.dwdradar.map

import android.content.Context
import net.sibbl.dwdradar.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GermanyOutlineRepository(
    private val context: Context
) {
    @Volatile
    private var cachedOutlines: List<List<GeoPoint>>? = null

    suspend fun loadOutlines(): List<List<GeoPoint>> = withContext(Dispatchers.IO) {
        cachedOutlines ?: parseOutlines().also { cachedOutlines = it }
    }

    private fun parseOutlines(): List<List<GeoPoint>> {
        val root = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        val geoJson = JSONObject(root)
        val features = geoJson.getJSONArray("features")
        val outlines = mutableListOf<List<GeoPoint>>()

        for (index in 0 until features.length()) {
            val geometry = features.getJSONObject(index)
                .getJSONObject("geometry")
            when (geometry.getString("type")) {
                "Polygon" -> outlines += parseRing(
                    geometry.getJSONArray("coordinates").getJSONArray(0)
                )

                "MultiPolygon" -> {
                    val polygons = geometry.getJSONArray("coordinates")
                    for (polygonIndex in 0 until polygons.length()) {
                        val polygon = polygons.getJSONArray(polygonIndex)
                        outlines += parseRing(polygon.getJSONArray(0))
                    }
                }
            }
        }

        return outlines
    }

    private fun parseRing(array: JSONArray): List<GeoPoint> {
        return buildList {
            for (index in 0 until array.length()) {
                val coordinate = array.getJSONArray(index)
                add(
                    GeoPoint(
                        latitude = coordinate.getDouble(1),
                        longitude = coordinate.getDouble(0)
                    )
                )
            }
        }
    }

    private companion object {
        const val ASSET_PATH = "map/geojson/germany.geojson"
    }
}

