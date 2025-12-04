package com.example.iccc_alert_app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelDetailActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: ChannelEventsAdapter
    private lateinit var channelId: String
    private lateinit var channelArea: String
    private lateinit var channelType: String
    private var channelEventType: String = ""
    private val handler = Handler(Looper.getMainLooper())

    private val pendingEvents = mutableListOf<Event>()
    private var snackbar: Snackbar? = null

    // Use a supervised scope to prevent crashes from affecting the entire activity
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val eventListener: (Event) -> Unit = { event ->
        val eventId = event.id ?: ""
        if (eventId.isNotEmpty() && eventId.contains(channelId)) {
            // Use coroutine scope instead of handler.post for better control
            activityScope.launch {
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

        // Apply window insets for curved displays
        setupWindowInsets()

        adapter = ChannelEventsAdapter(
            context = this,
            onBackClick = { finish() },
            onMuteClick = { toggleMute() },
            channelArea = channelArea,
            channelType = channelType,
            channelEventType = channelEventType
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Force dispatch insets to RecyclerView items
        recyclerView.post {
            ViewCompat.requestApplyInsets(recyclerView)
        }

        SubscriptionManager.markAsRead(channelId)
        updateMuteButtonState()

        WebSocketManager.addEventListener(eventListener)

        // Load events asynchronously to prevent ANR
        loadEventsAsync()
    }

    override fun onResume() {
        super.onResume()

        // ✅ Clear notifications for THIS channel when user opens it
        clearChannelNotifications()
    }

    /**
     * ✅ UPDATED: Clear notifications for this specific channel
     */
    private fun clearChannelNotifications() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNotifications = nm.activeNotifications
                val groupKey = "group_$channelId"

                // Cancel all notifications in this channel's group
                activeNotifications.forEach { statusBarNotification ->
                    try {
                        val notification = statusBarNotification.notification
                        val notificationGroup = notification.group

                        // Check if notification belongs to this channel's group
                        if (notificationGroup == groupKey) {
                            nm.cancel(statusBarNotification.id)
                            Log.d("ChannelDetail", "Cleared notification ID: ${statusBarNotification.id}")
                        }
                    } catch (e: Exception) {
                        Log.e("ChannelDetail", "Error processing notification: ${e.message}")
                    }
                }

                // Also cancel the summary notification for this channel
                val summaryId = channelId.hashCode()
                nm.cancel(summaryId)

                Log.d("ChannelDetail", "Finished clearing notifications for $channelId")
            }
        } catch (e: Exception) {
            Log.e("ChannelDetail", "Error clearing notifications: ${e.message}")
        }
    }

    /**
     * Setup window insets to handle curved displays and notches
     * This ensures the UI stays within safe area boundaries
     */
    private fun setupWindowInsets() {
        // Apply insets to RecyclerView
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

            // Apply padding to avoid curved edges
            view.setPadding(
                maxOf(systemBars.left, displayCutout.left, 0),
                view.paddingTop,
                maxOf(systemBars.right, displayCutout.right, 0),
                view.paddingBottom
            )

            insets
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
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
            loadEventsAsync()
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
            updateMuteButtonState()
            Log.d("ChannelDetail", "Channel $channelId notifications ${if (channel.isMuted) "muted" else "unmuted"}")
        }
    }

    private fun updateMuteButtonState() {
        val channel = SubscriptionManager.getSubscriptions().find { it.id == channelId }
        val isMuted = channel?.isMuted ?: false

        // Update the mute button in adapter
        adapter.updateMuteState(isMuted)
    }

    /**
     * Load events asynchronously to prevent ANR
     * This is the key fix for the "Input dispatching timed out" error
     */
    private fun loadEventsAsync() {
        activityScope.launch {
            try {
                // Fetch events on background thread
                val events = withContext(Dispatchers.Default) {
                    SubscriptionManager.getEventsForChannel(channelId)
                }

                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    if (events.isEmpty()) {
                        recyclerView.visibility = View.GONE
                        emptyView.visibility = View.VISIBLE
                    } else {
                        recyclerView.visibility = View.VISIBLE
                        emptyView.visibility = View.GONE
                        adapter.updateEvents(events)
                    }
                }
            } catch (e: Exception) {
                Log.e("ChannelDetail", "Error loading events: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    // Show error state
                    recyclerView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WebSocketManager.removeEventListener(eventListener)
        snackbar?.dismiss()

        // Clear image cache to free memory
        adapter.clearImageCache()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}