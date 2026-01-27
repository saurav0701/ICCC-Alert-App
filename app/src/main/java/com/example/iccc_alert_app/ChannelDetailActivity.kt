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

    // ✅ NEW: Event save helper for priority and comments
    private lateinit var eventSaveHelper: ChannelEventSaveHelper

    // UI Enhancement Properties
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

        // ✅ NEW: Initialize event save helper
        eventSaveHelper = ChannelEventSaveHelper(
            context = this,
            fragmentManager = supportFragmentManager,
            channelArea = channelArea
        )

        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        recyclerView = findViewById(R.id.events_recycler)
        emptyView = findViewById(R.id.empty_events_view)

        setupSwipeRefresh()
        setupWindowInsets()
        setupEmptyState()

        adapter = ChannelEventsAdapter(
            context = this,
            onBackClick = { finish() },
            onMuteClick = { toggleMute() },
            channelArea = channelArea,
            channelType = channelType,
            channelEventType = channelEventType
        )
        adapter.initializeEventSaveHelper(supportFragmentManager)

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

    private fun setupEmptyState() {
        try {
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

            val index = parent.indexOfChild(emptyView)
            parent.removeView(emptyView)

            emptyView = enhancedEmpty
            parent.addView(emptyView, index)

            emptyStateAnimator = EmptyStateAnimator(emptyView)

            emptyView.findViewById<View>(R.id.empty_action_button)?.setOnClickListener {
                startActivity(Intent(this, SearchActivity::class.java))
            }
        } catch (e: Exception) {
            Log.e("ChannelDetail", "Error setting up empty state: ${e.message}", e)
        }
    }

    private fun showSkeletonLoading() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE

        if (skeletonView == null) {
            val parent = swipeRefreshLayout.parent as? ViewGroup
            if (parent == null) {
                Log.e("ChannelDetail", "Cannot find parent for skeleton")
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

        skeletonView?.let { skeleton ->
            val skeletonViews = skeleton.findViewsById(R.id.skeleton_image)
            skeletonViews.forEach { shimmerView ->
                shimmerEffect = shimmerView.startShimmer()
            }
        }
    }

    private fun hideSkeletonLoading() {
        shimmerEffect?.stop()
        skeletonView?.visibility = View.GONE
    }

    private fun showEmptyState() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        emptyStateAnimator?.animate()
    }

    private fun hideEmptyState() {
        emptyStateAnimator?.stopAll()
        emptyView.visibility = View.GONE
    }

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
                // ✅ UPDATED: Show save dialog instead of quick save
                showSaveEventDialog(event)
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

    // ✅ NEW: Show dialog to save event with priority and comment
    private fun showSaveEventDialog(event: Event) {
        val eventId = event.id
        if (eventId == null) {
            recyclerView.showErrorSnackbar("Cannot save: Invalid event")
            return
        }

        // Check if already saved
        if (SavedMessagesManager.isMessageSaved(eventId)) {
            recyclerView.showWarningSnackbar("Event already saved")
            return
        }

        // Show the save dialog
        SaveEventDialogFragment.show(
            fragmentManager = supportFragmentManager,
            event = event,
            onSave = { priority, comment ->
                handleEventSaveConfirm(event, priority, comment)
            }
        )
    }

    // ✅ NEW: Handle save confirmation from dialog
    private fun handleEventSaveConfirm(event: Event, priority: Priority, comment: String) {
        eventSaveHelper.saveEventWithPriority(
            event = event,
            priority = priority,
            comment = comment,
            onSuccess = {
                recyclerView.showSuccessSnackbar("✓ Event saved and synced")
                adapter.notifyDataSetChanged()
            },
            onError = { errorMsg ->
                recyclerView.showErrorSnackbar("Failed: $errorMsg")
            }
        )
    }

    // ✅ UPDATED: Mark as important now uses save dialog
    private fun markEventAsImportant(event: Event) {
        val eventId = event.id
        if (eventId == null) {
            recyclerView.showErrorSnackbar("Cannot save: Invalid event")
            return
        }

        if (SavedMessagesManager.isMessageSaved(eventId)) {
            recyclerView.showWarningSnackbar("Event already saved")
            return
        }

        // Show dialog with HIGH priority pre-selected
        SaveEventDialogFragment.show(
            fragmentManager = supportFragmentManager,
            event = event,
            defaultPriority = Priority.HIGH,
            onSave = { priority, comment ->
                handleEventSaveConfirm(event, priority, comment)
            }
        )
    }

    // ✅ REMOVED: Old quickSaveEvent - now handled by showSaveEventDialog

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
                EventActionsBottomSheet.ActionType.SAVE -> showSaveEventDialog(event)
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

                recyclerView.showSuccessSnackbar("Events refreshed")
            } catch (e: Exception) {
                Log.e("ChannelDetail", "Error refreshing events: ${e.message}")
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

    private fun loadEventsAsync() {
        activityScope.launch {
            try {
                showSkeletonLoading()

                val events = withContext(Dispatchers.Default) {
                    kotlinx.coroutines.delay(500)
                    SubscriptionManager.getEventsForChannel(channelId)
                }

                withContext(Dispatchers.Main) {
                    hideSkeletonLoading()

                    if (events.isEmpty()) {
                        showEmptyState()
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

        emptyStateAnimator?.stopAll()
        shimmerEffect?.stop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}