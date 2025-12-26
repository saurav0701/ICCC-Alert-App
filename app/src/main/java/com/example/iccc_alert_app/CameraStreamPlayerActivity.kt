package com.example.iccc_alert_app

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class CameraStreamPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraStreamPlayer"
        private const val CONTROLS_HIDE_DELAY = 3000L
    }

    private lateinit var webView: WebView
    private lateinit var loadingView: FrameLayout
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var errorView: LinearLayout
    private lateinit var errorTextView: TextView
    private lateinit var retryButton: Button
    private lateinit var topControlBar: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var fullscreenButton: ImageButton
    private lateinit var cameraInfoText: TextView

    private var cameraId: String = ""
    private var cameraName: String = ""
    private var cameraArea: String = ""
    private var streamUrl: String = ""
    private var isFullscreen = false
    private var areControlsVisible = true

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

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

        Log.d(TAG, "🎥 Opening stream: $streamUrl")

        initializeViews()
        setupListeners()

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
        loadingSpinner = findViewById(R.id.loading_spinner)
        loadingText = findViewById(R.id.loading_text)
        errorView = findViewById(R.id.error_view)
        errorTextView = findViewById(R.id.error_text)
        retryButton = findViewById(R.id.retry_button)
        topControlBar = findViewById(R.id.top_control_bar)
        backButton = findViewById(R.id.back_button)
        fullscreenButton = findViewById(R.id.fullscreen_button)
        cameraInfoText = findViewById(R.id.camera_info_text)

        cameraInfoText.text = "$cameraName - $cameraArea"
    }

    private fun setupListeners() {
        backButton.setOnClickListener { finish() }
        fullscreenButton.setOnClickListener { toggleFullscreen() }
        retryButton.setOnClickListener {
            cleanupWebView()
            loadStream()
        }

        webView.setOnClickListener {
            if (areControlsVisible) {
                hideControls()
            } else {
                showControls()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE // ✅ CRITICAL: Don't cache
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "✅ Stream page loaded")

                hideControlsHandler.postDelayed({
                    showPlayer()
                    scheduleControlsHide()
                }, 1500)
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "❌ WebView error: $description")
                showError("Connection failed: $description")
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    private fun loadStream() {
        showLoading()
        loadingText.text = "Connecting to camera..."

        // ✅ Generate unique session ID to prevent caching issues
        val sessionId = System.currentTimeMillis()

        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <style>
        * { 
            margin: 0; 
            padding: 0; 
            box-sizing: border-box;
        }
        body { 
            background: #000; 
            overflow: hidden;
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100vh;
            width: 100vw;
        }
        #video-container {
            position: relative;
            width: 100%;
            height: 100%;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        video { 
            max-width: 100%;
            max-height: 100%;
            width: auto;
            height: auto;
            object-fit: contain;
        }
        .buffer-indicator {
            position: absolute;
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%);
            color: white;
            background: rgba(0,0,0,0.7);
            padding: 10px 20px;
            border-radius: 5px;
            display: none;
            font-family: Arial, sans-serif;
        }
    </style>
    <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
</head>
<body>
    <div id="video-container">
        <video id="video" controls autoplay playsinline webkit-playsinline></video>
        <div class="buffer-indicator" id="buffer">Buffering...</div>
    </div>

    <script>
        const video = document.getElementById('video');
        const buffer = document.getElementById('buffer');
        const streamUrl = '${streamUrl}';
        let hls = null;

        // ✅ CRITICAL: Session ID to bust cache
        const sessionId = ${sessionId};
        console.log('Loading stream session:', sessionId);

        video.addEventListener('waiting', () => {
            buffer.style.display = 'block';
        });

        video.addEventListener('playing', () => {
            buffer.style.display = 'none';
        });

        video.addEventListener('canplay', () => {
            buffer.style.display = 'none';
        });

        // ✅ CRITICAL: Cleanup function
        function cleanup() {
            if (hls) {
                try {
                    hls.destroy();
                    hls = null;
                    console.log('HLS instance destroyed');
                } catch (e) {
                    console.error('Error destroying HLS:', e);
                }
            }
            if (video) {
                video.pause();
                video.removeAttribute('src');
                video.load();
            }
        }

        // ✅ Cleanup on page unload
        window.addEventListener('beforeunload', cleanup);
        window.addEventListener('pagehide', cleanup);

        if (Hls.isSupported()) {
            hls = new Hls({
                debug: false,
                enableWorker: true,
                lowLatencyMode: true,
                backBufferLength: 90,
                maxBufferLength: 30,
                maxMaxBufferLength: 60,
                maxBufferSize: 60 * 1000 * 1000,
                maxBufferHole: 0.5,
                highBufferWatchdogPeriod: 2,
                nudgeMaxRetry: 3,
                maxFragLookUpTolerance: 0.25,
                liveSyncDurationCount: 3,
                liveMaxLatencyDurationCount: 10,
                liveDurationInfinity: true,
                manifestLoadingTimeOut: 10000,
                manifestLoadingMaxRetry: 4,
                manifestLoadingRetryDelay: 1000,
                levelLoadingTimeOut: 10000,
                levelLoadingMaxRetry: 4,
                fragLoadingTimeOut: 20000,
                fragLoadingMaxRetry: 6,
                startFragPrefetch: true,
                testBandwidth: true
            });

            hls.loadSource(streamUrl);
            hls.attachMedia(video);

            hls.on(Hls.Events.MANIFEST_PARSED, function() {
                console.log('Stream ready');
                video.play().catch(e => {
                    console.log('Autoplay blocked, user interaction required');
                });
            });

            hls.on(Hls.Events.ERROR, function(event, data) {
                console.error('HLS Error:', data);
                if (data.fatal) {
                    switch(data.type) {
                        case Hls.ErrorTypes.NETWORK_ERROR:
                            console.log('Network error, trying to recover...');
                            setTimeout(() => hls.startLoad(), 1000);
                            break;
                        case Hls.ErrorTypes.MEDIA_ERROR:
                            console.log('Media error, trying to recover...');
                            hls.recoverMediaError();
                            break;
                        default:
                            console.log('Fatal error, destroying player');
                            cleanup();
                            break;
                    }
                }
            });

            let stallTimeout;
            video.addEventListener('waiting', () => {
                stallTimeout = setTimeout(() => {
                    console.log('Stream stalled, recovering...');
                    hls.startLoad();
                    video.play();
                }, 5000);
            });

            video.addEventListener('playing', () => {
                clearTimeout(stallTimeout);
            });

        } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
            video.src = streamUrl;
            video.play().catch(e => console.log('Play error:', e));
        } else {
            console.error('HLS not supported in this browser');
        }
    </script>
