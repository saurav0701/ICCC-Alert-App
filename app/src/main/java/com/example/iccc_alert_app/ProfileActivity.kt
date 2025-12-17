package com.example.iccc_alert_app

import android.content.Intent
import android.os.Bundle
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

    // ✅ FIXED: Properly typed as ProgressBar
    private lateinit var loadingView: ProgressBar
    private lateinit var contentView: View
    // TODO: Fix logout bugs before re-enabling
    // private lateinit var logoutButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        try {
            setContentView(R.layout.activity_profile)

            // Setup toolbar
            supportActionBar?.apply {
                title = "Profile"
                setDisplayHomeAsUpEnabled(true)
            }

            setSelectedMenuItem(R.id.nav_profile)

            initializeViews()
            loadUserProfile()

        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "Error in onCreate", e)
            Toast.makeText(this, "Error loading profile", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * ✅ IMPROVED: Better error handling in view initialization
     */
    private fun initializeViews() {
        try {
            nameText = findViewById(R.id.profile_name)
            phoneText = findViewById(R.id.profile_phone)
            designationText = findViewById(R.id.profile_designation)
            areaText = findViewById(R.id.profile_area)
            workingForText = findViewById(R.id.profile_working_for)
            subscribedChannelsContainer = findViewById(R.id.subscribed_channels_container)

            // ✅ FIXED: Correctly cast to ProgressBar
            loadingView = findViewById(R.id.profile_loading)
            contentView = findViewById(R.id.profile_content)

            // TODO: Fix logout bugs before re-enabling
            // logoutButton = findViewById(R.id.logout_button)
            // logoutButton.setOnClickListener {
            //     showLogoutConfirmation()
            // }

        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "Error initializing views", e)
            throw e // Re-throw to be caught by onCreate
        }
    }

    /**
     * ✅ IMPROVED: Better error handling and null safety
     */
    private fun loadUserProfile() {
        try {
            loadingView.visibility = View.VISIBLE
            contentView.visibility = View.GONE

            val user = AuthManager.getCurrentUser()

            if (user != null) {
                // Display user information with null safety
                nameText.text = user.name ?: "User"
                phoneText.text = "+91 ${user.phone ?: "XXXXXXXXXX"}"
                designationText.text = user.designation ?: "N/A"
                areaText.text = user.area ?: "N/A"
                workingForText.text = user.workingFor ?: "N/A"

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

        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "Error loading profile", e)
            loadingView.visibility = View.GONE
            Toast.makeText(this, "Error loading profile data", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * ✅ IMPROVED: Better error handling and memory efficiency
     */
    private fun loadSubscribedChannels() {
        try {
            subscribedChannelsContainer.removeAllViews()

            val subscriptions = SubscriptionManager.getSubscriptions()

            if (subscriptions.isEmpty()) {
                val emptyView = layoutInflater.inflate(
                    R.layout.item_empty_subscriptions,
                    subscribedChannelsContainer,
                    false
                )
                subscribedChannelsContainer.addView(emptyView)
                return
            }

            // Group by area for better organization
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
                    try {
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

                    } catch (e: Exception) {
                        android.util.Log.e("ProfileActivity", "Error adding channel view", e)
                        // Continue with other channels
                    }
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

        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "Error loading subscribed channels", e)
            Toast.makeText(this, "Error loading channels", Toast.LENGTH_SHORT).show()
        }
    }

    // TODO: Fix logout bugs before re-enabling
    /**
     * ✅ IMPROVED: Better UX with clear messaging
     */
    /*
    private fun showLogoutConfirmation() {
        try {
            AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage(
                    "Are you sure you want to logout?\n\n" +
                            "✓ Your subscriptions will be preserved\n" +
                            "✓ Your events will be saved\n" +
                            "✓ You'll catch up on missed events when you log back in"
                )
                .setPositiveButton("Logout") { _, _ ->
                    performLogout()
                }
                .setNegativeButton("Cancel", null)
                .show()

        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "Error showing logout dialog", e)
            // Fallback to direct logout if dialog fails
            performLogout()
        }
    }
    */

    // TODO: Fix logout bugs before re-enabling
    /**
     * ✅ IMPROVED: Better error handling and user feedback
     */
    /*
    private fun performLogout() {
        try {
            loadingView.visibility = View.VISIBLE
            contentView.visibility = View.GONE

            AuthManager.logout { success, message ->
                runOnUiThread {
                    try {
                        if (success) {
                            // Stop WebSocket service
                            WebSocketService.stop(this)

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

                    } catch (e: Exception) {
                        android.util.Log.e("ProfileActivity", "Error in logout callback", e)
                        finish()
                    }
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "Error performing logout", e)
            loadingView.visibility = View.GONE
            contentView.visibility = View.VISIBLE
            Toast.makeText(this, "Logout failed. Please try again.", Toast.LENGTH_SHORT).show()
        }
    }
    */

    /**
     * ✅ IMPROVED: Proper cleanup to prevent memory leaks
     */
    override fun onDestroy() {
        try {
            // Clear any pending callbacks
            subscribedChannelsContainer.removeAllViews()
        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "Error in onDestroy", e)
        }

        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}