package com.example.iccc_alert_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.*

class CameraGridViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraGridView"
        private const val GRID_COLUMNS = 2
        private const val PREF_LAST_AREA = "last_grid_area"
    }

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var areaSpinner: Spinner
    private lateinit var statsTextView: TextView
    private lateinit var backButton: ImageButton
    private lateinit var adapter: CameraGridAdapter

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentArea = "barora"
    private var areas = listOf<String>()

    private val cameraUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "📹 Camera update broadcast received")
            refreshCurrentArea()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_grid_view)

        // Make status bar transparent
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        // Hide action bar since we have custom toolbar
        supportActionBar?.hide()

        // Load last selected area
        currentArea = getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
            .getString(PREF_LAST_AREA, "barora") ?: "barora"

        initializeViews()
        setupSwipeRefresh()
        setupRecyclerView()
        setupAreaSpinner()
        setupBackButton()

        // Register camera update receiver
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

        loadCamerasForArea(currentArea)
    }

    private fun initializeViews() {
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        recyclerView = findViewById(R.id.grid_recycler)
        emptyView = findViewById(R.id.empty_view)
        areaSpinner = findViewById(R.id.area_spinner)
        statsTextView = findViewById(R.id.stats_text)
        backButton = findViewById(R.id.back_button)
    }

    private fun setupBackButton() {
        backButton.setOnClickListener {
            finish()
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeResources(
            R.color.colorPrimary,
            R.color.colorAccent,
            R.color.colorPrimaryDark
        )

        swipeRefreshLayout.setOnRefreshListener {
            Log.d(TAG, "🔄 Manual refresh triggered")
            refreshCurrentArea()
        }
    }

    private fun setupAreaSpinner() {
        areas = CameraManager.getAreas().map {
            it.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }

        if (areas.isEmpty()) {
            Log.w(TAG, "⚠️ No areas available")
            return
        }

        Log.d(TAG, "📍 Available areas: $areas")

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
            areaSpinner.setSelection(currentIndex)
        }

        areaSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedArea = areas[position].lowercase()
                if (selectedArea != currentArea) {
                    currentArea = selectedArea
                    saveLastSelectedArea(currentArea)
                    Log.d(TAG, "Area changed to: $currentArea")
                    loadCamerasForArea(currentArea)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerView() {
        adapter = CameraGridAdapter(this) { camera ->
            openFullscreenCamera(camera)
        }

        recyclerView.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        recyclerView.adapter = adapter

        val spacing = resources.getDimensionPixelSize(R.dimen.grid_spacing)
        recyclerView.addItemDecoration(GridSpacingItemDecoration(GRID_COLUMNS, spacing, true))
    }

    private fun loadCamerasForArea(area: String) {
        Log.d(TAG, "🔍 Loading online cameras for area: $area")

        activityScope.launch {
            try {
                swipeRefreshLayout.isRefreshing = true

                val cameras = withContext(Dispatchers.Default) {
                    CameraManager.getOnlineCamerasByArea(area)
                }

                Log.d(TAG, "📹 Loaded ${cameras.size} online cameras for $area")

                withContext(Dispatchers.Main) {
                    swipeRefreshLayout.isRefreshing = false

                    if (cameras.isEmpty()) {
                        showEmptyView()
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
                    showEmptyView()
                }
            }
        }
    }

    private fun refreshCurrentArea() {
        loadCamerasForArea(currentArea)
    }

    private fun updateStatistics(area: String) {
        val stats = CameraManager.getAreaStatistics(area)
        statsTextView.text = "Showing: ${stats.online} online cameras"
        Log.d(TAG, "📊 Stats for $area: ${stats.online} online cameras")
    }

    private fun openFullscreenCamera(camera: CameraInfo) {
        Log.d(TAG, "🎥 Opening stream for: ${camera.name}")

        // ✅ CRITICAL: Pause all grid streams before opening fullscreen
        adapter.pauseAllStreams()

        val intent = Intent(this, CameraStreamPlayerActivity::class.java)
        intent.putExtra("CAMERA_ID", camera.id)
        intent.putExtra("CAMERA_NAME", camera.name)
        intent.putExtra("CAMERA_AREA", camera.area)
        intent.putExtra("STREAM_URL", camera.getStreamURL())
        startActivity(intent)
    }

    private fun showEmptyView() {
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

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Activity resumed - resuming streams")

        // ✅ CRITICAL: Resume streams when returning from fullscreen
        adapter.resumeAllStreams()

        // Refresh to ensure we have latest status
        refreshCurrentArea()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "Activity paused - pausing streams")

        // ✅ Pause all streams when leaving
        adapter.pauseAllStreams()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "Activity stopped - cleaning up resources")

        // ✅ CRITICAL: Clean up when activity is no longer visible
        adapter.cleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(cameraUpdateReceiver)

        // ✅ CRITICAL: Final cleanup
        adapter.cleanup()

        activityScope.cancel()
        Log.d(TAG, "Activity destroyed")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // ✅ Clean up before going back
        adapter.pauseAllStreams()
        super.onBackPressed()
    }
}

class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacing: Int,
    private val includeEdge: Boolean
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        val column = position % spanCount

        if (includeEdge) {
            outRect.left = spacing - column * spacing / spanCount
            outRect.right = (column + 1) * spacing / spanCount

            if (position < spanCount) {
                outRect.top = spacing
            }
            outRect.bottom = spacing
        } else {
            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            if (position >= spanCount) {
                outRect.top = spacing
            }
        }
    }
}