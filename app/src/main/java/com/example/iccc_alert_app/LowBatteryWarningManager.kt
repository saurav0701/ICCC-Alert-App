package com.example.iccc_alert_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.appcompat.app.AlertDialog

/**
 * Monitors battery level and warns user when critical
 * At low battery, Android WILL kill network connections regardless of settings
 */
object LowBatteryWarningManager {

    private const val TAG = "LowBatteryWarning"
    private const val PREFS_NAME = "battery_warnings"
    private const val KEY_LAST_WARNING = "last_warning_time"
    private const val WARNING_COOLDOWN_MS = 30 * 60 * 1000L // 30 minutes

    private var isRegistered = false
    private var lastWarningTime = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = (level * 100 / scale.toFloat()).toInt()

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            Log.d(TAG, "Battery: $batteryPct%, charging: $isCharging")

            // Critical threshold: 15%
            if (!isCharging && batteryPct <= 15) {
                showLowBatteryWarning(context, batteryPct)
            }
        }
    }

    fun initialize(context: Context) {
        if (!isRegistered) {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context.applicationContext.registerReceiver(batteryReceiver, filter)
            isRegistered = true

            // Get current battery level immediately
            val batteryStatus = context.registerReceiver(null, filter)
            batteryStatus?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = (level * 100 / scale.toFloat()).toInt()

                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                Log.i(TAG, "Initial battery: $batteryPct%, charging: $isCharging")

                if (!isCharging && batteryPct <= 15) {
                    showLowBatteryWarning(context, batteryPct)
                }
            }

            Log.d(TAG, "LowBatteryWarningManager initialized")
        }
    }

    fun shutdown(context: Context) {
        if (isRegistered) {
            try {
                context.applicationContext.unregisterReceiver(batteryReceiver)
                isRegistered = false
                Log.d(TAG, "LowBatteryWarningManager shutdown")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }
    }

    private fun showLowBatteryWarning(context: Context, batteryPct: Int) {
        // Check cooldown to avoid spam
        val now = System.currentTimeMillis()
        if (now - lastWarningTime < WARNING_COOLDOWN_MS) {
            return
        }

        lastWarningTime = now

        // Save warning time
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_WARNING, now).apply()

        Log.w(TAG, """
            ⚠️ LOW BATTERY WARNING:
            - Battery at $batteryPct%
            - Android may restrict network access
            - Alert monitoring may be interrupted
            - Please connect charger for 24/7 monitoring
        """.trimIndent())

        // Try to show dialog if we have an activity context
        if (context is android.app.Activity) {
            showWarningDialog(context, batteryPct)
        } else {
            // Show notification instead
            showWarningNotification(context, batteryPct)
        }
    }

    private fun showWarningDialog(activity: android.app.Activity, batteryPct: Int) {
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("⚠️ Low Battery Warning")
                .setMessage(
                    "Battery is at $batteryPct%.\n\n" +
                            "At low battery levels, Android restricts background network access.\n\n" +
                            "This may cause you to miss critical alerts until you charge your phone.\n\n" +
                            "For 24/7 alert monitoring, please keep your device charged."
                )
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .setNegativeButton("Don't Show Again") { dialog, _ ->
                    val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("disable_warnings", true).apply()
                    dialog.dismiss()
                }
                .setCancelable(true)
                .show()
        }
    }

    private fun showWarningNotification(context: Context, batteryPct: Int) {
        // Implementation for showing notification
        // (Use NotificationManager similar to your alert notifications)
        Log.w(TAG, "Would show notification: Battery at $batteryPct%")
    }

    fun getCurrentBatteryLevel(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun isCharging(context: Context): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.isCharging
    }
}