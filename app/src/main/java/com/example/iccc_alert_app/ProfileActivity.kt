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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class ProfileActivity : BaseDrawerActivity() {

    private lateinit var nameText: TextView
    private lateinit var phoneText: TextView
    private lateinit var designationText: TextView
    private lateinit var areaText: TextView
    private lateinit var workingForText: TextView
    private lateinit var roleChip: TextView
    private lateinit var subscribedChannelsContainer: LinearLayout
    private lateinit var loadingView: ProgressBar
    private lateinit var contentView: View
    private lateinit var logoutButton: Button
    private var channelCountBadge: TextView? = null

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
            roleChip = findViewById(R.id.profile_role_chip)
            subscribedChannelsContainer = findViewById(R.id.subscribed_channels_container)
            channelCountBadge = findViewById(R.id.profile_channel_count_badge)
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

                // Populate role chip
                val designation = user.designation ?: "Officer"
                roleChip.text = "$designation · ICCC"

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
            channelCountBadge?.text = subscriptions.size.toString()

            if (subscriptions.isEmpty()) {
                val empty = TextView(this)
                empty.text = "No channels subscribed yet"
                empty.textSize = 13f
                empty.setTextColor(getColor(R.color.text_secondary))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(4, 4, 4, 4)
                empty.layoutParams = lp
                subscribedChannelsContainer.addView(empty)
                return
            }

            // Group channels by area
            val byArea = subscriptions.groupBy { it.areaDisplay ?: "Other" }
            val sortedAreas = byArea.keys.sorted()

            sortedAreas.forEachIndexed { index, area ->
                val channels = byArea[area] ?: return@forEachIndexed

                // Area label
                val areaLabel = TextView(this)
                areaLabel.text = area.uppercase()
                areaLabel.textSize = 10f
                areaLabel.setTextColor(getColor(R.color.text_tertiary))
                areaLabel.typeface = android.graphics.Typeface.DEFAULT_BOLD
                areaLabel.letterSpacing = 0.08f
                val labelLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                labelLp.topMargin = if (index == 0) 0 else (12 * resources.displayMetrics.density).toInt()
                labelLp.bottomMargin = 4
                areaLabel.layoutParams = labelLp
                subscribedChannelsContainer.addView(areaLabel)

                // ChipGroup for this area's channels
                val chipGroup = ChipGroup(this)
                chipGroup.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                channels.forEach { channel ->
                    try {
                        val chip = Chip(this)
                        chip.text = channel.eventTypeDisplay
                        chip.isClickable = true
                        chip.isFocusable = true
                        chip.isChipIconVisible = false
                        chip.isCheckedIconVisible = false
                        if (channel.isMuted) {
                            chip.setChipBackgroundColorResource(R.color.surface_variant_light)
                            chip.setTextColor(getColor(R.color.text_secondary))
                            chip.alpha = 0.7f
                        } else {
                            chip.setChipBackgroundColorResource(R.color.navy_50)
                            chip.setTextColor(getColor(R.color.navy_600))
                            chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(
                                getColor(R.color.navy_100)
                            )
                            chip.chipStrokeWidth = resources.displayMetrics.density // 1dp
                        }
                        chip.setOnClickListener {
                            val intent = Intent(this, ChannelDetailActivity::class.java)
                            intent.putExtra("CHANNEL_ID", channel.id)
                            intent.putExtra("CHANNEL_AREA", channel.areaDisplay)
                            intent.putExtra("CHANNEL_TYPE", channel.eventTypeDisplay)
                            startActivity(intent)
                        }
                        chipGroup.addView(chip)
                    } catch (e: Exception) {
                        android.util.Log.e("ProfileActivity", "Error adding chip", e)
                    }
                }

                subscribedChannelsContainer.addView(chipGroup)
            }

        } catch (e: Exception) {
            android.util.Log.e("ProfileActivity", "Error loading subscribed channels", e)
            Toast.makeText(this, "Error loading channels", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        try {
            if (::subscribedChannelsContainer.isInitialized) {
                subscribedChannelsContainer.removeAllViews()
            }
        } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}