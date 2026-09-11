package com.example.iccc_alert_app

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drives the full "comment on a VTS alert" flow against [VtsApiService],
 * matching how the CCL Alert Dashboard behaves:
 *
 *  - a live alert reaches the VTS database a moment after it reaches us,
 *    so a 404 is retried [MAX_RETRIES] times, [RETRY_DELAY_MS] apart
 *  - if a remark already exists the user is asked before overwriting it,
 *    and only then is the request resent with force=true
 *
 * This is the VTS counterpart to the VA comment flow in EventPriorityManager.
 */
object VtsCommentSubmitter {

    private const val TAG = "VtsCommentSubmitter"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 3_000L

    /**
     * @param onProgress called with a status line while retrying, so the caller
     *                   can keep the user informed instead of appearing frozen.
     * @param onResult   terminal callback: success flag plus a message to show.
     */
    fun submit(
        context: Context,
        scope: CoroutineScope,
        event: Event,
        comment: String,
        onProgress: ((String) -> Unit)? = null,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        if (comment.isBlank()) {
            onResult(false, "Please enter a comment before saving")
            return
        }
        if (!VtsAlertTypes.isVtsAlert(event.type)) {
            onResult(false, "Not a vehicle alert")
            return
        }

        scope.launch {
            when (val result = postWithRetry(event, comment, force = false, onProgress)) {
                is VtsApiService.CommentResult.Success ->
                    onResult(true, "Comment saved to VTS")

                is VtsApiService.CommentResult.AlreadyHasRemark ->
                    confirmOverride(context, scope, event, comment, result.existingRemark, onResult)

                is VtsApiService.CommentResult.NotRecordedYet ->
                    onResult(
                        false,
                        "This alert has not reached the VTS database yet. " +
                            "Please try again in a few moments."
                    )

                is VtsApiService.CommentResult.Error ->
                    onResult(false, result.message)
            }
        }
    }

    /**
     * Posts the comment, retrying only the "not recorded yet" case. Every other
     * outcome is returned to the caller immediately.
     */
    private suspend fun postWithRetry(
        event: Event,
        comment: String,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): VtsApiService.CommentResult {
        var attempt = 0
        while (true) {
            val result = VtsApiService.addComment(event, comment, force)

            if (result !is VtsApiService.CommentResult.NotRecordedYet) return result
            if (attempt >= MAX_RETRIES) return result

            attempt++
            Log.d(TAG, "Alert not in DB yet, retry $attempt/$MAX_RETRIES")
            onProgress?.invoke("Alert is still being recorded, retrying ($attempt/$MAX_RETRIES)...")
            delay(RETRY_DELAY_MS)
        }
    }

    /**
     * Overwriting someone else's remark is destructive and shared - the row is
     * visible to dashboard operators too - so it always needs confirmation.
     */
    private fun confirmOverride(
        context: Context,
        scope: CoroutineScope,
        event: Event,
        comment: String,
        existingRemark: String,
        onResult: (Boolean, String) -> Unit
    ) {
        // This runs after a network round trip, so the user may have navigated
        // away. Showing a dialog on a dead window throws BadTokenException.
        if (!context.canShowDialog()) {
            onResult(false, "This alert already has a remark")
            return
        }

        AlertDialog.Builder(context)
            .setTitle("This alert already has a remark")
            .setMessage(
                "An existing remark will be replaced:\n\n" +
                    existingRemark.ifBlank { "(empty)" } +
                    "\n\nReplace it with your comment?"
            )
            .setPositiveButton("Replace") { dialog, _ ->
                dialog.dismiss()
                scope.launch {
                    when (val forced = postWithRetry(event, comment, force = true, null)) {
                        is VtsApiService.CommentResult.Success ->
                            onResult(true, "Comment replaced in VTS")
                        is VtsApiService.CommentResult.Error ->
                            onResult(false, forced.message)
                        else ->
                            onResult(false, "Could not replace the existing remark")
                    }
                }
            }
            .setNegativeButton("Keep existing") { dialog, _ ->
                dialog.dismiss()
                onResult(false, "Kept the existing remark")
            }
            .setCancelable(false)
            .show()
    }
}

/**
 * True when [this] context still has a live window to attach a dialog to.
 *
 * Anything shown from a network callback has to check: the activity may have
 * been finished or destroyed while the request was in flight, and attaching a
 * dialog to it then throws WindowManager.BadTokenException.
 */
internal fun Context.canShowDialog(): Boolean {
    var ctx: Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) {
            return !ctx.isFinishing && !ctx.isDestroyed
        }
        ctx = ctx.baseContext
    }
    return false
}
