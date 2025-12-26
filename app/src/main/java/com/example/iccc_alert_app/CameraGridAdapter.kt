package com.example.iccc_alert_app

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

class CameraGridAdapter(
    private val context: Context,
    private val onCameraClick: (CameraInfo) -> Unit
) : RecyclerView.Adapter<CameraGridAdapter.GridViewHolder>() {

    companion object {
        private const val TAG = "CameraGridAdapter"
    }

    private var cameras = listOf<CameraInfo>()
    private val activeWebViews = mutableMapOf<Int, WebView>() // position -> WebView
    private var isPaused = false

    class GridViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.grid_card)
        val webView: WebView = view.findViewById(R.id.grid_webview)
        val loadingContainer: FrameLayout = view.findViewById(R.id.grid_loading_container)
        val loadingView: ProgressBar = view.findViewById(R.id.grid_loading)
        val cameraLabel: TextView = view.findViewById(R.id.grid_camera_label)
        val cameraArea: TextView = view.findViewById(R.id.grid_camera_area)
        val overlayView: View = view.findViewById(R.id.grid_overlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_camera_grid, parent, false)
        return GridViewHolder(view)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
        val camera = cameras[position]

        holder.cameraLabel.text = camera.name
        holder.cameraArea.text = camera.area

        // ✅ CRITICAL: Clean up previous content and remove from tracking
        activeWebViews.remove(position)?.let { oldWebView ->
            cleanupWebView(oldWebView)
        }
        cleanupWebView(holder.webView)

        // Setup WebView for this grid item
        holder.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
        }

        holder.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                holder.loadingContainer.visibility = View.GONE
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "Grid WebView error for ${camera.name}: $description")
                holder.loadingContainer.visibility = View.GONE
            }
        }

        holder.webView.webChromeClient = WebChromeClient()

        // Track active WebView
        activeWebViews[position] = holder.webView

        // Load stream if not paused
        if (!isPaused) {
            loadStreamInGrid(holder.webView, camera.getStreamURL(), camera.id)
        }

        // Click to open fullscreen
        holder.overlayView.setOnClickListener {
            // ✅ Pause ALL WebViews before opening fullscreen
            pauseAllStreams()
            onCameraClick(camera)
        }
    }

    private fun loadStreamInGrid(webView: WebView, streamUrl: String, cameraId: String) {
        // ✅ Generate unique session ID per load
        val sessionId = System.currentTimeMillis()

        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        * { margin: 0; padding: 0; }
        body { 
            background: #000; 
            overflow: hidden;
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100vh;
        }
        video { 
            width: 100%; 
            height: 100%;
            object-fit: cover;
        }
    </style>
    <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
</head>
<body>
    <video id="video" autoplay muted playsinline webkit-playsinline></video>

    <script>
        const video = document.getElementById('video');
        const streamUrl = '${streamUrl}';
        const sessionId = ${sessionId};
        let hls = null;

        console.log('Grid loading:', '${cameraId}', 'session:', sessionId);

        // ✅ Cleanup function
        function cleanup() {
            if (hls) {
                try {
                    hls.destroy();
                    hls = null;
                    console.log('Grid HLS destroyed:', '${cameraId}');
                } catch (e) {
                    console.error('Grid cleanup error:', e);
                }
            }
            if (video) {
                video.pause();
                video.removeAttribute('src');
                video.load();
            }
        }

        window.addEventListener('beforeunload', cleanup);
        window.addEventListener('pagehide', cleanup);

        if (Hls.isSupported()) {
            hls = new Hls({
                debug: false,
                enableWorker: false,
                lowLatencyMode: false,
                maxBufferLength: 10,
                maxMaxBufferLength: 15,
                maxBufferSize: 10 * 1000 * 1000,
                liveSyncDurationCount: 2,
                fragLoadingMaxRetry: 2,
                manifestLoadingMaxRetry: 2,
                manifestLoadingTimeOut: 5000,
                fragLoadingTimeOut: 10000
            });

            hls.loadSource(streamUrl);
            hls.attachMedia(video);

            hls.on(Hls.Events.MANIFEST_PARSED, function() {
                video.play().catch(e => console.log('Grid play error:', e));
            });

            hls.on(Hls.Events.ERROR, function(event, data) {
                if (data.fatal) {
                    console.error('Grid fatal error:', '${cameraId}');
                    setTimeout(() => {
                        if (hls) hls.startLoad();
                    }, 2000);
                }
            });

        } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
            video.src = streamUrl;
            video.play();
        }
    </script>
</body>
</html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    override fun getItemCount() = cameras.size

    fun updateCameras(newCameras: List<CameraInfo>) {
        // ✅ Clean up ALL old WebViews before updating
        cleanupAll()

        cameras = newCameras
        notifyDataSetChanged()

        Log.d(TAG, "Updated with ${cameras.size} cameras")
    }

    override fun onViewRecycled(holder: GridViewHolder) {
        super.onViewRecycled(holder)

        // ✅ CRITICAL: Proper cleanup when view is recycled
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            activeWebViews.remove(position)
        }
        cleanupWebView(holder.webView)
        holder.loadingContainer.visibility = View.VISIBLE
    }

    override fun onViewDetachedFromWindow(holder: GridViewHolder) {
        super.onViewDetachedFromWindow(holder)
        // ✅ Pause when view leaves screen
        holder.webView.onPause()
    }

    override fun onViewAttachedToWindow(holder: GridViewHolder) {
        super.onViewAttachedToWindow(holder)
        // ✅ Resume only if adapter is not paused
        if (!isPaused) {
            holder.webView.onResume()
        }
    }

    /**
     * Clean up a single WebView
     */
    private fun cleanupWebView(webView: WebView) {
        try {
            webView.onPause()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.clearCache(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning WebView: ${e.message}")
        }
    }

    /**
     * Clean up all WebViews
     */
    private fun cleanupAll() {
        Log.d(TAG, "Cleaning up ${activeWebViews.size} active WebViews")
        activeWebViews.values.forEach { webView ->
            try {
                cleanupWebView(webView)
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning WebView: ${e.message}")
            }
        }
        activeWebViews.clear()
    }

    /**
     * Pause all active streams (call when leaving activity or opening fullscreen)
     */
    fun pauseAllStreams() {
        isPaused = true
        Log.d(TAG, "Pausing ${activeWebViews.size} active streams")
        activeWebViews.values.forEach { webView ->
            try {
                webView.onPause()
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing stream: ${e.message}")
            }
        }
    }

    /**
     * Resume all streams (call when returning to activity)
     */
    fun resumeAllStreams() {
        isPaused = false
        Log.d(TAG, "Resuming ${activeWebViews.size} active streams")
        activeWebViews.values.forEach { webView ->
            try {
                webView.onResume()
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming stream: ${e.message}")
            }
        }
    }

    /**
     * Clean up all WebViews (call on destroy)
     */
    fun cleanup() {
        Log.d(TAG, "Final cleanup of ${activeWebViews.size} WebViews")
        cleanupAll()
    }
}