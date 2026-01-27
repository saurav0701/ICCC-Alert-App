package com.example.iccc_alert_app

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class EventPriorityManager(
    private val context: Context,
    private val channelArea: String
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "EventPriorityManager"
    }

    suspend fun checkEventHasComments(eventId: String, event: Event): EventCommentInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val area = event.area ?: return@withContext null
                val baseUrl = getHttpUrlForArea(area)
                val apiUrl = "$baseUrl/api/va/events"

                Log.d(TAG, "🔍 Checking API for existing comments: $eventId")

                val eventTimeStr = event.data["eventTime"] as? String
                val eventTimestamp = if (eventTimeStr != null) {
                    try {
                        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        parser.parse(eventTimeStr)?.time ?: (event.timestamp * 1000)
                    } catch (e: Exception) {
                        event.timestamp * 1000
                    }
                } else {
                    event.timestamp * 1000
                }

                val fromTime = Date(eventTimestamp - 60000)
                val toTime = Date(eventTimestamp + 60000)

                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }

                val requestBody = mapOf(
                    "deviceId" to 0,
                    "locationType" to listOf("ALL"),
                    "from" to dateFormat.format(fromTime),
                    "to" to dateFormat.format(toTime),
                    "eventTypes" to listOf(event.type?.uppercase() ?: ""),
                    "page" to 1,
                    "perPage" to 250,
                    "sort" to "DESC"
                )

                val jsonBody = gson.toJson(requestBody)
                val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()

                response.use { resp ->
                    val responseBody = resp.body?.string()

                    if (!resp.isSuccessful || responseBody.isNullOrEmpty()) {
                        return@withContext null
                    }

                    val jsonElement = JsonParser.parseString(responseBody)

                    if (!jsonElement.isJsonArray) {
                        return@withContext null
                    }

                    val eventsArray = jsonElement.asJsonArray

                    if (eventsArray.size() == 0) {
                        return@withContext null
                    }

                    var targetEvent: com.google.gson.JsonObject? = null
                    for (element in eventsArray) {
                        if (element.isJsonObject) {
                            val obj = element.asJsonObject
                            if (obj.has("eventId")) {
                                val foundEventId = obj.get("eventId").asString
                                if (foundEventId == eventId) {
                                    targetEvent = obj
                                    break
                                }
                            }
                        }
                    }

                    if (targetEvent == null) {
                        return@withContext null
                    }

                    // Extract severity
                    val severity = if (targetEvent.has("severity") && !targetEvent.get("severity").isJsonNull) {
                        targetEvent.get("severity").asString
                    } else {
                        null
                    }

                    // Extract remarks
                    val remarks = if (targetEvent.has("remarks") && !targetEvent.get("remarks").isJsonNull) {
                        targetEvent.get("remarks")
                    } else {
                        null
                    }

                    val hasSeverity = severity != null &&
                            severity.isNotEmpty() &&
                            severity.lowercase() != "null"

                    val hasRemarks = remarks != null && !remarks.isJsonNull

                    if (!hasSeverity && !hasRemarks) {
                        return@withContext null
                    }

                    // ✅ FIXED: Parse remarks properly
                    val parsedComments = if (hasRemarks) {
                        parseRemarksFromJson(remarks)
                    } else {
                        emptyList()
                    }

                    if (parsedComments.isEmpty() && !hasSeverity) {
                        return@withContext null
                    }

                    val latestComment = if (parsedComments.isNotEmpty()) {
                        parsedComments.first()
                    } else {
                        CommentEntry(
                            user = "System",
                            comment = "Priority: $severity",
                            timestamp = System.currentTimeMillis().toString()
                        )
                    }

                    Log.d(TAG, "⚠️ Existing data found - Priority: $severity, Comments: ${parsedComments.size}")

                    return@withContext EventCommentInfo(
                        hasPreviousComments = true,
                        priority = severity ?: "Unknown",
                        comment = latestComment.comment,
                        savedTimestamp = parseTimestamp(latestComment.timestamp),
                        isSyncedWithApi = true
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ API check error: ${e.message}", e)
                return@withContext null
            }
        }
    }

    /**
     * ✅ FIXED: Properly parse remarks JSON structure
     */
    private fun parseRemarksFromJson(remarksJson: com.google.gson.JsonElement?): List<CommentEntry> {
        if (remarksJson == null || remarksJson.isJsonNull) return emptyList()

        try {
            // Case 1: remarks is a JSON object with "comment" field
            if (remarksJson.isJsonObject) {
                val obj = remarksJson.asJsonObject

                if (obj.has("comment")) {
                    val commentStr = obj.get("comment").asString
                    val user = if (obj.has("user")) obj.get("user").asString else "Unknown"
                    val timestamp = if (obj.has("timestamp")) obj.get("timestamp").asString
                    else System.currentTimeMillis().toString()

                    // Check if comment contains REMARKS_HISTORY
                    if (commentStr.startsWith("REMARKS_HISTORY:")) {
                        return parseRemarksHistory(commentStr)
                    }

                    // Check if comment contains EVENT_CLOSED_BY_USER
                    if (commentStr.startsWith("EVENT_CLOSED_BY_USER:")) {
                        return parseEventClosedByUser(commentStr)
                    }

                    // Simple comment
                    return listOf(CommentEntry(user, commentStr, timestamp))
                }
            }

            // Case 2: remarks is a string
            if (remarksJson.isJsonPrimitive && remarksJson.asJsonPrimitive.isString) {
                val remarksStr = remarksJson.asString

                if (remarksStr.startsWith("REMARKS_HISTORY:")) {
                    return parseRemarksHistory(remarksStr)
                }

                if (remarksStr.startsWith("EVENT_CLOSED_BY_USER:")) {
                    return parseEventClosedByUser(remarksStr)
                }

                return listOf(
                    CommentEntry(
                        user = "Unknown",
                        comment = remarksStr,
                        timestamp = System.currentTimeMillis().toString()
                    )
                )
            }

            // Case 3: remarks is an array of comments
            if (remarksJson.isJsonArray) {
                val comments = mutableListOf<CommentEntry>()
                remarksJson.asJsonArray.forEach { element ->
                    if (element.isJsonObject) {
                        val obj = element.asJsonObject
                        comments.add(
                            CommentEntry(
                                user = if (obj.has("user")) obj.get("user").asString else "Unknown",
                                comment = if (obj.has("comment")) obj.get("comment").asString else "",
                                timestamp = if (obj.has("timestamp")) obj.get("timestamp").asString
                                else System.currentTimeMillis().toString()
                            )
                        )
                    }
                }
                return comments.sortedByDescending { parseTimestamp(it.timestamp) }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing remarks: ${e.message}")
        }

        return emptyList()
    }

    /**
     * ✅ NEW: Parse REMARKS_HISTORY structure
     */
    private fun parseRemarksHistory(remarksStr: String): List<CommentEntry> {
        try {
            val historyJson = remarksStr.removePrefix("REMARKS_HISTORY:")
            val history = gson.fromJson(historyJson, RemarksHistory::class.java)

            // Parse each comment in the history
            val allComments = mutableListOf<CommentEntry>()

            for (entry in history.comments) {
                // Check if this entry contains nested EVENT_CLOSED_BY_USER
                if (entry.comment.startsWith("EVENT_CLOSED_BY_USER:")) {
                    val closedEvents = parseEventClosedByUser(entry.comment)
                    allComments.addAll(closedEvents)
                } else {
                    allComments.add(entry)
                }
            }

            return allComments.sortedByDescending { parseTimestamp(it.timestamp) }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing REMARKS_HISTORY: ${e.message}")
        }
        return emptyList()
    }

    /**
     * ✅ NEW: Parse EVENT_CLOSED_BY_USER structure
     */
    private fun parseEventClosedByUser(commentStr: String): List<CommentEntry> {
        try {
            val eventDataJson = commentStr.removePrefix("EVENT_CLOSED_BY_USER:")
            val eventData = gson.fromJson(eventDataJson, EventClosedData::class.java)

            val displayComment = buildString {
                append("Event closed by ${eventData.closedBy}\n")
                if (!eventData.finalComment.isNullOrEmpty()) {
                    append("Comment: ${eventData.finalComment}")
                }
            }

            return listOf(
                CommentEntry(
                    user = eventData.closedBy,
                    comment = displayComment.trim(),
                    timestamp = eventData.closedAt
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EVENT_CLOSED_BY_USER: ${e.message}")
        }
        return emptyList()
    }

    private fun parseTimestamp(timestamp: String?): Long {
        if (timestamp.isNullOrEmpty()) return System.currentTimeMillis()

        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .parse(timestamp)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            try {
                timestamp.toLongOrNull() ?: System.currentTimeMillis()
            } catch (e2: Exception) {
                System.currentTimeMillis()
            }
        }
    }

    fun getEventCommentInfo(eventId: String): EventCommentInfo? {
        val savedMessage = SavedMessagesManager.getSavedMessage(eventId)
        if (savedMessage != null) {
            return EventCommentInfo(
                hasPreviousComments = true,
                priority = savedMessage.priority.displayName,
                comment = savedMessage.comment,
                savedTimestamp = savedMessage.savedTimestamp,
                isSyncedWithApi = savedMessage.isSyncedWithApi
            )
        }
        return null
    }

    suspend fun updateEventPriority(
        event: Event,
        priority: Priority,
        comment: String,
        onCommentExists: (() -> Unit)? = null,
        onUpdateSuccess: (() -> Unit)? = null,
        onUpdateError: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.Main) {
        try {
            val eventId = event.id ?: return@withContext

            val existingCommentInfo = checkEventHasComments(eventId, event)

            if (existingCommentInfo != null) {
                Log.w(TAG, "⚠️ Event has existing comments - blocking save")
                onCommentExists?.invoke()
                return@withContext
            }

            performPriorityUpdate(event, priority, comment, onUpdateSuccess, onUpdateError)

        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            onUpdateError?.invoke("Error: ${e.message}")
        }
    }

    private fun performPriorityUpdate(
        event: Event,
        priority: Priority,
        comment: String,
        onUpdateSuccess: (() -> Unit)? = null,
        onUpdateError: ((String) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val eventId = event.id ?: run {
                    onUpdateError?.invoke("Invalid event ID")
                    return@launch
                }

                val (localSuccess, localError) = SavedMessagesManager.saveMessage(
                    eventId, event, priority, comment
                )

                if (!localSuccess) {
                    onUpdateError?.invoke(localError ?: "Failed to save")
                    return@launch
                }

                val (apiSuccess, apiError) = withContext(Dispatchers.IO) {
                    syncWithBackend(event, priority, comment)
                }

                if (apiSuccess) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Saved and synced", Toast.LENGTH_SHORT).show()
                    }
                    onUpdateSuccess?.invoke()
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Saved locally, sync failed", Toast.LENGTH_LONG).show()
                    }
                    onUpdateSuccess?.invoke()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
                onUpdateError?.invoke("Error: ${e.message}")
            }
        }
    }

    private suspend fun syncWithBackend(
        event: Event,
        priority: Priority,
        comment: String
    ): Pair<Boolean, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val area = event.area ?: return@withContext Pair(false, "Area not found")
                val eventId = event.id ?: return@withContext Pair(false, "Event ID not found")
                val baseUrl = getHttpUrlForArea(area)

                val requestBody = EventUpdateRequest(
                    rowData = mapOf("eventId" to eventId),
                    severity = priority.displayName,
                    remarks = mapOf(
                        "user" to "mobile-app",
                        "comment" to comment
                    )
                )

                val jsonBody = gson.toJson(requestBody)
                val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url("$baseUrl/api/va/event")
                    .put(body)
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()

                response.use {
                    if (it.isSuccessful) {
                        SavedMessagesManager.markAsSynced(eventId)
                        return@withContext Pair(true, null)
                    } else {
                        return@withContext Pair(false, "HTTP ${it.code}")
                    }
                }

            } catch (e: Exception) {
                return@withContext Pair(false, e.message ?: "Network error")
            }
        }
    }

    fun showExistingCommentDialog(
        fragmentManager: androidx.fragment.app.FragmentManager,
        eventId: String,
        existingInfo: EventCommentInfo,
        onUpdate: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ) {
        val dialog = ExistingCommentDialogFragment.newInstance(eventId, existingInfo).apply {
            onUpdateCallback = onUpdate
            onCancelCallback = onCancel
        }
        dialog.show(fragmentManager, "ExistingCommentDialog")
    }

    private fun getHttpUrlForArea(area: String): String {
        val normalizedArea = area.lowercase().replace(" ", "").replace("_", "")

        if (BackendConfig.isCCL()) {
            return when (normalizedArea) {
                "barkasayal" -> "https://barkasayal.cclai.in"
                "argada" -> "https://argada.cclai.in"
                "northkaranpura" -> "https://nk.cclai.in"
                "bokarokargali" -> "https://bk.cclai.in"
                "kathara" -> "https://kathara.cclai.in"
                "giridih" -> "https://giridih.cclai.in"
                "amrapali" -> "https://amrapali.cclai.in"
                "magadh" -> "https://magadh.cclai.in"
                "rajhara" -> "https://rajhara.cclai.in"
                "kuju" -> "https://kuju.cclai.in"
                "hazaribagh" -> "https://hazaribagh.cclai.in"
                "rajrappa" -> "https://rajrappa.cclai.in"
                "dhori" -> "https://dhori.cclai.in"
                "piparwar" -> "https://piparwar.cclai.in"
                else -> "https://barkasayal.cclai.in"
            }
        }

        return when (normalizedArea) {
            "sijua", "katras" -> "http://a5va.bccliccc.in:10050"
            "kusunda" -> "http://a6va.bccliccc.in:5050"
            else -> "http://a5va.bccliccc.in:10050"
        }
    }
}

data class EventCommentInfo(
    val hasPreviousComments: Boolean,
    val priority: String,
    val comment: String,
    val savedTimestamp: Long,
    val isSyncedWithApi: Boolean
)

data class CommentEntry(
    val user: String,
    val comment: String,
    val timestamp: String
)

data class RemarksHistory(
    val comments: List<CommentEntry>,
    val totalComments: Int = 0,
    val lastUpdated: String = "",
    val version: String = "2.0"
)

data class EventClosedData(
    val closedBy: String,
    val closedAt: String,
    val priority: String,
    val finalComment: String?
)