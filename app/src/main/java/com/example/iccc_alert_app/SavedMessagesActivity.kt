package com.example.iccc_alert_app

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup

class SavedMessagesActivity : BaseDrawerActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: SavedMessagesAdapter

    private lateinit var filterChipGroup: ChipGroup

    private var currentFilter: Priority? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_messages)

        supportActionBar?.title = "Saved Messages"
        setSelectedMenuItem(R.id.nav_saved_messages)

        recyclerView = findViewById(R.id.saved_recycler)
        emptyView = findViewById(R.id.empty_saved_view)
        filterChipGroup = findViewById(R.id.chip_group_saved)

        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = SavedMessagesAdapter(
            context = this,
            onDeleteClick = { savedMessage ->
                SavedMessagesManager.deleteMessage(savedMessage.eventId)
                refreshData()
            }
        )

        recyclerView.adapter = adapter

        setupChipFilters()
        refreshData()
    }

    private fun setupChipFilters() {
        filterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chip_filter_critical) -> Priority.HIGH
                checkedIds.contains(R.id.chip_filter_warning)  -> Priority.MODERATE
                checkedIds.contains(R.id.chip_filter_info)     -> Priority.LOW
                else                                           -> null  // "All"
            }
            refreshData()
        }
    }

    override fun onResume() {
        super.onResume()
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
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.updateMessages(filteredMessages)
        }
    }
}
