package com.example.iccc_alert_app

import android.app.Activity
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.snackbar.Snackbar
import android.graphics.Color
import android.widget.TextView

object FeedbackHelper {

    fun showSuccess(activity: Activity, message: String, actionText: String? = null, action: (() -> Unit)? = null) {
        val rootView = activity.findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)

        // Green background for success
        val snackbarView = snackbar.view
        snackbarView.setBackgroundColor(Color.parseColor("#4CAF50"))

        // White text
        val textView = snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(Color.WHITE)
        textView.textSize = 15f

        // Add action if provided
        if (actionText != null && action != null) {
            snackbar.setAction(actionText) {
                action()
            }
            snackbar.setActionTextColor(Color.WHITE)
        }

        snackbar.show()
    }

    fun showError(activity: Activity, message: String, actionText: String? = "Retry", action: (() -> Unit)? = null) {
        val rootView = activity.findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)

        // Red background for errors
        val snackbarView = snackbar.view
        snackbarView.setBackgroundColor(Color.parseColor("#F44336"))

        // White text
        val textView = snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(Color.WHITE)
        textView.textSize = 15f

        // Add retry action if provided
        if (actionText != null && action != null) {
            snackbar.setAction(actionText) {
                action()
            }
            snackbar.setActionTextColor(Color.WHITE)
        }

        snackbar.show()
    }

    fun showInfo(activity: Activity, message: String, actionText: String? = null, action: (() -> Unit)? = null) {
        val rootView = activity.findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)

        // Blue background for info
        val snackbarView = snackbar.view
        snackbarView.setBackgroundColor(Color.parseColor("#2196F3"))

        // White text
        val textView = snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(Color.WHITE)
        textView.textSize = 15f

        // Add action if provided
        if (actionText != null && action != null) {
            snackbar.setAction(actionText) {
                action()
            }
            snackbar.setActionTextColor(Color.WHITE)
        }

        snackbar.show()
    }

    fun showWarning(activity: Activity, message: String, actionText: String? = null, action: (() -> Unit)? = null) {
        val rootView = activity.findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)

        // Orange background for warnings
        val snackbarView = snackbar.view
        snackbarView.setBackgroundColor(Color.parseColor("#FF9800"))

        // White text
        val textView = snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(Color.WHITE)
        textView.textSize = 15f

        // Add action if provided
        if (actionText != null && action != null) {
            snackbar.setAction(actionText) {
                action()
            }
            snackbar.setActionTextColor(Color.WHITE)
        }

        snackbar.show()
    }

    fun showWithUndo(activity: Activity, message: String, onUndo: () -> Unit) {
        val rootView = activity.findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)

        // Default background
        val snackbarView = snackbar.view
        snackbarView.setBackgroundColor(Color.parseColor("#323232"))

        // White text
        val textView = snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(Color.WHITE)
        textView.textSize = 15f

        // Add undo action
        snackbar.setAction("UNDO") {
            onUndo()
        }
        snackbar.setActionTextColor(Color.parseColor("#FFD600"))

        snackbar.show()
    }
}
