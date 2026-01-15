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

        // Set status bar icons to dark/light based on theme
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // ✅ Safe: insetsController will be available after we return from this method
            window.decorView.post {
                window.insetsController?.apply {
                    setSystemBarsAppearance(
                        if (isDarkTheme()) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    )
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
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
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
                R.id.nav_camera_streams -> {
                    if (this !is CameraStreamsActivity) {
                        navigateToActivity(CameraStreamsActivity::class.java)
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
            R.id.nav_camera_streams -> this is CameraStreamsActivity
            R.id.nav_saved_messages -> this is SavedMessagesActivity
            R.id.nav_settings -> this is SettingsActivity
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
        val viewProfileButton = headerView.findViewById<TextView>(R.id.view_profile_button)

        // Load user data from AuthManager
        val user = AuthManager.getCurrentUser()
        if (user != null) {
            userName.text = user.name
            userPhone.text = "+91 ${user.phone}"
            userInfo.text = "${user.designation} • ${user.area}"

            // ✅ Set avatar with user initials
            userAvatar.text = getInitials(user.name)
        } else {
            userName.text = "ICCC User"
            userPhone.text = "+91 XXXXXXXXXX"
            userInfo.text = "Tap to view profile"
            userAvatar.text = "IC"
        }

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

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_channels -> {
                if (this !is MainActivity) {
                    navigateToActivity(MainActivity::class.java)
                }
            }
            R.id.nav_camera_streams -> {
                if (this !is CameraStreamsActivity) {
                    navigateToActivity(CameraStreamsActivity::class.java)
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}