package com.example.iccc_alert_app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : BaseDrawerActivity() {

    private var currentFragment: androidx.fragment.app.Fragment? = null

    // Notification permission launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted - service already started by MyApplication
        } else {
            // Permission denied - show explanation
            showNotificationPermissionRationale()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request notification permission first (Android 13+)
        requestNotificationPermission()

        // Then request battery optimization exemption
        BatteryOptimizationHelper.requestBatteryOptimizationExemption(this)

        // DON'T start service here - MyApplication already handles it!
        // WebSocketManager is already initialized in MyApplication.onCreate()

        // Initialize low battery warning
        LowBatteryWarningManager.initialize(this)

        supportActionBar?.title = "My Channels"
        setSelectedMenuItem(R.id.nav_channels)

        val fab: FloatingActionButton = findViewById(R.id.fab_search)
        fab.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        if (savedInstanceState == null) {
            val fragment = ChannelsFragment()
            currentFragment = fragment
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure Channels tab is selected
        setSelectedMenuItem(R.id.nav_channels)

        // ✅ Clear alert notifications when user opens app
        clearAlertNotifications()
    }

    // ✅ UPDATED: Helper function to clear all channel notifications
    private fun clearAlertNotifications() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Get all active notifications
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNotifications = nm.activeNotifications

                // Cancel all notifications from the alerts channel
                activeNotifications.forEach { statusBarNotification ->
                    // Check if it's an alert notification (not the service notification)
                    if (statusBarNotification.id != 1001) { // Skip service notification
                        nm.cancel(statusBarNotification.id)
                    }
                }
            }

            Log.d("MainActivity", "Cleared alert notifications")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error clearing notifications: ${e.message}")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted - service started by MyApplication
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Show rationale before requesting
                    showNotificationPermissionRationale()
                }
                else -> {
                    // Request permission
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        // For Android 12 and below, no permission needed
        // Service already started by MyApplication
    }

    private fun showNotificationPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Enable Notifications")
            .setMessage(
                "⚠️ Critical Alert Notifications Required\n\n" +
                        "This app monitors real-time security events and sends important alerts.\n\n" +
                        "Without notification permission:\n" +
                        "• You won't receive critical alerts\n" +
                        "• Events will only show when you open the app\n" +
                        "• Real-time monitoring won't work properly\n\n" +
                        "For 24/7 alert monitoring, please enable notifications."
            )
            .setPositiveButton("Enable") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            .setNegativeButton("Not Now") { dialog, _ ->
                dialog.dismiss()
                // Service already started by MyApplication
            }
            .setCancelable(false)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_channels, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_filter -> {
                (currentFragment as? ChannelsFragment)?.showFilterDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPause() {
        super.onPause()
        ChannelSyncState.forceSave()
        SubscriptionManager.forceSave()
    }

    override fun onDestroy() {
        super.onDestroy()
        ChannelSyncState.forceSave()
        SubscriptionManager.forceSave()
    }
}