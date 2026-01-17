package com.example.iccc_alert_app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.iccc_alert_app.auth.AuthManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * ✅ Create Multi-Camera View Activity (Fixed)
 * Select up to 4 cameras for quad view
 * Prevents RecyclerView IllegalStateException
 */
class CreateMultiCameraViewActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var viewNameInput: EditText
    private lateinit var selectedCountText: TextView
    private lateinit var areaChips: ChipGroup
    private lateinit var searchView: SearchView
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnCreate: Button
    private lateinit var btnClear: Button

    private lateinit var adapter: CameraSelectionAdapter
    private val selectedCameraIds = mutableSetOf<String>()
    private var selectedArea: String? = null
    private var allowedAreas = listOf<String>()

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_multi_camera_view)

        // Hide the action bar since we have a custom header
        supportActionBar?.hide()

        loadUserAllowedAreas()
        initializeViews()
        setupAreaFilters()
        setupRecyclerView()
        setupListeners()
    }

    private fun loadUserAllowedAreas() {
        val user = AuthManager.getCurrentUser()
        val userArea = user?.area?.trim() ?: ""

        allowedAreas = when {
            userArea.uppercase() == "HQ" -> CameraManager.getAreas()
            userArea.contains(",") -> userArea.split(",").map { it.trim().lowercase() }
            userArea.isNotEmpty() -> listOf(userArea.lowercase())
            else -> emptyList()
        }
    }

    private fun initializeViews() {
        backButton = findViewById(R.id.back_button)
        viewNameInput = findViewById(R.id.view_name_input)
        selectedCountText = findViewById(R.id.selected_count_text)
        areaChips = findViewById(R.id.area_chips)
        searchView = findViewById(R.id.search_view)
        recyclerView = findViewById(R.id.cameras_recycler)
        btnCreate = findViewById(R.id.btn_create)
        btnClear = findViewById(R.id.btn_clear)

        // Handle back button
        backButton.setOnClickListener {
            finish()
        }
    }

    private fun setupAreaFilters() {
        areaChips.removeAllViews()

        val availableAreas = CameraManager.getAreas().filter { backendArea ->
            allowedAreas.any { AreaNormalizer.areasMatch(it, backendArea) }
        }

        availableAreas.forEachIndexed { index, area ->
            val chip = Chip(this).apply {
                text = area.uppercase()
                isCheckable = true
                isChecked = index == 0

                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        // Uncheck others (single selection)
                        for (i in 0 until areaChips.childCount) {
                            val otherChip = areaChips.getChildAt(i) as? Chip
                            if (otherChip != null && otherChip != this) {
                                otherChip.isChecked = false
                            }
                        }
                        selectedArea = area
                        filterCameras()
                    }
                }
            }
            areaChips.addView(chip)
        }

        // Select first area by default
        if (availableAreas.isNotEmpty()) {
            selectedArea = availableAreas.first()
        }
    }

    private fun setupRecyclerView() {
        adapter = CameraSelectionAdapter(
            onCameraSelected = { camera ->
                if (selectedCameraIds.contains(camera.id)) {
                    selectedCameraIds.remove(camera.id)
                } else if (selectedCameraIds.size < 4) {
                    selectedCameraIds.add(camera.id)
                } else {
                    Toast.makeText(this, "Maximum 4 cameras allowed", Toast.LENGTH_SHORT).show()
                }
                updateUI()
            },
            isSelected = { cameraId -> selectedCameraIds.contains(cameraId) },
            canSelect = { selectedCameraIds.size < 4 || selectedCameraIds.contains(it) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        filterCameras()
    }

    private fun setupListeners() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterCameras()
                return true
            }
        })

        btnClear.setOnClickListener {
            selectedCameraIds.clear()
            updateUI()
        }

        btnCreate.setOnClickListener {
            createView()
        }

        // Enable create button when typing
        viewNameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateUI()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun filterCameras() {
        val query = searchView.query.toString()
        val area = selectedArea ?: return

        val cameras = CameraManager.getCamerasByArea(area)
            .filter { it.isOnline() }
            .filter { camera ->
                if (query.isEmpty()) true
                else camera.name.contains(query, ignoreCase = true) ||
                        camera.location.contains(query, ignoreCase = true)
            }

        adapter.updateCameras(cameras)
    }

    private fun updateUI() {
        // Update UI elements
        selectedCountText.text = "Selected: ${selectedCameraIds.size}/4"
        btnCreate.isEnabled = viewNameInput.text.isNotBlank() && selectedCameraIds.isNotEmpty()
        btnClear.isEnabled = selectedCameraIds.isNotEmpty()

        // ✅ CRITICAL FIX: Post adapter update to next frame
        // This ensures RecyclerView is not in the middle of layout computation
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun createView() {
        val name = viewNameInput.text.toString().trim()

        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a view name", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedCameraIds.isEmpty()) {
            Toast.makeText(this, "Select at least one camera", Toast.LENGTH_SHORT).show()
            return
        }

        MultiCameraManager.createView(name, selectedCameraIds.toList())
        Toast.makeText(this, "View created successfully", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up any pending handler callbacks
        mainHandler.removeCallbacksAndMessages(null)
    }
}