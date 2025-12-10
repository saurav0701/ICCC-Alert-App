package com.example.iccc_alert_app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import com.example.iccc_alert_app.auth.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : BaseDrawerActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var themeContainer: RelativeLayout
    private lateinit var themeValue: TextView
    private lateinit var switchNotifications: Switch
    private lateinit var switchVibration: Switch
    private lateinit var helpContainer: RelativeLayout
    private lateinit var notificationStatusContainer: RelativeLayout

    // ✅ NEW: Backend info views
    private lateinit var backendInfoContainer: RelativeLayout
    private lateinit var backendInfoText: TextView

    // Storage management
    private lateinit var storageInfoText: TextView
    private lateinit var clearDataButton: Button

    companion object {
        private const val PREFS_NAME = "ICCCAlertPrefs"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_VIBRATION = "vibration_enabled"

        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        fun areNotificationsEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_NOTIFICATIONS, true)
        }

        fun isVibrationEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_VIBRATION, true)
        }

        fun getCurrentTheme(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_THEME, THEME_LIGHT) ?: THEME_LIGHT
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.title = "Settings"
        setSelectedMenuItem(R.id.nav_settings)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        initializeViews()
        loadSettings()
        setupListeners()
        updateStorageInfo()
        updateBackendInfo() // ✅ NEW
    }

    override fun onResume() {
        super.onResume()
        setSelectedMenuItem(R.id.nav_settings)
        updateBackendInfo() // ✅ Refresh on resume
    }

    private fun initializeViews() {
        themeContainer = findViewById(R.id.theme_container)
        themeValue = findViewById(R.id.theme_value)
        switchNotifications = findViewById(R.id.switch_notifications)
        switchVibration = findViewById(R.id.switch_vibration)
        helpContainer = findViewById(R.id.help_container)
        notificationStatusContainer = findViewById(R.id.notification_status_container)

        // ✅ NEW: Backend info views
        backendInfoContainer = findViewById(R.id.backend_info_container)
        backendInfoText = findViewById(R.id.backend_info_text)

        storageInfoText = findViewById(R.id.storage_info_text)
        clearDataButton = findViewById(R.id.clear_data_button)
    }

    private fun loadSettings() {
        val currentTheme = prefs.getString(KEY_THEME, THEME_LIGHT) ?: THEME_LIGHT
        updateThemeDisplay(currentTheme)

        val notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        switchNotifications.isChecked = notificationsEnabled

        val vibrationEnabled = prefs.getBoolean(KEY_VIBRATION, true)
        switchVibration.isChecked = vibrationEnabled
    }

    private fun setupListeners() {
        themeContainer.setOnClickListener {
            showThemeDialog()
        }

        switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_NOTIFICATIONS, isChecked).apply()

            if (isChecked) {
                Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Notifications disabled", Toast.LENGTH_SHORT).show()
            }
        }

        switchVibration.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_VIBRATION, isChecked).apply()

            if (isChecked) {
                Toast.makeText(this, "Vibration enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Vibration disabled", Toast.LENGTH_SHORT).show()
            }
        }

        helpContainer.setOnClickListener {
            showHelpDialog()
        }

        notificationStatusContainer.setOnClickListener {
            NotificationStatusHelper.showNotificationStatusDialog(this)
        }

        // ✅ NEW: Backend info click listener
        backendInfoContainer.setOnClickListener {
            showBackendInfoDialog()
        }

        clearDataButton.setOnClickListener {
            showClearDataConfirmation()
        }
    }

    // ============================================
    // ✅ NEW: BACKEND CONFIGURATION SECTION
    // ============================================

    /**
     * Update backend info display
     */
    private fun updateBackendInfo() {
        try {
            val user = AuthManager.getCurrentUser()
            val org = user?.workingFor ?: BackendConfig.getOrganization()
            val backendUrl = BackendConfig.getHttpBaseUrl()

            // Show organization and backend URL
            backendInfoText.text = "$org Backend\n$backendUrl"

            android.util.Log.d("SettingsActivity", "Backend info: $org - $backendUrl")
        } catch (e: Exception) {
            backendInfoText.text = "Backend info unavailable"
            android.util.Log.e("SettingsActivity", "Error updating backend info", e)
        }
    }

    /**
     * Show detailed backend configuration dialog
     */
    private fun showBackendInfoDialog() {
        try {
            val user = AuthManager.getCurrentUser()
            val info = BackendConfig.getOrganizationInfo()
            val channelInfo = AvailableChannels.getOrganizationInfo()

            val message = buildString {
                append("━━━━━━━━━━━━━━━━━━━━━━\n")
                append("BACKEND CONFIGURATION\n")
                append("━━━━━━━━━━━━━━━━━━━━━━\n\n")

                append("Organization:\n")
                append("  ${info["organization"]}\n\n")

                append("Backend Server:\n")
                append("  ${info["backend"]}\n\n")

                append("HTTP Endpoint:\n")
                append("  ${info["httpUrl"]}\n\n")

                append("WebSocket URL:\n")
                append("  ${info["wsUrl"]}\n\n")

                append("Available Channels:\n")
                append("  $channelInfo\n\n")

                if (user != null) {
                    append("━━━━━━━━━━━━━━━━━━━━━━\n")
                    append("USER INFORMATION\n")
                    append("━━━━━━━━━━━━━━━━━━━━━━\n\n")
                    append("Name: ${user.name}\n")
                    append("Phone: +91 ${user.phone}\n")
                    append("Area: ${user.area}\n")
                    append("Designation: ${user.designation}\n")
                    append("Organization: ${user.workingFor}\n\n")
                }

                append("━━━━━━━━━━━━━━━━━━━━━━\n")
                append("Status: Connected\n")
                append("━━━━━━━━━━━━━━━━━━━━━━")
            }

            AlertDialog.Builder(this)
                .setTitle("Backend Configuration")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setNeutralButton("Test Connection") { _, _ ->
                    testBackendConnection()
                }
                .setNegativeButton("View Statistics") { _, _ ->
                    showBackendStatistics()
                }
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error loading backend info: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("SettingsActivity", "Error showing backend dialog", e)
        }
    }

    /**
     * Test backend connectivity
     */
    private fun testBackendConnection() {
        val progressDialog = android.app.ProgressDialog(this)
        progressDialog.setMessage("Testing connection to ${BackendConfig.getOrganization()} backend...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        Thread {
            try {
                val url = java.net.URL(BackendConfig.getHttpBaseUrl())
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                val responseTime = connection.headerFields["Date"]?.firstOrNull() ?: "Unknown"

                runOnUiThread {
                    progressDialog.dismiss()

                    val statusEmoji = when {
                        responseCode in 200..299 -> "✅"
                        responseCode == 404 -> "⚠️"
                        else -> "❌"
                    }

                    val message = buildString {
                        append("$statusEmoji Connection Test Results\n\n")
                        append("Backend: ${BackendConfig.getOrganization()}\n")
                        append("URL: ${BackendConfig.getHttpBaseUrl()}\n")
                        append("Status: HTTP $responseCode\n")
                        append("Response Time: ${responseTime}\n\n")

                        when {
                            responseCode in 200..299 -> append("Backend is reachable and responding normally.")
                            responseCode == 404 -> append("Backend is reachable but endpoint not found (this is normal).")
                            else -> append("Backend responded with unexpected status code.")
                        }
                    }

                    AlertDialog.Builder(this)
                        .setTitle("Connection Test")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }

                connection.disconnect()

            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()

                    val message = buildString {
                        append("❌ Connection Failed\n\n")
                        append("Backend: ${BackendConfig.getOrganization()}\n")
                        append("URL: ${BackendConfig.getHttpBaseUrl()}\n\n")
                        append("Error: ${e.message}\n\n")
                        append("Possible causes:\n")
                        append("• Network connectivity issues\n")
                        append("• Backend server is down\n")
                        append("• Firewall blocking connection\n")
                        append("• Incorrect backend URL\n")
                    }

                    AlertDialog.Builder(this)
                        .setTitle("Connection Test Failed")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    /**
     * Show detailed backend statistics
     */
    private fun showBackendStatistics() {
        try {
            val stats = AvailableChannels.getStats()
            val syncStats = ChannelSyncState.getStats()
            val storageStats = SubscriptionManager.getStorageStats()

            val message = buildString {
                append("━━━━━━━━━━━━━━━━━━━━━━\n")
                append("SYSTEM STATISTICS\n")
                append("━━━━━━━━━━━━━━━━━━━━━━\n\n")

                append("Organization:\n")
                append("  Current: ${stats["organization"]}\n")
                append("  Is CCL: ${stats["isCCL"]}\n")
                append("  Is BCCL: ${stats["isBCCL"]}\n\n")

                append("Available Channels:\n")
                append("  BCCL Areas: ${stats["bcclAreas"]}\n")
                append("  CCL Areas: ${stats["cclAreas"]}\n")
                append("  Current Areas: ${stats["currentAreas"]}\n")
                append("  Event Types: ${stats["eventTypes"]}\n")
                append("  Total Channels: ${stats["totalChannels"]}\n\n")

                append("Subscriptions:\n")
                append("  Active Channels: ${storageStats.size}\n")
                append("  Cached Events: ${storageStats.values.sum()}\n\n")

                append("Synchronization:\n")
                val syncInfo = syncStats as? Map<*, *>
                if (syncInfo != null) {
                    append("  Channel Count: ${syncInfo["channelCount"]}\n")
                    append("  Total Events: ${syncInfo["totalEvents"]}\n")
                }
            }

            AlertDialog.Builder(this)
                .setTitle("Backend Statistics")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error loading statistics: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("SettingsActivity", "Error showing statistics", e)
        }
    }

    // ============================================
    // THEME MANAGEMENT
    // ============================================

    private fun showThemeDialog() {
        val themes = arrayOf("Light", "Dark", "System Default")
        val themeValues = arrayOf(THEME_LIGHT, THEME_DARK, THEME_SYSTEM)

        val currentTheme = prefs.getString(KEY_THEME, THEME_LIGHT) ?: THEME_LIGHT
        val currentIndex = themeValues.indexOf(currentTheme)

        AlertDialog.Builder(this)
            .setTitle("Choose Theme")
            .setSingleChoiceItems(themes, currentIndex) { dialog, which ->
                val selectedTheme = themeValues[which]
                applyTheme(selectedTheme)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyTheme(theme: String) {
        prefs.edit().putString(KEY_THEME, theme).apply()

        when (theme) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        updateThemeDisplay(theme)
        recreate()
    }

    private fun updateThemeDisplay(theme: String) {
        themeValue.text = when (theme) {
            THEME_LIGHT -> "Light"
            THEME_DARK -> "Dark"
            THEME_SYSTEM -> "System Default"
            else -> "Light"
        }
    }

    // ============================================
    // STORAGE MANAGEMENT
    // ============================================

    private fun updateStorageInfo() {
        val stats = SubscriptionManager.getStorageStats()
        val totalEvents = stats.values.sum()
        val totalChannels = stats.size
        val savedMessages = SavedMessagesManager.getMessageCount()

        val infoText = buildString {
            append("Storage Usage:\n\n")
            append("• $totalChannels active channels\n")
            append("• $totalEvents cached events\n")
            append("• $savedMessages saved messages\n\n")
            append("Clearing data will remove all cached events and saved messages but keep your subscriptions and login session intact.")
        }

        storageInfoText.text = infoText
    }

    private fun showClearDataConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear App Data")
            .setMessage(
                "This will permanently delete:\n\n" +
                        "• All cached events (${SubscriptionManager.getTotalEventCount()} events)\n" +
                        "• All saved messages (${SavedMessagesManager.getMessageCount()} messages)\n" +
                        "• Channel sync state\n\n" +
                        "This will NOT delete:\n" +
                        "• Your login session\n" +
                        "• Your channel subscriptions\n\n" +
                        "After clearing, you'll receive current events as if you just subscribed.\n\n" +
                        "Are you sure you want to continue?"
            )
            .setPositiveButton("Clear Data") { _, _ ->
                performClearData()
            }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun performClearData() {
        val progressDialog = android.app.ProgressDialog(this)
        progressDialog.setMessage("Clearing data...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        val subscriptions = SubscriptionManager.getSubscriptions()

        try {
            WebSocketService.stop(this)

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    clearEventData()
                    SavedMessagesManager.clearAll()
                    ChannelSyncState.clearAll()
                    ClientIdManager.resetClientId(this)
                    restoreSubscriptions(subscriptions)

                    SubscriptionManager.initialize(this)
                    ChannelSyncState.initialize(this)
                    SavedMessagesManager.initialize(this)

                    progressDialog.dismiss()

                    AlertDialog.Builder(this)
                        .setTitle("Data Cleared Successfully")
                        .setMessage(
                            "✓ All cached events deleted\n" +
                                    "✓ All saved messages deleted\n" +
                                    "✓ Sync history reset\n" +
                                    "✓ Client ID reset\n" +
                                    "✓ Your subscriptions preserved\n" +
                                    "✓ Login session intact\n\n" +
                                    "The service will restart with a NEW connection.\n" +
                                    "You'll receive current events for all your subscriptions."
                        )
                        .setPositiveButton("OK") { _, _ ->
                            updateStorageInfo()
                            WebSocketService.start(this)
                            Toast.makeText(
                                this,
                                "Service restarted - receiving current events",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        .setCancelable(false)
                        .show()

                } catch (e: Exception) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        this,
                        "Error clearing data: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }, 1000)

        } catch (e: Exception) {
            progressDialog.dismiss()
            Toast.makeText(
                this,
                "Error clearing data: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun clearEventData() {
        val prefsName = "subscriptions"
        val keyEvents = "events"
        val keyUnread = "unread"

        val eventPrefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        eventPrefs.edit()
            .remove(keyEvents)
            .remove(keyUnread)
            .commit()
    }

    private fun restoreSubscriptions(subscriptions: List<Channel>) {
        val prefsName = "subscriptions"
        val keyChannels = "channels"

        val subPrefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val json = com.google.gson.Gson().toJson(subscriptions)
        subPrefs.edit()
            .putString(keyChannels, json)
            .commit()
    }

    // ============================================
    // HELP DIALOG
    // ============================================

    private fun showHelpDialog() {
        val helpText = """
            ICCC Alert - Help & FAQs
            
            📱 Getting Started
            • Subscribe to channels to receive real-time alerts
            • Tap on any alert to view details
            • Save important events for later reference
            
            🔔 Notifications
            • Enable notifications to receive instant alerts
            • Mute individual channels if needed
            • Control vibration settings
            • Check notification status to diagnose issues
            
            📋 Managing Events
            • View all events from subscribed channels
            • Search and filter events by date and time
            • Save events with priority levels and notes
            
            💾 Saved Messages
            • Access saved events from the menu
            • Filter by priority level
            • Add and edit notes anytime
            
            🗑️ Storage Management
            • Clear cached events and saved messages to free up space
            • Your subscriptions and login remain intact
            • You'll receive current events after clearing
            
            🌐 Backend Configuration
            • View current organization (BCCL/CCL)
            • Test backend connectivity
            • Check system statistics
            
            ⚠️ Battery & Notifications
            • Keep device charged for 24/7 monitoring
            • Battery below 15% may affect notifications
            • Disable battery optimization when prompted
            • Check notification status if alerts aren't working
            
            ❓ Need More Help?
            Contact system administrator for technical support.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Help & Support")
            .setMessage(helpText)
            .setPositiveButton("Got it", null)
            .show()
    }
}