package com.example.iccc_alert_app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class SyncInfo(
    var lastEventId: String? = null,
    var lastEventTimestamp: Long = 0L,
    var highestSeq: Long = 0L,
    var lowestOutOfOrderSeq: Long = Long.MAX_VALUE,
    var totalEvents: Long = 0L,
    var lastSavedSeq: Long = 0L
)

object ChannelSyncState {
    private const val TAG = "ChannelSyncState"
    private const val PREFS_NAME = "channel_sync_state"
    private const val KEY_SYNC_STATES = "sync_states"
    private const val SAVE_DELAY_MS = 1000L

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private val syncStates = ConcurrentHashMap<String, SyncInfo>()
    private val recentEventIds = ConcurrentHashMap<String, Long>()

    private val handler = Handler(Looper.getMainLooper())
    private val savePending = AtomicBoolean(false)

    private val catchUpChannels = ConcurrentHashMap.newKeySet<String>()
    private val channelStuckSince = ConcurrentHashMap<String, Long>()

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSyncStates()
        startRecentEventCleanup()
        Log.d(TAG, "ChannelSyncState initialized with ${syncStates.size} channels")
    }

    private fun loadSyncStates() {
        try {
            val json = prefs.getString(KEY_SYNC_STATES, "{}")
            val type = object : TypeToken<Map<String, SyncInfo>>() {}.type
            val loaded: Map<String, SyncInfo> = gson.fromJson(json, type)
            syncStates.clear()
            syncStates.putAll(loaded)

            val totalSeq = syncStates.values.sumOf { it.highestSeq }
            Log.d(TAG, "Loaded sync state for ${syncStates.size} channels (total events: $totalSeq)")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading sync states: ${e.message}", e)
        }
    }

    fun enableCatchUpMode(channelId: String) {
        catchUpChannels.add(channelId)
        channelStuckSince[channelId] = System.currentTimeMillis()
        Log.d(TAG, "🔄 Catch-up mode enabled for $channelId")
        SubscriptionManager.setCatchUpMode(true)
    }

    fun disableCatchUpMode(channelId: String) {
        catchUpChannels.remove(channelId)
        channelStuckSince.remove(channelId)

        if (catchUpChannels.isEmpty()) {
            Log.d(TAG, "✅ All channels caught up - switching to live mode")
            SubscriptionManager.setCatchUpMode(false)
        } else {
            Log.d(TAG, "✅ $channelId caught up - ${catchUpChannels.size} channels still catching up")
        }
    }

    fun isInCatchUpMode(channelId: String): Boolean {
        return catchUpChannels.contains(channelId)
    }

    // ✅ FIXED: Removed out-of-order rejection that was causing 60% event loss
    fun recordEventReceived(
        channelId: String,
        eventId: String,
        timestamp: Long,
        seq: Long
    ): Boolean {
        // Check for recent duplicates (websocket message delivered twice)
        val lastSeen = recentEventIds[eventId]
        if (lastSeen != null && (System.currentTimeMillis() - lastSeen) < 30000) {
            Log.d(TAG, "⏭️ Duplicate event $eventId (seen ${System.currentTimeMillis() - lastSeen}ms ago)")
            return false
        }
        recentEventIds[eventId] = System.currentTimeMillis()

        val info = syncStates.computeIfAbsent(channelId) { SyncInfo() }

        synchronized(info) {
            info.totalEvents++
            info.lastEventId = eventId
            info.lastEventTimestamp = timestamp

            if (seq > 0) {
                // ✅ FIXED: Only update if it's a NEW higher sequence
                // Accept ALL other sequences (including out-of-order, which is normal)
                if (seq > info.highestSeq) {
                    info.highestSeq = seq
                    info.lastSavedSeq = seq
                    Log.d(TAG, "✅ Updated $channelId: seq=$seq (total events: ${info.totalEvents})")
                } else {
                    // ✅ IMPORTANT: Out-of-order events are NORMAL and VALID
                    // Don't reject them - accept and continue processing
                    // The deduplication at SubscriptionManager level will handle real duplicates
                    Log.d(TAG, "⏭️ Accepted out-of-order/duplicate sequence $seq for $channelId (current: ${info.highestSeq})")
                }
            }
        }

        scheduleSave()
        return true  // ✅ Accept ALL events - let SubscriptionManager handle dedup
    }

    fun getSyncInfo(channelId: String): SyncInfo? {
        return syncStates[channelId]?.let {
            synchronized(it) { it.copy() }
        }
    }

    fun getLastEventId(channelId: String): String? {
        return syncStates[channelId]?.lastEventId
    }

    fun getHighestSeq(channelId: String): Long {
        return syncStates[channelId]?.highestSeq ?: 0L
    }

    fun clearChannel(channelId: String) {
        syncStates.remove(channelId)
        catchUpChannels.remove(channelId)
        channelStuckSince.remove(channelId)
        scheduleSave()
        Log.d(TAG, "Cleared sync state for $channelId")
    }

    fun clearAll() {
        syncStates.clear()
        catchUpChannels.clear()
        recentEventIds.clear()
        channelStuckSince.clear()

        val cleared = prefs.edit()
            .remove(KEY_SYNC_STATES)
            .commit()

        if (cleared) {
            Log.d(TAG, "✅ Cleared all channel sync states")
        }
    }

    private fun startRecentEventCleanup() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val oneMinuteAgo = System.currentTimeMillis() - 60000
                val iterator = recentEventIds.entries.iterator()
                var cleaned = 0

                while (iterator.hasNext()) {
                    if (iterator.next().value < oneMinuteAgo) {
                        iterator.remove()
                        cleaned++
                    }
                }

                if (cleaned > 0) {
                    Log.d(TAG, "Cleaned $cleaned old event IDs")
                }

                handler.postDelayed(this, 60000)
            }
        }, 60000)
    }

    private fun scheduleSave() {
        if (savePending.compareAndSet(false, true)) {
            handler.postDelayed({
                savePending.set(false)
                saveNow()
            }, SAVE_DELAY_MS)
        }
    }

    private fun saveNow() {
        try {
            val snapshot = HashMap(syncStates)
            val json = gson.toJson(snapshot)
            val success = prefs.edit().putString(KEY_SYNC_STATES, json).commit()

            if (success) {
                Log.d(TAG, "✅ Saved sync state for ${snapshot.size} channels")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving sync states: ${e.message}", e)
        }
    }

    fun forceSave() {
        handler.removeCallbacksAndMessages(null)
        savePending.set(false)
        saveNow()

        Log.d(TAG, "✅ Force saved all channel states")
    }

    fun getStats(): Map<String, Any> {
        val totalEvents = syncStates.values.sumOf { it.totalEvents }
        val maxSeq = syncStates.values.maxOfOrNull { it.highestSeq } ?: 0L

        return mapOf(
            "totalChannels" to syncStates.size,
            "inCatchUp" to catchUpChannels.size,
            "totalEventsReceived" to totalEvents,
            "maxSequence" to maxSeq,
            "recentEventIds" to recentEventIds.size
        )
    }

    fun getStuckChannels(): List<Pair<String, Long>> {
        val now = System.currentTimeMillis()
        return channelStuckSince.mapNotNull { (channelId, since) ->
            val duration = now - since
            if (duration > 30000) {
                Pair(channelId, duration)
            } else null
        }
    }
}