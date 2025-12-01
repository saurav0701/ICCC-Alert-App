package com.example.iccc_alert_app

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SavedMessagesActivity : BaseDrawerActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: SavedMessagesAdapter
    private lateinit var filterContainer: LinearLayout
    private lateinit var filterButton: Button
    private lateinit var priorityFilterSpinner: Spinner
    private lateinit var applyFilterButton: Button
    private lateinit var clearFilterButton: Button
    private lateinit var filterStatusText: TextView

    private var currentFilter: Priority? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_messages)

        supportActionBar?.title = "Saved Messages"
        setSelectedMenuItem(R.id.nav_saved_messages)

        recyclerView = findViewById(R.id.saved_recycler)
        emptyView = findViewById(R.id.empty_saved_view)
        filterContainer = findViewById(R.id.filter_container)
        filterButton = findViewById(R.id.filter_button)
        priorityFilterSpinner = findViewById(R.id.priority_filter_spinner)
        applyFilterButton = findViewById(R.id.apply_filter_button)
        clearFilterButton = findViewById(R.id.clear_filter_button)
        filterStatusText = findViewById(R.id.filter_status_text)

        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = SavedMessagesAdapter(
            context = this,
            onDeleteClick = { savedMessage ->
                SavedMessagesManager.deleteMessage(savedMessage.eventId)
                refreshData()
            }
        )

        recyclerView.adapter = adapter

        setupFilterUI()
        refreshData()
    }

    private fun setupFilterUI() {
        // Setup priority filter spinner
        val priorities = listOf("All Priorities") + Priority.values().map { it.displayName }
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            priorities
        )
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        priorityFilterSpinner.adapter = spinnerAdapter

        // Filter button
        filterButton.setOnClickListener {
            if (filterContainer.visibility == View.VISIBLE) {
                filterContainer.visibility = View.GONE
            } else {
                filterContainer.visibility = View.VISIBLE
            }
        }

        // Apply filter
        applyFilterButton.setOnClickListener {
            val selectedPosition = priorityFilterSpinner.selectedItemPosition
            currentFilter = if (selectedPosition == 0) {
                null // All priorities
            } else {
                Priority.values()[selectedPosition - 1]
            }
            refreshData()
            filterContainer.visibility = View.GONE
            updateFilterStatus()
        }

        // Clear filter
        clearFilterButton.setOnClickListener {
            currentFilter = null
            priorityFilterSpinner.setSelection(0)
            refreshData()
            filterContainer.visibility = View.GONE
            updateFilterStatus()
        }
    }

    private fun updateFilterStatus() {
        if (currentFilter != null) {
            filterStatusText.visibility = View.VISIBLE
            filterStatusText.text = "Filtered by: ${currentFilter!!.displayName} Priority"
            filterButton.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_filter_active, 0, 0, 0
            )
        } else {
            filterStatusText.visibility = View.GONE
            filterButton.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_filter, 0, 0, 0
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure Saved Messages tab is selected
        setSelectedMenuItem(R.id.nav_saved_messages)
        refreshData()
    }

    private fun refreshData() {
        val allSavedMessages = SavedMessagesManager.getSavedMessages()

        val filteredMessages = if (currentFilter != null) {
            allSavedMessages.filter { it.priority == currentFilter }
        } else {
            allSavedMessages
        }

        if (filteredMessages.isEmpty()) {
            showEmptyState()
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.updateMessages(filteredMessages)
        }
    }

    private fun showEmptyState() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }
}