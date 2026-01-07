package com.example.iccc_alert_app

import android.webkit.WebView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

class ZoomController(
    private val zoomControlBar: LinearLayout,
    private val zoomInButton: ImageButton,
    private val zoomOutButton: ImageButton,
    private val resetZoomButton: ImageButton,
    private val zoomLevelText: TextView,
    private val webView: WebView,
    private val onControlsInteraction: () -> Unit
) {

    companion object {
        private const val MIN_ZOOM = 1.0f
        private const val MAX_ZOOM = 3.0f
        private const val ZOOM_STEP = 0.25f
    }

    private var currentZoom = 1.0f

    init {
        setupListeners()
        updateZoomDisplay()
    }

    private fun setupListeners() {
        zoomInButton.setOnClickListener {
            zoomIn()
        }

        zoomOutButton.setOnClickListener {
            zoomOut()
        }

        resetZoomButton.setOnClickListener {
            resetZoom()
        }
    }

    private fun zoomIn() {
        if (currentZoom < MAX_ZOOM) {
            currentZoom += ZOOM_STEP
            applyZoom()
            updateZoomDisplay()
            onControlsInteraction()
        }
    }

    private fun zoomOut() {
        if (currentZoom > MIN_ZOOM) {
            currentZoom -= ZOOM_STEP
            applyZoom()
            updateZoomDisplay()
            onControlsInteraction()
        }
    }

    private fun resetZoom() {
        currentZoom = 1.0f
        webView.evaluateJavascript("window.resetZoom();", null)
        updateZoomDisplay()
        onControlsInteraction()
    }

    private fun applyZoom() {
        webView.evaluateJavascript("window.setZoom($currentZoom);", null)
    }

    private fun updateZoomDisplay() {
        zoomLevelText.text = String.format("%.1fx", currentZoom)

        zoomInButton.isEnabled = currentZoom < MAX_ZOOM
        zoomOutButton.isEnabled = currentZoom > MIN_ZOOM
        resetZoomButton.isEnabled = currentZoom != 1.0f

        zoomInButton.alpha = if (currentZoom < MAX_ZOOM) 1.0f else 0.5f
        zoomOutButton.alpha = if (currentZoom > MIN_ZOOM) 1.0f else 0.5f
        resetZoomButton.alpha = if (currentZoom != 1.0f) 1.0f else 0.5f
    }
}