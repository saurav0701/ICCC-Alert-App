package com.example.iccc_alert_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.GridLayoutManager
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
    private lateinit var fabGridView: com.google.android.material.floatingactionbutton.FloatingActionButton

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
        setSelectedMenuItem(R.id.nav_camera_streams)

        initializeViews()

        // Load last selected area
        currentArea = getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
            .getString(PREF_LAST_AREA, "barora") ?: "barora"

        setupSwipeRefresh()
        setupRecyclerView()
        setupFab()

        // Register receiver
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

        // Initial UI setup
        refreshUI()
    }

    private fun initializeViews() {
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        recyclerView = findViewById(R.id.cameras_recycler)
        emptyView = findViewById(R.id.empty_cameras_view)
        areaSpinner = findViewById(R.id.area_spinner)
        statsTextView = findViewById(R.id.stats_text)
        fabGridView = findViewById(R.id.fab_grid_view)
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeResources(
            R.color.colorPrimary,
            R.color.colorAccent,
            R.color.colorPrimaryDark
        )

        swipeRefreshLayout.setOnRefreshListener {
            Log.d(TAG, "🔄 Manual refresh triggered")
            refreshUI()
        }
    }

    private fun setupAreaSpinner() {
        // Get areas from CameraManager
        val availableAreas = CameraManager.getAreas()

        if (availableAreas.isEmpty()) {
            Log.w(TAG, "⚠️ No areas available - waiting for camera data")
            areaSpinner.visibility = View.GONE
            return
        }

        areaSpinner.visibility = View.VISIBLE

        areas = availableAreas.map { area ->
            area.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }

        Log.d(TAG, "📍 Setting up spinner with ${areas.size} areas: $areas")

        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            areas
        )
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        areaSpinner.adapter = spinnerAdapter

        // Set current area selection
        val currentIndex = areas.indexOfFirst { it.equals(currentArea, ignoreCase = true) }
        if (currentIndex >= 0) {
            areaSpinner.setSelection(currentIndex, false) // false = don't trigger listener
            Log.d(TAG, "📍 Set spinner to position $currentIndex: ${areas[currentIndex]}")
        }

        areaSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedArea = areas[position].lowercase()

                if (selectedArea != currentArea) {
                    currentArea = selectedArea
                    saveLastSelectedArea(currentArea)
                    Log.d(TAG, "📍 Area changed to: $currentArea")
                    loadCamerasForArea(currentArea)
                } else if (isFirstLoad) {
                    // Initial selection - load cameras
                    isFirstLoad = false
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

        recyclerView.layoutManager = GridLayoutManager(this, 2)
        recyclerView.adapter = adapter
    }

    private fun setupFab() {
        fabGridView.setOnClickListener {
            val intent = Intent(this, CameraGridViewActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * Refresh UI - check if we have data, setup spinner if needed, load cameras
     */
    private fun refreshUI() {
        activityScope.launch {
            try {
                swipeRefreshLayout.isRefreshing = true

                withContext(Dispatchers.Default) {
                    delay(100) // Brief delay to ensure CameraManager has processed update
                }

                if (!CameraManager.hasData()) {
                    Log.d(TAG, "⏳ No camera data yet - showing waiting message")
                    showWaitingForData()
                    swipeRefreshLayout.isRefreshing = false
                    return@launch
                }

                // Setup spinner if not already setup
                if (areas.isEmpty()) {
                    setupAreaSpinner()
                }

                // Load cameras for current area
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
                        showEmptyView("No cameras found in $area")
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
        val text = "Total: ${stats.total} | Online: ${stats.online} | Offline: ${stats.offline}"
        statsTextView.text = text
        Log.d(TAG, "📊 Stats for $area: $text")
    }

    private fun openCameraStream(camera: CameraInfo) {
        Log.d(TAG, "🎥 Opening stream for: ${camera.name}")

        val intent = Intent(this, CameraStreamPlayerActivity::class.java)
        intent.putExtra("CAMERA_ID", camera.id)
        intent.putExtra("CAMERA_NAME", camera.name)
        intent.putExtra("CAMERA_AREA", camera.area)
        intent.putExtra("STREAM_URL", camera.getStreamURL())
        startActivity(intent)
    }

    private fun showWaitingForData() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        statsTextView.text = "Waiting for camera data..."
    }

    private fun showEmptyView(message: String = "No cameras found") {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
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
            R.id.action_grid_view -> {
                val intent = Intent(this, CameraGridViewActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_filter_online -> {
                showOnlineOnlyForArea()
                true
            }
            R.id.action_refresh -> {
                refreshUI()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showOnlineOnlyForArea() {
        Log.d(TAG, "Filtering to show online cameras only for $currentArea")

        activityScope.launch(Dispatchers.Default) {
            val onlineCameras = CameraManager.getOnlineCamerasByArea(currentArea)

            Log.d(TAG, "Found ${onlineCameras.size} online cameras in $currentArea")

            withContext(Dispatchers.Main) {
                if (onlineCameras.isEmpty()) {
                    showEmptyView("No online cameras in $currentArea")
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
        // ✅ Pause adapter when leaving
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