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

        try {
            PersistentLogger.initialize(this)
            PersistentLogger.logEvent("SYSTEM", "===== APPLICATION STARTED =====")
            PersistentLogger.logEvent("SYSTEM", "Build: ${Build.MANUFACTURER} ${Build.MODEL}")
            PersistentLogger.logEvent("SYSTEM", "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            Log.d(TAG, "PersistentLogger initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PersistentLogger", e)
        }

        applySavedTheme()

        // ✅ CRITICAL: Initialize these SYNCHRONOUSLY before MainActivity can access them
        try {
            BackendConfig.initialize(this)
            Log.d(TAG, "✅ BackendConfig initialized (sync)")
            PersistentLogger.logEvent("SYSTEM", "BackendConfig initialized")

            AuthManager.initialize(this)
            Log.d(TAG, "✅ AuthManager initialized (sync)")
            PersistentLogger.logEvent("SYSTEM", "AuthManager initialized")

            SubscriptionManager.initialize(this)
            Log.d(TAG, "✅ SubscriptionManager initialized (sync)")
            PersistentLogger.logEvent("SYSTEM", "SubscriptionManager initialized")

            ChannelSyncState.initialize(this)
            Log.d(TAG, "✅ ChannelSyncState initialized (sync)")
            PersistentLogger.logEvent("SYSTEM", "ChannelSyncState initialized")

            SavedMessagesManager.initialize(this)
            Log.d(TAG, "✅ SavedMessagesManager initialized (sync)")
            PersistentLogger.logEvent("SYSTEM", "SavedMessagesManager initialized")

            MultiCameraManager.initialize(this)
            Log.d(TAG, "✅ MultiCameraManager initialized (sync)")
            PersistentLogger.logEvent("SYSTEM", "MultiCameraManager initialized")

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: Failed to initialize core managers", e)
            PersistentLogger.logError("SYSTEM", "Core initialization failed", e)
        }

        // ✅ Non-critical initialization can happen async
        applicationScope.launch {
            try {
                CameraManager.initializeContext(this@MyApplication)
                Log.d(TAG, "CameraManager context initialized (async)")
                PersistentLogger.logEvent("SYSTEM", "CameraManager context ready")

                Log.d(TAG, "===== APPLICATION INITIALIZATION COMPLETE =====")
                PersistentLogger.logEvent("SYSTEM", "===== APPLICATION INITIALIZATION COMPLETE =====")

            } catch (e: Exception) {
                Log.e(TAG, "Error during async initialization", e)
                PersistentLogger.logError("SYSTEM", "Async initialization failed", e)
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
            PersistentLogger.logError("SYSTEM", "Failed to apply theme", e)
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
        PersistentLogger.logEvent("SYSTEM", "LOW MEMORY WARNING")
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