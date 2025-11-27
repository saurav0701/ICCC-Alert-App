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
    private const val KEY_LAST_APP_KILL_CHECK = "last_app_kill_check"
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

    // ✅ FIXED: Smart duplicate detection
    // Only use this for RECENT events (last 5 minutes) to avoid immediate duplicates
    // Don't use it to block catch-up events after app kill
    private val recentEventIds = ConcurrentHashMap.newKeySet<String>()
    private val eventTimestamps = ConcurrentHashMap<String, Long>()

    private var wasAppKilled = false

    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // ✅ Check if app was killed (low battery, etc.)
        wasAppKilled = detectAppKill()

        loadEventsCache()
        loadUnreadCache()

        if (wasAppKilled) {
            Log.w(TAG, """
                ⚠️ APP KILL DETECTED:
                - App was killed while running (low battery? system kill?)
                - Clearing recent event IDs to allow catch-up
                - Old events kept in cache for display
                - Server will re-deliver missed events
            """.trimIndent())

            // Don't build seenEventIds from old cache
            // This allows catch-up events to be added
            recentEventIds.clear()
            eventTimestamps.clear()

            // Mark that we've handled this kill
            prefs.edit().putLong(KEY_LAST_APP_KILL_CHECK, System.currentTimeMillis()).apply()
        } else {
            // Normal startup - build recent IDs from cache
            buildRecentEventIds()
        }

        // Start cleanup job for old event IDs
        startRecentEventCleanup()

        Log.d(TAG, "SubscriptionManager initialized (wasKilled=$wasAppKilled, recentIds=${recentEventIds.size})")
    }

    /**
     * Detect if app was killed unexpectedly
     */
    private fun detectAppKill(): Boolean {
        val lastCheck = prefs.getLong(KEY_LAST_APP_KILL_CHECK, 0L)
        val now = System.currentTimeMillis()
        val timeSinceLastCheck = now - lastCheck

        // If it's been more than 10 minutes since last check,
        // assume app was killed (normal app usage has checks every minute)
        if (lastCheck > 0 && timeSinceLastCheck > 10 * 60 * 1000) {
            return true
        }

        return false
    }

    /**
     * Build recent event IDs only from very recent events (last 5 minutes)
     * This prevents immediate duplicates but allows catch-up
     */
    private fun buildRecentEventIds() {
        recentEventIds.clear()
        eventTimestamps.clear()

        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        var recentCount = 0

        eventsCache.values.forEach { events ->
            synchronized(events) {
                events.forEach { event ->
                    if (event.timestamp > fiveMinutesAgo) {
                        event.id?.let {
                            recentEventIds.add(it)
                            eventTimestamps[it] = event.timestamp
                            recentCount++
                        }
                    }
                }
            }
        }

        Log.d(TAG, "Built recent event IDs: $recentCount events from last 5 minutes")
    }

    /**
     * Periodically clean up old event IDs from memory
     */
    private fun startRecentEventCleanup() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
                val iterator = eventTimestamps.entries.iterator()
                var cleaned = 0

                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.value < fiveMinutesAgo) {
                        recentEventIds.remove(entry.key)
                        iterator.remove()
                        cleaned++
                    }
                }

                if (cleaned > 0) {
                    Log.d(TAG, "Cleaned $cleaned old event IDs from memory")
                }

                // Update app alive timestamp
                prefs.edit().putLong(KEY_LAST_APP_KILL_CHECK, System.currentTimeMillis()).apply()

                handler.postDelayed(this, 60 * 1000) // Every minute
            }
        }, 60 * 1000)
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
                        event.id?.let {
                            recentEventIds.remove(it)
                            eventTimestamps.remove(it)
                        }
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
     * ✅ FIXED: Smart duplicate detection
     * - Check if event already EXISTS in cache (real duplicate)
     * - OR if event ID was seen in last 5 minutes (immediate duplicate)
     * - Otherwise ALLOW it (catch-up after app kill)
     */
    fun addEvent(event: Event): Boolean {
        val eventId = event.id ?: return false
        val channelId = "${event.area}_${event.type}"
        val timestamp = event.timestamp

        // ✅ First check: Is this event already in our cache?
        val channelEvents = eventsCache[channelId]
        if (channelEvents != null) {
            synchronized(channelEvents) {
                if (channelEvents.any { it.id == eventId }) {
                    Log.d(TAG, "⏭️ Event $eventId already in cache, true duplicate")
                    return false
                }
            }
        }

        // ✅ Second check: Did we see this ID very recently? (last 5 min)
        val lastSeenTime = eventTimestamps[eventId]
        if (lastSeenTime != null) {
            val timeSinceLastSeen = System.currentTimeMillis() - lastSeenTime
            if (timeSinceLastSeen < 5 * 60 * 1000) {
                Log.d(TAG, "⏭️ Event $eventId seen ${timeSinceLastSeen / 1000}s ago, recent duplicate")
                return false
            }
        }

        // ✅ Not a duplicate - add it!
        val events = eventsCache.computeIfAbsent(channelId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }

        synchronized(events) {
            events.add(0, event)

            if (events.size > 500) {
                val removed = events.removeAt(events.size - 1)
                removed.id?.let {
                    recentEventIds.remove(it)
                    eventTimestamps.remove(it)
                }
            }
        }

        // Track this event ID
        recentEventIds.add(eventId)
        eventTimestamps[eventId] = timestamp

        Log.d(TAG, "✅ Added event $eventId to $channelId (timestamp: ${java.util.Date(timestamp)}, total: ${events.size})")

        // Update unread count
        unreadCountCache.compute(channelId) { _, count -> (count ?: 0) + 1 }

        // Schedule batched save
        scheduleSaveEventsCache()
        scheduleSaveUnreadCache()

        // Notify listeners on main thread
        handler.post { notifyListeners() }

        return true
    }

    // ... rest of the existing methods remain the same ...

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

    fun getEventCount(channelId: String): Int {
        val events = eventsCache[channelId] ?: return 0
        synchronized(events) {
            return events.size
        }
    }

    fun getTotalEventCount(): Int {
        return eventsCache.values.sumOf {
            synchronized(it) { it.size }
        }
    }
}