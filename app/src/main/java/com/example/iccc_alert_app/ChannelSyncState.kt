package com.example.iccc_alert_app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap

/**
 * ✅ FINAL FIX: Properly handles out-of-order events during bulk catch-up
 *
 * Key insight: When 100-200 events arrive during catch-up, they're processed
 * by 4 parallel threads and arrive OUT OF ORDER (seq 200, 155, 180, 156).
 *
 * Old logic: if (seq > lastEventSeq) → WRONG, skips legitimate events
 * New logic: Track ALL sequences during catch-up, then switch to simple comparison
 */
object ChannelSyncState {
    private const val TAG = "ChannelSyncState"
    private const val PREFS_NAME = "channel_sync_state"
    private const val KEY_CHANNEL_STATES = "channel_states"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    // Thread-safe map: channelId -> ChannelSyncInfo
    private val syncStates = ConcurrentHashMap<String, ChannelSyncInfo>()

    // ✅ NEW: Separate tracking for catch-up vs live mode
    private val catchUpMode = ConcurrentHashMap<String, Boolean>()
    private val receivedSequences = ConcurrentHashMap<String, MutableSet<Long>>()

    data class ChannelSyncInfo(
        val channelId: String,
        var lastEventId: String? = null,
        var lastEventTimestamp: Long = 0L,
        var lastEventSeq: Long = 0L,
        var highestSeq: Long = 0L,
        var totalReceived: Long = 0L,
        var lastSyncTime: Long = System.currentTimeMillis()
    )

    private val savePending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun initialize(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadStates()
        Log.d(TAG, "ChannelSyncState initialized with ${syncStates.size} channels")
    }

