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
    private lateinit var loadingView: ProgressBar
    private lateinit var contentView: View
    private lateinit var logoutButton: Button  // ✅ NEW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_profile)

            supportActionBar?.apply {
                title = "Profile"
                setDisplayHomeAsUpEnabled(true)
            }

            setSelectedMenuItem(R.id.nav_profile)

            initializeViews()
            loadUserProfile()
            setupLogoutButton()  // ✅ NEW

        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "Error in onCreate", e)
            Toast.makeText(this, "Error loading profile", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun initializeViews() {
        try {
            nameText = findViewById(R.id.profile_name)
            phoneText = findViewById(R.id.profile_phone)
            designationText = findViewById(R.id.profile_designation)
            areaText = findViewById(R.id.profile_area)
            workingForText = findViewById(R.id.profile_working_for)
            subscribedChannelsContainer = findViewById(R.id.subscribed_channels_container)
            loadingView = findViewById(R.id.profile_loading)
            contentView = findViewById(R.id.profile_content)
            logoutButton = findViewById(R.id.logout_button)

        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "Error initializing views", e)
            throw e
        }
    }

    // ✅ NEW: Setup logout button
    private fun setupLogoutButton() {
        logoutButton.setOnClickListener {
            showLogoutConfirmation()
        }
    }

    // ✅ NEW: Show logout confirmation dialog
    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Sign Out")
            .setMessage("""
                Are you sure you want to sign out?
                
                Your data will be preserved:
                • ${SubscriptionManager.getSubscriptions().size} subscribed channels
                • ${SubscriptionManager.getTotalEventCount()} cached events
                • ${SavedMessagesManager.getSavedMessages().size} saved messages
                
                When you log back in with the same phone number, you'll receive all pending events.
            """.trimIndent())
            .setPositiveButton("Sign Out") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ✅ NEW: Perform logout
    private fun performLogout() {
        // Show loading
        logoutButton.isEnabled = false
        logoutButton.text = "Signing out..."

        AuthManager.logout { success, message ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

                    // Navigate to login screen
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                } else {
                    logoutButton.isEnabled = true
                    logoutButton.text = "Sign Out"
                    Toast.makeText(this, "Logout failed: $message", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadUserProfile() {
        try {
            loadingView.visibility = View.VISIBLE
            contentView.visibility = View.GONE

            val user = AuthManager.getCurrentUser()

            if (user != null) {
                nameText.text = user.name ?: "User"
                phoneText.text = "+91 ${user.phone ?: "XXXXXXXXXX"}"
                designationText.text = user.designation ?: "N/A"

                val areaDisplay = when {
                    user.area?.uppercase() == "HQ" -> "🏢 Headquarters (HQ) - All Areas"
                    user.area?.contains(",") == true -> {
                        val areas = user.area!!.split(",").map { it.trim() }
                        val displayAreas = areas.take(2).joinToString(", ")
                        if (areas.size > 2) "$displayAreas +${areas.size - 2} more" else displayAreas
                    }
                    else -> user.area ?: "N/A"
                }
                areaText.text = areaDisplay

                workingForText.text = user.workingFor ?: "N/A"

                loadSubscribedChannels()

                loadingView.visibility = View.GONE
                contentView.visibility = View.VISIBLE
            } else {
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

            val groupedByArea = subscriptions.groupBy { it.area }

            groupedByArea.forEach { (area, channels) ->
                val headerView = layoutInflater.inflate(
                    R.layout.item_subscription_area_header,
                    subscribedChannelsContainer,
                    false
                )

                val headerText = headerView.findViewById<TextView>(R.id.area_name)
                headerText.text = "${area.uppercase()} (${channels.size})"
                subscribedChannelsContainer.addView(headerView)

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
                    }
                }
            }

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

    override fun onDestroy() {
        try {
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