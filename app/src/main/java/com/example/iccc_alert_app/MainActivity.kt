package com.example.iccc_alert_app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : BaseDrawerActivity() {

    private var currentFragment: androidx.fragment.app.Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request battery optimization exemption for persistent connection
        BatteryOptimizationHelper.requestBatteryOptimizationExemption(this)

        // Initialize WebSocketManager (which starts the service)
        WebSocketManager.initialize(this)

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

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Show filter icon in toolbar for Channels fragment
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
        // Force save channel states when app goes to background
        ChannelSyncState.forceSave()
        SubscriptionManager.forceSave()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Final save before app closes
        ChannelSyncState.forceSave()
        SubscriptionManager.forceSave()
    }
}