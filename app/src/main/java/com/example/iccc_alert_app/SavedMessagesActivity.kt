package com.example.iccc_alert_app

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SavedMessagesActivity : BaseDrawerActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: SavedMessagesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_messages)

        supportActionBar?.title = "Saved Messages"
        setSelectedMenuItem(R.id.nav_saved_messages)

        recyclerView = findViewById(R.id.saved_recycler)
        emptyView = findViewById(R.id.empty_saved_view)

        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = SavedMessagesAdapter(
            onDeleteClick = { savedMessage ->
                SavedMessagesManager.deleteMessage(savedMessage.eventId)
                refreshData()
            }
        )

        recyclerView.adapter = adapter

        refreshData()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    private fun refreshData() {
        val savedMessages = SavedMessagesManager.getSavedMessages()

        if (savedMessages.isEmpty()) {
            showEmptyState()
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.updateMessages(savedMessages)
        }
    }

    private fun showEmptyState() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }
}