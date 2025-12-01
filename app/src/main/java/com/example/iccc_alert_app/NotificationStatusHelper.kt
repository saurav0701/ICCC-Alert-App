package com.example.iccc_alert_app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat

/**
 * Helper to check notification status and guide users to fix issues
 */
object NotificationStatusHelper {

    data class NotificationStatus(
        val canReceiveNotifications: Boolean,
        val issues: List<String>,
        val warnings: List<String>
    )

    fun checkNotificationStatus(context: Context): NotificationStatus {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 1. Check if notification permission is granted (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                issues.add("Notification permission not granted")
            }
        }

        // 2. Check if notifications are enabled at system level
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!notificationManager.areNotificationsEnabled()) {
                issues.add("Notifications disabled in system settings")
            }
        }

        // 3. Check if alert channel is enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel("iccc_alerts")
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                issues.add("Alert channel is blocked")
            }
        }

        // 4. Check if notifications are enabled in app settings
        if (!SettingsActivity.areNotificationsEnabled(context)) {
            issues.add("Notifications disabled in app settings")
        }

        // 5. Check battery optimization
        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) {
            warnings.add("Battery optimization is enabled - may affect reliability")
        }

        // 6. Check battery level
        val batteryLevel = LowBatteryWarningManager.getCurrentBatteryLevel(context)
        val isCharging = LowBatteryWarningManager.isCharging(context)

        if (batteryLevel <= 15 && !isCharging) {
            warnings.add("Battery critically low ($batteryLevel%) - notifications may not work")
        } else if (batteryLevel <= 30 && !isCharging) {
            warnings.add("Battery low ($batteryLevel%) - consider charging for 24/7 monitoring")
        }

        val canReceiveNotifications = issues.isEmpty()

        return NotificationStatus(canReceiveNotifications, issues, warnings)
    }

    fun showNotificationStatusDialog(context: Context) {
        val status = checkNotificationStatus(context)

        val message = buildString {
            if (status.canReceiveNotifications) {
                append("✅ Notifications are working correctly\n\n")

                if (status.warnings.isNotEmpty()) {
                    append("⚠️ Warnings:\n")
                    status.warnings.forEach { warning ->
                        append("• $warning\n")
                    }
                }

                append("\nYou will receive alerts for all subscribed, unmuted channels.")
            } else {
                append("❌ Notifications are not working\n\n")
                append("Issues found:\n")
                status.issues.forEach { issue ->
                    append("• $issue\n")
                }

                if (status.warnings.isNotEmpty()) {
                    append("\n⚠️ Warnings:\n")
                    status.warnings.forEach { warning ->
                        append("• $warning\n")
                    }
                }

                append("\nTap 'Fix Issues' to resolve these problems.")
            }
        }

        val builder = AlertDialog.Builder(context)
            .setTitle("Notification Status")
            .setMessage(message)
            .setPositiveButton("OK", null)

        if (!status.canReceiveNotifications) {
            builder.setNeutralButton("Fix Issues") { _, _ ->
                showFixIssuesDialog(context, status)
            }
        }

        builder.show()
    }

    private fun showFixIssuesDialog(context: Context, status: NotificationStatus) {
        val fixes = mutableListOf<Pair<String, () -> Unit>>()

        // Add fixes for each issue
        status.issues.forEach { issue ->
            when {
                issue.contains("Notification permission") -> {
                    fixes.add("Grant notification permission" to {
                        openAppSettings(context)
                    })
                }
                issue.contains("system settings") -> {
                    fixes.add("Enable notifications in system settings" to {
                        openAppSettings(context)
                    })
                }
                issue.contains("Alert channel") -> {
                    fixes.add("Enable alert channel" to {
                        openChannelSettings(context)
                    })
                }
                issue.contains("app settings") -> {
                    fixes.add("Enable notifications in app settings" to {
                        val intent = Intent(context, SettingsActivity::class.java)
                        context.startActivity(intent)
                    })
                }
            }
        }

        // Add fixes for warnings
        status.warnings.forEach { warning ->
            when {
                warning.contains("Battery optimization") -> {
                    fixes.add("Disable battery optimization" to {
                        BatteryOptimizationHelper.requestBatteryOptimizationExemption(context)
                    })
                }
                warning.contains("Battery") -> {
                    fixes.add("View battery info" to {
                        // Just show the dialog again
                    })
                }
            }
        }

        if (fixes.isEmpty()) {
            return
        }

        val options = fixes.map { it.first }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Fix Notification Issues")
            .setItems(options) { _, which ->
                fixes[which].second()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openChannelSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                intent.putExtra(Settings.EXTRA_CHANNEL_ID, "iccc_alerts")
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            openAppSettings(context)
        }
    }

    /**
     * Show a compact status indicator
     */
    fun getStatusText(context: Context): String {
        val status = checkNotificationStatus(context)
        return when {
            status.canReceiveNotifications && status.warnings.isEmpty() -> "✅ Active"
            status.canReceiveNotifications && status.warnings.isNotEmpty() -> "⚠️ Active (${status.warnings.size} warnings)"
            else -> "❌ Not working (${status.issues.size} issues)"
        }
    }

    /**
     * Get a simple boolean status
     */
    fun areNotificationsWorking(context: Context): Boolean {
        return checkNotificationStatus(context).canReceiveNotifications
    }
}