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
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView

abstract class BaseDrawerActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    protected lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    protected lateinit var bottomNavigationView: BottomNavigationView
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
        bottomNavigationView = findViewById(R.id.bottom_navigation)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navigationView.setNavigationItemSelectedListener(this)

        // Setup bottom navigation
        setupBottomNavigation()

        // Setup navigation header with user data
        setupNavigationHeader()
    }

    private fun setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener { item ->
            // Prevent reselecting the same item
            if (item.itemId == selectedMenuItemId && isCurrentActivity(item.itemId)) {
                return@setOnItemSelectedListener false
            }

            when (item.itemId) {
                R.id.nav_channels -> {
                    if (this !is MainActivity) {
                        navigateToActivity(MainActivity::class.java)
                    }
                    true
                }
                R.id.nav_saved_messages -> {
                    if (this !is SavedMessagesActivity) {
                        navigateToActivity(SavedMessagesActivity::class.java)
                    }
                    true
                }
                R.id.nav_settings -> {
                    if (this !is SettingsActivity) {
                        navigateToActivity(SettingsActivity::class.java)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun isCurrentActivity(itemId: Int): Boolean {
        return when (itemId) {
            R.id.nav_channels -> this is MainActivity
            R.id.nav_saved_messages -> this is SavedMessagesActivity
            R.id.nav_settings -> this is SettingsActivity
            else -> false
        }
    }

    private fun navigateToActivity(activityClass: Class<*>) {
        val intent = Intent(this, activityClass)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        // Smooth transition animation
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
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
                    navigateToActivity(MainActivity::class.java)
                }
            }
            R.id.nav_saved_messages -> {
                if (this !is SavedMessagesActivity) {
                    navigateToActivity(SavedMessagesActivity::class.java)
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
                if (this !is SettingsActivity) {
                    navigateToActivity(SettingsActivity::class.java)
                }
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
        // Update bottom navigation selection
        bottomNavigationView.menu.findItem(itemId)?.isChecked = true
    }

    override fun onResume() {
        super.onResume()
        // Ensure correct item is selected when activity resumes
        setSelectedMenuItem(selectedMenuItemId)
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}