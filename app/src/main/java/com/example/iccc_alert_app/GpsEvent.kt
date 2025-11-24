package com.example.iccc_alert_app

import com.google.gson.annotations.SerializedName

/**
 * GPS-specific event data model for off-route and tamper alerts
 */
data class GpsEvent(
    @SerializedName("id") val id: String? = null,
    @SerializedName("timestamp") val timestamp: Long = 0L,
    @SerializedName("source") val source: String? = null,
    @SerializedName("area") val area: String? = null,
    @SerializedName("areaDisplay") val areaDisplay: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("typeDisplay") val typeDisplay: String? = null,
    @SerializedName("groupId") val groupId: String? = null,
    @SerializedName("vehicleNumber") val vehicleNumber: String? = null,
    @SerializedName("vehicleTransporter") val vehicleTransporter: String? = null,
    @SerializedName("data") val data: GpsEventData? = null
) {
    fun isGpsEvent(): Boolean {
        return type == "off-route" || type == "tamper" || type == "overspeed"
    }
}

data class GpsEventData(
    @SerializedName("currentLocation") val currentLocation: GpsLocation? = null,
    @SerializedName("alertLocation") val alertLocation: GpsLocation? = null,
    @SerializedName("timestamp") val timestamp: String? = null,
    @SerializedName("alertSubType") val alertSubType: String? = null,
    @SerializedName("allocatedGeofence") val allocatedGeofence: List<String>? = null,
    @SerializedName("geofence") val geofence: GeofenceInfo? = null
)

data class GpsLocation(
    @SerializedName("lat") val lat: Double = 0.0,
    @SerializedName("lng") val lng: Double = 0.0
)

data class GeofenceInfo(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("attributes") val attributes: GeofenceAttributes? = null,
    @SerializedName("geojson") val geojson: GeoJsonGeometry? = null
)

data class GeofenceAttributes(
    @SerializedName("color") val color: String? = "#3388ff",
    @SerializedName("polylineColor") val polylineColor: String? = null,
    @SerializedName("speed") val speed: Int? = null
)

data class GeoJsonGeometry(
    @SerializedName("type") val type: String? = null,
    @SerializedName("coordinates") val coordinates: Any? = null
) {
    fun getCoordinatesAsList(): List<List<Double>>? {
        return when (coordinates) {
            is List<*> -> {
                when (type) {
                    "Point" -> listOf(coordinates as List<Double>)
                    "LineString" -> coordinates as? List<List<Double>>
                    "Polygon" -> (coordinates as? List<*>)?.firstOrNull() as? List<List<Double>>
                    else -> null
                }
            }
            else -> null
        }
    }
}

/**
 * Convert standard Event to GpsEvent if it's a GPS type
 * FIXED: Get vehicle info from root level, not from data map
 */
fun Event.toGpsEvent(): GpsEvent? {
    if (type != "off-route" && type != "tamper" && type != "overspeed") {
        return null
    }

    val currentLoc = (data["currentLocation"] as? Map<*, *>)?.let {
        GpsLocation(
            lat = (it["lat"] as? Number)?.toDouble() ?: 0.0,
            lng = (it["lng"] as? Number)?.toDouble() ?: 0.0
        )
    }

    val alertLoc = (data["alertLocation"] as? Map<*, *>)?.let {
        GpsLocation(
            lat = (it["lat"] as? Number)?.toDouble() ?: 0.0,
            lng = (it["lng"] as? Number)?.toDouble() ?: 0.0
        )
    }

    val geofenceData = (data["geofence"] as? Map<*, *>)?.let { gf ->
        val geoJsonData = gf["geojson"] as? Map<*, *>
        GeofenceInfo(
            id = (gf["id"] as? Number)?.toInt() ?: 0,
            name = gf["name"] as? String,
            description = gf["description"] as? String,
            type = gf["type"] as? String,
            attributes = (gf["attributes"] as? Map<*, *>)?.let { attrs ->
                GeofenceAttributes(
                    color = attrs["color"] as? String ?: "#3388ff",
                    polylineColor = attrs["polylineColor"] as? String,
                    speed = (attrs["speed"] as? Number)?.toInt()
                )
            },
            geojson = geoJsonData?.let {
                GeoJsonGeometry(
                    type = it["type"] as? String,
                    coordinates = it["coordinates"]
                )
            }
        )
    }

    val gpsData = GpsEventData(
        currentLocation = currentLoc,
        alertLocation = alertLoc,
        timestamp = data["timestamp"] as? String,
        alertSubType = data["alertSubType"] as? String,
        allocatedGeofence = (data["allocatedGeofence"] as? List<*>)?.mapNotNull { it as? String },
        geofence = geofenceData
    )

    return GpsEvent(
        id = id,
        timestamp = timestamp,
        source = source,
        area = area,
        areaDisplay = areaDisplay,
        type = type,
        typeDisplay = typeDisplay,
        groupId = groupId,
        vehicleNumber = vehicleNumber,  // FIXED: Use root level property
        vehicleTransporter = vehicleTransporter,  // FIXED: Use root level property
        data = gpsData
    )
}