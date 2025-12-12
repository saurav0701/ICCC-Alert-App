package com.example.iccc_alert_app

import android.content.Context
import android.content.Intent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * ✅ FIXED: Smart gesture handler with working implementations
 */
class SmartGestureHandler(
    private val context: Context,
    private val recyclerView: RecyclerView
) {

    // Gesture callbacks
    var onSwipeLeft: ((Int) -> Unit)? = null
    var onSwipeRight: ((Int) -> Unit)? = null
    var onDoubleTap: ((Int) -> Unit)? = null
    var onLongPress: ((Int) -> Unit)? = null
    var onPinchZoom: ((Float, Int) -> Unit)? = null

    // ✅ NEW: Access to adapter for implementing actions
    var adapter: ChannelEventsAdapter? = null
    var bindingHelpers: EventBindingHelpers? = null

    // Gesture detectors
    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector

    // Gesture state
    private var isGestureInProgress = false
    private var swipeThreshold = 200f
    private var velocityThreshold = 200f
    private var targetView: View? = null
    private var targetPosition = -1

    init {
        swipeThreshold = 200f * context.resources.displayMetrics.density
        velocityThreshold = 200f * context.resources.displayMetrics.density

        gestureDetector = GestureDetector(context, RecyclerViewGestureListener())
        scaleDetector = ScaleGestureDetector(context, RecyclerViewScaleListener())

        setupRecyclerViewTouchListener()
    }

    private fun setupRecyclerViewTouchListener() {
        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                val child = rv.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    targetView = child
                    targetPosition = rv.getChildAdapterPosition(child)
                }

                gestureDetector.onTouchEvent(e)
                scaleDetector.onTouchEvent(e)

                return isGestureInProgress
            }
        })
    }

    private inner class RecyclerViewGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            isGestureInProgress = false
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (targetPosition >= 0) {
                performHapticFeedback()
                onDoubleTap?.invoke(targetPosition)
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (targetPosition >= 0) {
                performHapticFeedback()
                onLongPress?.invoke(targetPosition)
            }
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null || targetPosition < 0) return false

            val diffX = e2.x - e1.x
            val diffY = e2.y - e1.y

            if (abs(diffX) > abs(diffY) &&
                abs(diffX) > swipeThreshold &&
                abs(velocityX) > velocityThreshold
            ) {
                isGestureInProgress = true
                performHapticFeedback()

                if (diffX > 0) {
                    animateSwipe(targetView, targetPosition, isRight = true)
                    onSwipeRight?.invoke(targetPosition)
                } else {
                    animateSwipe(targetView, targetPosition, isRight = false)
                    onSwipeLeft?.invoke(targetPosition)
                }

                return true
            }

            return false
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            return isGestureInProgress
        }
    }

    private inner class RecyclerViewScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (targetPosition >= 0) {
                val scaleFactor = detector.scaleFactor
                onPinchZoom?.invoke(scaleFactor, targetPosition)
                return true
            }
            return false
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isGestureInProgress = true
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isGestureInProgress = false
        }
    }

    private fun animateSwipe(view: View?, position: Int, isRight: Boolean) {
        view ?: return

        val translationX = if (isRight) view.width.toFloat() else -view.width.toFloat()

        view.animate()
            .translationX(translationX)
            .alpha(0.3f)
            .setDuration(200)
            .withEndAction {
                view.translationX = 0f
                view.alpha = 1f
            }
            .start()
    }

    // ✅ FIXED: Implement action handlers using adapter methods

    fun handleDownloadImage(event: Event) {
        val progressDialog = android.app.ProgressDialog(context)
        progressDialog.setMessage("Downloading image...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            val file = bindingHelpers?.downloadEventImage(event)
            progressDialog.dismiss()

            if (file != null) {
                Toast.makeText(context, "Image saved: ${file.name}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Failed to download image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleShareImage(event: Event) {
        val progressDialog = android.app.ProgressDialog(context)
        progressDialog.setMessage("Preparing to share...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            val file = bindingHelpers?.prepareShareImage(event)
            progressDialog.dismiss()

            if (file != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "Event: ${event.typeDisplay}\nLocation: ${event.data["location"]}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Event Image"))
            } else {
                Toast.makeText(context, "Failed to share image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleGeneratePDF(event: Event) {
        val progressDialog = android.app.ProgressDialog(context)
        progressDialog.setMessage("Generating PDF...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            val pdfFile = bindingHelpers?.generateSingleEventPdf(event)
            progressDialog.dismiss()

            if (pdfFile != null) {
                Toast.makeText(context, "PDF saved: ${pdfFile.name}", Toast.LENGTH_LONG).show()
                bindingHelpers?.openPdfFile(pdfFile)
            } else {
                Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleViewOnMap(event: Event) {
        bindingHelpers?.openMapView(event)
    }

    private fun performHapticFeedback() {
        recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun cleanup() {
        targetView = null
        targetPosition = -1
        isGestureInProgress = false
    }
}