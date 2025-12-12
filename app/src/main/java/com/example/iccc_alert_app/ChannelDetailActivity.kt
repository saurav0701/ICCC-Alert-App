package com.example.iccc_alert_app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelDetailActivity : AppCompatActivity() {

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
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

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var bindingHelpers: EventBindingHelpers
    private lateinit var gestureHandler: SmartGestureHandler

    // ✅ NEW: UI Enhancement Properties
    private var skeletonView: View? = null
    private var shimmerEffect: ShimmerEffect? = null
    private var emptyStateAnimator: EmptyStateAnimator? = null

    private val eventListener: (Event) -> Unit = { event ->
        val eventId = event.id ?: ""
        if (eventId.isNotEmpty() && eventId.contains(channelId)) {
            activityScope.launch {
                pendingEvents.add(event)
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

        bindingHelpers = EventBindingHelpers(
            this,
            channelArea,
            channelType,
            channelEventType
        )

        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        recyclerView = findViewById(R.id.events_recycler)
        emptyView = findViewById(R.id.empty_events_view)

        setupSwipeRefresh()
        setupWindowInsets()
        setupEmptyState() // ✅ NEW: Setup enhanced empty state

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

        setupGestureHandler()

        recyclerView.post {
            ViewCompat.requestApplyInsets(recyclerView)
        }

        SubscriptionManager.markAsRead(channelId)
        updateMuteButtonState()

        WebSocketManager.addEventListener(eventListener)

        loadEventsAsync()
    }

    // ✅ NEW: Setup Enhanced Empty State
    private fun setupEmptyState() {
        try {
            // Get the parent of emptyView (should be the root layout)
            val parent = emptyView.parent as? ViewGroup
            if (parent == null) {
                Log.e("ChannelDetail", "Empty view has no parent")
                return
            }

            val enhancedEmpty = layoutInflater.inflate(
                R.layout.empty_state_enhanced,
                parent,
                false
            )

            // Get the index and remove old empty view
            val index = parent.indexOfChild(emptyView)
            parent.removeView(emptyView)

            // Add enhanced empty view at same position
            emptyView = enhancedEmpty
            parent.addView(emptyView, index)

            // Setup animator
            emptyStateAnimator = EmptyStateAnimator(emptyView)

            // Setup button click
            emptyView.findViewById<View>(R.id.empty_action_button)?.setOnClickListener {
                startActivity(Intent(this, SearchActivity::class.java))
            }
        } catch (e: Exception) {
            Log.e("ChannelDetail", "Error setting up empty state: ${e.message}", e)
        }
    }

    // ✅ NEW: Show Skeleton Loading
    private fun showSkeletonLoading() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE

        if (skeletonView == null) {
            // Get parent from swipeRefreshLayout
            val parent = swipeRefreshLayout.parent as? ViewGroup
            if (parent == null) {
                Log.e("ChannelDetail", "Cannot find parent for skeleton")
                // Fallback: just show refresh spinner
                swipeRefreshLayout.isRefreshing = true
                return
            }

            skeletonView = layoutInflater.inflate(
                R.layout.skeleton_loading_layout,
                parent,
                false
            )
            parent.addView(skeletonView)
        }

        skeletonView?.visibility = View.VISIBLE

        // Start shimmer effect on all skeleton views
        skeletonView?.let { skeleton ->
            val skeletonViews = skeleton.findViewsById(R.id.skeleton_image)
            skeletonViews.forEach { shimmerView ->
                shimmerEffect = shimmerView.startShimmer()
            }
        }
    }

    // ✅ NEW: Hide Skeleton Loading
    private fun hideSkeletonLoading() {
        shimmerEffect?.stop()
        skeletonView?.visibility = View.GONE
    }

    // ✅ NEW: Show Empty State with Animation
    private fun showEmptyState() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        emptyStateAnimator?.animate()
    }

    // ✅ NEW: Hide Empty State
    private fun hideEmptyState() {
        emptyStateAnimator?.stopAll()
        emptyView.visibility = View.GONE
    }

    // ✅ NEW: Helper to Find Views Recursively
    private fun View.findViewsById(id: Int): List<View> {
        val views = mutableListOf<View>()

        if (this.id == id) {
            views.add(this)
        }

        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                views.addAll(getChildAt(i).findViewsById(id))
            }
        }

        return views
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeResources(
            R.color.colorPrimary,
            R.color.colorAccent,
            R.color.colorPrimaryDark
        )

        swipeRefreshLayout.setOnRefreshListener {
            refreshEvents()
        }

        swipeRefreshLayout.setProgressViewOffset(
            false,
            0,
            (resources.displayMetrics.density * 64).toInt()
        )
    }

    private fun setupGestureHandler() {
        gestureHandler = SmartGestureHandler(this, recyclerView)

        gestureHandler.adapter = adapter
        gestureHandler.bindingHelpers = bindingHelpers

        gestureHandler.onSwipeLeft = { position ->
            val event = adapter.getEventAtPosition(position)
            if (event != null) {
                showDeleteConfirmation(event, position)
            }
        }

        gestureHandler.onSwipeRight = { position ->
            val event = adapter.getEventAtPosition(position)
            if (event != null) {
                quickSaveEvent(event)
            }
        }

        gestureHandler.onDoubleTap = { position ->
            val event = adapter.getEventAtPosition(position)
            if (event != null) {
                openEventDetails(event)
            }
        }

        gestureHandler.onLongPress = { position ->
            val event = adapter.getEventAtPosition(position)
            if (event != null) {
                showEventActionsMenu(event)
            }
        }
    }

    private fun markEventAsImportant(event: Event) {
        val eventId = event.id
        if (eventId == null) {
            // ✅ UPDATED: Use custom snackbar
            recyclerView.showErrorSnackbar("Cannot save: Invalid event")
            return
        }

        if (SavedMessagesManager.isMessageSaved(eventId)) {
            recyclerView.showWarningSnackbar("Event already saved")
            return
        }

        val saved = SavedMessagesManager.saveMessage(
            eventId,
            event,
            Priority.HIGH,
            "Marked as important"
        )

        if (saved) {
            // ✅ UPDATED: Use custom success snackbar
            recyclerView.showSuccessSnackbar("✓ Marked as important")
            adapter.notifyDataSetChanged()
        }
    }

    private fun quickSaveEvent(event: Event) {
        val eventId = event.id
        if (eventId == null) {
            recyclerView.showErrorSnackbar("Cannot save: Invalid event")
            return
        }

        if (SavedMessagesManager.isMessageSaved(eventId)) {
            recyclerView.showWarningSnackbar("Event already saved")
            return
        }

        val saved = SavedMessagesManager.saveMessage(
            eventId,
            event,
            Priority.MODERATE,
            "Quick saved via swipe"
        )

        if (saved) {
            // ✅ UPDATED: Use custom success snackbar
            recyclerView.showSuccessSnackbar("✓ Event saved")
            adapter.notifyDataSetChanged()
        }
    }

    private fun showDeleteConfirmation(event: Event, position: Int) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Remove Event")
            .setMessage("Remove this event from the list? This won't delete it from the server.")
            .setPositiveButton("Remove") { _, _ ->
                removeEvent(event, position)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeEvent(event: Event, position: Int) {
        adapter.removeEvent(event)

        // ✅ UPDATED: Use custom warning snackbar with UNDO action
        recyclerView.showWarningSnackbar(
            "Event removed",
            "UNDO" to { loadEventsAsync() }
        )
    }

    private fun openEventDetails(event: Event) {
        when (event.type) {
            "off-route", "tamper", "overspeed" -> {
                bindingHelpers.openMapView(event)
            }
            else -> {
                recyclerView.showInfoSnackbar("Loading image...")
            }
        }
    }

    private fun showEventActionsMenu(event: Event) {
        EventActionsBottomSheet.show(
            supportFragmentManager,
            event,
            bindingHelpers
        ) { actionType ->
            when (actionType) {
                EventActionsBottomSheet.ActionType.SAVE -> quickSaveEvent(event)
                EventActionsBottomSheet.ActionType.DOWNLOAD ->
                    gestureHandler.handleDownloadImage(event)
                EventActionsBottomSheet.ActionType.SHARE ->
                    gestureHandler.handleShareImage(event)
                EventActionsBottomSheet.ActionType.VIEW_MAP ->
                    gestureHandler.handleViewOnMap(event)
                EventActionsBottomSheet.ActionType.GENERATE_PDF ->
                    gestureHandler.handleGeneratePDF(event)
                EventActionsBottomSheet.ActionType.MARK_IMPORTANT ->
                    markEventAsImportant(event)
            }
        }
    }

    private fun refreshEvents() {
        activityScope.launch {
            try {
                if (pendingEvents.isNotEmpty()) {
                    pendingEvents.clear()
                    snackbar?.dismiss()
                }

                loadEventsAsync()

                // ✅ UPDATED: Use custom success snackbar
                recyclerView.showSuccessSnackbar("Events refreshed")
            } catch (e: Exception) {
                Log.e("ChannelDetail", "Error refreshing events: ${e.message}")
                // ✅ UPDATED: Use custom error snackbar with retry
                recyclerView.showErrorSnackbar(
                    "Failed to refresh events",
                    "RETRY" to { refreshEvents() }
                )
            } finally {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        clearChannelNotifications()
    }

    private fun clearChannelNotifications() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNotifications = nm.activeNotifications
                val groupKey = "group_$channelId"

                activeNotifications.forEach { statusBarNotification ->
                    try {
                        val notification = statusBarNotification.notification
                        val notificationGroup = notification.group

                        if (notificationGroup == groupKey) {
                            nm.cancel(statusBarNotification.id)
                            Log.d("ChannelDetail", "Cleared notification ID: ${statusBarNotification.id}")
                        }
                    } catch (e: Exception) {
                        Log.e("ChannelDetail", "Error processing notification: ${e.message}")
                    }
                }

                val summaryId = channelId.hashCode()
                nm.cancel(summaryId)

                Log.d("ChannelDetail", "Finished clearing notifications for $channelId")
            }
        } catch (e: Exception) {
            Log.e("ChannelDetail", "Error clearing notifications: ${e.message}")
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(swipeRefreshLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

            view.setPadding(
                maxOf(systemBars.left, displayCutout.left, 0),
                view.paddingTop,
                maxOf(systemBars.right, displayCutout.right, 0),
                view.paddingBottom
            )

            insets
        }
    }

    private fun showNewEventNotification() {
        val count = pendingEvents.size
        val message = if (count == 1) "1 new event" else "$count new events"

        snackbar?.dismiss()

        // ✅ UPDATED: Use custom info snackbar
        snackbar = CustomSnackbar.info(
            recyclerView,
            message,
            "VIEW" to { loadPendingEvents() }
        )
    }

    private fun loadPendingEvents() {
        if (pendingEvents.isNotEmpty()) {
            loadEventsAsync()
            pendingEvents.clear()
            snackbar?.dismiss()

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

            // ✅ UPDATED: Use custom snackbar for mute feedback
            if (channel.isMuted) {
                recyclerView.showWarningSnackbar("Notifications muted for this channel")
            } else {
                recyclerView.showSuccessSnackbar("Notifications enabled for this channel")
            }

            Log.d("ChannelDetail", "Channel $channelId notifications ${if (channel.isMuted) "muted" else "unmuted"}")
        }
    }

    private fun updateMuteButtonState() {
        val channel = SubscriptionManager.getSubscriptions().find { it.id == channelId }
        val isMuted = channel?.isMuted ?: false
        adapter.updateMuteState(isMuted)
    }

    // ✅ UPDATED: Enhanced Loading with Skeleton
    private fun loadEventsAsync() {
        activityScope.launch {
            try {
                // Show skeleton loading instead of spinner
                showSkeletonLoading()

                val events = withContext(Dispatchers.Default) {
                    // Simulate realistic loading time
                    kotlinx.coroutines.delay(500)
                    SubscriptionManager.getEventsForChannel(channelId)
                }

                withContext(Dispatchers.Main) {
                    hideSkeletonLoading()

                    if (events.isEmpty()) {
                        showEmptyState() // ✅ Use animated empty state
                    } else {
                        hideEmptyState()
                        recyclerView.visibility = View.VISIBLE
                        adapter.updateEvents(events)
                    }
                }
            } catch (e: Exception) {
                Log.e("ChannelDetail", "Error loading events: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    hideSkeletonLoading()
                    showEmptyState()

                    // ✅ Show error snackbar
                    recyclerView.showErrorSnackbar(
                        "Failed to load events",
                        "RETRY" to { loadEventsAsync() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WebSocketManager.removeEventListener(eventListener)
        snackbar?.dismiss()

        adapter.clearImageCache()
        gestureHandler.cleanup()

        // ✅ NEW: Cleanup animations
        emptyStateAnimator?.stopAll()
        shimmerEffect?.stop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}