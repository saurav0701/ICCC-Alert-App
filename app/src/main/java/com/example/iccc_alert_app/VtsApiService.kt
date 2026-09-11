package com.example.iccc_alert_app

import android.util.Log
import com.example.iccc_alert_app.auth.AuthManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to the bbstate backend that also backs the CCL Alert Dashboard.
 *
 * Two things live here:
 *  - VTS alert comments (closing an alert with a remark)
 *  - Geofence geometry for off-route / off-area / tamper maps
 *
 * These are the exact endpoints the dashboard uses, so a remark written from
 * the app lands in the same database row the dashboard would have written,
 * and a fence drawn in the app matches the one drawn on the dashboard.
 */
object VtsApiService {

    private const val TAG = "VtsApiService"
    private const val BASE_URL = "https://bbstate.cclai.in"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // -- Comments ---------------------------------------------------------

    sealed class CommentResult {
        /** Remark saved. [remark] is the full string the server stored. */
        data class Success(val remark: String) : CommentResult()

        /** HTTP 409 - a remark is already present; resend with force to override. */
        data class AlreadyHasRemark(val existingRemark: String) : CommentResult()

        /**
         * HTTP 404 - the live alert has not reached the VTS database yet.
         * The caller may retry shortly; the dashboard retries 3 times, 3s apart.
         */
        object NotRecordedYet : CommentResult()

        data class Error(val message: String) : CommentResult()
    }

    /**
     * Attributes the remark to the signed-in user and makes clear it came from
     * the app rather than the dashboard, since dashboard operators read these.
     */
    private fun userLabel(): String {
        val name = try {
            AuthManager.getCurrentUser()?.name?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        return if (name != null) "$name via CCL Alert App" else "CCL Alert App"
    }

    /**
     * Closes a live VTS alert with a remark, matching the alert on vehicle
     * number + timestamp (the server searches a 30-second window either side).
     *
     * [event] must be a VTS alert. The raw socket timestamp is read from
     * data["timestamp"], which is the value the VTS database stores.
     */
    suspend fun addComment(
        event: Event,
        comment: String,
        force: Boolean = false
    ): CommentResult = withContext(Dispatchers.IO) {

        val apiType = VtsAlertTypes.apiType(event.type)
            ?: return@withContext CommentResult.Error("Not a vehicle alert")

        val vehicleNumber = event.vehicleNumber?.takeIf { it.isNotBlank() }
            ?: return@withContext CommentResult.Error("Alert has no vehicle number")

        // The raw VTS timestamp ("yyyy-MM-dd HH:mm:ss", UTC) as sent by the
        // socket. The server parses it as UTC, so pass it through untouched.
        val timestamp = (event.data["timestamp"] as? String)?.takeIf { it.isNotBlank() }
            ?: return@withContext CommentResult.Error("Alert has no timestamp")

        val payload = JsonObject().apply {
            addProperty("vehicleNumber", vehicleNumber)
            addProperty("timestamp", timestamp)
            addProperty("comment", comment)
            addProperty("user", userLabel())
            addProperty("force", force)
        }

        val request = Request.Builder()
            .url("$BASE_URL/api/reports/$apiType/close-by-vehicle")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                val json = runCatching {
                    JsonParser.parseString(bodyText).asJsonObject
                }.getOrNull()

                when {
                    response.code == 409 &&
                        json?.get("hasExistingRemark")?.asBoolean == true -> {
                        CommentResult.AlreadyHasRemark(
                            json.get("existingRemark")?.asString.orEmpty()
                        )
                    }

                    response.code == 404 && json?.get("notFound")?.asBoolean == true -> {
                        Log.d(TAG, "Alert not yet in VTS DB: $vehicleNumber at $timestamp")
                        CommentResult.NotRecordedYet
                    }

                    response.isSuccessful -> {
                        CommentResult.Success(json?.get("remark")?.asString.orEmpty())
                    }

                    else -> {
                        val msg = json?.get("error")?.asString ?: "HTTP ${response.code}"
                        Log.w(TAG, "Comment failed: $msg")
                        CommentResult.Error(msg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Comment network error", e)
            CommentResult.Error(e.message ?: "Network error")
        }
    }

    // -- Geofence ---------------------------------------------------------

    /**
     * A fence as bbstate returns it.
     *
     * [points] are already flipped to (latitude, longitude) for osmdroid.
     * bbstate returns GeoJSON order, which is [longitude, latitude].
     */
    data class Geofence(
        val id: Int,
        val name: String?,
        val geotype: String?,
        val color: String?,
        val polylineDistance: Double?,
        /** "Polygon", "LineString" or "Point". */
        val geometryType: String?,
        /** Outer ring for a polygon, the line for a LineString, one point for a Point. */
        val points: List<Pair<Double, Double>>
    )

    /**
     * Fetches fence geometry for [geofenceId] from the same cache the dashboard
     * reads. Returns null if the fence is unknown or the call fails.
     */
    suspend fun fetchGeofence(geofenceId: Int): Geofence? = withContext(Dispatchers.IO) {
        if (geofenceId <= 0) return@withContext null

        val request = Request.Builder()
            .url("$BASE_URL/api/geofences/$geofenceId?includeArea=1")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "Geofence $geofenceId returned HTTP ${response.code}")
                    return@withContext null
                }
                val root = JsonParser
                    .parseString(response.body?.string().orEmpty())
                    .asJsonObject
                val data = root.getAsJsonObject("data")
                    ?: return@withContext null
                parseGeofence(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Geofence $geofenceId fetch failed", e)
            null
        }
    }

    private fun parseGeofence(data: JsonObject): Geofence? {
        val area = data.getAsJsonObject("area") ?: return null
        val geometryType = area.get("type")?.asString
        val coords = area.get("coordinates")?.asJsonArray ?: return null

        // Polygon    -> coordinates[0] is the outer ring [[lon,lat], ...]
        // LineString -> coordinates is [[lon,lat], ...]
        // Point      -> coordinates is [lon,lat]
        val points: List<Pair<Double, Double>> = when (geometryType) {
            "Point" -> {
                val lon = coords.itemOrNull(0)?.asDouble
                val lat = coords.itemOrNull(1)?.asDouble
                if (lon != null && lat != null) listOf(lat to lon) else emptyList()
            }
            "Polygon" -> coords.itemOrNull(0)?.asJsonArray.toLatLngList()
            else -> coords.toLatLngList()
        }

        if (points.isEmpty()) return null

        return Geofence(
            id = data.get("id")?.asInt ?: 0,
            name = data.get("name")?.takeIf { !it.isJsonNull }?.asString,
            geotype = data.get("geotype")?.takeIf { !it.isJsonNull }?.asString,
            color = data.get("color")?.takeIf { !it.isJsonNull }?.asString,
            polylineDistance = data.get("polylineDistance")?.takeIf { !it.isJsonNull }?.asDouble,
            geometryType = geometryType,
            points = points
        )
    }

    /** Converts a GeoJSON [[lon,lat], ...] array to (lat, lon) pairs. */
    private fun JsonArray?.toLatLngList(): List<Pair<Double, Double>> {
        if (this == null) return emptyList()
        return mapNotNull { element ->
            val pair = element as? JsonArray ?: return@mapNotNull null
            val lon = pair.itemOrNull(0)?.asDouble
            val lat = pair.itemOrNull(1)?.asDouble
            if (lon != null && lat != null) lat to lon else null
        }
    }

    private fun JsonArray.itemOrNull(index: Int) =
        if (index < size()) get(index) else null
}
