package com.example.iccc_alert_app

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.iccc_alert_app.auth.AuthManager

class ProfileActivity : BaseDrawerActivity() {

    private lateinit var nameText: TextView
    private lateinit var phoneText: TextView
    private lateinit var designationText: TextView
    private lateinit var areaText: TextView
    private lateinit var workingForText: TextView
    private lateinit var subscribedChannelsContainer: LinearLayout
    private lateinit var loadingView: ProgressBar
    private lateinit var contentView: View
    private lateinit var logoutButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        // Setup toolbar - title will be set by BaseDrawerActivity
        supportActionBar?.apply {
            title = "Profile"
        }

        // Set selected menu item
        setSelectedMenuItem(R.id.nav_profile)

        initializeViews()
        loadUserProfile()
    }

    private fun initializeViews() {
        nameText = findViewById(R.id.profile_name)
        phoneText = findViewById(R.id.profile_phone)
        designationText = findViewById(R.id.profile_designation)
        areaText = findViewById(R.id.profile_area)
        workingForText = findViewById(R.id.profile_working_for)
        subscribedChannelsContainer = findViewById(R.id.subscribed_channels_container)
        loadingView = findViewById(R.id.profile_loading)
        contentView = findViewById(R.id.profile_content)
        logoutButton = findViewById(R.id.logout_button)

        logoutButton.setOnClickListener {
            showLogoutConfirmation()
        }
    }

    private fun loadUserProfile() {
        loadingView.visibility = View.VISIBLE
        contentView.visibility = View.GONE

        val user = AuthManager.getCurrentUser()

        if (user != null) {
            // Display user information
            nameText.text = user.name
            phoneText.text = "+91 ${user.phone}"
            designationText.text = user.designation
            areaText.text = user.area
            workingForText.text = user.workingFor

            // Load subscribed channels
            loadSubscribedChannels()

            loadingView.visibility = View.GONE
            contentView.visibility = View.VISIBLE
        } else {
            // User data not available
            loadingView.visibility = View.GONE
            Toast.makeText(this, "Failed to load profile", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadSubscribedChannels() {
        subscribedChannelsContainer.removeAllViews()

        val subscriptions = SubscriptionManager.getSubscriptions()

        if (subscriptions.isEmpty()) {
            val emptyView = layoutInflater.inflate(
                R.layout.item_empty_subscriptions,
                subscribedChannelsContainer,
                false
            )
            subscribedChannelsContainer.addView(emptyView)
        } else {
            // Group by area
            val groupedByArea = subscriptions.groupBy { it.area }

            groupedByArea.forEach { (area, channels) ->
                // Add area header
                val headerView = layoutInflater.inflate(
                    R.layout.item_subscription_area_header,
                    subscribedChannelsContainer,
                    false
                )
                val headerText = headerView.findViewById<TextView>(R.id.area_name)
                headerText.text = "${area.uppercase()} (${channels.size})"
                subscribedChannelsContainer.addView(headerView)

                // Add channels under this area
                channels.forEach { channel ->
                    val channelView = layoutInflater.inflate(
                        R.layout.item_subscription_channel,
                        subscribedChannelsContainer,
                        false
                    )

                    val channelName = channelView.findViewById<TextView>(R.id.channel_name)
                    val eventCount = channelView.findViewById<TextView>(R.id.event_count)
                    val muteIndicator = channelView.findViewById<View>(R.id.mute_indicator)

                    channelName.text = channel.eventTypeDisplay
                    val count = SubscriptionManager.getEventCount(channel.id)
                    eventCount.text = "$count events"

                    muteIndicator.visibility = if (channel.isMuted) View.VISIBLE else View.GONE

                    subscribedChannelsContainer.addView(channelView)
                }
            }

            // Add total count
            val totalView = layoutInflater.inflate(
                R.layout.item_subscription_total,
                subscribedChannelsContainer,
                false
            )
            val totalText = totalView.findViewById<TextView>(R.id.total_text)
            val totalCount = SubscriptionManager.getTotalEventCount()
            totalText.text = "Total: ${subscriptions.size} channels • $totalCount events"
            subscribedChannelsContainer.addView(totalView)
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?\n\n✓ Your subscriptions will be preserved\n✓ Your events will be saved\n✓ You'll catch up on missed events when you log back in")
            .setPositiveButton("Logout") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        loadingView.visibility = View.VISIBLE
        contentView.visibility = View.GONE

        // ✅ LOGOUT STRATEGY:
        // 1. Stop WebSocket service (disconnect from server)
        // 2. Clear auth token (invalidate session on server)
        // 3. PRESERVE: subscriptions, events, saved messages, sync state
        // 4. On re-login: WebSocket will resume with catch-up mechanism

        AuthManager.logout { success, message ->
            runOnUiThread {
                if (success) {
                    // ✅ Only stop WebSocket service - everything else stays
                    WebSocketService.stop(this)

                    // ✅ Auth token is cleared by AuthManager.logout()
                    // ✅ Subscriptions remain in SubscriptionManager
                    // ✅ Events remain in SubscriptionManager
                    // ✅ Saved messages remain in SavedMessagesManager
                    // ✅ Sync state remains in ChannelSyncState

                    Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()

                    // Navigate to login
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                } else {
                    loadingView.visibility = View.GONE
                    contentView.visibility = View.VISIBLE
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}