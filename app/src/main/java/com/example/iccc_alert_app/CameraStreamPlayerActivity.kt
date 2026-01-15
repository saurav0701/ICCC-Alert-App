package com.example.iccc_alert_app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class CameraStreamPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraStreamPlayer"
        private const val CONTROLS_HIDE_DELAY = 4000L
        private const val REQUEST_STORAGE_PERMISSION = 100
        private const val STREAM_TIMEOUT_MS = 10000L
        const val ACTION_MEDIA_CONTROL = "media_control"
        const val EXTRA_CONTROL_TYPE = "control_type"
        const val CONTROL_TYPE_PAUSE = 1
        const val CONTROL_TYPE_PLAY = 2
    }

    private lateinit var webView: WebView
    private lateinit var loadingView: FrameLayout
    private lateinit var loadingText: TextView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorTextView: TextView
    private lateinit var retryButton: Button
    private lateinit var topControlBar: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var fullscreenButton: ImageButton
    private lateinit var screenshotButton: ImageButton
    private lateinit var pipButton: ImageButton
    private lateinit var cameraInfoText: TextView
    private lateinit var zoomControlBar: LinearLayout
    private lateinit var screenshotNotification: CardView

    private lateinit var zoomController: ZoomController
    private lateinit var pipController: PipController
    private lateinit var screenshotManager: ScreenshotManager

    private var cameraId: String = ""
    private var cameraName: String = ""
    private var cameraArea: String = ""
    private var streamUrl: String = ""
    private var isFullscreen = false
    private var areControlsVisible = true
    private var isInPipMode = false

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast received: ${intent?.action}")
            if (intent?.action == ACTION_MEDIA_CONTROL) {
                val controlType = intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)
                Log.d(TAG, "Control type: $controlType")
                when (controlType) {
                    CONTROL_TYPE_PAUSE -> {
                        Log.d(TAG, "Pause command received")
                        pipController.pauseStream()
                    }
                    CONTROL_TYPE_PLAY -> {
                        Log.d(TAG, "Play command received")
                        pipController.playStream()
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_stream_player)

        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraId = intent.getStringExtra("CAMERA_ID") ?: ""
        cameraName = intent.getStringExtra("CAMERA_NAME") ?: ""
        cameraArea = intent.getStringExtra("CAMERA_AREA") ?: ""
        streamUrl = intent.getStringExtra("STREAM_URL") ?: ""

        Log.d(TAG, "Opening stream: $streamUrl")

        initializeViews()
        initializeControllers()
        setupListeners()

        val intentFilter = IntentFilter(ACTION_MEDIA_CONTROL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, intentFilter)
        }
        Log.d(TAG, "Broadcast receiver registered for PiP controls")

        if (streamUrl.isEmpty()) {
            showError("Invalid stream URL")
            return
        }

        setupWebView()
        loadStream()
    }

    private fun initializeViews() {
        webView = findViewById(R.id.webview)
        loadingView = findViewById(R.id.loading_view)
        loadingText = findViewById(R.id.loading_text)
        loadingSpinner = findViewById(R.id.loading_spinner)
        errorView = findViewById(R.id.error_view)
        errorTextView = findViewById(R.id.error_text)
        retryButton = findViewById(R.id.retry_button)
        topControlBar = findViewById(R.id.top_control_bar)
        backButton = findViewById(R.id.back_button)
        fullscreenButton = findViewById(R.id.fullscreen_button)
        screenshotButton = findViewById(R.id.screenshot_button)
        pipButton = findViewById(R.id.pip_button)
        cameraInfoText = findViewById(R.id.camera_info_text)
        zoomControlBar = findViewById(R.id.zoom_control_bar)
        screenshotNotification = findViewById(R.id.screenshot_notification)

        cameraInfoText.text = "$cameraName - $cameraArea"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pipButton.visibility = View.VISIBLE
        } else {
            pipButton.visibility = View.GONE
        }
    }

    private fun initializeControllers() {
        zoomController = ZoomController(
            zoomControlBar = zoomControlBar,
            zoomInButton = findViewById(R.id.zoom_in_button),
            zoomOutButton = findViewById(R.id.zoom_out_button),
            resetZoomButton = findViewById(R.id.reset_zoom_button),
            zoomLevelText = findViewById(R.id.zoom_level_text),
            webView = webView,
            onControlsInteraction = {
                showControls()
                scheduleControlsHide()
            }
        )

        pipController = PipController(this, webView)

        screenshotManager = ScreenshotManager(
            activity = this,
            webView = webView,
            cameraName = cameraName,
            cameraArea = cameraArea
        )
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            if (isFullscreen) {
                exitFullscreen()
            } else {
                finish()
            }
        }

        fullscreenButton.setOnClickListener { toggleFullscreen() }

        screenshotButton.setOnClickListener {
            takeScreenshot()
        }

        pipButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "PiP button clicked")
                pipController.enterPipMode()
            } else {
                Toast.makeText(this, "PiP requires Android 8.0+", Toast.LENGTH_SHORT).show()
            }
        }

        retryButton.setOnClickListener {
            WebViewManager.cleanup(webView)
            loadStream()
        }

        webView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (areControlsVisible) {
                    hideControls()
                } else {
                    showControls()
                }
            }
            false
        }
    }

    private fun takeScreenshot() {
        if (checkStoragePermission()) {
            screenshotManager.captureScreenshot { success, uri, message ->
                if (success && uri != null) {
                    showScreenshotSuccess()
                    screenshotManager.showScreenshotOptions(uri)
                } else {
                    Toast.makeText(this, message ?: "Screenshot failed", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            requestStoragePermission()
        }
    }

    private fun showScreenshotSuccess() {
        screenshotNotification.visibility = View.VISIBLE
        screenshotNotification.animate()
            .alpha(1f)
            .setDuration(300)
            .start()

        Handler(Looper.getMainLooper()).postDelayed({
            screenshotNotification.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    screenshotNotification.visibility = View.GONE
                }
                .start()
        }, 2000)
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            REQUEST_STORAGE_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                takeScreenshot()
            } else {
                Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebViewManager.setup(webView, object : WebViewManager.WebViewCallback {
            override fun onPageLoaded() {
                Log.d(TAG, "Page loaded, waiting for stream...")
            }

            override fun onStreamStarted() {
                Log.d(TAG, "Stream started successfully")
                cancelTimeout()
                hideControlsHandler.postDelayed({
                    showPlayer()
                    scheduleControlsHide()
                }, 500)
            }

            override fun onError(description: String) {
                Log.e(TAG, "Stream error: $description")
                cancelTimeout()
                showError(description)
            }

            override fun onTimeout() {
                Log.w(TAG, "Stream timeout")
                showError("Connection timeout. Camera may be offline.")
            }

            override fun onBuffering() {
                Log.d(TAG, "Showing buffering indicator")
                runOnUiThread {
                    if (webView.visibility == View.VISIBLE && errorView.visibility == View.GONE) {
                        loadingView.visibility = View.VISIBLE
                        loadingText.text = "Reconnecting..."
                    }
                }
            }

            override fun onBufferingEnd() {
                Log.d(TAG, "Hiding buffering indicator")
                runOnUiThread {
                    if (errorView.visibility == View.GONE) {
                        loadingView.visibility = View.GONE
                    }
                }
            }
        })
    }

    private fun loadStream() {
        showLoading()
        loadingText.text = "Connecting to camera..."
        startStreamTimeout()
        WebViewManager.loadHlsStream(webView, streamUrl)
    }

    private fun startStreamTimeout() {
        cancelTimeout()
        timeoutRunnable = Runnable {
            Log.w(TAG, "Stream loading timeout after ${STREAM_TIMEOUT_MS}ms")
            showError("Connection timeout. Please try again.")
        }
        timeoutHandler.postDelayed(timeoutRunnable!!, STREAM_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { runnable ->
            timeoutHandler.removeCallbacks(runnable)
        }
        timeoutRunnable = null
    }

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        topControlBar.visibility = View.VISIBLE
        zoomControlBar.visibility = View.GONE
    }

    private fun showPlayer() {
        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        zoomControlBar.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        loadingView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        webView.visibility = View.GONE
        zoomControlBar.visibility = View.GONE

        // Remove ALL emojis and provide professional error messages
        val cleanMessage = message.replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), "").trim()

        val professionalMessage = when {
            cleanMessage.contains("timeout", ignoreCase = true) ||
                    cleanMessage.contains("not responding", ignoreCase = true) ->
                "Connection Timeout\n\nThe camera is not responding. Please verify the camera is online and try again."

            cleanMessage.contains("offline", ignoreCase = true) ||
                    cleanMessage.contains("unavailable", ignoreCase = true) ->
                "Camera Offline\n\nThis camera is currently offline. Please try again later."

            cleanMessage.contains("network", ignoreCase = true) ||
                    cleanMessage.contains("connection", ignoreCase = true) ->
                "Network Error\n\nPlease check your internet connection and try again."

            cleanMessage.contains("failed", ignoreCase = true) ->
                "Connection Failed\n\nUnable to connect to camera stream. Please verify camera status and try again."

            cleanMessage.isEmpty() ->
                "Connection Error\n\nUnable to load camera stream. Please try again."

            else -> {
                // Clean any remaining message of emojis
                if (cleanMessage.length > 100) {
                    "Connection Error\n\nUnable to connect to camera. Please try again later."
                } else {
                    cleanMessage
                }
            }
        }

        errorTextView.text = professionalMessage
        topControlBar.visibility = View.VISIBLE
    }

    private fun showControls() {
        areControlsVisible = true
        topControlBar.visibility = View.VISIBLE
        topControlBar.animate().alpha(1f).setDuration(300).start()
        zoomControlBar.visibility = View.VISIBLE
        zoomControlBar.animate().alpha(1f).setDuration(300).start()
        if (isFullscreen) scheduleControlsHide()
    }

    private fun hideControls() {
        if (!isFullscreen) return
        areControlsVisible = false
        topControlBar.animate().alpha(0f).setDuration(300).withEndAction {
            topControlBar.visibility = View.GONE
        }.start()
        zoomControlBar.animate().alpha(0f).setDuration(300).withEndAction {
            zoomControlBar.visibility = View.GONE
        }.start()
        cancelControlsHide()
    }

    private fun scheduleControlsHide() {
        cancelControlsHide()
        if (isFullscreen) {
            hideControlsHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY)
        }
    }

    private fun cancelControlsHide() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }

    private fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        isFullscreen = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, webView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        fullscreenButton.setImageResource(R.drawable.ic_fullscreen_exit)
        showControls()
    }

    private fun exitFullscreen() {
        isFullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, webView).show(WindowInsetsCompat.Type.systemBars())
        fullscreenButton.setImageResource(R.drawable.ic_fullscreen)
        showControls()
        cancelControlsHide()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        pipController.onPipModeChanged(isInPictureInPictureMode, topControlBar, zoomControlBar)

        if (isInPictureInPictureMode) {
            Log.d(TAG, "Entered PiP - disabling heartbeat")
            webView.evaluateJavascript(
                "(function() { window.isPipMode = true; })();",
                null
            )
        } else {
            Log.d(TAG, "Exited PiP - re-enabling heartbeat")
            webView.evaluateJavascript(
                "(function() { window.isPipMode = false; })();",
                null
            )
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isInPipMode) {
            Log.d(TAG, "Pausing WebView (not in PiP)")
            webView.onPause()
        } else {
            Log.d(TAG, "Keeping WebView active (in PiP)")
        }
        cancelControlsHide()
    }

    override fun onResume() {
        super.onResume()
        if (!isInPipMode) {
            Log.d(TAG, "Resuming WebView")
            webView.onResume()
        }
        if (isFullscreen) scheduleControlsHide()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipMode) {
            Log.d(TAG, "Stopping - cleaning up WebView")
            WebViewManager.cleanup(webView)
        } else {
            Log.d(TAG, "In PiP mode - keeping WebView alive")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(broadcastReceiver)
            Log.d(TAG, "Broadcast receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
        cancelTimeout()
        cancelControlsHide()
        WebViewManager.cleanup(webView)
        webView.destroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            super.onBackPressed()
        }
    }
}