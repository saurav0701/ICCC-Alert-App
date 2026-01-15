package com.example.iccc_alert_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.*

class CameraStreamsActivity : BaseDrawerActivity() {

    companion object {
        private const val TAG = "CameraStreamsActivity"
        private const val PREF_LAST_AREA = "last_selected_area"
    }

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var areaSpinner: Spinner
    private lateinit var statsTextView: TextView
    private lateinit var adapter: CameraListAdapter
    private val handler = Handler(Looper.getMainLooper())

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentArea: String = "barora"
    private var searchQuery: String = ""
    private var areas = listOf<String>()
    private var isFirstLoad = true

    private val cameraUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "📹 Camera update broadcast received")
            refreshUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_streams)

        supportActionBar?.title = "Camera Streams"
        supportActionBar?.elevation = 0f
        setSelectedMenuItem(R.id.nav_camera_streams)

        initializeViews()

        currentArea = getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
            .getString(PREF_LAST_AREA, "barora") ?: "barora"

        setupSwipeRefresh()
        setupRecyclerView()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                cameraUpdateReceiver,
                IntentFilter("com.example.iccc_alert_app.CAMERA_UPDATE"),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                cameraUpdateReceiver,
                IntentFilter("com.example.iccc_alert_app.CAMERA_UPDATE")
            )
        }

        Log.d(TAG, "✅ Activity created, loading area: $currentArea")
        refreshUI()
    }

    private fun initializeViews() {
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        recyclerView = findViewById(R.id.cameras_recycler)
        emptyView = findViewById(R.id.empty_cameras_view)
        areaSpinner = findViewById(R.id.area_spinner)
        statsTextView = findViewById(R.id.stats_text)
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeResources(
            R.color.colorPrimary,
            R.color.colorAccent,
            android.R.color.holo_green_light
        )

        swipeRefreshLayout.setOnRefreshListener {
            Log.d(TAG, "🔄 Pull-to-refresh: Requesting latest camera data from backend")

            // Show loading message
            statsTextView.text = "Loading latest camera data..."

            // Force refresh from backend
            CameraManager.forceRefresh()

            // Wait for data to update, then refresh UI
            handler.postDelayed({
                val totalCameras = CameraManager.getAllCameras().size
                val totalAreas = CameraManager.getAreas().size

                if (totalCameras > 0) {
                    Toast.makeText(
                        this,
                        "Loaded $totalCameras cameras from $totalAreas areas",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "No camera data available. Please try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                refreshUI()
            }, 2000) // Give backend time to fetch data
        }
    }

    private fun setupAreaSpinner() {
        val availableAreas = CameraManager.getAreas()

        if (availableAreas.isEmpty()) {
            Log.w(TAG, "⚠️ No areas available - waiting for camera data")
            areaSpinner.visibility = View.GONE
            statsTextView.text = "Pull down to load camera data"
            return
        }

        areaSpinner.visibility = View.VISIBLE

        // Create display names (capitalized) but keep lowercase for internal use
        areas = availableAreas.map { area ->
            area.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }

        Log.d(TAG, "📍 Setting up spinner with ${areas.size} areas: $areas")
        Log.d(TAG, "📍 Current area (lowercase): $currentArea")

        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            areas
        )
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        areaSpinner.adapter = spinnerAdapter

        // ✅ FIX: Case-insensitive matching
        val currentIndex = areas.indexOfFirst {
            it.equals(currentArea, ignoreCase = true)
        }

        if (currentIndex >= 0) {
            areaSpinner.setSelection(currentIndex, false)
            Log.d(TAG, "✅ Set spinner to position $currentIndex: ${areas[currentIndex]}")
        } else {
            Log.w(TAG, "⚠️ Could not find area '$currentArea' in available areas")
            // Set to first area as fallback
            if (areas.isNotEmpty()) {
                currentArea = areas[0].lowercase()
                areaSpinner.setSelection(0, false)
                saveLastSelectedArea(currentArea)
            }
        }

        areaSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Convert display name back to lowercase for internal use
                val selectedArea = areas[position].lowercase()

                if (selectedArea != currentArea) {
                    currentArea = selectedArea
                    saveLastSelectedArea(currentArea)
                    Log.d(TAG, "📍 Area changed to: $currentArea")
                    loadCamerasForArea(currentArea)
                } else if (isFirstLoad) {
                    isFirstLoad = false
                    Log.d(TAG, "📍 Initial load for area: $currentArea")
                    loadCamerasForArea(currentArea)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerView() {
        adapter = CameraListAdapter(this) { camera ->
            openCameraStream(camera)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun refreshUI() {
        activityScope.launch {
            try {
                swipeRefreshLayout.isRefreshing = true

                withContext(Dispatchers.Default) {
                    delay(100)
                }

                if (!CameraManager.hasData()) {
                    Log.d(TAG, "⏳ No camera data yet")
                    showWaitingForData()
                    swipeRefreshLayout.isRefreshing = false
                    return@launch
                }

                // ✅ Setup spinner if not already done or if areas changed
                val availableAreas = CameraManager.getAreas()
                if (areas.isEmpty() || areas.size != availableAreas.size) {
                    setupAreaSpinner()
                }

                loadCamerasForArea(currentArea)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error refreshing UI: ${e.message}", e)
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    private fun loadCamerasForArea(area: String) {
        Log.d(TAG, "🔍 Loading cameras for area: $area")

        activityScope.launch {
            try {
                swipeRefreshLayout.isRefreshing = true

                val cameras = withContext(Dispatchers.Default) {
                    if (searchQuery.isEmpty()) {
                        CameraManager.getCamerasByArea(area)
                    } else {
                        CameraManager.getCamerasByArea(area).filter { camera ->
                            camera.name.contains(searchQuery, ignoreCase = true) ||
                                    camera.location.contains(searchQuery, ignoreCase = true) ||
                                    camera.transporter.contains(searchQuery, ignoreCase = true)
                        }
                    }
                }

                Log.d(TAG, "📹 Loaded ${cameras.size} cameras for $area")

                withContext(Dispatchers.Main) {
                    swipeRefreshLayout.isRefreshing = false

                    if (cameras.isEmpty()) {
                        val displayArea = area.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase() else it.toString()
                        }
                        showEmptyView("No cameras found in $displayArea")
                    } else {
                        hideEmptyView()
                        adapter.updateCameras(cameras)
                    }

                    updateStatistics(area)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading cameras: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    swipeRefreshLayout.isRefreshing = false
                    showEmptyView("Error loading cameras")
                }
            }
        }
    }

    private fun updateStatistics(area: String) {
        val stats = CameraManager.getAreaStatistics(area)
        val allStats = CameraManager.getStatistics()

        // ✅ FIX: Capitalize area name for display
        val displayArea = area.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }

        val text = "$displayArea: ${stats.total} cameras (${stats.online} online) | " +
                "Total: ${allStats.totalCameras} cameras across ${CameraManager.getAreas().size} areas"

        statsTextView.text = text
        Log.d(TAG, "📊 Stats: $text")
    }

    private fun openCameraStream(camera: CameraInfo) {
        Log.d(TAG, "🎥 Opening stream for: ${camera.name}")

        val intent = Intent(this, CameraStreamPlayerActivity::class.java)
        intent.putExtra("CAMERA_ID", camera.id)
        intent.putExtra("CAMERA_NAME", camera.name)
        intent.putExtra("CAMERA_AREA", camera.area)
        intent.putExtra("STREAM_URL", camera.getStreamURL())
        startActivity(intent)

        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
    }

    private fun showWaitingForData() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        statsTextView.text = "Pull down to load camera data"
    }

    private fun showEmptyView(message: String = "No cameras found") {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE

        // Update the empty view text if you have a TextView in your empty view layout
        // findViewById<TextView>(R.id.empty_message)?.text = message
    }

    private fun hideEmptyView() {
        recyclerView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
    }

    private fun saveLastSelectedArea(area: String) {
        getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_AREA, area)
            .apply()
        Log.d(TAG, "💾 Saved last selected area: $area")
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_camera_streams, menu)

        val searchItem = menu?.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView

        searchView?.queryHint = "Search cameras..."
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText ?: ""
                loadCamerasForArea(currentArea)
                return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_map_view -> {
                openMapView()
                true
            }
            R.id.action_filter_online -> {
                showOnlineOnlyForArea()
                true
            }
            R.id.action_refresh -> {
                // Manual refresh button
                Toast.makeText(this, "Refreshing camera list...", Toast.LENGTH_SHORT).show()
                statsTextView.text = "Loading latest camera data..."

                CameraManager.forceRefresh()

                handler.postDelayed({
                    val total = CameraManager.getAllCameras().size
                    val areas = CameraManager.getAreas().size

                    Toast.makeText(
                        this,
                        "Loaded $total cameras from $areas areas",
                        Toast.LENGTH_SHORT
                    ).show()

                    refreshUI()
                }, 2000)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openMapView() {
        if (!CameraManager.hasData()) {
            Toast.makeText(
                this,
                "Loading camera data, please wait...",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val intent = Intent(this, CameraMapActivity::class.java)
        startActivity(intent)
        Log.d(TAG, "🗺️ Opening camera map view")
    }

    private fun showOnlineOnlyForArea() {
        Log.d(TAG, "Filtering to show online cameras only for $currentArea")

        activityScope.launch(Dispatchers.Default) {
            val onlineCameras = CameraManager.getOnlineCamerasByArea(currentArea)

            Log.d(TAG, "Found ${onlineCameras.size} online cameras in $currentArea")

            withContext(Dispatchers.Main) {
                if (onlineCameras.isEmpty()) {
                    val displayArea = currentArea.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    }
                    showEmptyView("No online cameras in $displayArea")
                } else {
                    hideEmptyView()
                    adapter.updateCameras(onlineCameras)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Activity resumed")
        refreshUI()
    }

    override fun onPause() {
        super.onPause()
        adapter.pauseStreams()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(cameraUpdateReceiver)
        adapter.cleanup()
        activityScope.cancel()
        Log.d(TAG, "Activity destroyed")
    }
}