package com.example.iccc_alert_app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar

class ChannelDetailActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var muteButton: Button
    private lateinit var adapter: ChannelEventsAdapter
    private lateinit var channelId: String
    private lateinit var channelArea: String
    private lateinit var channelType: String
    private var channelEventType: String = ""
    private val handler = Handler(Looper.getMainLooper())

    // Track new events while viewing
    private val pendingEvents = mutableListOf<Event>()
    private var snackbar: Snackbar? = null

    private val eventListener: (Event) -> Unit = { event ->
        val eventId = event.id ?: ""
        if (eventId.isNotEmpty() && eventId.contains(channelId)) {
            handler.post {
                // Add to pending events
                pendingEvents.add(event)

                // Show snackbar notification
                showNewEventNotification()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_detail)

        supportActionBar?.hide()

        channelId = intent.getStringExtra("CHANNEL_ID") ?: ""
        channelArea = intent.getStringExtra("CHANNEL_AREA") ?: "Unknown"
        channelType = intent.getStringExtra("CHANNEL_TYPE") ?: "Unknown"
        channelEventType = channelId.substringAfter("_", "")

        recyclerView = findViewById(R.id.events_recycler)
        emptyView = findViewById(R.id.empty_events_view)
        muteButton = findViewById(R.id.mute_button)

        adapter = ChannelEventsAdapter(
            context = this,
            onBackClick = { finish() },
            channelArea = channelArea,
            channelType = channelType,
            channelEventType = channelEventType
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        SubscriptionManager.markAsRead(channelId)
        updateMuteButton()

        muteButton.setOnClickListener {
            toggleMute()
        }

        WebSocketManager.addEventListener(eventListener)
        loadEvents()
    }

    private fun showNewEventNotification() {
        val count = pendingEvents.size
        val message = if (count == 1) "1 new event" else "$count new events"

        snackbar?.dismiss()
        snackbar = Snackbar.make(
            recyclerView,
            message,
            Snackbar.LENGTH_INDEFINITE
        ).setAction("VIEW") {
            loadPendingEvents()
        }.setActionTextColor(getColor(R.color.colorAccent))

        snackbar?.show()
    }

    private fun loadPendingEvents() {
        if (pendingEvents.isNotEmpty()) {
            // Reload all events to include new ones
            loadEvents()
            pendingEvents.clear()
            snackbar?.dismiss()

            // Smooth scroll to top to show new events
            recyclerView.smoothScrollToPosition(0)
        }
    }

    private fun toggleMute() {
        val subscriptions = SubscriptionManager.getSubscriptions()
        val channel = subscriptions.find { it.id == channelId }

        if (channel != null) {
            channel.isMuted = !channel.isMuted
            SubscriptionManager.updateChannel(channel)
            updateMuteButton()
            Log.d("ChannelDetail", "Channel $channelId notifications ${if (channel.isMuted) "muted" else "unmuted"}")
        }
    }

    private fun updateMuteButton() {
        val channel = SubscriptionManager.getSubscriptions().find { it.id == channelId }
        val isMuted = channel?.isMuted ?: false

        if (isMuted) {
            muteButton.text = "UNMUTE"
            muteButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_notifications_off, 0, 0, 0)
        } else {
            muteButton.text = "MUTE"
            muteButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_notifications, 0, 0, 0)
        }
    }

    private fun loadEvents() {
        val events = SubscriptionManager.getEventsForChannel(channelId)

        if (events.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.updateEvents(events)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WebSocketManager.removeEventListener(eventListener)
        snackbar?.dismiss()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}