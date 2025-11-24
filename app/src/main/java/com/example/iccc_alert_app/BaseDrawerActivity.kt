package com.example.iccc_alert_app

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.example.iccc_alert_app.auth.AuthManager
import com.google.android.material.navigation.NavigationView

abstract class BaseDrawerActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    protected lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private var selectedMenuItemId: Int = R.id.nav_channels

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(R.layout.activity_base_drawer)

        val contentFrame = findViewById<View>(R.id.content_frame)
        layoutInflater.inflate(layoutResID, contentFrame as android.view.ViewGroup, true)

        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navigationView.setNavigationItemSelectedListener(this)

        // Setup navigation header with user data
        setupNavigationHeader()
    }

    private fun setupNavigationHeader() {
        val headerView = navigationView.getHeaderView(0)
        val userName = headerView.findViewById<TextView>(R.id.user_name)
        val userPhone = headerView.findViewById<TextView>(R.id.user_phone)
        val userInfo = headerView.findViewById<TextView>(R.id.user_info)
        val viewProfileButton = headerView.findViewById<TextView>(R.id.view_profile_button)

        // Load user data from AuthManager
        val user = AuthManager.getCurrentUser()
        if (user != null) {
            userName.text = user.name
            userPhone.text = "+91 ${user.phone}"
            userInfo.text = "${user.designation} • ${user.area}"
        } else {
            userName.text = "ICCC User"
            userPhone.text = "+91 XXXXXXXXXX"
            userInfo.text = "Tap to view profile"
        }

        // View profile button click
        viewProfileButton.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_channels -> {
                if (this !is MainActivity) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
            R.id.nav_saved_messages -> {
                if (this !is SavedMessagesActivity) {
                    startActivity(Intent(this, SavedMessagesActivity::class.java))
                }
            }
            R.id.nav_search -> {
                startActivity(Intent(this, SearchActivity::class.java))
            }
            R.id.nav_share -> {
                shareApp()
            }
            R.id.nav_profile -> {
                startActivity(Intent(this, ProfileActivity::class.java))
            }
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun shareApp() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ICCC Alert App")
            putExtra(Intent.EXTRA_TEXT, "Download the ICCC Alert App for real-time monitoring and alerts!")
        }
        startActivity(Intent.createChooser(shareIntent, "Share App"))
    }

    protected fun setSelectedMenuItem(itemId: Int) {
        selectedMenuItemId = itemId
        navigationView.setCheckedItem(itemId)
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}