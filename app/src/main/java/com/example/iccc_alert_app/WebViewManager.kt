package com.example.iccc_alert_app

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

object WebViewManager {

    private const val TAG = "WebViewManager"
    // Must exceed hls.js's own manifest timeout and retries, otherwise the
    // app reports "camera offline" while the player is still connecting.
    private const val STREAM_TIMEOUT_MS = 25000L

    interface WebViewCallback {
        fun onPageLoaded()
        fun onStreamStarted()
        fun onError(description: String)
        fun onTimeout()
        fun onBuffering()
        fun onBufferingEnd()
    }

    private var timeoutHandler: Handler? = null
    private var timeoutRunnable: Runnable? = null
    private var streamStarted = false

    @SuppressLint("SetJavaScriptEnabled")
    fun setup(webView: WebView, callback: WebViewCallback) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        webView.setOnLongClickListener { true }
        webView.isLongClickable = false

        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onStreamStarted() {
                Log.d(TAG, "✅ Stream started")
                streamStarted = true
                cancelTimeout()
                Handler(Looper.getMainLooper()).post { callback.onStreamStarted() }
            }

            @android.webkit.JavascriptInterface
            fun onStreamError(error: String) {
                Log.e(TAG, "❌ Stream error: $error")
                cancelTimeout()
                Handler(Looper.getMainLooper()).post { callback.onError(error) }
            }

            @android.webkit.JavascriptInterface
            fun onBuffering() {
                Log.d(TAG, "🔄 Buffering started")
                Handler(Looper.getMainLooper()).post { callback.onBuffering() }
            }

            @android.webkit.JavascriptInterface
            fun onBufferingEnd() {
                Log.d(TAG, "✅ Buffering ended")
                Handler(Looper.getMainLooper()).post { callback.onBufferingEnd() }
            }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                callback.onPageLoaded()
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "❌ WebView error: $description")
                cancelTimeout()
                callback.onError(description ?: "Unknown error")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                // hls.js reports codec and network problems here. Without this
                // the page fails silently and the app only ever shows
                // "buffering", which makes stream faults undiagnosable.
                Log.d(TAG, "JS[${msg.messageLevel()}] ${msg.message()}")
                return true
            }
        }
    }

    fun loadHlsStream(webView: WebView, streamUrl: String) {
        streamStarted = false
        startTimeout()

        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        html, body { 
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            background: #000;
        }
        body { 
            background: #000; 
            overflow: hidden;
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100vh;
            width: 100vw;
            position: relative;
        }
        #video-container {
            position: absolute;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: #000;
            z-index: 1;
        }
        video { 
            width: 100%;
            height: 100%;
            object-fit: contain;
            background: #000;
            position: absolute;
            top: 0;
            left: 0;
        }
        /* ✅ COMPLETE VIDEO CONTROL REMOVAL */
        video::-webkit-media-controls { display: none !important; }
        video::-webkit-media-controls-panel { display: none !important; }
        video::-webkit-media-controls-play-button { display: none !important; }
        video::-webkit-media-controls-volume-slider { display: none !important; }
        video::-webkit-media-controls-mute-button { display: none !important; }
        video::-webkit-media-controls-toggle-closed-captions-button { display: none !important; }
        video::-webkit-media-controls-fullscreen-button { display: none !important; }
        video::-webkit-media-controls-timeline { display: none !important; }
        video::-webkit-media-controls-time-display { display: none !important; }
        video::-webkit-media-controls-current-time-display { display: none !important; }
        video::-webkit-media-controls-download-button { display: none !important; }
        video::-webkit-media-controls-overlay-play-button { display: none !important; }
        video::-webkit-media-controls-overlay { display: none !important; }
        
        /* Block all video interactions */
        video { pointer-events: none !important; }
        
        #loading-overlay {
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: rgba(0, 0, 0, 0.7);
            display: flex;
            align-items: center;
            justify-content: center;
            z-index: 9999;
            opacity: 0;
            pointer-events: none;
            transition: opacity 0.3s ease;
        }
        #loading-overlay.active {
            opacity: 1;
            pointer-events: auto;
        }
        .spinner {
            width: 50px;
            height: 50px;
            border: 4px solid rgba(255, 255, 255, 0.3);
            border-top: 4px solid #FFFFFF;
            border-radius: 50%;
            animation: spin 1s linear infinite;
        }
        @keyframes spin {
            to { transform: rotate(360deg); }
        }
    </style>
    <!-- Bundled in the APK. Previously fetched from jsdelivr on every open,
         which made a stream fail to start whenever the site network was weak -
         exactly the areas where streams were reported as flaky. It was also
         pinned to @latest, so an upstream release could break every stream in
         the field without an app update. -->
    <script src="hls.min.js"></script>
