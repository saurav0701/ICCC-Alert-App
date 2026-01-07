package com.example.iccc_alert_app

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.example.iccc_alert_app.CameraStreamPlayerActivity.Companion.ACTION_MEDIA_CONTROL
import com.example.iccc_alert_app.CameraStreamPlayerActivity.Companion.CONTROL_TYPE_PAUSE
import com.example.iccc_alert_app.CameraStreamPlayerActivity.Companion.CONTROL_TYPE_PLAY
import com.example.iccc_alert_app.CameraStreamPlayerActivity.Companion.EXTRA_CONTROL_TYPE

class PipController(
    private val activity: AppCompatActivity,
    private val webView: WebView
) {

    companion object {
        private const val TAG = "PipController"
        private const val REQUEST_PAUSE = 1
        private const val REQUEST_PLAY = 2
    }

    private var isPlaying = true

    @RequiresApi(Build.VERSION_CODES.O)
    fun enterPipMode() {
        try {
            val aspectRatio = Rational(16, 9)

            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .setActions(getPipActions())
                .build()

            val success = activity.enterPictureInPictureMode(params)
            if (success) {
                Log.d(TAG, "📺 Entered PiP mode successfully")
            } else {
                Log.w(TAG, "⚠️ Failed to enter PiP mode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error entering PiP mode: ${e.message}", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getPipActions(): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()

        if (isPlaying) {
            // Pause action
            val pauseIntent = PendingIntent.getBroadcast(
                activity,
                REQUEST_PAUSE,
                Intent(ACTION_MEDIA_CONTROL).apply {
                    putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PAUSE)
                    setPackage(activity.packageName) // Ensure it's delivered to our app
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val pauseIcon = Icon.createWithResource(activity, R.drawable.ic_pause)
            val pauseAction = RemoteAction(
                pauseIcon,
                "Pause",
                "Pause stream",
                pauseIntent
            )
            actions.add(pauseAction)
        } else {
            // Play action
            val playIntent = PendingIntent.getBroadcast(
                activity,
                REQUEST_PLAY,
                Intent(ACTION_MEDIA_CONTROL).apply {
                    putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY)
                    setPackage(activity.packageName)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val playIcon = Icon.createWithResource(activity, R.drawable.ic_play)
            val playAction = RemoteAction(
                playIcon,
                "Play",
                "Resume stream",
                playIntent
            )
            actions.add(playAction)
        }

        return actions
    }

    fun pauseStream() {
        isPlaying = false
        webView.evaluateJavascript(
            "(function() { " +
                    "  try { " +
                    "    if(typeof window.pauseStream === 'function') { " +
                    "      window.pauseStream(); " +
                    "      return 'paused'; " +
                    "    } " +
                    "    return 'function not found'; " +
                    "  } catch(e) { " +
                    "    return 'error: ' + e.message; " +
                    "  } " +
                    "})();"
        ) { result ->
            Log.d(TAG, "⏸️ Pause result: $result")
        }
        updatePipActions()
        Log.d(TAG, "⏸️ Stream pause requested")
    }

    fun playStream() {
        isPlaying = true
        webView.evaluateJavascript(
            "(function() { " +
                    "  try { " +
                    "    if(typeof window.playStream === 'function') { " +
                    "      window.playStream(); " +
                    "      return 'playing'; " +
                    "    } " +
                    "    return 'function not found'; " +
                    "  } catch(e) { " +
                    "    return 'error: ' + e.message; " +
                    "  } " +
                    "})();"
        ) { result ->
            Log.d(TAG, "▶️ Play result: $result")
        }
        updatePipActions()
        Log.d(TAG, "▶️ Stream play requested")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipActions() {
        if (activity.isInPictureInPictureMode) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setActions(getPipActions())
                    .build()
                activity.setPictureInPictureParams(params)
                Log.d(TAG, "✅ PiP actions updated successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating PiP actions: ${e.message}")
            }
        }
    }

    fun onPipModeChanged(
        isInPictureInPictureMode: Boolean,
        topControlBar: LinearLayout,
        zoomControlBar: LinearLayout
    ) {
        if (isInPictureInPictureMode) {
            // Hide all controls in PiP mode
            topControlBar.visibility = View.GONE
            zoomControlBar.visibility = View.GONE
            Log.d(TAG, "📺 In PiP mode - controls hidden")
        } else {
            // Restore controls when exiting PiP
            topControlBar.visibility = View.VISIBLE
            zoomControlBar.visibility = View.VISIBLE
            Log.d(TAG, "🖥️ Exited PiP mode - controls restored")
        }
    }
}