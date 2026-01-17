package com.example.iccc_alert_app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * ✅ Multi-Camera Views List Activity
 * Shows all saved quad views, allows creation/deletion
 */
class MultiCameraViewsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MultiCameraViews"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var fabCreate: FloatingActionButton
    private lateinit var backButton: ImageButton
    private lateinit var adapter: MultiCameraViewAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi_camera_views)

        // Hide the action bar since we have a custom header
        supportActionBar?.hide()

        initializeViews()
        setupRecyclerView()
        loadViews()
    }

    private fun initializeViews() {
        recyclerView = findViewById(R.id.multi_camera_recycler)
        emptyView = findViewById(R.id.empty_view)
        fabCreate = findViewById(R.id.fab_create_view)
        backButton = findViewById(R.id.back_button)

        // Handle back button click
        backButton.setOnClickListener {
            finish()
        }

        fabCreate.setOnClickListener {
            openCreateViewActivity()
        }
    }

    private fun setupRecyclerView() {
        adapter = MultiCameraViewAdapter(
            onViewClick = { view ->
                openQuadPlayer(view)
            },
            onDeleteClick = { view ->
                confirmDelete(view)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun loadViews() {
        val views = MultiCameraManager.savedViews

        if (views.isEmpty()) {
            showEmptyState()
        } else {
            hideEmptyState()
            adapter.updateViews(views)
        }
    }

    private fun openCreateViewActivity() {
        val intent = Intent(this, CreateMultiCameraViewActivity::class.java)
        startActivity(intent)
    }

    private fun openQuadPlayer(view: MultiCameraView) {
        val intent = Intent(this, QuadCameraPlayerActivity::class.java)
        intent.putExtra("VIEW_ID", view.id)
        startActivity(intent)
    }

    private fun confirmDelete(view: MultiCameraView) {
        AlertDialog.Builder(this)
            .setTitle("Delete View")
            .setMessage("Delete \"${view.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                MultiCameraManager.deleteView(view.id)
                loadViews()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEmptyState() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }

    private fun hideEmptyState() {
        recyclerView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        loadViews() // Refresh when returning from create/player
    }
}