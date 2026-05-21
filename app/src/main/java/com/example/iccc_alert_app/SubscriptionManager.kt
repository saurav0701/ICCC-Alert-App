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
import java.util.concurrent.atomic.AtomicInteger

object SubscriptionManager {

    private const val TAG = "SubscriptionManager"
    private const val PREFS_NAME = "subscriptions"
    private const val KEY_CHANNELS = "channels"
    private const val KEY_EVENTS = "events"
    private const val KEY_UNREAD = "unread"
    private const val KEY_LAST_RUNTIME_CHECK = "last_runtime_check"
    private const val KEY_SERVICE_STARTED_AT = "service_started_at"

    // ✅ Save intervals
    private const val SAVE_DELAY_NORMAL = 1000L
    private const val SAVE_DELAY_HIGHLOAD = 2000L
    private const val SAVE_DELAY_CATCHUP = 1500L
    private const val HIGH_LOAD_THRESHOLD = 100

    // ✅ MEMORY FIX: Limit events per channel to prevent OOM
    private const val MAX_EVENTS_PER_CHANNEL = 500  // ← CRITICAL: Prevent unlimited growth
    private const val MAX_EVENT_AGE_MS = 7 * 24 * 60 * 60 * 1000L  // 7 days
    private const val CLEANUP_INTERVAL_MS = 6 * 60 * 60 * 1000L  // Every 6 hours

    private lateinit var prefs: SharedPreferences
    private lateinit var context: Context
    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val eventsCache = ConcurrentHashMap<String, MutableList<Event>>()
    private val unreadCountCache = ConcurrentHashMap<String, Int>()
    private val lastEventCache = ConcurrentHashMap<String, Event?>()

    private val handler = Handler(Looper.getMainLooper())
    private val saveEventsPending = AtomicBoolean(false)
    private val saveUnreadPending = AtomicBoolean(false)

    private val unsavedEventCount = AtomicInteger(0)
    private const val FORCE_SAVE_THRESHOLD = 50
    private const val CATCHUP_SAVE_THRESHOLD = 25

    private val recentEventIds = ConcurrentHashMap.newKeySet<String>()
    private val eventTimestamps = ConcurrentHashMap<String, Long>()
    private var inCatchUpMode = false

    private var wasAppKilled = false

    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        wasAppKilled = detectAppKillOrBackgroundClear()

        loadEventsCache()
        loadUnreadCache()

        if (wasAppKilled) {
            Log.w(TAG, "⚠️ APP WAS KILLED - Clearing recent event IDs for catch-up")
            recentEventIds.clear()
            eventTimestamps.clear()
            inCatchUpMode = true
        } else {
            buildRecentEventIds()
        }

        markServiceRunning()
        startRecentEventCleanup()
        startRuntimeChecker()
        startEventCleanup()