</body>
</html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        topControlBar.visibility = View.VISIBLE
    }

    private fun showPlayer() {
        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        loadingView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        webView.visibility = View.GONE
        errorTextView.text = message
        topControlBar.visibility = View.VISIBLE
        Log.e(TAG, "Error: $message")
    }

    private fun showControls() {
        areControlsVisible = true
        topControlBar.animate()
            .alpha(1f)
            .setDuration(200)
            .withStartAction {
                topControlBar.visibility = View.VISIBLE
            }
            .start()
        scheduleControlsHide()
    }

    private fun hideControls() {
        areControlsVisible = false
        topControlBar.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                topControlBar.visibility = View.GONE
            }
            .start()
        cancelControlsHide()
    }

    private fun scheduleControlsHide() {
        cancelControlsHide()
        if (!isFullscreen) return
        hideControlsHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY)
    }

    private fun cancelControlsHide() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }

    private fun toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            enterFullscreen()
        }
    }

    private fun enterFullscreen() {
        isFullscreen = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, webView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        fullscreenButton.setImageResource(R.drawable.ic_fullscreen_exit)
        scheduleControlsHide()
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

    // ✅ CRITICAL: Proper cleanup
    private fun cleanupWebView() {
        try {
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.clearCache(true)
            webView.onPause()
            Log.d(TAG, "WebView cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning WebView: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        cancelControlsHide()
        Log.d(TAG, "Activity paused - stream paused")
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (isFullscreen) {
            scheduleControlsHide()
        }
        Log.d(TAG, "Activity resumed - stream resumed")
    }

    override fun onStop() {
        super.onStop()
        // ✅ CRITICAL: Clean up when leaving activity
        cleanupWebView()
        Log.d(TAG, "Activity stopped - resources cleaned")
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelControlsHide()
        cleanupWebView()
        webView.destroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "Activity destroyed")
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