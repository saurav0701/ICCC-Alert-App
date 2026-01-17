package com.example.iccc_alert_app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * ✅ SAFE Quad Camera Player - 2x2 Grid with Timeout & Error Handling
 * Professional loading indicators with fullscreen option for each camera
 */
class QuadCameraPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "QuadCameraPlayer"
        private const val STREAM_TIMEOUT_MS = 15000L // 15 seconds timeout per camera
    }

    // WebViews for 2x2 grid
    private lateinit var webView1: WebView
    private lateinit var webView2: WebView
    private lateinit var webView3: WebView
    private lateinit var webView4: WebView

    // Loading overlays (professional semi-transparent)
    private lateinit var loadingOverlay1: FrameLayout
    private lateinit var loadingOverlay2: FrameLayout
    private lateinit var loadingOverlay3: FrameLayout
    private lateinit var loadingOverlay4: FrameLayout

    // Error overlays (new)
    private lateinit var errorOverlay1: LinearLayout
    private lateinit var errorOverlay2: LinearLayout
    private lateinit var errorOverlay3: LinearLayout
    private lateinit var errorOverlay4: LinearLayout

    // Camera info overlays
    private lateinit var info1: TextView
    private lateinit var info2: TextView
    private lateinit var info3: TextView
    private lateinit var info4: TextView

    // Fullscreen buttons
    private lateinit var fullscreen1: ImageButton
    private lateinit var fullscreen2: ImageButton
    private lateinit var fullscreen3: ImageButton
    private lateinit var fullscreen4: ImageButton

    // Controls
    private lateinit var topControlBar: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var viewNameText: TextView
    private lateinit var statsText: TextView

    // Empty slots
    private lateinit var empty2: LinearLayout
    private lateinit var empty3: LinearLayout
    private lateinit var empty4: LinearLayout

    private var viewId: String = ""
    private var multiView: MultiCameraView? = null
    private var cameras = listOf<CameraInfo>()

    // Timeout handlers for each camera
    private val timeoutHandlers = mutableMapOf<Int, Handler>()
    private val timeoutRunnables = mutableMapOf<Int, Runnable>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quad_camera_player)

        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        viewId = intent.getStringExtra("VIEW_ID") ?: ""
        multiView = MultiCameraManager.getView(viewId)

        if (multiView == null) {
            Toast.makeText(this, "View not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        cameras = multiView!!.getCameras()

        if (cameras.isEmpty()) {
            Toast.makeText(this, "No cameras available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializeViews()
        setupListeners()
        loadCameras()

        topControlBar.visibility = View.VISIBLE
    }

    private fun initializeViews() {
        // WebViews
        webView1 = findViewById(R.id.webview_1)
        webView2 = findViewById(R.id.webview_2)
        webView3 = findViewById(R.id.webview_3)
        webView4 = findViewById(R.id.webview_4)

        // Loading overlays
        loadingOverlay1 = findViewById(R.id.loading_overlay_1)
        loadingOverlay2 = findViewById(R.id.loading_overlay_2)
        loadingOverlay3 = findViewById(R.id.loading_overlay_3)
        loadingOverlay4 = findViewById(R.id.loading_overlay_4)

        // Info overlays
        info1 = findViewById(R.id.info_1)
        info2 = findViewById(R.id.info_2)
        info3 = findViewById(R.id.info_3)
        info4 = findViewById(R.id.info_4)

        // Fullscreen buttons
        fullscreen1 = findViewById(R.id.fullscreen_1)
        fullscreen2 = findViewById(R.id.fullscreen_2)
        fullscreen3 = findViewById(R.id.fullscreen_3)
        fullscreen4 = findViewById(R.id.fullscreen_4)

        // Empty slots
        empty2 = findViewById(R.id.empty_2)
        empty3 = findViewById(R.id.empty_3)
        empty4 = findViewById(R.id.empty_4)

        // Controls
        topControlBar = findViewById(R.id.top_control_bar)
        backButton = findViewById(R.id.back_button)
        viewNameText = findViewById(R.id.view_name_text)
        statsText = findViewById(R.id.stats_text)

        viewNameText.text = multiView?.name ?: "Quad View"
        val online = cameras.count { it.isOnline() }
        statsText.text = "${cameras.size} cameras • $online online"
    }

    private fun setupListeners() {
        backButton.setOnClickListener { finish() }

        // ✅ Fullscreen buttons for each camera
        fullscreen1.setOnClickListener { expandCamera(0) }
        fullscreen2.setOnClickListener { expandCamera(1) }
        fullscreen3.setOnClickListener { expandCamera(2) }
        fullscreen4.setOnClickListener { expandCamera(3) }
    }

    private fun loadCameras() {
        val webViews = listOf(webView1, webView2, webView3, webView4)
        val loadingOverlays = listOf(loadingOverlay1, loadingOverlay2, loadingOverlay3, loadingOverlay4)
        val infos = listOf(info1, info2, info3, info4)
        val fullscreenBtns = listOf(fullscreen1, fullscreen2, fullscreen3, fullscreen4)
        val empties = listOf(null, empty2, empty3, empty4)

        cameras.forEachIndexed { index, camera ->
            if (index < 4) {
                loadCameraInWebView(
                    index = index,
                    webView = webViews[index],
                    camera = camera,
                    loadingOverlay = loadingOverlays[index],
                    info = infos[index],
                    fullscreenBtn = fullscreenBtns[index]
                )
            }
        }

        // Hide empty slots and their fullscreen buttons
        for (i in cameras.size until 4) {
            webViews[i].visibility = View.GONE
            loadingOverlays[i].visibility = View.GONE
            infos[i].visibility = View.GONE
            fullscreenBtns[i].visibility = View.GONE
            empties[i]?.visibility = View.VISIBLE
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadCameraInWebView(
        index: Int,
        webView: WebView,
        camera: CameraInfo,
        loadingOverlay: FrameLayout,
        info: TextView,
        fullscreenBtn: ImageButton
    ) {
        info.text = "${camera.name}\n${if (camera.isOnline()) "LIVE" else "OFFLINE"}"

        if (!camera.isOnline()) {
            cancelTimeout(index)
            loadingOverlay.visibility = View.GONE
            fullscreenBtn.visibility = View.GONE
            webView.loadDataWithBaseURL(null, getOfflineHTML(camera), "text/html", "UTF-8", null)
            return
        }

        // ✅ Show professional loading overlay
        loadingOverlay.visibility = View.VISIBLE
        fullscreenBtn.visibility = View.VISIBLE

        // ✅ Start timeout for this camera
        startTimeout(index, camera, loadingOverlay, webView)

        WebViewManager.setup(webView, object : WebViewManager.WebViewCallback {
            override fun onPageLoaded() {
                Log.d(TAG, "Camera ${camera.name} - page loaded")
            }

            override fun onStreamStarted() {
                Log.d(TAG, "Camera ${camera.name} - stream started")
                runOnUiThread {
                    cancelTimeout(index)
                    loadingOverlay.visibility = View.GONE
                }
            }

            override fun onError(description: String) {
                Log.e(TAG, "Camera ${camera.name} - error: $description")
                runOnUiThread {
                    cancelTimeout(index)
                    showStreamError(index, camera, loadingOverlay, webView)
                }
            }

            override fun onTimeout() {
                Log.w(TAG, "Camera ${camera.name} - timeout")
                runOnUiThread {
                    showStreamError(index, camera, loadingOverlay, webView)
                }
            }

            override fun onBuffering() {
                runOnUiThread {
                    if (loadingOverlay.visibility != View.VISIBLE) {
                        loadingOverlay.visibility = View.VISIBLE
                    }
                }
            }

            override fun onBufferingEnd() {
                runOnUiThread {
                    if (loadingOverlay.visibility == View.VISIBLE) {
                        loadingOverlay.visibility = View.GONE
                    }
                }
            }
        })

        WebViewManager.loadHlsStream(webView, camera.getStreamURL())
    }

    // ✅ Timeout mechanism for each camera
    private fun startTimeout(index: Int, camera: CameraInfo, loadingOverlay: FrameLayout, webView: WebView) {
        cancelTimeout(index)

        val handler = Handler(Looper.getMainLooper())
        val runnable = Runnable {
            Log.w(TAG, "⏱️ Camera ${camera.name} - timeout after ${STREAM_TIMEOUT_MS}ms")
            showStreamError(index, camera, loadingOverlay, webView)
        }

        timeoutHandlers[index] = handler
        timeoutRunnables[index] = runnable
        handler.postDelayed(runnable, STREAM_TIMEOUT_MS)
    }

    // ✅ Cancel timeout for specific camera
    private fun cancelTimeout(index: Int) {
        timeoutRunnables[index]?.let { runnable ->
            timeoutHandlers[index]?.removeCallbacks(runnable)
        }
        timeoutHandlers.remove(index)
        timeoutRunnables.remove(index)
    }

    // ✅ Show error state for a specific camera
    private fun showStreamError(index: Int, camera: CameraInfo, loadingOverlay: FrameLayout, webView: WebView) {
        loadingOverlay.visibility = View.GONE

        val errorHTML = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body {
                    margin: 0;
                    padding: 0;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    height: 100vh;
                    background: #1a1a1a;
                    color: #666;
                    font-family: Arial, sans-serif;
                    text-align: center;
                }
                .offline {
                    font-size: 11px;
                }
                .icon {
                    font-size: 28px;
                    margin-bottom: 6px;
                    opacity: 0.5;
                }
                .status {
                    color: #FF6B6B;
                    margin-top: 4px;
                    font-weight: bold;
                }
            </style>
        </head>
        <body>
            <div class="offline">
                <div class="icon">⚠️</div>
                <div>${camera.name}</div>
                <div class="status">CONNECTION TIMEOUT</div>
            </div>
        </body>
        </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, errorHTML, "text/html", "UTF-8", null)
        Log.d(TAG, "Error state shown for camera: ${camera.name}")
    }

    private fun getOfflineHTML(camera: CameraInfo): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body {
                    margin: 0;
                    padding: 0;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    height: 100vh;
                    background: #1a1a1a;
                    color: #666;
                    font-family: Arial, sans-serif;
                    text-align: center;
                }
                .offline {
                    font-size: 11px;
                }
                .icon {
                    font-size: 28px;
                    margin-bottom: 6px;
                    opacity: 0.5;
                }
            </style>
        </head>
        <body>
            <div class="offline">
                <div class="icon">📹</div>
                <div>${camera.name}</div>
                <div style="color: #999; margin-top: 4px;">OFFLINE</div>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun expandCamera(index: Int) {
        if (index >= cameras.size) return

        val camera = cameras[index]
        if (!camera.isOnline()) {
            Toast.makeText(this, "${camera.name} is offline", Toast.LENGTH_SHORT).show()
            return
        }

        // ✅ Open fullscreen player for selected camera
        val intent = Intent(this, CameraStreamPlayerActivity::class.java).apply {
            putExtra("CAMERA_ID", camera.id)
            putExtra("CAMERA_NAME", camera.name)
            putExtra("CAMERA_AREA", camera.area)
            putExtra("STREAM_URL", camera.getStreamURL())
        }
        startActivity(intent)

        Log.d(TAG, "Opening fullscreen for camera: ${camera.name}")
    }

    override fun onPause() {
        super.onPause()
        webView1.onPause()
        webView2.onPause()
        webView3.onPause()
        webView4.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView1.onResume()
        webView2.onResume()
        webView3.onResume()
        webView4.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()

        // ✅ Cancel all timeouts
        for (i in 0..3) {
            cancelTimeout(i)
        }

        // Clean up all WebViews
        WebViewManager.cleanup(webView1)
        WebViewManager.cleanup(webView2)
        WebViewManager.cleanup(webView3)
        WebViewManager.cleanup(webView4)

        webView1.destroy()
        webView2.destroy()
        webView3.destroy()
        webView4.destroy()

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "✅ Quad player destroyed and cleaned up")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
    }
}