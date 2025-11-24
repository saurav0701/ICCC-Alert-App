package com.example.iccc_alert_app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object SubscriptionManager {

    private const val TAG = "SubscriptionManager"
    private const val PREFS_NAME = "subscriptions"
    private const val KEY_CHANNELS = "channels"
    private const val KEY_EVENTS = "events"
    private const val KEY_UNREAD = "unread"
    private const val SAVE_DELAY_MS = 500L

    private lateinit var prefs: SharedPreferences
    private lateinit var context: Context
    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val eventsCache = ConcurrentHashMap<String, MutableList<Event>>()
    private val unreadCountCache = ConcurrentHashMap<String, Int>()

    private val handler = Handler(Looper.getMainLooper())
    private val saveEventsPending = AtomicBoolean(false)
    private val saveUnreadPending = AtomicBoolean(false)

    // ✅ FIXED: Use ConcurrentHashMap.newKeySet() for thread-safe deduplication
    private val seenEventIds = ConcurrentHashMap.newKeySet<String>()

    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadEventsCache()
        loadUnreadCache()
        buildSeenEventIds()
        Log.d(TAG, "SubscriptionManager initialized with ${seenEventIds.size} known events")
    }

    private fun buildSeenEventIds() {
        seenEventIds.clear()
        eventsCache.values.forEach { events ->
            synchronized(events) {
                events.forEach { event ->
                    event.id?.let { seenEventIds.add(it) }
                }
            }
        }
    }

    fun getSubscriptions(): List<Channel> {
        val json = prefs.getString(KEY_CHANNELS, "[]")
        val type = object : TypeToken<List<Channel>>() {}.type
        return gson.fromJson(json, type)
    }

    fun subscribe(channel: Channel) {
        Log.d(TAG, "Subscribing to channel: ${channel.id}")
        val current = getSubscriptions().toMutableList()
        if (!current.any { it.id == channel.id }) {
            channel.isSubscribed = true
            current.add(channel)
            saveSubscriptions(current)
            notifyListeners()
            WebSocketManager.updateSubscription()
        }
    }

    fun unsubscribe(channelId: String) {
        Log.d(TAG, "Unsubscribing from channel: $channelId")
        val current = getSubscriptions().toMutableList()
        val removed = current.removeAll { it.id == channelId }

        if (removed) {
            saveSubscriptions(current)

            // Clear events for this channel
            eventsCache[channelId]?.let { events ->
                synchronized(events) {
                    events.forEach { event ->
                        event.id?.let { seenEventIds.remove(it) }
                    }
                }
            }
            eventsCache.remove(channelId)
            unreadCountCache.remove(channelId)

            // Clear sync state
            ChannelSyncState.clearChannel(channelId)

            scheduleSaveEventsCache()
            scheduleSaveUnreadCache()
            notifyListeners()
            WebSocketManager.updateSubscription()
        }
    }

    fun isSubscribed(channelId: String): Boolean {
        return getSubscriptions().any { it.id == channelId }
    }

    fun updateChannel(updatedChannel: Channel) {
        val current = getSubscriptions().toMutableList()
        val index = current.indexOfFirst { it.id == updatedChannel.id }

        if (index != -1) {
            current[index] = updatedChannel
            saveSubscriptions(current)
            notifyListeners()
        }
    }

    fun isChannelMuted(channelId: String): Boolean {
        return getSubscriptions().find { it.id == channelId }?.isMuted ?: false
    }

    private fun saveSubscriptions(channels: List<Channel>) {
        val json = gson.toJson(channels)
        prefs.edit().putString(KEY_CHANNELS, json).apply()
    }

    /**
     * ✅ FIXED: Proper thread-safe duplicate detection
     * Returns true if added, false if duplicate
     */
    fun addEvent(event: Event): Boolean {
        val eventId = event.id ?: return false
        val channelId = "${event.area}_${event.type}"

        // ✅ CRITICAL: Thread-safe duplicate check using Set.add()
        // Set.add() returns false if element already exists
        if (!seenEventIds.add(eventId)) {
            Log.d(TAG, "⏭️ Duplicate event $eventId, skipping")
            return false
        }

        // Get or create channel list
        val channelEvents = eventsCache.computeIfAbsent(channelId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }

        // Add to beginning (newest first)
        synchronized(channelEvents) {
            // Double-check within synchronized block
            if (channelEvents.any { it.id == eventId }) {
                Log.d(TAG, "⏭️ Duplicate in list $eventId, skipping")
                return false
            }
            channelEvents.add(0, event)

            // ✅ Limit list size to prevent memory issues
            if (channelEvents.size > 500) {
                val removed = channelEvents.removeAt(channelEvents.size - 1)
                removed.id?.let { seenEventIds.remove(it) }
            }
        }

        Log.d(TAG, "✅ Added event $eventId to $channelId (total: ${channelEvents.size})")

        // Update unread count
        unreadCountCache.compute(channelId) { _, count -> (count ?: 0) + 1 }

        // Schedule batched save
        scheduleSaveEventsCache()
        scheduleSaveUnreadCache()

        // Notify listeners on main thread
        handler.post { notifyListeners() }

        return true
    }

    private fun scheduleSaveEventsCache() {
        if (saveEventsPending.compareAndSet(false, true)) {
            handler.postDelayed({
                saveEventsPending.set(false)
                saveEventsCacheNow()
            }, SAVE_DELAY_MS)
        }
    }

    private fun scheduleSaveUnreadCache() {
        if (saveUnreadPending.compareAndSet(false, true)) {
            handler.postDelayed({
                saveUnreadPending.set(false)
                saveUnreadCacheNow()
            }, SAVE_DELAY_MS)
        }
    }

    private fun saveEventsCacheNow() {
        try {
            val snapshot = HashMap<String, List<Event>>()
            eventsCache.forEach { (key, value) ->
                synchronized(value) {
                    snapshot[key] = value.toList()
                }
            }

            val json = gson.toJson(snapshot)
            prefs.edit().putString(KEY_EVENTS, json).commit()

            val totalEvents = snapshot.values.sumOf { it.size }
            Log.d(TAG, "💾 Saved $totalEvents events across ${snapshot.size} channels")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving events cache: ${e.message}", e)
        }
    }

    private fun saveUnreadCacheNow() {
        try {
            val snapshot = HashMap(unreadCountCache)
            val json = gson.toJson(snapshot)
            prefs.edit().putString(KEY_UNREAD, json).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving unread cache: ${e.message}", e)
        }
    }

    fun getEventsForChannel(channelId: String): List<Event> {
        val events = eventsCache[channelId] ?: return emptyList()
        synchronized(events) {
            return events.toList()
        }
    }

    fun getLastEvent(channelId: String): Event? {
        val events = eventsCache[channelId] ?: return null
        synchronized(events) {
            return events.firstOrNull()
        }
    }

    fun getUnreadCount(channelId: String): Int {
        return unreadCountCache[channelId] ?: 0
    }

    fun markAsRead(channelId: String) {
        unreadCountCache[channelId] = 0
        scheduleSaveUnreadCache()
        notifyListeners()
    }

    private fun loadEventsCache() {
        try {
            val json = prefs.getString(KEY_EVENTS, "{}")
            val type = object : TypeToken<Map<String, List<Event>>>() {}.type
            val loaded: Map<String, List<Event>> = gson.fromJson(json, type)

            eventsCache.clear()
            loaded.forEach { (key, value) ->
                eventsCache[key] = java.util.Collections.synchronizedList(value.toMutableList())
            }

            val totalEvents = eventsCache.values.sumOf { it.size }
            Log.d(TAG, "Loaded ${eventsCache.size} channels with $totalEvents total events")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading events cache: ${e.message}", e)
            eventsCache.clear()
        }
    }

    private fun loadUnreadCache() {
        try {
            val json = prefs.getString(KEY_UNREAD, "{}")
            val type = object : TypeToken<Map<String, Int>>() {}.type
            val loaded: Map<String, Int> = gson.fromJson(json, type)
            unreadCountCache.clear()
            unreadCountCache.putAll(loaded)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading unread cache: ${e.message}", e)
        }
    }

    fun forceSave() {
        handler.removeCallbacksAndMessages(null)
        saveEventsCacheNow()
        saveUnreadCacheNow()
        Log.d(TAG, "Force saved all data")
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    fun getStorageStats(): Map<String, Int> {
        return eventsCache.mapValues {
            synchronized(it.value) { it.value.size }
        }
    }

    /**
     * ✅ NEW: Get count for a specific channel
     */
    fun getEventCount(channelId: String): Int {
        val events = eventsCache[channelId] ?: return 0
        synchronized(events) {
            return events.size
        }
    }

    /**
     * ✅ NEW: Get total event count across all channels
     */
    fun getTotalEventCount(): Int {
        return eventsCache.values.sumOf {
            synchronized(it) { it.size }
        }
    }
}