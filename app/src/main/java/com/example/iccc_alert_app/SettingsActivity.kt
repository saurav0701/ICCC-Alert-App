package com.example.iccc_alert_app

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate

class SettingsActivity : BaseDrawerActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var themeContainer: RelativeLayout
    private lateinit var themeValue: TextView
    private lateinit var switchNotifications: Switch
    private lateinit var switchVibration: Switch
    private lateinit var helpContainer: RelativeLayout
    private lateinit var notificationStatusContainer: RelativeLayout

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
    }

    private fun initializeViews() {
        themeContainer = findViewById(R.id.theme_container)
        themeValue = findViewById(R.id.theme_value)
        switchNotifications = findViewById(R.id.switch_notifications)
        switchVibration = findViewById(R.id.switch_vibration)
        helpContainer = findViewById(R.id.help_container)
        notificationStatusContainer = findViewById(R.id.notification_status_container)

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

        clearDataButton.setOnClickListener {
            showClearDataConfirmation()
        }
    }

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

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
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
            
            🎨 Appearance
            • Choose between Light, Dark, or System theme
            • Theme changes apply immediately
            
            📋 Managing Events
            • View all events from subscribed channels
            • Search and filter events by date
            • Save events with priority levels and notes
            
            💾 Saved Messages
            • Access saved events from the menu
            • Filter by priority level
            • Add and edit notes anytime
            
            🗑️ Storage Management
            • Clear cached events and saved messages to free up space
            • Your subscriptions and login remain intact
            • You'll receive current events after clearing
            
            ⚠️ Battery & Notifications
            • Keep device charged for 24/7 monitoring
            • Battery below 15% may affect notifications
            • Disable battery optimization when prompted
            • Check notification status if alerts aren't working
            
            ❓ Need More Help?
            Contact your system administrator for technical support.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Help & Support")
            .setMessage(helpText)
            .setPositiveButton("Got it", null)
            .show()
    }
}