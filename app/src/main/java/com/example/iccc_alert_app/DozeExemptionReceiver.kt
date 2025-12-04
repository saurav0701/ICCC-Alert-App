package com.example.iccc_alert_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * Monitors when device enters/exits Doze mode
 * Restarts service if needed when exiting doze
 */
class DozeExemptionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DozeExemptionReceiver"
        private const val PREFS_NAME = "doze_tracking"
        private const val KEY_DOZE_ENTERED_AT = "doze_entered_at"
        private const val KEY_DOZE_EXIT_COUNT = "doze_exit_count"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                handleDozeMode(context)
            }
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                handlePowerSaveMode(context)
            }
        }
    }

    private fun handleDozeMode(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isDozing = powerManager.isDeviceIdleMode

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (isDozing) {
            // Device entered doze
            val now = System.currentTimeMillis()
            prefs.edit().putLong(KEY_DOZE_ENTERED_AT, now).apply()

            val msg = """
                🌙 DEVICE ENTERED DOZE MODE
                - Background processing will be severely restricted
                - WebSocket connection may be dropped
                - Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now))}
            """.trimIndent()

            Log.w(TAG, msg)
            PersistentLogger.logDozeMode(true, "Entered at ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now))}")
        } else {
            // Device exited doze
            val dozeEnteredAt = prefs.getLong(KEY_DOZE_ENTERED_AT, 0L)
            val now = System.currentTimeMillis()
            val dozeDuration = if (dozeEnteredAt > 0) (now - dozeEnteredAt) / 1000 else 0

            val exitCount = prefs.getInt(KEY_DOZE_EXIT_COUNT, 0) + 1
            prefs.edit()
                .putInt(KEY_DOZE_EXIT_COUNT, exitCount)
                .remove(KEY_DOZE_ENTERED_AT)
                .apply()

            val msg = """
                ☀️ DEVICE EXITED DOZE MODE
                - Doze duration: ${dozeDuration}s (${dozeDuration / 60}m)
                - Exit count: $exitCount
                - Checking service state...
            """.trimIndent()

            Log.w(TAG, msg)
            PersistentLogger.logDozeMode(false, "Duration: ${dozeDuration / 60}m, Exit count: $exitCount")

            // If doze lasted more than 5 minutes, restart service to ensure connection
            if (dozeDuration > 300) {
                Log.w(TAG, "⚠️ Long doze detected (${dozeDuration / 60}m), restarting service")
                PersistentLogger.logServiceLifecycle("RESTART_AFTER_DOZE", "Duration: ${dozeDuration / 60}m")

                try {
                    // Stop and restart service to force reconnection
                    WebSocketService.stop(context)

                    // Wait a moment for cleanup
                    Thread.sleep(1000)

                    // Restart service
                    WebSocketService.start(context)

                    Log.i(TAG, "✅ Service restarted after doze exit")
                    PersistentLogger.logServiceLifecycle("RESTARTED", "Successfully restarted after doze")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart service: ${e.message}")
                    PersistentLogger.logError("SERVICE_RESTART", "Failed after doze", e)
                }
            } else {
                Log.d(TAG, "Short doze (${dozeDuration}s), service should auto-reconnect")
                PersistentLogger.logEvent("DOZE", "Short doze (${dozeDuration}s), no restart needed")
            }
        }
    }

    private fun handlePowerSaveMode(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isPowerSaveMode = powerManager.isPowerSaveMode

        if (isPowerSaveMode) {
            Log.w(TAG, """
                🔋 POWER SAVE MODE ENABLED
                - Background activity may be restricted
                - Network access may be limited
            """.trimIndent())
        } else {
            Log.d(TAG, "Power save mode disabled")
        }
    }
}