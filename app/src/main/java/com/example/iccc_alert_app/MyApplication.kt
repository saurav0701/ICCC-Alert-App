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

        // ✅ Initialize PersistentLogger FIRST - before anything else
        try {
            PersistentLogger.initialize(this)
            PersistentLogger.logEvent("SYSTEM", "===== APPLICATION STARTED =====")
            PersistentLogger.logEvent("SYSTEM", "Build: ${Build.MANUFACTURER} ${Build.MODEL}")
            PersistentLogger.logEvent("SYSTEM", "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            Log.d(TAG, "PersistentLogger initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PersistentLogger", e)
        }

        // Apply saved theme before anything else (UI operation - must be synchronous)
        applySavedTheme()

        // Initialize critical components on background thread to prevent ANR
        applicationScope.launch {
            try {
                // Initialize AuthManager FIRST (before anything that might use it)
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

                // Start WebSocket service (persistent connection)
                // This should be launched on Main dispatcher as it may involve UI
                launch(Dispatchers.Main) {
                    WebSocketManager.initialize(this@MyApplication)
                    Log.d(TAG, "WebSocketManager initialized")
                    PersistentLogger.logEvent("SYSTEM", "WebSocketManager initialized")
                }

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
        // Note: onTerminate() is rarely called in production (only when process is killed)
        // This is mainly useful for emulator/testing
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

        // Force flush logs on critical memory situations
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            PersistentLogger.flush()
        }
    }
}