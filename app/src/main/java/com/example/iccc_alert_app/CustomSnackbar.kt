package com.example.iccc_alert_app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar

/**
 * ✅ Custom Snackbar with icons, colors, and better styling
 */
object CustomSnackbar {

    enum class Type {
        SUCCESS,
        ERROR,
        WARNING,
        INFO,
        CUSTOM
    }

    data class Config(
        val message: String,
        val type: Type = Type.INFO,
        val duration: Int = Snackbar.LENGTH_SHORT,
        val actionText: String? = null,
        val actionCallback: (() -> Unit)? = null,
        val icon: Int? = null,
        val backgroundColor: Int? = null
    )

    fun show(view: View, config: Config): Snackbar {
        val snackbar = Snackbar.make(view, "", config.duration)

        // Get snackbar layout
        val snackbarLayout = snackbar.view as Snackbar.SnackbarLayout
        snackbarLayout.setPadding(0, 0, 0, 0)

        // Inflate custom layout
        val customView = LayoutInflater.from(view.context)
            .inflate(R.layout.custom_snackbar_layout, null)

        // Setup custom view
        val iconView = customView.findViewById<ImageView>(R.id.snackbar_icon)
        val messageView = customView.findViewById<TextView>(R.id.snackbar_message)
        val actionButton = customView.findViewById<TextView>(R.id.snackbar_action)

        messageView.text = config.message

        // Set icon and colors based on type
        when (config.type) {
            Type.SUCCESS -> {
                iconView.setImageResource(R.drawable.ic_check_circle)
                iconView.setColorFilter(ContextCompat.getColor(view.context, R.color.success_color))
                snackbarLayout.setBackgroundColor(
                    ContextCompat.getColor(view.context, R.color.success_bg)
                )
            }
            Type.ERROR -> {
                iconView.setImageResource(R.drawable.ic_error)
                iconView.setColorFilter(ContextCompat.getColor(view.context, R.color.error_color))
                snackbarLayout.setBackgroundColor(
                    ContextCompat.getColor(view.context, R.color.error_bg)
                )
            }
            Type.WARNING -> {
                iconView.setImageResource(R.drawable.ic_warning)
                iconView.setColorFilter(ContextCompat.getColor(view.context, R.color.warning_color))
                snackbarLayout.setBackgroundColor(
                    ContextCompat.getColor(view.context, R.color.warning_bg)
                )
            }
            Type.INFO -> {
                iconView.setImageResource(R.drawable.ic_info)
                iconView.setColorFilter(ContextCompat.getColor(view.context, R.color.info_color))
                snackbarLayout.setBackgroundColor(
                    ContextCompat.getColor(view.context, R.color.info_bg)
                )
            }
            Type.CUSTOM -> {
                config.icon?.let { iconView.setImageResource(it) }
                config.backgroundColor?.let { snackbarLayout.setBackgroundColor(it) }
            }
        }

        // Setup action button
        if (config.actionText != null && config.actionCallback != null) {
            actionButton.visibility = View.VISIBLE
            actionButton.text = config.actionText
            actionButton.setOnClickListener {
                config.actionCallback.invoke()
                snackbar.dismiss()
            }
        } else {
            actionButton.visibility = View.GONE
        }

        // Add custom view to snackbar
        snackbarLayout.addView(customView, 0)

        // Add elevation and corner radius
        snackbarLayout.elevation = 8f
        snackbarLayout.translationZ = 8f

        snackbar.show()
        return snackbar
    }

    // Convenience methods
    fun success(view: View, message: String, action: Pair<String, () -> Unit>? = null): Snackbar {
        return show(view, Config(
            message = message,
            type = Type.SUCCESS,
            actionText = action?.first,
            actionCallback = action?.second
        ))
    }

    fun error(view: View, message: String, action: Pair<String, () -> Unit>? = null): Snackbar {
        return show(view, Config(
            message = message,
            type = Type.ERROR,
            duration = Snackbar.LENGTH_LONG,
            actionText = action?.first,
            actionCallback = action?.second
        ))
    }

    fun warning(view: View, message: String, action: Pair<String, () -> Unit>? = null): Snackbar {
        return show(view, Config(
            message = message,
            type = Type.WARNING,
            actionText = action?.first,
            actionCallback = action?.second
        ))
    }

    fun info(view: View, message: String, action: Pair<String, () -> Unit>? = null): Snackbar {
        return show(view, Config(
            message = message,
            type = Type.INFO,
            actionText = action?.first,
            actionCallback = action?.second
        ))
    }
}

/**
 * ✅ Extension functions for even easier usage
 */
fun View.showSuccessSnackbar(message: String, action: Pair<String, () -> Unit>? = null) {
    CustomSnackbar.success(this, message, action)
}

fun View.showErrorSnackbar(message: String, action: Pair<String, () -> Unit>? = null) {
    CustomSnackbar.error(this, message, action)
}

fun View.showWarningSnackbar(message: String, action: Pair<String, () -> Unit>? = null) {
    CustomSnackbar.warning(this, message, action)
}

fun View.showInfoSnackbar(message: String, action: Pair<String, () -> Unit>? = null) {
    CustomSnackbar.info(this, message, action)
}

/**
 * ✅ Usage Examples:
 */
/*
// Success
recyclerView.showSuccessSnackbar("Event saved successfully")

// Error with retry action
recyclerView.showErrorSnackbar(
    "Failed to load events",
    "RETRY" to { loadEventsAsync() }
)

// Warning
recyclerView.showWarningSnackbar("Network connection unstable")

// Info with action
recyclerView.showInfoSnackbar(
    "New events available",
    "VIEW" to { scrollToTop() }
)

// Custom configuration
CustomSnackbar.show(recyclerView, CustomSnackbar.Config(
    message = "Event removed",
    type = CustomSnackbar.Type.INFO,
    duration = Snackbar.LENGTH_LONG,
    actionText = "UNDO",
    actionCallback = { restoreEvent() }
))
*/