        Log.d(TAG, "SubscriptionManager initialized (wasKilled=$wasAppKilled, recentIds=${recentEventIds.size})")
    }

    private fun detectAppKillOrBackgroundClear(): Boolean {
        val lastRuntimeCheck = prefs.getLong(KEY_LAST_RUNTIME_CHECK, 0L)
        val serviceStartedAt = prefs.getLong(KEY_SERVICE_STARTED_AT, 0L)
        val now = System.currentTimeMillis()

        if (serviceStartedAt > 0 && lastRuntimeCheck > 0) {
            val timeSinceLastCheck = now - lastRuntimeCheck
            if (timeSinceLastCheck > 2 * 60 * 1000) {
                Log.w(TAG, "🔴 DETECTED: App was killed (gap: ${timeSinceLastCheck / 1000}s)")
                prefs.edit().remove(KEY_SERVICE_STARTED_AT).apply()
                return true
            }
        }
        return false
    }

    private fun markServiceRunning() {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_SERVICE_STARTED_AT, now)
            .putLong(KEY_LAST_RUNTIME_CHECK, now)
            .apply()
    }

    private fun startRuntimeChecker() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                prefs.edit()
                    .putLong(KEY_LAST_RUNTIME_CHECK, System.currentTimeMillis())
                    .apply()
                handler.postDelayed(this, 60 * 1000)
            }
        }, 60 * 1000)
    }

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

                handler.postDelayed(this, 60 * 1000)
            }
        }, 60 * 1000)
    }

    // ✅ MEMORY FIX: More aggressive cleanup to prevent OOM
    private fun startEventCleanup() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val cutoffTime = now - MAX_EVENT_AGE_MS
                var totalCleaned = 0
                var totalTrimmed = 0

                eventsCache.forEach { (channelId, events) ->
                    synchronized(events) {
                        val initialSize = events.size

                        // ✅ FIX #6: Never delete unread events
                        events.removeAll { event ->
                            event.timestamp < cutoffTime &&
                                    (unreadCountCache[channelId] ?: 0) <= 0
                        }

                        // ✅ MEMORY FIX: If channel has too many events, trim oldest
                        if (events.size > MAX_EVENTS_PER_CHANNEL) {
                            val toRemove = events.size - MAX_EVENTS_PER_CHANNEL
                            val removed = events.drop(toRemove)
                            for (event in removed) {
                                event.id?.let {
                                    recentEventIds.remove(it)
                                    eventTimestamps.remove(it)
                                }
                            }
                            repeat(toRemove) {
                                events.removeAt(events.lastIndex)
                            }
                            totalTrimmed += toRemove
                            Log.d(TAG, "🔪 Trimmed $toRemove oldest events from $channelId (size: ${events.size})")
                        }

                        val cleaned = initialSize - events.size
                        if (cleaned > 0) {
                            totalCleaned += cleaned
                            Log.d(TAG, "🗑️ Cleaned $cleaned old events from $channelId")
                        }
                    }
                }

                if (totalCleaned > 0 || totalTrimmed > 0) {
                    unsavedEventCount.addAndGet(totalCleaned + totalTrimmed)
                    scheduleSaveEventsCache()
                    Log.i(TAG, "🗑️ Cleanup: removed $totalCleaned old, trimmed $totalTrimmed excess events")
                }

                handler.postDelayed(this, CLEANUP_INTERVAL_MS)
            }
        }, CLEANUP_INTERVAL_MS)
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
            lastEventCache.remove(channelId)

            ChannelSyncState.clearChannel(channelId)

            unsavedEventCount.set(0)
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

    fun addEvent(event: Event): Boolean {
        val eventId = event.id ?: return false
        val channelId = "${event.area}_${event.type}"
        val timestamp = event.timestamp
        val now = System.currentTimeMillis()

        if (inCatchUpMode) {
            val channelEvents = eventsCache[channelId]
            if (channelEvents != null) {
                synchronized(channelEvents) {
                    if (channelEvents.any { it.id == eventId }) {
                        Log.d(TAG, "⏭️ CATCH-UP: Duplicate event $eventId in cache")
                        return false
                    }
                }
            }

            val lastSeenTime = eventTimestamps[eventId]
            if (lastSeenTime != null && (now - lastSeenTime) < 5 * 60 * 1000) {
                Log.d(TAG, "⏭️ CATCH-UP: Duplicate event $eventId in recent IDs")
                return false
            }
        }

        val events = eventsCache.computeIfAbsent(channelId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }

        synchronized(events) {
            if (events.any { it.id == eventId }) {
                Log.d(TAG, "⏭️ Event $eventId already in cache")
                return false
            }

            // ✅ MEMORY FIX: Don't exceed max events per channel
            if (events.size >= MAX_EVENTS_PER_CHANNEL) {
                Log.w(TAG, "⚠️ Channel $channelId at max capacity (${MAX_EVENTS_PER_CHANNEL}), removing oldest")
                val removed = events.removeAt(events.lastIndex)
                removed.id?.let {
                    recentEventIds.remove(it)
                    eventTimestamps.remove(it)
                }
            }

            events.add(0, event)
        }

        lastEventCache[channelId] = event

        recentEventIds.add(eventId)
        eventTimestamps[eventId] = timestamp

        unreadCountCache.compute(channelId) { _, count -> (count ?: 0) + 1 }

        unsavedEventCount.incrementAndGet()

        if (inCatchUpMode) {
            saveEventsCacheImmediateIfCatchUp()
        } else {
            if (unsavedEventCount.get() >= FORCE_SAVE_THRESHOLD) {
                Log.d(TAG, "⚠️ Force saving: ${unsavedEventCount.get()} unsaved events")
                saveEventsCacheNow()
                unsavedEventCount.set(0)
            } else {
                scheduleSaveEventsCache()
            }
        }

        scheduleSaveUnreadCache()
        handler.post { notifyListeners() }

        return true
    }

    fun setCatchUpMode(enabled: Boolean) {
        inCatchUpMode = enabled
        Log.d(TAG, "Catch-up mode: $enabled")
    }

    fun getDetailedStorageStats(): Map<String, Any> {
        val now = System.currentTimeMillis()
        val channelStats = mutableMapOf<String, Map<String, Any>>()
        var totalEvents = 0
        var oldestTimestamp = Long.MAX_VALUE
        var newestTimestamp = 0L

        eventsCache.forEach { (channelId, events) ->
            synchronized(events) {
                totalEvents += events.size

                val oldest = events.lastOrNull()?.timestamp ?: 0L
                val newest = events.firstOrNull()?.timestamp ?: 0L

                if (oldest < oldestTimestamp) oldestTimestamp = oldest
                if (newest > newestTimestamp) newestTimestamp = newest

                channelStats[channelId] = mapOf(
                    "count" to events.size,
                    "oldestAge" to if (oldest > 0) "${(now - oldest) / (60 * 60 * 1000)}h" else "N/A",
                    "newestAge" to if (newest > 0) "${(now - newest) / (60 * 1000)}min" else "N/A"
                )
            }
        }

        return mapOf(
            "totalEvents" to totalEvents,
            "channels" to eventsCache.size,
            "unsavedEvents" to unsavedEventCount.get(),
            "oldestEventAge" to if (oldestTimestamp < Long.MAX_VALUE) {
                "${(now - oldestTimestamp) / (24 * 60 * 60 * 1000)} days"
            } else "N/A",
            "newestEventAge" to if (newestTimestamp > 0) {
                "${(now - newestTimestamp) / (60 * 1000)} minutes"
            } else "N/A",
            "catchUpMode" to inCatchUpMode,
            "channelDetails" to channelStats
        )
    }

    private fun scheduleSaveEventsCache() {
        if (saveEventsPending.compareAndSet(false, true)) {
            val delay = when {
                inCatchUpMode -> SAVE_DELAY_CATCHUP
                unsavedEventCount.get() > HIGH_LOAD_THRESHOLD -> SAVE_DELAY_HIGHLOAD
                else -> SAVE_DELAY_NORMAL
            }

            handler.postDelayed({
                saveEventsPending.set(false)
                saveEventsCacheNow()
            }, delay)
        }
    }

    private fun scheduleSaveUnreadCache() {
        if (saveUnreadPending.compareAndSet(false, true)) {
            handler.postDelayed({
                saveUnreadPending.set(false)
                saveUnreadCacheNow()
            }, SAVE_DELAY_NORMAL)
        }
    }

    // ✅ MEMORY FIX: Better error handling for OOM
    private fun saveEventsCacheNow() {
        try {
            val snapshot = HashMap<String, List<Event>>()
            eventsCache.forEach { (key, value) ->
                synchronized(value) {
                    snapshot[key] = value.toList()
                }
            }

            Log.d(TAG, "💾 Attempting to save ${snapshot.values.sumOf { it.size }} events across ${snapshot.size} channels")

            val json = gson.toJson(snapshot)
            val success = prefs.edit().putString(KEY_EVENTS, json).commit()

            if (success) {
                val totalEvents = snapshot.values.sumOf { it.size }
                Log.d(TAG, "✅ Saved $totalEvents events across ${snapshot.size} channels")
                unsavedEventCount.set(0)
            } else {
                Log.e(TAG, "❌ Failed to save events")
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ OUT OF MEMORY while saving - triggering aggressive cleanup", e)
            // Trigger immediate cleanup
            handler.post {
                eventsCache.forEach { (channelId, events) ->
                    synchronized(events) {
                        if (events.size > 100) {
                            val toRemove = events.size - 100
                            repeat(toRemove) {
                                val removed = events.removeAt(events.lastIndex)
                                removed.id?.let {
                                    recentEventIds.remove(it)
                                    eventTimestamps.remove(it)
                                }
                            }
                            Log.w(TAG, "🔪 Emergency trimmed $toRemove events from $channelId")
                        }
                    }
                }
            }
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

    private fun saveEventsCacheImmediateIfCatchUp() {
        if (!inCatchUpMode) {
            return
        }
        val unsaved = unsavedEventCount.get()
        if (unsaved >= CATCHUP_SAVE_THRESHOLD) {
            Log.d(TAG, "⚡ CATCH-UP: Force saving $unsaved events immediately")
            saveEventsCacheNow()
            unsavedEventCount.set(0)
        }
    }

    fun getEventsForChannel(channelId: String): List<Event> {
        val events = eventsCache[channelId] ?: return emptyList()
        synchronized(events) {
            return events.toList()
        }
    }

    fun getLastEvent(channelId: String): Event? {
        val cached = lastEventCache[channelId]
        if (cached != null) {
            return cached
        }

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
        saveEventsPending.set(false)
        saveUnreadPending.set(false)

        val unsavedCount = unsavedEventCount.getAndSet(0)

        saveEventsCacheNow()
        saveUnreadCacheNow()

        prefs.edit()
            .putLong(KEY_LAST_RUNTIME_CHECK, System.currentTimeMillis())
            .apply()

        if (unsavedCount > 0) {
            Log.d(TAG, "Force saved all data (was $unsavedCount unsaved events)")
        } else {
            Log.d(TAG, "Force saved all data (no unsaved events)")
        }
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

    fun clearAllEvents() {
        eventsCache.clear()
        unreadCountCache.clear()
        lastEventCache.clear()
        recentEventIds.clear()
        eventTimestamps.clear()
        unsavedEventCount.set(0)

        handler.removeCallbacksAndMessages(null)
        saveEventsPending.set(false)
        saveUnreadPending.set(false)

        saveEventsCacheNow()
        saveUnreadCacheNow()

        Log.d(TAG, "✅ Cleared all events and caches")
        notifyListeners()
    }
}