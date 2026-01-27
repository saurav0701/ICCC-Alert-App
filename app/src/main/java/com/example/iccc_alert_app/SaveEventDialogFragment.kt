package com.example.iccc_alert_app

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * Professional dialog to save event with priority and comment
 */
class SaveEventDialogFragment : DialogFragment() {

    private lateinit var event: Event
    private var defaultPriority: Priority = Priority.MODERATE
    private var onSaveCallback: ((Priority, String) -> Unit)? = null

    private lateinit var prioritySpinner: Spinner
    private lateinit var commentInput: EditText
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    companion object {
        private const val ARG_EVENT = "event_json"
        private const val ARG_DEFAULT_PRIORITY = "default_priority"

        fun show(
            fragmentManager: FragmentManager,
            event: Event,
            defaultPriority: Priority = Priority.MODERATE,
            onSave: (Priority, String) -> Unit
        ) {
            val dialog = SaveEventDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_EVENT, com.google.gson.Gson().toJson(event))
                    putString(ARG_DEFAULT_PRIORITY, defaultPriority.name)
                }
                onSaveCallback = onSave
            }
            dialog.show(fragmentManager, "SaveEventDialog")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val eventJson = arguments?.getString(ARG_EVENT)
        event = com.google.gson.Gson().fromJson(eventJson, Event::class.java)

        val priorityName = arguments?.getString(ARG_DEFAULT_PRIORITY)
        defaultPriority = try {
            Priority.valueOf(priorityName ?: "MODERATE")
        } catch (e: Exception) {
            Priority.MODERATE
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
        val view = createDialogView()
        builder.setView(view)
        return builder.create()
    }

    private fun createDialogView(): View {
        val context = requireContext()
        val rootLayout = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 24.dpToPx())
        }

        // Title
        val title = TextView(context).apply {
            text = "Save Event"
            textSize = 20f
            setTextColor(android.graphics.Color.parseColor("#212121"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16.dpToPx())
        }
        rootLayout.addView(title)

        // Event Info Section
        val eventInfoLayout = createEventInfoSection()
        rootLayout.addView(eventInfoLayout)

        // Priority Section
        val priorityLabel = TextView(context).apply {
            text = "Priority *"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#424242"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16.dpToPx(), 0, 8.dpToPx())
        }
        rootLayout.addView(priorityLabel)

        prioritySpinner = Spinner(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dpToPx()
            )
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
            setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
        }
        setupPrioritySpinner()
        rootLayout.addView(prioritySpinner)

        // Comment Section
        val commentLabel = TextView(context).apply {
            text = "Comment *"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#424242"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16.dpToPx(), 0, 8.dpToPx())
        }
        rootLayout.addView(commentLabel)

        commentInput = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                120.dpToPx()
            )
            hint = "Enter your comment..."
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
            setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
            gravity = android.view.Gravity.TOP
            minLines = 3
            maxLines = 5
        }
        rootLayout.addView(commentInput)

        // Character count
        val charCount = TextView(context).apply {
            text = "0 / 500"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#757575"))
            gravity = android.view.Gravity.END
            setPadding(0, 4.dpToPx(), 0, 0)
        }
        rootLayout.addView(charCount)

        commentInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val length = s?.length ?: 0
                charCount.text = "$length / 500"

                if (length > 500) {
                    charCount.setTextColor(android.graphics.Color.parseColor("#F44336"))
                } else {
                    charCount.setTextColor(android.graphics.Color.parseColor("#757575"))
                }
            }
        })

        // Button Container
        val buttonLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 24.dpToPx(), 0, 0)
            }
        }

        cancelButton = Button(context).apply {
            text = "Cancel"
            setTextColor(android.graphics.Color.parseColor("#757575"))
            setBackgroundColor(android.graphics.Color.parseColor("#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(
                0,
                48.dpToPx(),
                1f
            ).apply {
                setMargins(0, 0, 8.dpToPx(), 0)
            }
            setOnClickListener { dismiss() }
        }
        buttonLayout.addView(cancelButton)

        saveButton = Button(context).apply {
            text = "Save Event"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
            layoutParams = LinearLayout.LayoutParams(
                0,
                48.dpToPx(),
                1f
            )
            setOnClickListener { handleSave() }
        }
        buttonLayout.addView(saveButton)

        rootLayout.addView(buttonLayout)

        return rootLayout
    }

    private fun createEventInfoSection(): View {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#E3F2FD"))
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
        }

        // Event Type
        val eventType = TextView(context).apply {
            text = event.typeDisplay ?: "Event"
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#1976D2"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        container.addView(eventType)

        // Location
        val location = event.data["location"] as? String
        if (!location.isNullOrEmpty()) {
            val locationText = TextView(context).apply {
                text = "Location: $location"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#424242"))
                setPadding(0, 4.dpToPx(), 0, 0)
            }
            container.addView(locationText)
        }

        // Time
        val timeFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        val eventTimeStr = event.data["eventTime"] as? String
        val eventDate = if (eventTimeStr != null) {
            try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(eventTimeStr)
            } catch (e: Exception) {
                Date(event.timestamp * 1000)
            }
        } else {
            Date(event.timestamp * 1000)
        }

        val timeText = TextView(context).apply {
            text = "Time: ${timeFormat.format(eventDate)}"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#424242"))
            setPadding(0, 2.dpToPx(), 0, 0)
        }
        container.addView(timeText)

        return container
    }

    private fun setupPrioritySpinner() {
        val priorities = Priority.values().map { it.displayName }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            priorities
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        prioritySpinner.adapter = adapter

        // Set default priority
        val defaultIndex = Priority.values().indexOf(defaultPriority)
        if (defaultIndex >= 0) {
            prioritySpinner.setSelection(defaultIndex)
        }
    }

    private fun handleSave() {
        val comment = commentInput.text.toString().trim()

        // Validation
        if (comment.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter a comment", Toast.LENGTH_SHORT).show()
            commentInput.requestFocus()
            return
        }

        if (comment.length > 500) {
            Toast.makeText(requireContext(), "Comment too long (max 500 characters)", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedPriority = Priority.values()[prioritySpinner.selectedItemPosition]

        // Trigger callback
        onSaveCallback?.invoke(selectedPriority, comment)
        dismiss()
    }

    private fun Int.dpToPx(): Int {
        return (this * requireContext().resources.displayMetrics.density).toInt()
    }
}