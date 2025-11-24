package com.example.iccc_alert_app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SearchActivity : BaseDrawerActivity() {

    private lateinit var searchInput: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AreaGroupAdapter
    private var allChannels = listOf<Channel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        supportActionBar?.title = "Subscribe Channels"
        setSelectedMenuItem(R.id.nav_search)

        searchInput = findViewById(R.id.search_input)
        recyclerView = findViewById(R.id.channels_recycler)

        // Get all available channels
        allChannels = AvailableChannels.getAllChannels()

        // Mark which ones are already subscribed
        val subscribed = SubscriptionManager.getSubscriptions()
        allChannels.forEach { channel ->
            channel.isSubscribed = subscribed.any { it.id == channel.id }
        }

        // Use the new AreaGroupAdapter
        adapter = AreaGroupAdapter { channel ->
            toggleSubscription(channel)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Load initial data
        updateChannelsList(allChannels)

        // Setup search functionality
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterChannels(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun toggleSubscription(channel: Channel) {
        if (channel.isSubscribed) {
            SubscriptionManager.unsubscribe(channel.id)
            channel.isSubscribed = false
        } else {
            SubscriptionManager.subscribe(channel)
            channel.isSubscribed = true
        }

        // Refresh the current view
        filterChannels(searchInput.text.toString())
    }

    private fun filterChannels(query: String) {
        val filtered = if (query.isEmpty()) {
            allChannels
        } else {
            allChannels.filter {
                it.description.contains(query, ignoreCase = true) ||
                        it.areaDisplay.contains(query, ignoreCase = true) ||
                        it.eventTypeDisplay.contains(query, ignoreCase = true) ||
                        it.area.contains(query, ignoreCase = true) ||
                        it.eventType.contains(query, ignoreCase = true)
            }
        }
        updateChannelsList(filtered)
    }

    private fun updateChannelsList(channels: List<Channel>) {
        // Update subscription status before displaying
        val subscribedIds = SubscriptionManager.getSubscriptions().map { it.id }.toSet()
        channels.forEach { channel ->
            channel.isSubscribed = subscribedIds.contains(channel.id)
        }

        adapter.updateAreaGroups(channels)
    }

    override fun onResume() {
        super.onResume()
        // Refresh subscriptions when returning to this activity
        filterChannels(searchInput.text.toString())
    }
}