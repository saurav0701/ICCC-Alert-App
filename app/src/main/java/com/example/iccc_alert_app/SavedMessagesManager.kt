package com.example.iccc_alert_app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object SavedMessagesManager {
    private const val TAG = "SavedMessagesManager"
    private const val PREFS_NAME = "saved_messages_prefs"
    private const val KEY_SAVED_MESSAGES = "saved_messages"

    private val gson = Gson()
    private lateinit var prefs: SharedPreferences
    private val savedMessages = mutableListOf<SavedMessage>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSavedMessages()
        Log.d(TAG, "SavedMessagesManager initialized with ${savedMessages.size} messages")
    }

    private fun loadSavedMessages() {
        try {
            val json = prefs.getString(KEY_SAVED_MESSAGES, "[]")
            val type = object : TypeToken<List<SavedMessage>>() {}.type
            val loaded: List<SavedMessage> = gson.fromJson(json, type)
            savedMessages.clear()
            savedMessages.addAll(loaded)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved messages: ${e.message}", e)
            savedMessages.clear()
        }
    }

    private fun saveToDisk() {
        try {
            val json = gson.toJson(savedMessages)
            prefs.edit().putString(KEY_SAVED_MESSAGES, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving messages: ${e.message}", e)
        }
    }

    /**
     * Check if event already has priority/comments set
     */
    fun getEventComments(eventId: String): SavedMessage? {
        return savedMessages.find { it.eventId == eventId }
    }

    /**
     * Save message with API sync
     * Returns: Pair<Boolean, String?> - (success, errorMessage)
     */
    fun saveMessage(
        eventId: String,
        event: Event,
        priority: Priority,
        comment: String
    ): Pair<Boolean, String?> {
        // Check if already saved
        if (isMessageSaved(eventId)) {
            return Pair(false, "Event already saved")
        }

        val savedMessage = SavedMessage(
            eventId = eventId,
            event = event,
            priority = priority,
            comment = comment,
            savedTimestamp = System.currentTimeMillis(),
            isSyncedWithApi = false
        )

        savedMessages.add(0, savedMessage)
        saveToDisk()

        Log.d(TAG, "✓ Saved message: $eventId with priority ${priority.displayName}")
        return Pair(true, null)
    }

    /**
     * Update existing saved message with new priority and comments
     * Can also sync with API
     */
    suspend fun updateSavedMessage(
        eventId: String,
        newPriority: Priority,
        newComment: String,
        shouldSyncWithApi: Boolean = false,
        event: Event? = null
    ): Pair<Boolean, String?> {
        return withContext(Dispatchers.Default) {
            try {
                val message = savedMessages.find { it.eventId == eventId }
                    ?: return@withContext Pair(false, "Event not found in saved messages")

                val index = savedMessages.indexOf(message)

                // Update local message
                val updatedMessage = message.copy(
                    priority = newPriority,
                    comment = newComment,
                    savedTimestamp = System.currentTimeMillis(),
                    isSyncedWithApi = false
                )

                savedMessages[index] = updatedMessage
                saveToDisk()

                Log.d(TAG, "✓ Updated saved message: $eventId")

                // Sync with API if needed
                if (shouldSyncWithApi && event != null) {
                    return@withContext syncWithApi(eventId, event, newPriority, newComment)
                }

                return@withContext Pair(true, null)

            } catch (e: Exception) {
                Log.e(TAG, "Error updating saved message: ${e.message}", e)
                return@withContext Pair(false, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Sync saved message with backend API
     */
    private suspend fun syncWithApi(
        eventId: String,
        event: Event,
        priority: Priority,
        comment: String
    ): Pair<Boolean, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val area = event.area ?: return@withContext Pair(false, "Event area not found")
                val baseUrl = getHttpUrlForArea(area)

                val requestBody = EventUpdateRequest(
                    rowData = mapOf("eventId" to eventId),
                    severity = priority.displayName,
                    remarks = mapOf(
                        "user" to "mobile-app",
                        "comment" to comment
                    )
                )

                val json = gson.toJson(requestBody)
                val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url("$baseUrl/api/va/event")
                    .put(body)
                    .build()

                Log.d(TAG, "🔄 Syncing with API: $baseUrl/api/va/event")

                val response = client.newCall(request).execute()

                response.use {
                    if (it.isSuccessful) {
                        // Update sync status
                        val message = savedMessages.find { msg -> msg.eventId == eventId }
                        val index = savedMessages.indexOf(message)
                        if (index >= 0) {
                            savedMessages[index] = message!!.copy(isSyncedWithApi = true)
                            saveToDisk()
                        }

                        Log.d(TAG, "✅ Successfully synced with API: $eventId")
                        return@withContext Pair(true, null)
                    } else {
                        val errorBody = it.body?.string() ?: "Unknown error"
                        Log.e(TAG, "API sync failed: ${it.code} - $errorBody")
                        return@withContext Pair(false, "API sync failed: ${it.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing with API: ${e.message}", e)
                return@withContext Pair(false, "Sync error: ${e.message}")
            }
        }
    }

    fun isMessageSaved(eventId: String): Boolean {
        return savedMessages.any { it.eventId == eventId }
    }

    fun getSavedMessages(): List<SavedMessage> {
        return savedMessages.toList()
    }

    fun getSavedMessage(eventId: String): SavedMessage? {
        return savedMessages.find { it.eventId == eventId }
    }

    fun deleteMessage(eventId: String): Boolean {
        val removed = savedMessages.removeAll { it.eventId == eventId }
        if (removed) {
            saveToDisk()
            Log.d(TAG, "✓ Deleted message: $eventId")
        }
        return removed
    }

    fun updateMessage(eventId: String, priority: Priority, comment: String): Boolean {
        val message = savedMessages.find { it.eventId == eventId } ?: return false

        val index = savedMessages.indexOf(message)
        savedMessages[index] = message.copy(
            priority = priority,
            comment = comment,
            savedTimestamp = System.currentTimeMillis()
        )

        saveToDisk()
        Log.d(TAG, "✓ Updated message: $eventId")
        return true
    }

    fun getMessagesByPriority(priority: Priority): List<SavedMessage> {
        return savedMessages.filter { it.priority == priority }
    }

    fun searchMessages(query: String): List<SavedMessage> {
        val lowerQuery = query.lowercase()
        return savedMessages.filter { savedMessage ->
            val event = savedMessage.event
            val location = event.data["location"] as? String ?: ""
            val eventType = event.typeDisplay ?: ""
            val comment = savedMessage.comment

            location.lowercase().contains(lowerQuery) ||
                    eventType.lowercase().contains(lowerQuery) ||
                    comment.lowercase().contains(lowerQuery)
        }
    }

    fun getMessageCount(): Int {
        return savedMessages.size
    }

    fun clearAll() {
        savedMessages.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "✅ Cleared all saved messages")
    }

    fun markAsSynced(eventId: String): Boolean {
        val message = savedMessages.find { it.eventId == eventId } ?: return false
        val index = savedMessages.indexOf(message)

        savedMessages[index] = message.copy(isSyncedWithApi = true)
        saveToDisk()

        Log.d(TAG, "✓ Marked message as synced: $eventId")
        return true
    }

    private fun getHttpUrlForArea(area: String): String {
        val normalizedArea = area.lowercase().replace(" ", "").replace("_", "")

        if (BackendConfig.isCCL()) {
            return when (normalizedArea) {
                "barkasayal" -> "https://barkasayal.cclai.in/api"
                "argada" -> "https://argada.cclai.in/api"
                "northkaranpura" -> "https://nk.cclai.in/api"
                "bokarokargali" -> "https://bk.cclai.in/api"
                "kathara" -> "https://kathara.cclai.in/api"
                "giridih" -> "https://giridih.cclai.in/api"
                "amrapali" -> "https://amrapali.cclai.in/api"
                "magadh" -> "https://magadh.cclai.in/api"
                "rajhara" -> "https://rajhara.cclai.in/api"
                "kuju" -> "https://kuju.cclai.in/api"
                "hazaribagh" -> "https://hazaribagh.cclai.in/api"
                "rajrappa" -> "https://rajrappa.cclai.in/api"
                "dhori" -> "https://dhori.cclai.in/api"
                "piparwar" -> "https://piparwar.cclai.in/api"
                else -> "https://barkasayal.cclai.in"
            }
        }

        return when (normalizedArea) {
            "sijua", "katras" -> "http://a5va.bccliccc.in:10050"
            "kusunda" -> "http://a6va.bccliccc.in:5050"
            "bastacolla" -> "http://a9va.bccliccc.in:5050"
            "lodna" -> "http://a10va.bccliccc.in:5050"
            "govindpur" -> "http://103.208.173.163:5050"
            "barora" -> "http://103.208.173.131:5050"
            "block2" -> "http://103.208.173.147:5050"
            "pbarea" -> "http://103.208.173.195:5050"
            "wjarea" -> "http://103.208.173.211:5050"
            "ccwo" -> "http://103.208.173.179:5050"
            "cvarea" -> "http://103.210.88.211:5050"
            "ej" -> "http://103.210.88.194:5050"
            else -> "http://a5va.bccliccc.in:10050"
        }
    }
}

data class EventUpdateRequest(
    val rowData: Map<String, String>,
    val severity: String? = null,
    val remarks: Map<String, String>? = null
)