</head>
<body>
    <div id="video-container">
        <video id="video" playsinline webkit-playsinline controlsList="nodownload nofullscreen noremoteplayback"></video>
    </div>
    <div id="loading-overlay">
        <div class="spinner"></div>
    </div>

    <script>
        const video = document.getElementById('video');
        const loadingOverlay = document.getElementById('loading-overlay');
        let hls = null;
        let scale = 1;
        let isPlaying = true;
        let isRecovering = false;

        // ✅ AGGRESSIVE control removal
        video.removeAttribute('controls');
        Object.defineProperty(video, 'controls', { get: () => false, set: () => {} });
        video.style.pointerEvents = 'none';
        video.style.cursor = 'default';
        video.oncontextmenu = (e) => e.preventDefault();

        function hideLoading() {
            loadingOverlay.classList.remove('active');
        }

        function showLoading() {
            loadingOverlay.classList.add('active');
        }

        function notifyStreamStarted() {
            try {
                hideLoading();
                if (typeof Android !== 'undefined') Android.onStreamStarted();
            } catch(e) {}
        }

        function notifyStreamError(error) {
            try {
                if (typeof Android !== 'undefined') Android.onStreamError(error);
            } catch(e) {}
        }

        window.setZoom = function(zoomLevel) {
            scale = zoomLevel;
            video.style.transform = 'scale(' + scale + ')';
        };

        window.resetZoom = function() {
            scale = 1;
            video.style.transform = 'scale(1)';
        };

        window.pauseStream = function() {
            try {
                video.pause();
                isPlaying = false;
                return true;
            } catch(e) { return false; }
        };

        window.playStream = function() {
            try {
                video.play().catch(e => console.error('Play error:', e));
                isPlaying = true;
                return true;
            } catch(e) { return false; }
        };

        window.getPlaybackState = function() {
            return isPlaying ? 'playing' : 'paused';
        };

        var isBuffering = false;

        function showBuffering() {
            if (isBuffering) return;
            isBuffering = true;
            try {
                showLoading();
                if (typeof Android !== 'undefined') Android.onBuffering();
            } catch(e) {}
        }

        function hideBuffering() {
            if (!isBuffering) return;
            isBuffering = false;
            try {
                hideLoading();
                if (typeof Android !== 'undefined') Android.onBufferingEnd();
            } catch(e) {}
        }

        function recoverPlayback() {
            if (isRecovering) return;
            isRecovering = true;
            showBuffering();
            
            setTimeout(() => {
                if (hls && isPlaying) {
                    hls.recoverMediaError();
                    setTimeout(() => {
                        video.play().then(() => {
                            isRecovering = false;
                            hideBuffering();
                        }).catch(e => {
                            isRecovering = false;
                            hideBuffering();
                            notifyStreamError('Recovery failed');
                        });
                    }, 200);
                } else {
                    isRecovering = false;
                    hideBuffering();
                }
            }, 100);
        }

        var networkRetries = 0;
        var MAX_NETWORK_RETRIES = 5;

        // These streams are H.265. Hardware HEVC decoding does not imply the
        // WebView will accept HEVC through MediaSource, which is what hls.js
        // uses - and if MSE refuses it, playback stalls in buffering forever
        // rather than reporting an error.
        (function probeCodecs() {
            try {
                var hevc = 'video/mp4; codecs="hvc1.1.6.L150.0"';
                var h264 = 'video/mp4; codecs="avc1.42E01E"';
                console.log('CODEC-PROBE hevc_mse=' + (window.MediaSource ? MediaSource.isTypeSupported(hevc) : 'no-MSE') +
                            ' h264_mse=' + (window.MediaSource ? MediaSource.isTypeSupported(h264) : 'no-MSE') +
                            ' hevc_video=' + document.createElement('video').canPlayType(hevc) +
                            ' hlsSupported=' + (typeof Hls !== 'undefined' ? Hls.isSupported() : 'no-hls'));
            } catch (e) {
                console.log('CODEC-PROBE failed: ' + e);
            }
        })();

        function initializeStream() {
            if (Hls.isSupported()) {
                hls = new Hls({ 
                    lowLatencyMode: true,
                    enableWorker: true,
                    // A slightly deeper buffer rides out the jitter on site
                    // links; 10s was thin enough to stall regularly.
                    maxBufferLength: 20,
                    maxBufferSize: 30 * 1000 * 1000,
                    autoStartLoad: true,
                    startLevel: -1,
                    capLevelToPlayerSize: false,
                    maxLoadingDelay: 4,
                    manifestLoadingTimeOut: 15000,
                    manifestLoadingMaxRetry: 4,
                    manifestLoadingRetryDelay: 1000,
                    levelLoadingTimeOut: 15000,
                    levelLoadingMaxRetry: 4,
                    fragLoadingTimeOut: 20000,
                    fragLoadingMaxRetry: 6
                });
                
                hls.loadSource('${streamUrl}');
                hls.attachMedia(video);
                
                hls.on(Hls.Events.FRAG_BUFFERED, function() {
                    // Fires once per segment, so only act when something
                    // actually changed - otherwise this crosses the JS bridge
                    // every second on a healthy stream.
                    if (networkRetries !== 0) {
                        networkRetries = 0;
                    }
                    if (isBuffering) {
                        hideBuffering();
                    }
                });

                hls.on(Hls.Events.MANIFEST_PARSED, function() {
                    video.muted = false;
                    video.play().then(() => {
                        isPlaying = true;
                        notifyStreamStarted();
                    }).catch(e => {
                        video.muted = true;
                        video.play().then(() => {
                            isPlaying = true;
                            notifyStreamStarted();
                        }).catch(err => {
                            notifyStreamError('Failed to start playback');
                        });
                    });
                });
                
                hls.on(Hls.Events.ERROR, function(event, data) {
                    if (data.fatal) {
                        switch(data.type) {
                            case Hls.ErrorTypes.NETWORK_ERROR:
                                // An HTTP status from the media server is an
                                // answer, not a blip: the camera is not
                                // publishing. Retrying only spins for 15s
                                // before failing, so say so immediately.
                                var status = data.response && data.response.code;
                                if (status && status >= 400) {
                                    notifyStreamError(
                                        status === 404
                                            ? 'This camera is not streaming right now'
                                            : 'Camera stream unavailable (' + status + ')'
                                    );
                                    break;
                                }
                                // Recoverable: retry with backoff before giving
                                // up. Site links drop briefly and come back.
                                if (networkRetries < MAX_NETWORK_RETRIES) {
                                    networkRetries++;
                                    showBuffering();
                                    setTimeout(function() {
                                        try { hls.startLoad(); } catch (e) {}
                                    }, 1000 * networkRetries);
                                } else {
                                    notifyStreamError('Network error');
                                }
                                break;
                            case Hls.ErrorTypes.MEDIA_ERROR:
                                showBuffering();
                                hls.recoverMediaError();
                                setTimeout(() => hideBuffering(), 2000);
                                break;
                            default:
                                notifyStreamError('Stream error');
                                break;
                        }
                    } else if (data.details === Hls.ErrorDetails.BUFFER_STALLED_ERROR) {
                        recoverPlayback();
                    } else if (data.details === Hls.ErrorDetails.FRAG_LOAD_ERROR || 
                               data.details === Hls.ErrorDetails.FRAG_LOAD_TIMEOUT) {
                        showBuffering();
                        setTimeout(() => hideBuffering(), 2000);
                    }
                });

                hls.on(Hls.Events.MANIFEST_LOAD_ERROR, function() {
                    notifyStreamError('Failed to connect');
                });
                
            } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
                video.src = '${streamUrl}';
                video.addEventListener('loadedmetadata', function() {
                    video.muted = false;
                    video.play().catch(e => notifyStreamError('Failed to play'));
                });
            }
        }

        video.addEventListener('pause', function() {
            if (!video.ended && isPlaying && !isRecovering) {
                showBuffering();
                setTimeout(() => {
                    video.play().catch(e => hideBuffering());
                }, 100);
            }
        });

        video.addEventListener('playing', function() {
            isRecovering = false;
            hideBuffering();
        });

        video.addEventListener('waiting', function() { showBuffering(); });
        video.addEventListener('canplay', function() { hideBuffering(); });
        video.addEventListener('stalled', function() {
            showBuffering();
            setTimeout(() => {
                if (video.readyState < 3 && isPlaying) recoverPlayback();
            }, 3000);
        });

        video.addEventListener('error', function(e) {
            showBuffering();
            setTimeout(() => {
                if (isPlaying && !isRecovering) {
                    recoverPlayback();
                } else {
                    hideBuffering();
                }
            }, 1000);
        });

        // ✅ Prevent native media UI from showing
        document.addEventListener('fullscreenchange', (e) => {
            document.exitFullscreen().catch(() => {});
        });
        
        // Block any attempt to show native controls
        setInterval(() => {
            const video = document.querySelector('video');
            if (video && video.getAttribute('controls')) {
                video.removeAttribute('controls');
            }
        }, 100);

        initializeStream();

        // Heartbeat
        window.isPipMode = false;
        setInterval(function() {
            if (window.isPipMode) return;
            if (isPlaying && video.paused && !isRecovering) {
                video.play().catch(e => {});
            }
        }, 5000);
    </script>
</body>
</html>
        """.trimIndent()

        // Base URL must be the asset folder so <script src="hls.min.js"> resolves.
        webView.loadDataWithBaseURL(
            "file:///android_asset/", html, "text/html", "UTF-8", null
        )
    }

    private fun startTimeout() {
        cancelTimeout()
        timeoutHandler = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            if (!streamStarted) {
                Log.w(TAG, "⏱️ Stream timeout")
            }
        }
        timeoutHandler?.postDelayed(timeoutRunnable!!, STREAM_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { timeoutHandler?.removeCallbacks(it) }
        timeoutHandler = null
        timeoutRunnable = null
    }

    fun cleanup(webView: WebView) {
        try {
            cancelTimeout()
            streamStarted = false
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.clearCache(true)
            webView.onPause()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning WebView: ${e.message}")
        }
    }
}