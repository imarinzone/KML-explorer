package com.example

import android.content.Context
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

data class RouteResult(
    val points: List<KmlPoint>,
    val errorMessage: String? = null
)

suspend fun processSharedMapsText(context: Context, sharedText: String): RouteResult = withContext(Dispatchers.IO) {
    try {
        Log.i("RouteHelper", "Triggered Google Maps link route resolution for shared text: $sharedText")
        val urlRegex = "(https?://[a-zA-Z0-9./?=_%:-]+)".toRegex()
        val match = urlRegex.find(sharedText) ?: return@withContext RouteResult(emptyList(), "No URL found in shared text.").also {
            Log.e("RouteHelper", "No valid URL found in the shared text input.")
        }
        val urlStr = match.groupValues[1]

        var finalUrl = urlStr
        var attempt = 0
        while(attempt < 5) {
            val conn = URL(finalUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.instanceFollowRedirects = false
            try {
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (loc != null) {
                        finalUrl = if (loc.startsWith("/")) {
                            val url = URL(finalUrl)
                            "${url.protocol}://${url.host}$loc"
                        } else {
                            loc
                        }
                        attempt++
                    } else break
                } else break
            } finally {
                conn.disconnect()
            }
        }

        val dirIndex = finalUrl.indexOf("/dir/")
        if (dirIndex == -1) {
            Log.e("RouteHelper", "Directions missing: final URL resolved to '$finalUrl', which does not contain directions '/dir/'.")
            return@withContext RouteResult(emptyList(), "No directions found. URL resolved to: $finalUrl")
        }

        val endDirIndex = finalUrl.indexOf("/@", dirIndex)
        val pathPart = if (endDirIndex != -1) finalUrl.substring(dirIndex + 5, endDirIndex)
                       else finalUrl.substring(dirIndex + 5).substringBefore("?")

        val segments = pathPart.split("/").filter { it.isNotBlank() }
        if (segments.size < 2) {
            Log.e("RouteHelper", "Insufficient waypoints logic: extracted segments: $segments from route part '$pathPart'")
            return@withContext RouteResult(emptyList(), "Need at least 2 waypoints in the route.")
        }

        val geocoder = Geocoder(context)
        val waypoints = mutableListOf<KmlPoint>()
        val coordRegex = "(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)".toRegex()

        for (segment in segments) {
            val decoded = URLDecoder.decode(segment, "UTF-8")
            val coordMatch = coordRegex.find(decoded)
            if (coordMatch != null) {
                val lat = coordMatch.groupValues[1].toDouble()
                val lon = coordMatch.groupValues[2].toDouble()
                waypoints.add(KmlPoint(lat, lon, 0.0))
            } else {
                try {
                    val fallbackMatch = "(-?\\d+\\.\\d+)\\+(-?\\d+\\.\\d+)".toRegex().find(decoded)
                    if (fallbackMatch != null) {
                        waypoints.add(KmlPoint(fallbackMatch.groupValues[1].toDouble(), fallbackMatch.groupValues[2].toDouble(), 0.0))
                    } else {
                        val addresses = geocoder.getFromLocationName(decoded, 1)
                        if (!addresses.isNullOrEmpty()) {
                            waypoints.add(KmlPoint(addresses[0].latitude, addresses[0].longitude, 0.0))
                        }
                    }
                } catch(e: Exception) {
                    Log.e("RouteHelper", "Geocoding failed for \$decoded", e)
                }
            }
        }

        if (waypoints.size < 2) {
            Log.e("RouteHelper", "Waypoints resolution failed: resolved less than 2 waypoints.")
            return@withContext RouteResult(emptyList(), "Could not geo-resolve waypoints correctly.")
        }

        val coordsStr = waypoints.joinToString(";") { "${it.longitude},${it.latitude}" }
        val osrmUrl = "https://router.project-osrm.org/route/v1/driving/$coordsStr?overview=full&geometries=geojson"
        
        Log.i("RouteHelper", "Requesting OSRM route: $osrmUrl")
        Log.d("RouteHelper", "Waypoints target details: $waypoints")
        val routeConn = URL(osrmUrl).openConnection() as HttpURLConnection
        routeConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        val osrmJson = try {
            routeConn.connect()
            if (routeConn.responseCode != 200) {
                val errorMsg = routeConn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No response body"
                Log.e("RouteHelper", "OSRM error response code ${routeConn.responseCode}: $errorMsg")
                return@withContext RouteResult(emptyList(), "External Routing service failed (OSRM) with code ${routeConn.responseCode}.")
            }
            JSONObject(routeConn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            routeConn.disconnect()
        }

        val routes = osrmJson.optJSONArray("routes")
        if (routes == null || routes.length() == 0) {
            Log.w("RouteHelper", "OSRM response did not contain any routes.")
            return@withContext RouteResult(emptyList(), "No route found between these points.")
        }
        val coordinatesJson = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")

        val routePoints = mutableListOf<KmlPoint>()
        for (i in 0 until coordinatesJson.length()) {
            val pt = coordinatesJson.getJSONArray(i)
            routePoints.add(KmlPoint(pt.getDouble(1), pt.getDouble(0), 0.0)) // JSON is [lon, lat]
        }
        Log.i("RouteHelper", "Successfully loaded ${routePoints.size} points from OSRM.")

        // Simplify path if it's too huge: OpenTopoData limits to 100 points, 1 req per sec.
        val maxPoints = 300
        val sampledPoints = if (routePoints.size > maxPoints) {
            Log.i("RouteHelper", "Route size (${routePoints.size}) is over limit ($maxPoints). Simplifying down to sample points.")
            val step = routePoints.size.toDouble() / maxPoints
            val newPts = mutableListOf<KmlPoint>()
            for (i in 0 until maxPoints) {
                newPts.add(routePoints[(i * step).toInt()])
            }
            newPts.add(routePoints.last())
            newPts
        } else {
            routePoints
        }

        val elevatedPoints = mutableListOf<KmlPoint>()
        val chunked = sampledPoints.chunked(100)
        Log.i("RouteHelper", "Fetching elevation profiles for ${sampledPoints.size} points in ${chunked.size} request chunks.")
        for ((index, chunk) in chunked.withIndex()) {
            val locStr = chunk.joinToString("|") { "${it.latitude},${it.longitude}" }
            val elevUrl = "https://api.opentopodata.org/v1/srtm90m?locations=$locStr"
            
            var success = false
            try {
                Log.d("RouteHelper", "Requesting elevation chunk ${index + 1}/${chunked.size}")
                val elevConn = URL(elevUrl).openConnection() as HttpURLConnection
                elevConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                elevConn.connect()
                if (elevConn.responseCode == 200) {
                    val elevJson = JSONObject(elevConn.inputStream.bufferedReader().use { it.readText() })
                    val results = elevJson.optJSONArray("results")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            val elev = results.getJSONObject(i).optDouble("elevation", 0.0)
                            elevatedPoints.add(chunk[i].copy(altitude = elev))
                        }
                        success = true
                        Log.d("RouteHelper", "Successfully received elevation chunk ${index + 1}/${chunked.size}")
                    }
                } else {
                    Log.e("RouteHelper", "Elevation service error code ${elevConn.responseCode} on chunk ${index + 1}")
                }
            } catch(e: Exception) {
                Log.e("RouteHelper", "Elevation request failed on chunk ${index + 1}", e)
            }
            
            if (!success) { // Fallback if API fails
                Log.w("RouteHelper", "Using fallback coordinates without elevation details for chunk ${index + 1}")
                elevatedPoints.addAll(chunk)
            }
            delay(1100) // Rate limit 1 req per sec for opentopodata
        }

        Log.i("RouteHelper", "Route processed successfully with ${elevatedPoints.size} points.")
        return@withContext RouteResult(elevatedPoints)

    } catch (e: Exception) {
        Log.e("RouteHelper", "Exception during Google Maps route resolution sequence: ${e.message}", e)
        return@withContext RouteResult(emptyList(), "Failed to process: ${e.message}")
    }
}

