package com.example.iccc_alert_app

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ✅ ENHANCED Helper class with API-based comment checking
 * Now checks DATABASE via API, not just local storage
 */
class ChannelEventSaveHelper(
    private val context: Context,
    private val fragmentManager: FragmentManager,
    private val channelArea: String
) {
    private val priorityManager = EventPriorityManager(context, channelArea)

    companion object {
        private const val TAG = "ChannelEventSaveHelper"
    }

    /**
     * ✅ ENHANCED: Main entry point with API-based comment checking
     * This now checks the DATABASE via API before saving
     */
    fun saveEventWithPriority(
        event: Event,
        priority: Priority,
        comment: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val eventId = event.id
        if (eventId == null) {
            onError?.invoke("Invalid event ID")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d(TAG, "")
                Log.d(TAG, "════════════════════════════════════════")
                Log.d(TAG, "🔍 CHECKING FOR EXISTING COMMENTS")
                Log.d(TAG, "════════════════════════════════════════")
                Log.d(TAG, "Event ID: $eventId")
                Log.d(TAG, "Area: ${event.area}")

                // ✅ CRITICAL: Check API for existing comments (not just local)
                val existingCommentInfo = priorityManager.checkEventHasComments(eventId, event)

                if (existingCommentInfo != null) {
                    Log.w(TAG, "")
                    Log.w(TAG, "⚠️⚠️⚠️ EXISTING COMMENTS FOUND IN DATABASE ⚠️⚠️⚠️")
                    Log.w(TAG, "════════════════════════════════════════")
                    Log.w(TAG, "Priority: ${existingCommentInfo.priority}")
                    Log.w(TAG, "Comment: ${existingCommentInfo.comment}")
                    Log.w(TAG, "Timestamp: ${java.util.Date(existingCommentInfo.savedTimestamp)}")
                    Log.w(TAG, "Synced with API: ${existingCommentInfo.isSyncedWithApi}")
                    Log.w(TAG, "════════════════════════════════════════")

                    // Show dialog to user
                    showExistingCommentDialog(
                        eventId = eventId,
                        existingInfo = existingCommentInfo,
                        event = event,
                        priority = priority,
                        comment = comment,
                        onSuccess = onSuccess,
                        onError = onError
                    )
                } else {
                    Log.d(TAG, "")
                    Log.d(TAG, "✅ NO EXISTING COMMENTS FOUND")
                    Log.d(TAG, "════════════════════════════════════════")
                    Log.d(TAG, "Proceeding with save...")

                    // No existing comments - proceed with save
                    performSave(
                        event = event,
                        priority = priority,
                        comment = comment,
                        onSuccess = onSuccess,
                        onError = onError
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in save workflow: ${e.message}", e)
                onError?.invoke("Error checking comments: ${e.message}")
            }
        }
    }

    /**
     * Show dialog when event already has existing comments
     */
    private fun showExistingCommentDialog(
        eventId: String,
        existingInfo: EventCommentInfo,
        event: Event,
        priority: Priority,
        comment: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        Log.d(TAG, "📋 Showing ExistingCommentDialogFragment...")

        val dialog = ExistingCommentDialogFragment.newInstance(eventId, existingInfo)

        // If user chooses to update/replace
        dialog.onUpdateCallback = {
            Log.d(TAG, "✅ User chose to REPLACE existing comment")
            Log.d(TAG, "Proceeding with save (will overwrite)...")

            performSave(
                event = event,
                priority = priority,
                comment = comment,
                onSuccess = onSuccess,
                onError = onError
            )
        }

        // If user chooses to keep existing
        dialog.onCancelCallback = {
            Log.d(TAG, "🚫 User chose to KEEP existing comment")

            Toast.makeText(
                context,
                "Kept existing comment",
                Toast.LENGTH_SHORT
            ).show()

            // Don't call onError - user intentionally cancelled
        }

        dialog.show(fragmentManager, "ExistingCommentDialog")
    }

    /**
     * Perform the actual save operation
     */
    private fun performSave(
        event: Event,
        priority: Priority,
        comment: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d(TAG, "")
                Log.d(TAG, "════════════════════════════════════════")
                Log.d(TAG, "💾 STARTING SAVE OPERATION")
                Log.d(TAG, "════════════════════════════════════════")
                Log.d(TAG, "Event ID: ${event.id}")
                Log.d(TAG, "Priority: ${priority.displayName}")
                Log.d(TAG, "Comment: $comment")

                // Use EventPriorityManager to handle the save + API sync
                priorityManager.updateEventPriority(
                    event = event,
                    priority = priority,
                    comment = comment,
                    onCommentExists = {
                        // This shouldn't happen since we checked above, but just in case
                        Log.w(TAG, "⚠️ Unexpected: comment found during save (double-check)")
                        onError?.invoke("Event already has comment")
                    },
                    onUpdateSuccess = {
                        Log.d(TAG, "")
                        Log.d(TAG, "✅✅✅ SAVE SUCCESSFUL ✅✅✅")
                        Log.d(TAG, "════════════════════════════════════════")
                        onSuccess?.invoke()
                    },
                    onUpdateError = { errorMsg ->
                        Log.e(TAG, "")
                        Log.e(TAG, "❌❌❌ SAVE FAILED ❌❌❌")
                        Log.e(TAG, "════════════════════════════════════════")
                        Log.e(TAG, "Error: $errorMsg")
                        onError?.invoke(errorMsg)
                    }
                )

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error performing save: ${e.message}", e)
                onError?.invoke("Save error: ${e.message}")
            }
        }
    }

    /**
     * Quick save without dialog (if you want to skip the check)
     * NOT RECOMMENDED - only use for special cases
     */
    fun quickSaveEvent(
        event: Event,
        priority: Priority = Priority.MODERATE,
        comment: String = "Quick saved from dashboard",
        onSuccess: (() -> Unit)? = null
    ) {
        val eventId = event.id
        if (eventId == null) {
            Toast.makeText(context, "Invalid event", Toast.LENGTH_SHORT).show()
            return
        }

        Log.w(TAG, "⚠️ Using quickSaveEvent - skipping existing comment check!")

        CoroutineScope(Dispatchers.Main).launch {
            val (success, errorMsg) = SavedMessagesManager.saveMessage(
                eventId = eventId,
                event = event,
                priority = priority,
                comment = comment
            )

            if (success) {
                Toast.makeText(context, "Event saved", Toast.LENGTH_SHORT).show()
                onSuccess?.invoke()
            } else {
                Toast.makeText(context, errorMsg ?: "Save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}