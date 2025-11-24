package com.example.iccc_alert_app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object SavedMessagesManager {
    private const val TAG = "SavedMessagesManager"
    private const val PREFS_NAME = "saved_messages_prefs"
    private const val KEY_SAVED_MESSAGES = "saved_messages"

    private val gson = Gson()
    private lateinit var prefs: SharedPreferences
    private val savedMessages = mutableListOf<SavedMessage>()

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

    fun saveMessage(eventId: String, event: Event, priority: Priority, comment: String): Boolean {
        // Check if already saved
        if (isMessageSaved(eventId)) {
            return false
        }

        val savedMessage = SavedMessage(
            eventId = eventId,
            event = event,
            priority = priority,
            comment = comment,
            savedTimestamp = System.currentTimeMillis()
        )

        savedMessages.add(0, savedMessage) // Add to beginning
        saveToDisk()

        Log.d(TAG, "Saved message: $eventId with priority ${priority.displayName}")
        return true
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
            Log.d(TAG, "Deleted message: $eventId")
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
        Log.d(TAG, "Updated message: $eventId")
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

    /**
     * Clear all saved messages - used when clearing app data
     */
    fun clearAll() {
        savedMessages.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "✅ Cleared all saved messages")
    }
}