    private fun loadStates() {
        try {
            val json = prefs.getString(KEY_CHANNEL_STATES, "{}")
            val type = object : TypeToken<Map<String, ChannelSyncInfo>>() {}.type
            val loaded: Map<String, ChannelSyncInfo> = gson.fromJson(json, type)
            syncStates.clear()
            loaded.forEach { (key, value) ->
                syncStates[key] = value
                Log.d(TAG, "📊 Loaded $key: lastSeq=${value.lastEventSeq}, highestSeq=${value.highestSeq}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sync states: ${e.message}")
            syncStates.clear()
        }
    }

    private fun saveStates() {
        if (savePending.compareAndSet(false, true)) {
            saveHandler.postDelayed({
                savePending.set(false)
                saveStatesNow()
            }, 500)
        }
    }

    private fun saveStatesNow() {
        try {
            val snapshot = HashMap(syncStates)
            val json = gson.toJson(snapshot)
            val success = prefs.edit()
                .putString(KEY_CHANNEL_STATES, json)
                .commit()

            if (success) {
                Log.d(TAG, "✅ Saved ${snapshot.size} channel states")
            } else {
                Log.e(TAG, "❌ Failed to commit sync states")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception saving sync states: ${e.message}", e)
        }
    }

    /**
     * ✅ CRITICAL FIX: Enable catch-up mode for a channel
     *
     * Call this BEFORE sending subscription to backend.
     * During catch-up, we track ALL sequences to handle out-of-order delivery.
     */
    fun enableCatchUpMode(channelId: String) {
        catchUpMode[channelId] = true
        receivedSequences[channelId] = java.util.Collections.synchronizedSet(mutableSetOf())
        Log.d(TAG, "🔄 Enabled catch-up mode for $channelId")
    }

    /**
     * ✅ CRITICAL FIX: Disable catch-up mode after bulk sync completes
     *
     * Call this when backend signals catch-up is complete (numPending=0).
     * Switches to efficient sequence comparison mode.
     */
    fun disableCatchUpMode(channelId: String) {
        catchUpMode[channelId] = false
        receivedSequences[channelId]?.clear()
        Log.d(TAG, "✅ Disabled catch-up mode for $channelId (switched to live mode)")
    }

    /**
     * ✅ FIXED: Records event with proper out-of-order handling
     *
     * During catch-up mode:
     *   - Uses Set to track ALL sequences (handles out-of-order)
     *   - Never skips events just because sequence is "old"
     *
     * During live mode:
     *   - Uses simple lastEventSeq comparison (efficient)
     *   - Skips truly duplicate events
     *
     * Returns: true if NEW event, false if duplicate
     */
    fun recordEventReceived(channelId: String, eventId: String, timestamp: Long, seq: Long = 0L): Boolean {
        val state = syncStates.computeIfAbsent(channelId) { ChannelSyncInfo(it) }

        val inCatchUpMode = catchUpMode[channelId] == true

        if (seq > 0) {
            if (inCatchUpMode) {
                // ✅ CATCH-UP MODE: Use Set for out-of-order handling
                val seqSet = receivedSequences[channelId]!!

                if (!seqSet.add(seq)) {
                    Log.d(TAG, "⏭️ Duplicate seq $seq for $channelId (catch-up mode)")
                    return false
                }

                Log.d(TAG, "✅ Recorded $channelId: seq=$seq (CATCH-UP, total=${seqSet.size})")

            } else {
                // ✅ LIVE MODE: Simple comparison (efficient)
                synchronized(state) {
                    if (seq <= state.highestSeq) {
                        Log.d(TAG, "⏭️ Duplicate seq $seq for $channelId (live mode, highest=${state.highestSeq})")
                        return false
                    }
                }

                Log.d(TAG, "✅ Recorded $channelId: seq=$seq (LIVE)")
            }
        }

        // Update state with highest sequence
        synchronized(state) {
            state.totalReceived++
            state.lastSyncTime = System.currentTimeMillis()

            if (seq > 0 && seq > state.highestSeq) {
                state.highestSeq = seq
                state.lastEventId = eventId
                state.lastEventTimestamp = timestamp
                state.lastEventSeq = seq
            }  else if (seq == 0L && timestamp > state.lastEventTimestamp)  {
                // No sequence, use timestamp
                state.lastEventTimestamp = timestamp
                state.lastEventId = eventId
            }
        }

        saveStates()
        return true
    }

    /**
     * ✅ NEW: Check if channel is in catch-up mode
     */
    fun isInCatchUpMode(channelId: String): Boolean {
        return catchUpMode[channelId] == true
    }

    /**
     * ✅ NEW: Get catch-up progress
     */
    fun getCatchUpProgress(channelId: String): Int {
        return receivedSequences[channelId]?.size ?: 0
    }

    fun forceSave() {
        saveHandler.removeCallbacksAndMessages(null)
        savePending.set(false)
        saveStatesNow()
        Log.d(TAG, "✅ Force saved all channel states")
    }

    fun getSyncInfo(channelId: String): ChannelSyncInfo? = syncStates[channelId]

    fun getAllSyncStates(): Map<String, ChannelSyncInfo> = HashMap(syncStates)

    fun getLastEventId(channelId: String): String? = syncStates[channelId]?.lastEventId

    fun getLastSequence(channelId: String): Long = syncStates[channelId]?.lastEventSeq ?: 0L

    fun getHighestSequence(channelId: String): Long = syncStates[channelId]?.highestSeq ?: 0L

    fun getTotalEventsReceived(): Long = syncStates.values.sumOf { it.totalReceived }

    fun clearChannel(channelId: String) {
        syncStates.remove(channelId)
        receivedSequences.remove(channelId)
        catchUpMode.remove(channelId)
        saveStates()
        Log.d(TAG, "Cleared sync state for $channelId")
    }

    /**
     * Clear all sync state - used when clearing app data
     */
    fun clearAll() {
        syncStates.clear()
        receivedSequences.clear()
        catchUpMode.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "✅ Cleared all sync state")
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "channelCount" to syncStates.size,
            "totalEvents" to getTotalEventsReceived(),
            "channels" to syncStates.map { (k, v) ->
                mapOf(
                    "channel" to k,
                    "lastEventId" to v.lastEventId,
                    "lastSeq" to v.lastEventSeq,
                    "highestSeq" to v.highestSeq,
                    "totalReceived" to v.totalReceived,
                    "catchUpMode" to (catchUpMode[k] == true),
                    "trackedSequences" to (receivedSequences[k]?.size ?: 0)
                )
            }
        )
    }
}