fun generateKmlFromPoints(points: List<KmlPoint>): String {
    val date = java.time.Instant.now()
    var coordsStr = ""
    var trackNodes = ""
    for (i in points.indices) {
        val pt = points[i]
        coordsStr += "${pt.longitude},${pt.latitude},${pt.altitude}\n"
        val ptTime = date.plusSeconds((i * 8).toLong())
        val iso = ptTime.toString()
        trackNodes += "<when>$iso</when>\n"
    }
    for (i in points.indices) {
        val pt = points[i]
        trackNodes += "<gx:coord>${pt.longitude} ${pt.latitude} ${pt.altitude}</gx:coord>\n"
    }

    return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2" xmlns:kml="http://www.opengis.net/kml/2.2">
<Document>
<description><![CDATA[AlpineQuest Track 6/5/26 11:08:30 AM<br /><br />Generated by AlpineQuest®<br /><a href="https://www.alpinequest.net" alt="https://www.alpinequest.net">https://www.alpinequest.net</a>]]></description>
<visibility>1</visibility>
<open>1</open>
<Placemark>
<visibility>1</visibility>
<Style>
	<LineStyle>
		<width>3</width>
	</LineStyle>
</Style>
<LineString>
<tessellate>1</tessellate>
<altitudeMode>clampToGround</altitudeMode>
<coordinates>
$coordsStr</coordinates>
</LineString>
</Placemark>
<Placemark>
<visibility>1</visibility>
<Style>
	<LineStyle>
		<width>5</width>
	</LineStyle>
</Style>
<gx:MultiTrack>
<altitudeMode>clampToGround</altitudeMode>
<gx:Track>
$trackNodes</gx:Track>
</gx:MultiTrack>
</Placemark>
</Document>
</kml>"""
}
