package com.example.iccc_alert_app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class CameraMapActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraMapActivity"
        private const val DEFAULT_ZOOM = 13.0
        private const val DEFAULT_LAT = 23.7957
        private const val DEFAULT_LNG = 86.4304

        private const val PREF_CACHE_KEY = "camera_coordinates_cache"
        private const val PREF_CACHE_TIME_KEY = "camera_cache_timestamp"
        private const val CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private lateinit var mapView: MapView
    private lateinit var areaFilterChips: ChipGroup
    private lateinit var statusFilterChips: ChipGroup
    private lateinit var statsCard: CardView
    private lateinit var statsText: TextView
    private lateinit var loadingView: FrameLayout
    private lateinit var emptyView: LinearLayout
    private lateinit var selectedAreaText: TextView
    private lateinit var btnBack: ImageButton

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var allCameras = listOf<CameraInfo>()
    private var filteredCameras = listOf<CameraInfo>()
    private var selectedAreas = mutableSetOf<String>()
    private var showOnlineOnly = false

    private val markers = mutableListOf<Marker>()
    private val coordinateCache = mutableMapOf<String, Pair<Double, Double>>()

    private var loadAllAreas = false
    private var hasUserInteracted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Configuration.getInstance().load(
                applicationContext,
                PreferenceManager.getDefaultSharedPreferences(applicationContext)
            )
            Configuration.getInstance().userAgentValue = packageName
            Configuration.getInstance().tileFileSystemCacheMaxBytes = 50L * 1024L * 1024L
            Configuration.getInstance().tileFileSystemCacheTrimBytes = 40L * 1024L * 1024L
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing osmdroid: ${e.message}")
        }

        setContentView(R.layout.activity_camera_map_osm)

        supportActionBar?.title = "Camera Map View"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initializeViews()
        setupFilters()
        initializeMap()
        loadCoordinateCache()
        loadCameras()
    }

    private fun initializeViews() {
        mapView = findViewById(R.id.map_view)
        areaFilterChips = findViewById(R.id.area_filter_chips)
        statusFilterChips = findViewById(R.id.status_filter_chips)
        statsCard = findViewById(R.id.stats_card)
        statsText = findViewById(R.id.stats_text)
        loadingView = findViewById(R.id.loading_view)
        emptyView = findViewById(R.id.empty_view)
        selectedAreaText = findViewById(R.id.selected_area_text)
        btnBack = findViewById(R.id.btn_back)

        // Setup back button
        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun setupFilters() {
        val allChip = statusFilterChips.findViewById<Chip>(R.id.chip_all)
        val onlineChip = statusFilterChips.findViewById<Chip>(R.id.chip_online)

        allChip.isChecked = true

        statusFilterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            showOnlineOnly = checkedIds.contains(R.id.chip_online)
            hasUserInteracted = false
            filterAndDisplayCameras()
        }

        mapView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                hasUserInteracted = true
            }
            false
        }
    }

    private fun initializeMap() {
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
                val zoom = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                val serverIndex = (x + y) % baseUrls.size
                return "${baseUrls[serverIndex]}&x=$x&y=$y&z=$zoom&s=Ga"
            }
        })

        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)

        val defaultLocation = GeoPoint(DEFAULT_LAT, DEFAULT_LNG)
        mapView.controller.setZoom(DEFAULT_ZOOM)
        mapView.controller.setCenter(defaultLocation)

        Log.d(TAG, "✅ Map initialized")
    }

    private fun loadCameras() {
        activityScope.launch {
            try {
                showLoading()

                if (!CameraManager.hasData()) {
                    Log.w(TAG, "⚠️ No camera data available")
                    showEmpty("No camera data available")
                    return@launch
                }

                allCameras = withContext(Dispatchers.Default) {
                    CameraManager.getAllCameras()
                }

                Log.d(TAG, "📹 Loaded ${allCameras.size} cameras")

                if (allCameras.isEmpty()) {
                    showEmpty("No cameras found")
                    return@launch
                }

                cacheCoordinates(allCameras)
                setupAreaFilters()
                filterAndDisplayCameras()
                hideLoading()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading cameras: ${e.message}", e)
                showEmpty("Error loading cameras")
            }
        }
    }

    private fun setupAreaFilters() {
        areaFilterChips.removeAllViews()

        val areas = allCameras.map { it.area }.distinct().sorted()

        areas.forEachIndexed { index, area ->
            val chip = Chip(this).apply {
                text = area.uppercase()
                isCheckable = true
                isChecked = false

                setChipBackgroundColorResource(R.color.colorPrimary)
                setTextColor(Color.WHITE)

                setOnCheckedChangeListener { buttonView, isChecked ->
                    if (isChecked) {
                        // Uncheck all other chips
                        for (i in 0 until areaFilterChips.childCount) {
                            val otherChip = areaFilterChips.getChildAt(i) as? Chip
                            if (otherChip != null && otherChip != buttonView) {
                                otherChip.isChecked = false
                            }
                        }

                        selectedAreas.clear()
                        selectedAreas.add(area)
                        hasUserInteracted = false
                        updateSelectedAreaDisplay() // ✅ NEW
                        filterAndDisplayCameras()
                    }
                }
            }
            areaFilterChips.addView(chip)

            if (index == 0) {
                chip.isChecked = true
                selectedAreas.add(area)
            }
        }

        updateSelectedAreaDisplay() // ✅ NEW
        Log.d(TAG, "📍 Default area: ${selectedAreas.firstOrNull()}")
    }

    // ✅ UPDATED: Update selected area display
    private fun updateSelectedAreaDisplay() {
        val selectedArea = selectedAreas.firstOrNull()
        if (selectedArea != null) {
            selectedAreaText.text = selectedArea.uppercase()
            selectedAreaText.visibility = View.VISIBLE
        } else {
            selectedAreaText.text = "SELECT AREA"
            selectedAreaText.visibility = View.VISIBLE
        }
    }

    private fun filterAndDisplayCameras() {
        activityScope.launch(Dispatchers.Default) {
            filteredCameras = allCameras.filter { camera ->
                val areaMatch = selectedAreas.contains(camera.area)
                val statusMatch = if (showOnlineOnly) camera.isOnline() else true
                val hasCoordinates = coordinateCache.containsKey(camera.id)
                areaMatch && statusMatch && hasCoordinates
            }

            Log.d(TAG, "🔍 Filtered to ${filteredCameras.size} cameras for area: ${selectedAreas.firstOrNull()}")

            withContext(Dispatchers.Main) {
                if (filteredCameras.isEmpty()) {
                    showEmpty("No cameras in ${selectedAreas.firstOrNull()?.uppercase()}")
                } else {
                    displayCamerasOnMap(filteredCameras)
                    updateStats()
                }
            }
        }
    }

    private fun displayCamerasOnMap(cameras: List<CameraInfo>) {
        mapView.overlays.clear()
        markers.clear()

        val currentZoom = mapView.zoomLevelDouble
        val boundingPoints = mutableListOf<GeoPoint>()

        cameras.forEach { camera ->
            addCameraMarker(camera)?.let { marker ->
                mapView.overlays.add(marker)
                markers.add(marker)
                boundingPoints.add(marker.position)
            }
        }

        if (boundingPoints.isNotEmpty() && !hasUserInteracted) {
            try {
                val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(boundingPoints)
                mapView.post {
                    mapView.zoomToBoundingBox(boundingBox, true, 100)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error zooming to bounds: ${e.message}", e)
            }
        }

        mapView.invalidate()
        Log.d(TAG, "📍 Showing ${markers.size} individual camera markers (zoom: $currentZoom)")
    }

    private fun addCameraMarker(camera: CameraInfo): Marker? {
        val coords = coordinateCache[camera.id] ?: return null
        val (lat, lng) = coords

        val position = GeoPoint(lat, lng)
        val icon = createMarkerBitmap(camera.isOnline(), 1)

        val marker = Marker(mapView)
        marker.position = position
        marker.title = camera.name
        marker.snippet = "${camera.area.uppercase()} - ${if (camera.isOnline()) "ONLINE ✅" else "OFFLINE ❌"}\nTap to view stream"
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.icon = android.graphics.drawable.BitmapDrawable(resources, icon)

        marker.setOnMarkerClickListener { clickedMarker, _ ->
            clickedMarker.showInfoWindow()
            openCameraStream(camera)
            true
        }

        return marker
    }

    private fun createMarkerBitmap(isOnline: Boolean, count: Int): Bitmap {
        val size = 80
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        val color = if (isOnline) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRect(size / 3f, size / 2.5f, size * 2 / 3f, size / 1.5f, paint)

        return bitmap
    }

    private fun cacheCoordinates(cameras: List<CameraInfo>) {
        cameras.forEach { camera ->
            val lat = camera.latitude.toDoubleOrNull()
            val lng = camera.longitude.toDoubleOrNull()
            if (lat != null && lng != null) {
                coordinateCache[camera.id] = Pair(lat, lng)
            }
        }

        saveCoordinateCache()
        Log.d(TAG, "✅ Cached ${coordinateCache.size} camera coordinates")
    }

    private fun saveCoordinateCache() {
        val prefs = getSharedPreferences("camera_map_cache", MODE_PRIVATE)
        val editor = prefs.edit()

        val cacheJson = coordinateCache.entries.joinToString(";") { (id, coords) ->
            "$id,${coords.first},${coords.second}"
        }

        editor.putString(PREF_CACHE_KEY, cacheJson)
        editor.putLong(PREF_CACHE_TIME_KEY, System.currentTimeMillis())
        editor.apply()
    }

    private fun loadCoordinateCache() {
        val prefs = getSharedPreferences("camera_map_cache", MODE_PRIVATE)
        val cacheTime = prefs.getLong(PREF_CACHE_TIME_KEY, 0)

        if (System.currentTimeMillis() - cacheTime > CACHE_VALIDITY_MS) {
            Log.d(TAG, "Cache expired, will rebuild")
            return
        }

        val cacheJson = prefs.getString(PREF_CACHE_KEY, null) ?: return

        try {
            cacheJson.split(";").forEach { entry ->
                val parts = entry.split(",")
                if (parts.size == 3) {
                    val id = parts[0]
                    val lat = parts[1].toDouble()
                    val lng = parts[2].toDouble()
                    coordinateCache[id] = Pair(lat, lng)
                }
            }
            Log.d(TAG, "✅ Loaded ${coordinateCache.size} cached coordinates")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cache: ${e.message}")
            coordinateCache.clear()
        }
    }

    private fun openCameraStream(camera: CameraInfo) {
        if (!camera.isOnline()) {
            Toast.makeText(this, "${camera.name} is offline", Toast.LENGTH_SHORT).show()
            return
        }

        if (!camera.hasValidStreamURL()) {
            Toast.makeText(this, "Stream not available", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, CameraStreamPlayerActivity::class.java).apply {
            putExtra("CAMERA_ID", camera.id)
            putExtra("CAMERA_NAME", camera.name)
            putExtra("CAMERA_AREA", camera.area)
            putExtra("STREAM_URL", camera.getStreamURL())
        }
        startActivity(intent)
    }

    private fun updateStats() {
        val total = filteredCameras.size
        val online = filteredCameras.count { it.isOnline() }
        val offline = total - online

        statsText.text = "Showing: $total cameras | ✅ $online online | ❌ $offline offline"
        statsCard.visibility = View.VISIBLE
    }

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        statsCard.visibility = View.GONE
    }

    private fun hideLoading() {
        loadingView.visibility = View.GONE
    }

    private fun showEmpty(message: String) {
        loadingView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        statsCard.visibility = View.GONE

        emptyView.findViewById<TextView>(R.id.empty_text).text = message
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_camera_map, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // ✅ FIXED: Just finish, don't force navigation
                finish()
                true
            }
            R.id.action_refresh -> {
                loadCameras()
                true
            }
            R.id.action_reset_filters -> {
                selectedAreas.clear()
                setupAreaFilters()
                true
            }
            R.id.action_change_map_type -> {
                Toast.makeText(this, "Map type: Google Hybrid (Satellite + Labels)", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ✅ REMOVED: Let system handle back navigation naturally
    // override fun onBackPressed() - removed

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (allCameras.isNotEmpty()) {
            filterAndDisplayCameras()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        mapView.onDetach()
    }
}