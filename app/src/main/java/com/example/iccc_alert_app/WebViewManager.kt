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
    private const val STREAM_TIMEOUT_MS = 10000L // 10 seconds timeout

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
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true

            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }

        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        // Add JavaScript interface to detect stream status
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onStreamStarted() {
                Log.d(TAG, "✅ Stream started - detected from JavaScript")
                streamStarted = true
                cancelTimeout()
                Handler(Looper.getMainLooper()).post {
                    callback.onStreamStarted()
                }
            }

            @android.webkit.JavascriptInterface
            fun onStreamError(error: String) {
                Log.e(TAG, "❌ Stream error from JavaScript: $error")
                cancelTimeout()
                Handler(Looper.getMainLooper()).post {
                    callback.onError(error)
                }
            }

            @android.webkit.JavascriptInterface
            fun onBuffering() {
                Log.d(TAG, "🔄 Buffering started")
                Handler(Looper.getMainLooper()).post {
                    callback.onBuffering()
                }
            }

            @android.webkit.JavascriptInterface
            fun onBufferingEnd() {
                Log.d(TAG, "✅ Buffering ended")
                Handler(Looper.getMainLooper()).post {
                    callback.onBufferingEnd()
                }
            }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                callback.onPageLoaded()
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
                cancelTimeout()
                callback.onError(description ?: "Unknown error")
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    fun loadHlsStream(webView: WebView, streamUrl: String) {
        streamStarted = false
        startTimeout()

        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
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
            position: relative;
            width: 100%;
            height: 100%;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        video { 
            width: 100%;
            height: 100%;
            object-fit: contain;
            background: #000;
        }
    </style>
    <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
</head>
<body>
    <div id="video-container">
        <video id="video" playsinline webkit-playsinline></video>
    </div>

    <script>
        const video = document.getElementById('video');
        let hls = null;
        let scale = 1;
        let isPlaying = true;
        let isRecovering = false;
        let streamLoadTimeout = null;

        // Notify Android of stream status
        function notifyStreamStarted() {
            try {
                if (typeof Android !== 'undefined') {
                    Android.onStreamStarted();
                }
            } catch(e) {
                console.error('Failed to notify Android:', e);
            }
        }

        function notifyStreamError(error) {
            try {
                if (typeof Android !== 'undefined') {
                    Android.onStreamError(error);
                }
            } catch(e) {
                console.error('Failed to notify Android:', e);
            }
        }

        // Zoom functions
        window.setZoom = function(zoomLevel) {
            scale = zoomLevel;
            video.style.transform = 'scale(' + scale + ')';
            console.log('Zoom set to: ' + scale);
        };

        window.resetZoom = function() {
            scale = 1;
            video.style.transform = 'scale(1)';
            console.log('Zoom reset');
        };

        // Playback control functions
        window.pauseStream = function() {
            try {
                video.pause();
                isPlaying = false;
                console.log('Stream paused');
                return true;
            } catch(e) {
                console.error('Pause error:', e);
                return false;
            }
        };

        window.playStream = function() {
            try {
                video.play().then(() => {
                    isPlaying = true;
                    console.log('Stream playing');
                }).catch(e => {
                    console.error('Play error:', e);
                });
                return true;
            } catch(e) {
                console.error('Play error:', e);
                return false;
            }
        };

        window.getPlaybackState = function() {
            return isPlaying ? 'playing' : 'paused';
        };

        // Show buffering indicator
        function showBuffering() {
            try {
                if (typeof Android !== 'undefined') {
                    Android.onBuffering();
                }
            } catch(e) {
                console.error('Failed to notify buffering:', e);
            }
        }

        function hideBuffering() {
            try {
                if (typeof Android !== 'undefined') {
                    Android.onBufferingEnd();
                }
            } catch(e) {
                console.error('Failed to notify buffering end:', e);
            }
        }

        // Auto-recovery function
        function recoverPlayback() {
            if (isRecovering) return;
            isRecovering = true;
            showBuffering();
            
            console.log('Attempting playback recovery...');
            
            setTimeout(() => {
                if (hls && isPlaying) {
                    console.log('Recovering HLS stream...');
                    hls.recoverMediaError();
                    
                    setTimeout(() => {
                        video.play().then(() => {
                            console.log('Recovery successful');
                            isRecovering = false;
                            hideBuffering();
                        }).catch(e => {
                            console.error('Recovery play failed:', e);
                            isRecovering = false;
                            hideBuffering();
                            notifyStreamError('Recovery failed: ' + e.message);
                        });
                    }, 200);
                } else {
                    isRecovering = false;
                    hideBuffering();
                }
            }, 100);
        }

        // Initialize HLS
        function initializeStream() {
            if (Hls.isSupported()) {
                hls = new Hls({ 
                    lowLatencyMode: true,
                    enableWorker: true,
                    maxBufferLength: 10,
                    maxBufferSize: 30 * 1000 * 1000,
                    autoStartLoad: true,
                    startLevel: -1,
                    capLevelToPlayerSize: false,
                    maxLoadingDelay: 4,
                    manifestLoadingTimeOut: 10000,
                    manifestLoadingMaxRetry: 2,
                    levelLoadingTimeOut: 10000,
                    fragLoadingTimeOut: 20000
                });
                
                hls.loadSource('${streamUrl}');
                hls.attachMedia(video);
                
                hls.on(Hls.Events.MANIFEST_PARSED, function() {
                    console.log('✅ HLS manifest parsed');
                    video.muted = false;
                    video.play().then(() => {
                        isPlaying = true;
                        console.log('✅ Auto-play successful');
                        notifyStreamStarted();
                    }).catch(e => {
                        console.log('Auto-play prevented, trying muted:', e.message);
                        video.muted = true;
                        video.play().then(() => {
                            isPlaying = true;
                            console.log('✅ Playing muted');
                            notifyStreamStarted();
                        }).catch(err => {
                            console.error('❌ Failed to start playback:', err);
                            notifyStreamError('Failed to start playback: ' + err.message);
                        });
                    });
                });
                
                hls.on(Hls.Events.ERROR, function(event, data) {
                    console.error('❌ HLS error:', data.type, data.details, data);
                    
                    if (data.fatal) {
                        switch(data.type) {
                            case Hls.ErrorTypes.NETWORK_ERROR:
                                console.error('❌ Fatal network error');
                                notifyStreamError('Network error - camera may be offline');
                                break;
                            case Hls.ErrorTypes.MEDIA_ERROR:
                                console.log('Media error, trying to recover...');
                                showBuffering();
                                hls.recoverMediaError();
                                setTimeout(() => {
                                    hideBuffering();
                                }, 2000);
                                break;
                            default:
                                console.error('❌ Fatal error:', data.details);
                                notifyStreamError('Stream error: ' + data.details);
                                break;
                        }
                    } else if (data.details === Hls.ErrorDetails.BUFFER_STALLED_ERROR) {
                        console.log('Buffer stalled, attempting recovery...');
                        recoverPlayback();
                    } else if (data.details === Hls.ErrorDetails.FRAG_LOAD_ERROR || 
                               data.details === Hls.ErrorDetails.FRAG_LOAD_TIMEOUT) {
                        console.log('Fragment load issue, showing buffering...');
                        showBuffering();
                        setTimeout(() => {
                            hideBuffering();
                        }, 2000);
                    }
                });
                
                hls.on(Hls.Events.MANIFEST_LOADING, function() {
                    console.log('📡 Loading manifest...');
                });

                hls.on(Hls.Events.MANIFEST_LOAD_ERROR, function() {
                    console.error('❌ Failed to load manifest');
                    notifyStreamError('Failed to connect to camera');
                });
                
            } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
                video.src = '${streamUrl}';
                video.addEventListener('loadedmetadata', function() {
                    video.muted = false;
                    video.play().then(() => {
                        isPlaying = true;
                        notifyStreamStarted();
                    }).catch(e => {
                        console.error('Play error:', e);
                        notifyStreamError('Failed to play: ' + e.message);
                    });
                });
            }
        }

        // Monitor video events
        video.addEventListener('pause', function() {
            if (!video.ended && document.visibilityState === 'visible' && isPlaying && !isRecovering) {
                console.log('Video paused unexpectedly, resuming...');
                showBuffering();
                setTimeout(function() {
                    video.play().then(() => {
                        hideBuffering();
                    }).catch(e => {
                        console.log('Resume play error:', e);
                        hideBuffering();
                    });
                }, 100);
            }
        });

        video.addEventListener('playing', function() {
            console.log('✅ Video is playing');
            isRecovering = false;
            hideBuffering();
        });

        video.addEventListener('waiting', function() {
            console.log('🔄 Video buffering...');
            showBuffering();
        });

        video.addEventListener('canplay', function() {
            console.log('✅ Video can play');
            hideBuffering();
        });

        video.addEventListener('stalled', function() {
            console.log('⚠️ Video stalled');
            showBuffering();
            setTimeout(() => {
                if (video.readyState < 3 && isPlaying) {
                    recoverPlayback();
                }
            }, 3000);
        });

        video.addEventListener('error', function(e) {
            console.error('❌ Video element error:', e);
            showBuffering();
            setTimeout(() => {
                if (isPlaying && !isRecovering) {
                    recoverPlayback();
                } else {
                    hideBuffering();
                }
            }, 1000);
        });

        // Start stream
        initializeStream();

        // Heartbeat
        window.isPipMode = false;
        setInterval(function() {
            if (window.isPipMode) {
                return;
            }
            if (isPlaying && video.paused && !isRecovering) {
                console.log('Heartbeat: Recovering paused stream...');
                video.play().catch(e => console.log('Heartbeat play error:', e));
            }
        }, 5000);
    </script>
</body>
</html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun startTimeout() {
        cancelTimeout()
        timeoutHandler = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            if (!streamStarted) {
                Log.w(TAG, "⏱️ Stream timeout - no response after ${STREAM_TIMEOUT_MS}ms")
                // This will be handled in the activity
            }
        }
        timeoutHandler?.postDelayed(timeoutRunnable!!, STREAM_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { runnable ->
            timeoutHandler?.removeCallbacks(runnable)
        }
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