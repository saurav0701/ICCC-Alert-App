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

                Log.d(TAG, "")
                Log.d(TAG, "════════════════════════════════════════")
                Log.d(TAG, "🔍 CHECKING API FOR EXISTING COMMENTS")
                Log.d(TAG, "════════════════════════════════════════")
                Log.d(TAG, "Event ID: $eventId")
                Log.d(TAG, "Area: $area")
                Log.d(TAG, "API URL: $apiUrl")

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
                Log.d(TAG, "📤 Request body: $jsonBody")

                val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()

                response.use { resp ->
                    val responseBody = resp.body?.string()

                    Log.d(TAG, "📥 Response code: ${resp.code}")
                    Log.d(TAG, "📥 Response body length: ${responseBody?.length ?: 0}")

                    if (!resp.isSuccessful || responseBody.isNullOrEmpty()) {
                        Log.w(TAG, "❌ API request failed or empty response")
                        return@withContext null
                    }

                    if (responseBody.length > 500) {
                        Log.d(TAG, "📥 Response preview: ${responseBody.substring(0, 500)}...")
                    } else {
                        Log.d(TAG, "📥 Full response: $responseBody")
                    }

                    val jsonElement = JsonParser.parseString(responseBody)

                    val eventsArray = when {
                        jsonElement.isJsonArray -> {
                            Log.d(TAG, "✅ Response is a JSON array")
                            jsonElement.asJsonArray
                        }
                        jsonElement.isJsonObject -> {
                            val obj = jsonElement.asJsonObject
                            Log.d(TAG, "✅ Response is a JSON object with keys: ${obj.keySet()}")

                            when {
                                obj.has("data") && obj.get("data").isJsonArray -> {
                                    Log.d(TAG, "✅ Found 'data' field containing events array")
                                    obj.get("data").asJsonArray
                                }
                                obj.has("events") && obj.get("events").isJsonArray -> {
                                    Log.d(TAG, "✅ Found 'events' field containing events array")
                                    obj.get("events").asJsonArray
                                }
                                obj.has("results") && obj.get("results").isJsonArray -> {
                                    Log.d(TAG, "✅ Found 'results' field containing events array")
                                    obj.get("results").asJsonArray
                                }
                                obj.has("rows") && obj.get("rows").isJsonArray -> {
                                    Log.d(TAG, "✅ Found 'rows' field containing events array")
                                    obj.get("rows").asJsonArray
                                }
                                else -> {
                                    Log.w(TAG, "❌ Response is object but no known events array field found")
                                    Log.w(TAG, "Available keys: ${obj.keySet().joinToString()}")
                                    return@withContext null
                                }
                            }
                        }
                        else -> {
                            Log.w(TAG, "❌ Response is neither array nor object")
                            return@withContext null
                        }
                    }

                    Log.d(TAG, "📊 Found ${eventsArray.size()} events in API response")

                    if (eventsArray.size() == 0) {
                        Log.d(TAG, "✅ No events found in API")
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
                                    Log.d(TAG, "✅ Found matching event in API: $eventId")
                                    break
                                }
                            }
                        }
                    }

                    if (targetEvent == null) {
                        Log.d(TAG, "❌ Event $eventId not found in API response")
                        return@withContext null
                    }

                    Log.d(TAG, "")
                    Log.d(TAG, "════════════════════════════════════════")
                    Log.d(TAG, "📋 PARSING EVENT DATA FROM API")
                    Log.d(TAG, "════════════════════════════════════════")

                    val severity = if (targetEvent.has("severity") && !targetEvent.get("severity").isJsonNull) {
                        targetEvent.get("severity").asString
                    } else {
                        null
                    }
                    Log.d(TAG, "Priority/Severity: ${severity ?: "null"}")

                    val remarks = if (targetEvent.has("remarks") && !targetEvent.get("remarks").isJsonNull) {
                        targetEvent.get("remarks")
                    } else {
                        null
                    }
                    Log.d(TAG, "Remarks present: ${remarks != null}")
                    Log.d(TAG, "Remarks JSON: ${remarks?.toString()}")

                    val hasSeverity = severity != null &&
                            severity.isNotEmpty() &&
                            severity.lowercase() != "null"

                    val hasRemarks = remarks != null && !remarks.isJsonNull

                    Log.d(TAG, "Has severity: $hasSeverity")
                    Log.d(TAG, "Has remarks: $hasRemarks")

                    if (!hasSeverity && !hasRemarks) {
                        Log.d(TAG, "✅ No severity or remarks found - event is clean")
                        return@withContext null
                    }

                    val parsedComments = if (hasRemarks) {
                        parseRemarksFromJson(remarks)
                    } else {
                        emptyList()
                    }

                    Log.d(TAG, "Parsed ${parsedComments.size} comments from remarks")

                    if (parsedComments.isEmpty() && !hasSeverity) {
                        Log.d(TAG, "✅ Remarks exist but no parseable comments + no severity")
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

                    Log.d(TAG, "")
                    Log.d(TAG, "⚠️⚠️⚠️ EXISTING DATA FOUND ⚠️⚠️⚠️")
                    Log.d(TAG, "════════════════════════════════════════")
                    Log.d(TAG, "Priority: $severity")
                    Log.d(TAG, "Latest comment: ${latestComment.comment}")
                    Log.d(TAG, "Comment by: ${latestComment.user}")
                    Log.d(TAG, "Total comments: ${parsedComments.size}")
                    Log.d(TAG, "════════════════════════════════════════")

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
                e.printStackTrace()
                return@withContext null
            }
        }
    }

    private fun parseRemarksFromJson(remarksJson: com.google.gson.JsonElement?): List<CommentEntry> {
        if (remarksJson == null || remarksJson.isJsonNull) {
            Log.d(TAG, "❌ remarksJson is null or JsonNull")
            return emptyList()
        }

        try {
            Log.d(TAG, "🔍 Parsing remarks: ${remarksJson.javaClass.simpleName}")

            if (remarksJson.isJsonObject) {
                val obj = remarksJson.asJsonObject
                Log.d(TAG, "📦 Remarks is a JSON object with keys: ${obj.keySet()}")

                if (obj.has("comment")) {
                    val commentElement = obj.get("comment")

                    // ✅ FIX: Handle both string and non-string comment values
                    val commentStr = if (commentElement.isJsonPrimitive && commentElement.asJsonPrimitive.isString) {
                        commentElement.asString
                    } else {
                        commentElement.toString()
                    }

                    val user = if (obj.has("user")) {
                        val userElement = obj.get("user")
                        if (userElement.isJsonPrimitive && userElement.asJsonPrimitive.isString) {
                            userElement.asString
                        } else {
                            "Unknown"
                        }
                    } else {
                        "Unknown"
                    }

                    val timestamp = if (obj.has("timestamp")) {
                        val tsElement = obj.get("timestamp")
                        if (tsElement.isJsonPrimitive && tsElement.asJsonPrimitive.isString) {
                            tsElement.asString
                        } else {
                            System.currentTimeMillis().toString()
                        }
                    } else {
                        System.currentTimeMillis().toString()
                    }

                    Log.d(TAG, "✅ Found comment: user=$user, comment=$commentStr, timestamp=$timestamp")

                    if (commentStr.startsWith("REMARKS_HISTORY:")) {
                        Log.d(TAG, "🔄 Comment contains REMARKS_HISTORY, parsing nested structure")
                        return parseRemarksHistory(commentStr)
                    }

                    if (commentStr.startsWith("EVENT_CLOSED_BY_USER:")) {
                        Log.d(TAG, "🔒 Comment contains EVENT_CLOSED_BY_USER, parsing nested structure")
                        return parseEventClosedByUser(commentStr)
                    }

                    Log.d(TAG, "✅ Returning simple comment")
                    return listOf(CommentEntry(user, commentStr, timestamp))
                } else {
                    Log.w(TAG, "⚠️ Remarks object exists but has no 'comment' field. Keys: ${obj.keySet()}")
                }
            }

            if (remarksJson.isJsonPrimitive && remarksJson.asJsonPrimitive.isString) {
                val remarksStr = remarksJson.asString
                Log.d(TAG, "📝 Remarks is a string: $remarksStr")

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

            if (remarksJson.isJsonArray) {
                Log.d(TAG, "📚 Remarks is an array")
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

            Log.w(TAG, "⚠️ Unknown remarks format: ${remarksJson.javaClass.simpleName}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing remarks: ${e.message}", e)
            e.printStackTrace()
        }

        return emptyList()
    }

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
            "Lodna" -> "http://a10va.bccliccc.in:5050"
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