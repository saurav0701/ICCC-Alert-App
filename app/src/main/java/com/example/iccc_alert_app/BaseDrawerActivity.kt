package com.example.iccc_alert_app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowInsetsController
import android.widget.TextView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.example.iccc_alert_app.auth.AuthManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView

abstract class BaseDrawerActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    protected lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    protected lateinit var bottomNavigationView: BottomNavigationView
    private var selectedMenuItemId: Int = R.id.nav_channels

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge setup will be done after setContentView in the overridden method
    }

    override fun setContentView(layoutResID: Int) {
        // ✅ Setup edge-to-edge BEFORE calling super.setContentView
        setupEdgeToEdge()

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
        // Force white hamburger icon to match navy toolbar
        toggle.drawerArrowDrawable.color = Color.WHITE

        // Refresh counts every time the drawer opens
        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: android.view.View) {
                refreshDrawerStats()
            }
        })

        navigationView.setNavigationItemSelectedListener(this)

        // Setup bottom navigation
        setupBottomNavigation()

        // Setup navigation header with user data
        setupNavigationHeader()

        // ✅ Handle window insets for edge-to-edge
        setupWindowInsets()
    }

    private fun setupEdgeToEdge() {
        // Enable edge-to-edge content
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Make status bar transparent
        window.statusBarColor = Color.TRANSPARENT

        // Make navigation bar transparent (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.navigationBarColor = Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
        } else {
            // Semi-transparent for older Android versions
            window.navigationBarColor = Color.parseColor("#40000000")
        }

        // Navy toolbar — always use white (light) status bar icons
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.post {
                window.insetsController?.apply {
                    // Clear LIGHT flags so icons are white on the navy background
                    setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                    setSystemBarsAppearance(
                        if (isDarkTheme()) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (isDarkTheme()) {
                0
            } else {
                // Only light navigation bar; keep status bar icons white for navy toolbar
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }

    private fun isDarkTheme(): Boolean {
        return when (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) {
            android.content.res.Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }

    private fun setupWindowInsets() {
        // ✅ Handle status bar insets for AppBarLayout (not toolbar directly)
        val appBarLayout = findViewById<AppBarLayout>(R.id.app_bar_layout)
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        // ✅ Add bottom padding to content frame to prevent overlap with bottom navigation
        val contentFrame = findViewById<View>(R.id.content_frame)
        val bottomNavHeight = resources.getDimensionPixelSize(R.dimen.bottom_navigation_height)

        ViewCompat.setOnApplyWindowInsetsListener(contentFrame) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Add bottom navigation height + system navigation bar height
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                bottomNavHeight + systemBars.bottom
            )
            insets
        }

        // Handle navigation bar insets for bottom navigation
        ViewCompat.setOnApplyWindowInsetsListener(bottomNavigationView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                systemBars.bottom
            )
            insets
        }

        // Navigation drawer handles its own insets via fitsSystemWindows="true"
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
                R.id.nav_alerts -> {
                    if (this !is AlertsActivity) {
                        navigateToActivity(AlertsActivity::class.java)
                    }
                    true
                }
                R.id.nav_saved_messages -> {
                    if (this !is SavedMessagesActivity) {
                        navigateToActivity(SavedMessagesActivity::class.java)
                    }
                    true
                }
                R.id.nav_profile -> {
                    if (this !is ProfileActivity) {
                        navigateToActivity(ProfileActivity::class.java)
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
            R.id.nav_alerts -> this is AlertsActivity
            R.id.nav_saved_messages -> this is SavedMessagesActivity
            R.id.nav_profile -> this is ProfileActivity
            else -> false
        }
    }

    private fun navigateToActivity(activityClass: Class<*>) {
        val intent = Intent(this, activityClass)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    /**
     * ✅ Generate user initials from name
     */
    private fun getInitials(name: String): String {
        return name.split(" ")
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .joinToString("")
    }

    private fun setupNavigationHeader() {
        val headerView = navigationView.getHeaderView(0)
        val userName = headerView.findViewById<TextView>(R.id.user_name)
        val userPhone = headerView.findViewById<TextView>(R.id.user_phone)
        val userInfo = headerView.findViewById<TextView>(R.id.user_info)
        val userAvatar = headerView.findViewById<TextView>(R.id.user_avatar)
        val viewProfileButton = headerView.findViewById<View>(R.id.view_profile_button)
        val channelCount = headerView.findViewById<TextView?>(R.id.header_channel_count)
        val eventsCount = headerView.findViewById<TextView?>(R.id.header_events_count)
        val savedCount = headerView.findViewById<TextView?>(R.id.header_saved_count)

        // Load user data from AuthManager
        val user = AuthManager.getCurrentUser()
        if (user != null) {
            userName.text = user.name
            userPhone.text = "+91 ${user.phone}"
            userInfo.text = "${user.designation} · ${user.area ?: "ICCC"}"

            // Set avatar with user initials
            userAvatar.text = getInitials(user.name)
        } else {
            userName.text = "ICCC User"
            userPhone.text = "+91 XXXXXXXXXX"
            userInfo.text = "Officer · ICCC"
            userAvatar.text = "IC"
        }

        // Populate live stats
        channelCount?.text = SubscriptionManager.getSubscriptions().size.toString()
        eventsCount?.text = SubscriptionManager.getTotalEventCount().toString()
        savedCount?.text = SavedMessagesManager.getSavedMessages().size.toString()

        // View profile button click with animation
        viewProfileButton.setOnClickListener {
            it.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    it.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()

                    drawerLayout.closeDrawer(GravityCompat.START)
                    startActivity(Intent(this, ProfileActivity::class.java))
                }
                .start()
        }
    }

    /** Refresh the three stat counts in the drawer header to stay in sync with Settings. */
    private fun refreshDrawerStats() {
        val headerView = navigationView.getHeaderView(0)
        headerView.findViewById<TextView?>(R.id.header_channel_count)
            ?.text = SubscriptionManager.getSubscriptions().size.toString()
        headerView.findViewById<TextView?>(R.id.header_events_count)
            ?.text = SubscriptionManager.getTotalEventCount().toString()
        headerView.findViewById<TextView?>(R.id.header_saved_count)
            ?.text = SavedMessagesManager.getSavedMessages().size.toString()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_channels -> {
                if (this !is MainActivity) navigateToActivity(MainActivity::class.java)
            }
            R.id.nav_alerts -> {
                if (this !is AlertsActivity) navigateToActivity(AlertsActivity::class.java)
            }
            R.id.nav_saved_messages -> {
                if (this !is SavedMessagesActivity) navigateToActivity(SavedMessagesActivity::class.java)
            }
            R.id.nav_search -> {
                startActivity(Intent(this, SearchActivity::class.java))
            }
            R.id.nav_map -> {
                startActivity(Intent(this, CameraMapActivity::class.java))
            }
            R.id.nav_camera_streams -> {
                if (this !is CameraStreamsActivity) navigateToActivity(CameraStreamsActivity::class.java)
            }
            R.id.nav_profile -> {
                if (this !is ProfileActivity) navigateToActivity(ProfileActivity::class.java)
            }
            R.id.nav_settings -> {
                if (this !is SettingsActivity) navigateToActivity(SettingsActivity::class.java)
            }
            R.id.nav_sign_out -> {
                signOut()
            }
        }

        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun signOut() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sign Out")
            .setMessage("Are you sure you want to sign out?")
            .setPositiveButton("Sign Out") { _, _ ->
                AuthManager.logout { _, _ ->
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}