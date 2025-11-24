package com.example.iccc_alert_app

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate

class MyApplication : Application() {

    companion object {
        private const val TAG = "MyApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application starting")

        // Apply saved theme before anything else
        applySavedTheme()

        // Initialize SubscriptionManager first
        SubscriptionManager.initialize(this)

        // Initialize SavedMessagesManager
        SavedMessagesManager.initialize(this)

        // Start WebSocket service (persistent connection)
        WebSocketManager.initialize(this)

        Log.d(TAG, "Application initialized")
    }

    private fun applySavedTheme() {
        val theme = SettingsActivity.getCurrentTheme(this)

        when (theme) {
            SettingsActivity.THEME_LIGHT -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
            SettingsActivity.THEME_DARK -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
            SettingsActivity.THEME_SYSTEM -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }

        Log.d(TAG, "Applied theme: $theme")
    }
}