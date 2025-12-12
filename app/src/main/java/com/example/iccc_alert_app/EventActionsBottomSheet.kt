package com.example.iccc_alert_app

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class EventActionsBottomSheet(
    private val event: Event,
    private val bindingHelpers: EventBindingHelpers,
    private val onAction: (ActionType) -> Unit
) : BottomSheetDialogFragment() {

    enum class ActionType {
        SAVE,
        DOWNLOAD,
        SHARE,
        VIEW_MAP,
        GENERATE_PDF,
        MARK_IMPORTANT
    }

    private lateinit var saveButton: LinearLayout
    private lateinit var downloadButton: LinearLayout
    private lateinit var shareButton: LinearLayout
    private lateinit var mapButton: LinearLayout
    private lateinit var pdfButton: LinearLayout
    private lateinit var importantButton: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_event_actions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        val eventTitle = view.findViewById<TextView>(R.id.event_title)
        val eventLocation = view.findViewById<TextView>(R.id.event_location)
        val eventTime = view.findViewById<TextView>(R.id.event_time)
        val closeButton = view.findViewById<ImageView>(R.id.close_button)

        saveButton = view.findViewById(R.id.action_save)
        downloadButton = view.findViewById(R.id.action_download)
        shareButton = view.findViewById(R.id.action_share)
        mapButton = view.findViewById(R.id.action_map)
        pdfButton = view.findViewById(R.id.action_pdf)
        importantButton = view.findViewById(R.id.action_important)

        // Set event info
        eventTitle.text = event.typeDisplay ?: "Event"
        eventLocation.text = event.data["location"] as? String ?: "Unknown"
        eventTime.text = bindingHelpers.timeFormat.format(
            bindingHelpers.getEventDate(event)
        )

        // Close button
        closeButton.setOnClickListener {
            performHapticFeedback()
            dismiss()
        }

        // Setup action buttons
        setupActionButtons()

        // Check if event is already saved
        val isSaved = event.id?.let { SavedMessagesManager.isMessageSaved(it) } ?: false
        updateSaveButtonState(isSaved)
    }

    private fun setupActionButtons() {
        saveButton.setOnClickListener {
            performHapticFeedback()
            onAction(ActionType.SAVE)
            dismiss()
        }

        downloadButton.setOnClickListener {
            performHapticFeedback()
            onAction(ActionType.DOWNLOAD)
            dismiss()
        }

        shareButton.setOnClickListener {
            performHapticFeedback()
            onAction(ActionType.SHARE)
            dismiss()
        }

        mapButton.setOnClickListener {
            performHapticFeedback()
            onAction(ActionType.VIEW_MAP)
            dismiss()
        }

        pdfButton.setOnClickListener {
            performHapticFeedback()
            onAction(ActionType.GENERATE_PDF)
            dismiss()
        }

        importantButton.setOnClickListener {
            performHapticFeedback()
            onAction(ActionType.MARK_IMPORTANT)
            dismiss()
        }

        // Add press animations
        listOf(
            saveButton, downloadButton, shareButton,
            mapButton, pdfButton, importantButton
        ).forEach { button ->
            button.setOnTouchListener { view, motionEvent ->
                when (motionEvent.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.animate()
                            .scaleX(0.95f)
                            .scaleY(0.95f)
                            .setDuration(100)
                            .start()
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .start()
                    }
                }
                false
            }
        }
    }

    private fun updateSaveButtonState(isSaved: Boolean) {
        val icon = saveButton.findViewById<ImageView>(R.id.action_icon)
        val text = saveButton.findViewById<TextView>(R.id.action_text)

        if (isSaved) {
            icon.setImageResource(R.drawable.ic_check_circle)
            text.text = "Saved"
            saveButton.alpha = 0.6f
            saveButton.isEnabled = false
        } else {
            icon.setImageResource(R.drawable.ic_bookmark)
            text.text = "Save Event"
            saveButton.alpha = 1f
            saveButton.isEnabled = true
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        dialog.setOnShowListener { dialogInterface ->
            val d = dialogInterface as BottomSheetDialog
            val bottomSheet = d.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )

            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)

                // Set peek height to show top actions
                behavior.peekHeight = 400.dpToPx()
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED

                // Allow swiping to dismiss
                behavior.isHideable = true
                behavior.skipCollapsed = false
            }
        }

        return dialog
    }

    private fun performHapticFeedback() {
        view?.performHapticFeedback(
            android.view.HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val TAG = "EventActionsBottomSheet"

        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            event: Event,
            bindingHelpers: EventBindingHelpers,
            onAction: (ActionType) -> Unit
        ) {
            val sheet = EventActionsBottomSheet(event, bindingHelpers, onAction)
            sheet.show(fragmentManager, TAG)
        }
    }
}