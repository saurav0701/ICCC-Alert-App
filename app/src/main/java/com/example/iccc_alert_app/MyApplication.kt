package com.example.iccc_alert_app

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.example.iccc_alert_app.auth.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApplication : Application() {

    companion object {
        private const val TAG = "MyApplication"
        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "Application starting")

        // ✅ Initialize PersistentLogger FIRST
        try {
            PersistentLogger.initialize(this)
            PersistentLogger.logEvent("SYSTEM", "===== APPLICATION STARTED =====")
            PersistentLogger.logEvent("SYSTEM", "Build: ${Build.MANUFACTURER} ${Build.MODEL}")
            PersistentLogger.logEvent("SYSTEM", "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            Log.d(TAG, "PersistentLogger initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PersistentLogger", e)
        }

        // Apply saved theme
        applySavedTheme()

        // Initialize critical components on background thread
        applicationScope.launch {
            try {
                // ✅ Initialize BackendConfig FIRST (but don't set organization yet)
                BackendConfig.initialize(this@MyApplication)
                Log.d(TAG, "BackendConfig initialized")
                PersistentLogger.logEvent("SYSTEM", "BackendConfig initialized (org will be set after login)")

                // Initialize AuthManager
                AuthManager.initialize(this@MyApplication)
                Log.d(TAG, "AuthManager initialized")
                PersistentLogger.logEvent("SYSTEM", "AuthManager initialized")

                // Initialize SubscriptionManager
                SubscriptionManager.initialize(this@MyApplication)
                Log.d(TAG, "SubscriptionManager initialized")
                PersistentLogger.logEvent("SYSTEM", "SubscriptionManager initialized")

                // Initialize ChannelSyncState
                ChannelSyncState.initialize(this@MyApplication)
                Log.d(TAG, "ChannelSyncState initialized")
                PersistentLogger.logEvent("SYSTEM", "ChannelSyncState initialized")

                // Initialize SavedMessagesManager
                SavedMessagesManager.initialize(this@MyApplication)
                Log.d(TAG, "SavedMessagesManager initialized")
                PersistentLogger.logEvent("SYSTEM", "SavedMessagesManager initialized")

                // ❌ DO NOT START WEBSOCKET HERE
                // Let MainActivity start it after checking if user is logged in
                // The organization will be set during login flow
                Log.d(TAG, "WebSocket will be started by MainActivity after login check")
                PersistentLogger.logEvent("SYSTEM", "WebSocket start deferred to MainActivity")

                Log.d(TAG, "Application initialized successfully")
                PersistentLogger.logEvent("SYSTEM", "===== APPLICATION INITIALIZATION COMPLETE =====")

            } catch (e: Exception) {
                Log.e(TAG, "Error during application initialization", e)
                PersistentLogger.logError("SYSTEM", "Application initialization failed", e)
            }
        }
    }

    private fun applySavedTheme() {
        try {
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
            PersistentLogger.logEvent("SYSTEM", "Theme applied: $theme")

        } catch (e: Exception) {
            Log.e(TAG, "Error applying theme, using system default", e)
            PersistentLogger.logError("SYSTEM", "Failed to apply theme, using system default", e)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    override fun onTerminate() {
        PersistentLogger.logEvent("SYSTEM", "===== APPLICATION TERMINATING =====")
        PersistentLogger.shutdown()
        super.onTerminate()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "⚠️ LOW MEMORY WARNING")
        PersistentLogger.logEvent("SYSTEM", "LOW MEMORY WARNING - system requesting memory release")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        val levelName = when (level) {
            TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            TRIM_MEMORY_MODERATE -> "MODERATE"
            TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "UNKNOWN($level)"
        }

        Log.w(TAG, "Memory trim requested: $levelName")
        PersistentLogger.logEvent("SYSTEM", "Memory trim: $levelName")

        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            PersistentLogger.flush()
        }
    }
}