package com.example.iccc_alert_app

import android.graphics.Color
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var progressBar: ProgressBar
    private var gpsEvent: GpsEvent? = null

    companion object {
        private const val TAG = "MapActivity"
        const val EXTRA_GPS_EVENT = "gps_event"

        // Jharkhand state center coordinates
        private const val JHARKHAND_CENTER_LAT = 23.6102
        private const val JHARKHAND_CENTER_LNG = 85.2799
        private const val JHARKHAND_DEFAULT_ZOOM = 8.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize osmdroid configuration
        try {
            Configuration.getInstance().load(
                applicationContext,
                PreferenceManager.getDefaultSharedPreferences(applicationContext)
            )
            Configuration.getInstance().userAgentValue = packageName

            // Set cache sizes to prevent memory issues
            Configuration.getInstance().tileFileSystemCacheMaxBytes = 50L * 1024L * 1024L // 50MB
            Configuration.getInstance().tileFileSystemCacheTrimBytes = 40L * 1024L * 1024L // 40MB
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing osmdroid: ${e.message}")
        }

        setContentView(R.layout.activity_map)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Event Location"

        progressBar = findViewById(R.id.map_loading_progress)

        // Setup back button
        findViewById<LinearLayout>(R.id.map_back_button)?.setOnClickListener {
            finish()
        }

        // Parse GPS event from intent
        val eventJson = intent.getStringExtra(EXTRA_GPS_EVENT)
        if (eventJson != null) {
            try {
                gpsEvent = Gson().fromJson(eventJson, GpsEvent::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing GPS event: ${e.message}")
            }
        }

        if (gpsEvent == null) {
            Toast.makeText(this, "Invalid GPS event data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Setup info header (lightweight, can be done on main thread)
        setupInfoHeader()

        // Initialize map in background
        initializeMapAsync()
    }

    private fun setupInfoHeader() {
        val event = gpsEvent ?: return

        findViewById<TextView>(R.id.event_type_text).text = event.typeDisplay ?: "GPS Alert"
        findViewById<TextView>(R.id.vehicle_number_text).text = event.vehicleNumber ?: "N/A"
        findViewById<TextView>(R.id.vehicle_transporter_text).text = event.vehicleTransporter ?: "N/A"

        // Show alert sub-type for tamper events
        val alertSubtypeContainer = findViewById<LinearLayout>(R.id.alert_subtype_container)
        if (event.type == "tamper" && event.data?.alertSubType != null) {
            alertSubtypeContainer.visibility = View.VISIBLE
            findViewById<TextView>(R.id.alert_subtype_text).text = event.data.alertSubType
        } else {
            alertSubtypeContainer.visibility = View.GONE
        }

        // Update legend text based on event type
        val legendText = when (event.type) {
            "off-route" -> "Off-Route Point"
            "tamper" -> "Tamper Location"
            "overspeed" -> "Overspeed Point"
            else -> "Alert Location"
        }
        findViewById<TextView>(R.id.legend_alert_text)?.text = legendText
    }

    private fun initializeMapAsync() {
        progressBar.visibility = View.VISIBLE

        // Log event info to ensure we're loading correct data
        gpsEvent?.let { event ->
            Log.d(TAG, "Loading map for event: ${event.id}, type: ${event.type}, vehicle: ${event.vehicleNumber}")
            event.data?.alertLocation?.let {
                Log.d(TAG, "Alert Location: lat=${it.lat}, lng=${it.lng}")
            }
            event.data?.currentLocation?.let {
                Log.d(TAG, "Current Location: lat=${it.lat}, lng=${it.lng}")
            }
            event.data?.geofence?.let { geofence ->
                Log.d(TAG, "Geofence: id=${geofence.id}, name=${geofence.name}, type=${geofence.type}")
            }
        }

        lifecycleScope.launch {
            try {
                // Do heavy initialization in background
                val mapData = withContext(Dispatchers.Default) {
                    prepareMapData()
                }

                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    setupMap(mapData)
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing map: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@MapActivity,
                        "Error loading map: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private data class MapData(
        val markers: List<MarkerData>,
        val geofenceOverlays: List<GeofenceOverlay>,
        val centerPoint: GeoPoint?,
        val boundingPoints: List<GeoPoint>
    )

    private data class MarkerData(
        val position: GeoPoint,
        val title: String,
        val snippet: String?,
        val iconRes: Int
    )

    private sealed class GeofenceOverlay {
        data class PolygonOverlay(
            val points: List<GeoPoint>,
            val fillColor: Int,
            val strokeColor: Int,
            val strokeWidth: Float,
            val name: String?,
            val description: String?
        ) : GeofenceOverlay()

        data class PolylineOverlay(
            val points: List<GeoPoint>,
            val strokeColor: Int,
            val strokeWidth: Float,
            val name: String?,
            val description: String?
        ) : GeofenceOverlay()

        data class CircleOverlay(
            val center: GeoPoint,
            val radius: Double,
            val fillColor: Int,
            val strokeColor: Int,
            val strokeWidth: Float,
            val name: String?,
            val description: String?
        ) : GeofenceOverlay()
    }

    private fun prepareMapData(): MapData {
        val event = gpsEvent ?: throw IllegalStateException("No GPS event")

        val markers = mutableListOf<MarkerData>()
        val geofenceOverlays = mutableListOf<GeofenceOverlay>()
        val boundingPoints = mutableListOf<GeoPoint>()

        // Event type specific logic:
        // OFF-ROUTE: alertLocation = where vehicle went off-route (outside geofence)
        // TAMPER: alertLocation = where tamper occurred (can be anywhere, geofence optional)
        // OVERSPEED: alertLocation = where overspeed was detected

        // Always show alert location (primary marker) - THIS IS THE KEY FOR TAMPER EVENTS
        event.data?.alertLocation?.let { loc ->
            val position = GeoPoint(loc.lat, loc.lng)
            val (title, iconRes) = when (event.type) {
                "off-route" -> Pair("Off-Route Location", R.drawable.ic_location_red)
                "tamper" -> Pair("Tamper Alert", R.drawable.ic_location_red)
                "overspeed" -> Pair("Overspeed Location", R.drawable.ic_location_red)
                else -> Pair("Alert Location", R.drawable.ic_location_red)
            }

            markers.add(
                MarkerData(
                    position = position,
                    title = title,
                    snippet = "Vehicle: ${event.vehicleNumber ?: "Unknown"}",
                    iconRes = iconRes
                )
            )
            boundingPoints.add(position)

            // For tamper events without geofence, add padding points around alert location
            // This ensures the map zooms appropriately
            if (event.type == "tamper" && event.data.geofence == null) {
                // Add padding points (approximately 200 meters in each direction)
                boundingPoints.add(GeoPoint(position.latitude + 0.002, position.longitude))
                boundingPoints.add(GeoPoint(position.latitude - 0.002, position.longitude))
                boundingPoints.add(GeoPoint(position.latitude, position.longitude + 0.002))
                boundingPoints.add(GeoPoint(position.latitude, position.longitude - 0.002))
            }
        }

        // Show current location only if different from alert location
        event.data?.currentLocation?.let { loc ->
            val alert = event.data.alertLocation
            // Check if current location is significantly different (more than 10 meters)
            val isDifferent = if (alert != null) {
                val latDiff = Math.abs(loc.lat - alert.lat)
                val lngDiff = Math.abs(loc.lng - alert.lng)
                latDiff > 0.0001 || lngDiff > 0.0001 // ~11 meters
            } else {
                true
            }

            if (isDifferent) {
                val position = GeoPoint(loc.lat, loc.lng)
                markers.add(
                    MarkerData(
                        position = position,
                        title = "Current Location",
                        snippet = "Latest position",
                        iconRes = R.drawable.ic_location_orange
                    )
                )
                boundingPoints.add(position)
            }
        }

        // Prepare geofence overlays (for context)
        // Note: For tamper events, geofence is optional - just for reference if available
        event.data?.geofence?.let { geofence ->
            Log.d(TAG, "Processing geofence: id=${geofence.id}, name=${geofence.name}, type=${geofence.type}, geoJsonType=${geofence.geojson?.type}")

            val coordinates = geofence.geojson?.getCoordinatesAsList()
            if (!coordinates.isNullOrEmpty()) {
                // Get colors from attributes
                val color = parseColor(geofence.attributes?.color)
                val strokeColor = parseColor(geofence.attributes?.polylineColor) ?: color

                // Determine stroke width based on geofence type
                val strokeWidth = if (geofence.type == "P") 8f else 5f // Path = thicker

                Log.d(TAG, "Geofence coordinates: ${coordinates.size} points, color: ${geofence.attributes?.color}, geoJsonType: ${geofence.geojson?.type}")

                when (geofence.geojson?.type) {
                    "Point" -> {
                        val point = coordinates.first()
                        val position = GeoPoint(point[1], point[0])
                        geofenceOverlays.add(
                            GeofenceOverlay.CircleOverlay(
                                center = position,
                                radius = 100.0, // 100m radius
                                fillColor = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color)),
                                strokeColor = strokeColor,
                                strokeWidth = 3f,
                                name = geofence.name,
                                description = geofence.description
                            )
                        )
                        boundingPoints.add(position)
                        // Add padding points
                        boundingPoints.add(GeoPoint(position.latitude + 0.002, position.longitude))
                        boundingPoints.add(GeoPoint(position.latitude - 0.002, position.longitude))

                        Log.d(TAG, "Added Point geofence: ${geofence.name} at [${point[1]}, ${point[0]}]")
                    }
                    "LineString" -> {
                        val points = coordinates.map { GeoPoint(it[1], it[0]) }

                        // Priority: 1. polylineColor, 2. attributes.color, 3. yellow for type P, 4. default blue
                        val lineColor = when {
                            geofence.attributes?.polylineColor != null ->
                                parseColor(geofence.attributes.polylineColor)
                            geofence.attributes?.color != null ->
                                parseColor(geofence.attributes.color)
                            geofence.type == "P" ->
                                Color.parseColor("#FFC107") // Yellow fallback for paths
                            else ->
                                Color.parseColor("#3388ff") // Default blue
                        }

                        geofenceOverlays.add(
                            GeofenceOverlay.PolylineOverlay(
                                points = points,
                                strokeColor = lineColor,
                                strokeWidth = strokeWidth,
                                name = geofence.name,
                                description = geofence.description
                            )
                        )
                        boundingPoints.addAll(points)

                        Log.d(TAG, "Added LineString geofence: ${geofence.name} with ${points.size} points, type=${geofence.type}, color=${geofence.attributes?.color}, lineColor=${String.format("#%06X", 0xFFFFFF and lineColor)}")
                    }

                    "Polygon" -> {
                        val points = coordinates.map { GeoPoint(it[1], it[0]) }
                        geofenceOverlays.add(
                            GeofenceOverlay.PolygonOverlay(
                                points = points,
                                fillColor = Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)),
                                strokeColor = strokeColor,
                                strokeWidth = 4f,
                                name = geofence.name,
                                description = geofence.description
                            )
                        )
                        boundingPoints.addAll(points)

                        Log.d(TAG, "Added Polygon geofence: ${geofence.name} with ${points.size} points")
                    }

                    else -> {
                        Log.w(TAG, "Unknown geofence GeoJSON type: ${geofence.geojson?.type}")
                    }
                }
            } else {
                Log.w(TAG, "Geofence has no valid coordinates: ${geofence.name}")
            }
        } ?: Log.d(TAG, "No geofence data for ${event.type} event (this is OK for tamper events)")

        // Determine center point priority:
        // 1. Alert location (where the event happened - CRITICAL for tamper)
        // 2. Current location (if alert location missing)
        val centerPoint = event.data?.alertLocation?.let { GeoPoint(it.lat, it.lng) }
            ?: event.data?.currentLocation?.let { GeoPoint(it.lat, it.lng) }

        Log.d(TAG, "MapData prepared: ${markers.size} markers, ${geofenceOverlays.size} geofences, ${boundingPoints.size} bounding points, centerPoint: $centerPoint")

        return MapData(markers, geofenceOverlays, centerPoint, boundingPoints)
    }

    private fun setupMap(mapData: MapData) {
        mapView = findViewById(R.id.map_view)

        // Configure map with Google Hybrid tiles (satellite + labels)
        val baseUrls = arrayOf(
            "https://mt0.google.com/vt/lyrs=y&hl=en",
            "https://mt1.google.com/vt/lyrs=y&hl=en",
            "https://mt2.google.com/vt/lyrs=y&hl=en",
            "https://mt3.google.com/vt/lyrs=y&hl=en"
        )

        mapView.setTileSource(object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
            "Google-Hybrid",
            0, 22, 256, ".png",
            baseUrls
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val zoom = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
                val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
                val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
                val serverIndex = (x + y) % baseUrls.size
                return "${baseUrls[serverIndex]}&x=$x&y=$y&z=$zoom&s=Ga"
            }
        })

        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)

        // Set initial view to Jharkhand (before zooming to actual location)
        val jharkhandCenter = GeoPoint(JHARKHAND_CENTER_LAT, JHARKHAND_CENTER_LNG)
        mapView.controller.setZoom(JHARKHAND_DEFAULT_ZOOM)
        mapView.controller.setCenter(jharkhandCenter)

        // Add geofence overlays FIRST (so they appear behind markers)
        mapData.geofenceOverlays.forEach { overlay ->
            when (overlay) {
                is GeofenceOverlay.CircleOverlay -> {
                    val circle = Polygon(mapView)
                    circle.points = Polygon.pointsAsCircle(overlay.center, overlay.radius)
                    circle.fillPaint.color = overlay.fillColor
                    circle.outlinePaint.color = overlay.strokeColor
                    circle.outlinePaint.strokeWidth = overlay.strokeWidth
                    mapView.overlays.add(circle)
                }

                is GeofenceOverlay.PolylineOverlay -> {
                    val polyline = Polyline(mapView)
                    polyline.setPoints(overlay.points)
                    polyline.outlinePaint.color = overlay.strokeColor
                    polyline.outlinePaint.strokeWidth = overlay.strokeWidth
                    polyline.outlinePaint.isAntiAlias = true
                    mapView.overlays.add(polyline)
                }

                is GeofenceOverlay.PolygonOverlay -> {
                    val polygon = Polygon(mapView)
                    polygon.points = overlay.points
                    polygon.fillPaint.color = overlay.fillColor
                    polygon.outlinePaint.color = overlay.strokeColor
                    polygon.outlinePaint.strokeWidth = overlay.strokeWidth
                    polygon.outlinePaint.isAntiAlias = true
                    mapView.overlays.add(polygon)
                }
            }
        }

        // Add location markers ON TOP of geofences
        mapData.markers.forEach { markerData ->
            val marker = Marker(mapView)
            marker.position = markerData.position
            marker.title = markerData.title
            marker.snippet = markerData.snippet
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.icon = ContextCompat.getDrawable(this, markerData.iconRes)
            mapView.overlays.add(marker)
        }

        // Zoom to actual event location with animation
        if (mapData.boundingPoints.isNotEmpty()) {
            try {
                val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(mapData.boundingPoints)
                mapView.post {
                    mapView.zoomToBoundingBox(boundingBox, true, 150)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error zooming to bounds: ${e.message}")
                mapData.centerPoint?.let {
                    mapView.controller.animateTo(it, 15.0, 1000L)
                }
            }
        } else {
            // If no bounding points, zoom to center point or stay at Jharkhand view
            mapData.centerPoint?.let {
                mapView.controller.animateTo(it, 15.0, 1000L)
            }
        }

        Log.d(TAG, "Map setup complete with ${mapData.markers.size} markers and ${mapData.geofenceOverlays.size} geofences")
    }

    private fun parseColor(colorStr: String?): Int {
        return try {
            Color.parseColor(colorStr ?: "#3388ff")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse color: $colorStr, using default")
            Color.parseColor("#3388ff")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) {
            mapView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) {
            mapView.onPause()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mapView.isInitialized) {
            mapView.onDetach()
        }
    }
}