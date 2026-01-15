package com.example.iccc_alert_app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.iccc_alert_app.auth.AuthManager

class SearchActivity : BaseDrawerActivity() {

    private lateinit var searchInput: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AreaGroupAdapter
    private lateinit var accessibleAreasText: TextView
    private var allChannels = listOf<Channel>()
    private var accessibleChannels = listOf<Channel>()
    private var userAreas = setOf<String>()
    private var isHQUser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        supportActionBar?.title = "Subscribe Channels"
        setSelectedMenuItem(R.id.nav_search)

        searchInput = findViewById(R.id.search_input)
        recyclerView = findViewById(R.id.channels_recycler)
        accessibleAreasText = findViewById(R.id.accessible_areas_text)

        // ✅ Get user's area access
        loadUserAreaAccess()

        // Get all available channels
        allChannels = AvailableChannels.getAllChannels()

        // ✅ Filter channels based on user's area access
        filterAccessibleChannels()

        // Mark which ones are already subscribed
        val subscribed = SubscriptionManager.getSubscriptions()
        accessibleChannels.forEach { channel ->
            channel.isSubscribed = subscribed.any { it.id == channel.id }
        }

        // Use the new AreaGroupAdapter
        adapter = AreaGroupAdapter { channel ->
            toggleSubscription(channel)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Load initial data
        updateChannelsList(accessibleChannels)

        // Setup search functionality
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterChannels(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /**
     * ✅ Load user's area access from auth data
     */
    private fun loadUserAreaAccess() {
        val user = AuthManager.getCurrentUser()

        if (user != null) {
            val areaString = user.area ?: ""

            // ✅ Check if user is from HQ (special case)
            if (areaString.uppercase() == "HQ" || areaString.uppercase() == "HEADQUARTERS") {
                isHQUser = true
                userAreas = emptySet()
                accessibleAreasText.text = "🏢 HQ Access: All areas"
                accessibleAreasText.setTextColor(android.graphics.Color.parseColor("#2196F3"))
            } else {
                // Parse comma-separated areas
                userAreas = areaString.split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()

                if (userAreas.isNotEmpty()) {
                    val areaDisplayNames = AvailableChannels.getAreas()
                        .filter { userAreas.contains(it.first.lowercase()) }
                        .map { it.second }

                    accessibleAreasText.text = "📍 Access: ${areaDisplayNames.joinToString(", ")}"
                    accessibleAreasText.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                }
            }
        }
    }

    /**
     * ✅ Filter channels based on user's area access
     */
    private fun filterAccessibleChannels() {
        accessibleChannels = if (isHQUser) {
            // HQ user can see all channels
            allChannels
        } else {
            // Regular user can only see their assigned areas
            allChannels.filter { channel ->
                userAreas.contains(channel.area.lowercase())
            }
        }
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
            accessibleChannels
        } else {
            accessibleChannels.filter {
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