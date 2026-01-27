package com.example.iccc_alert_app

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Professional dialog to display existing event comments
 */
class ExistingCommentDialogFragment : DialogFragment() {

    private lateinit var eventId: String
    private lateinit var commentInfo: EventCommentInfo
    var onUpdateCallback: (() -> Unit)? = null
    var onCancelCallback: (() -> Unit)? = null

    companion object {
        private const val ARG_EVENT_ID = "event_id"
        private const val ARG_PRIORITY = "priority"
        private const val ARG_COMMENT = "comment"
        private const val ARG_TIMESTAMP = "timestamp"
        private const val ARG_SYNCED = "synced"

        fun newInstance(
            eventId: String,
            commentInfo: EventCommentInfo
        ) = ExistingCommentDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_EVENT_ID, eventId)
                putString(ARG_PRIORITY, commentInfo.priority)
                putString(ARG_COMMENT, commentInfo.comment)
                putLong(ARG_TIMESTAMP, commentInfo.savedTimestamp)
                putBoolean(ARG_SYNCED, commentInfo.isSyncedWithApi)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eventId = arguments?.getString(ARG_EVENT_ID) ?: ""
        commentInfo = EventCommentInfo(
            hasPreviousComments = true,
            priority = arguments?.getString(ARG_PRIORITY) ?: "Unknown",
            comment = arguments?.getString(ARG_COMMENT) ?: "",
            savedTimestamp = arguments?.getLong(ARG_TIMESTAMP) ?: 0L,
            isSyncedWithApi = arguments?.getBoolean(ARG_SYNCED) ?: false
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
            .setView(createDialogView())
            .create()
    }

    private fun createDialogView(): View {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 24.dpToPx())
        }

        // Title
        val title = TextView(context).apply {
            text = "Event Already Has Comments"
            textSize = 20f
            setTextColor(android.graphics.Color.parseColor("#F44336"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16.dpToPx())
        }
        container.addView(title)

        // Info Section
        val infoLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16.dpToPx())
            }
            setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
        }

        // Priority display
        val priorityLabel = TextView(context).apply {
            text = "Current Priority"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#757575"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        infoLayout.addView(priorityLabel)

        val priorityValue = TextView(context).apply {
            text = commentInfo.priority
            textSize = 16f
            setTextColor(getPriorityColor(commentInfo.priority))
            setPadding(0, 4.dpToPx(), 0, 12.dpToPx())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        infoLayout.addView(priorityValue)

        // Comment display
        val commentLabel = TextView(context).apply {
            text = "Existing Comment"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#757575"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        infoLayout.addView(commentLabel)

        val commentValue = TextView(context).apply {
            text = commentInfo.comment
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#212121"))
            setPadding(0, 4.dpToPx(), 0, 12.dpToPx())
            setLineSpacing(4f, 1.2f)
        }
        infoLayout.addView(commentValue)

        // Timestamp
        val timestampText = SimpleDateFormat(
            "MMM dd, yyyy HH:mm:ss",
            Locale.getDefault()
        ).format(Date(commentInfo.savedTimestamp))

        val timestampLabel = TextView(context).apply {
            text = "Saved: $timestampText"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#999999"))
        }
        infoLayout.addView(timestampLabel)

        // Sync status
        val syncStatusContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dpToPx(), 0, 0)
        }

        val syncStatusIndicator = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(8.dpToPx(), 8.dpToPx()).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setMargins(0, 0, 8.dpToPx(), 0)
            }
            setBackgroundResource(R.drawable.circle_background)
            (background as? android.graphics.drawable.GradientDrawable)?.setColor(
                if (commentInfo.isSyncedWithApi)
                    android.graphics.Color.parseColor("#4CAF50")
                else
                    android.graphics.Color.parseColor("#FF9800")
            )
        }
        syncStatusContainer.addView(syncStatusIndicator)

        val syncBadge = TextView(context).apply {
            text = if (commentInfo.isSyncedWithApi) "Synced to Database" else "Not Yet Synced"
            textSize = 11f
            setTextColor(
                if (commentInfo.isSyncedWithApi)
                    android.graphics.Color.parseColor("#4CAF50")
                else
                    android.graphics.Color.parseColor("#FF9800")
            )
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        syncStatusContainer.addView(syncBadge)

        infoLayout.addView(syncStatusContainer)
        container.addView(infoLayout)

        // Message
        val message = TextView(context).apply {
            text = "This event already has a priority and comment. You can:\n\n• Keep the existing comment\n• Replace with a new comment (overwrites previous)\n• Cancel and exit"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#424242"))
            setPadding(0, 0, 0, 20.dpToPx())
            setLineSpacing(2f, 1.3f)
        }
        container.addView(message)

        // Button Container
        val buttonLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Keep Existing Button
        val keepButton = Button(context).apply {
            text = "Keep Existing"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dpToPx()
            ).apply {
                setMargins(0, 0, 0, 8.dpToPx())
            }
            setOnClickListener {
                dismiss()
                onCancelCallback?.invoke()
            }
        }
        buttonLayout.addView(keepButton)

        // Replace Comment Button
        val replaceButton = Button(context).apply {
            text = "Replace Comment"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#FF9800"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dpToPx()
            ).apply {
                setMargins(0, 0, 0, 8.dpToPx())
            }
            setOnClickListener {
                dismiss()
                onUpdateCallback?.invoke()
            }
        }
        buttonLayout.addView(replaceButton)

        // Cancel Button
        val cancelButton = Button(context).apply {
            text = "Cancel"
            setTextColor(android.graphics.Color.parseColor("#757575"))
            setBackgroundColor(android.graphics.Color.parseColor("#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dpToPx()
            )
            setOnClickListener {
                dismiss()
            }
        }
        buttonLayout.addView(cancelButton)

        container.addView(buttonLayout)

        return container
    }

    private fun getPriorityColor(priority: String): Int {
        return when (priority.lowercase()) {
            "high" -> android.graphics.Color.parseColor("#F44336")
            "moderate" -> android.graphics.Color.parseColor("#FF9800")
            "low" -> android.graphics.Color.parseColor("#4CAF50")
            else -> android.graphics.Color.parseColor("#757575")
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * requireContext().resources.displayMetrics.density).toInt()
    }
}