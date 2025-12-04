package com.example.iccc_alert_app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import java.util.concurrent.TimeUnit
import android.os.IBinder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import android.Manifest
import java.text.SimpleDateFormat
import java.util.*

data class AckMessage(
    @SerializedName("type") val type: String,
    @SerializedName("eventId") val eventId: String? = null,
    @SerializedName("eventIds") val eventIds: List<String>? = null,
    @SerializedName("clientId") val clientId: String
)

class WebSocketService : Service() {

    companion object {
        private const val TAG = "WebSocketService"
        private const val WS_URL = "ws://202.140.131.90:2222/ws"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "iccc_alerts_service"
        private const val RECONNECT_DELAY = 5000L
        private const val PING_INTERVAL = 30000L

        private val instanceLock = Any()
        private var instance: WebSocketService? = null
        private val isServiceRunning = AtomicBoolean(false)

        fun start(context: Context) {
            synchronized(instanceLock) {
                if (isServiceRunning.get()) {
                    Log.w(TAG, "Service already running, ignoring duplicate start")
                    PersistentLogger.logServiceLifecycle("START_IGNORED", "Service already running")
                    return
                }

                val intent = Intent(context, WebSocketService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            synchronized(instanceLock) {
                context.stopService(Intent(context, WebSocketService::class.java))
                isServiceRunning.set(false)
                PersistentLogger.logServiceLifecycle("STOPPED", "Service stopped by user")
            }
        }

        fun updateSubscriptions() {
            instance?.sendSubscriptionV2()
        }

        fun getDeviceId(context: Context): String = ClientIdManager.getOrCreateClientId(context)
    }

    private var webSocket: WebSocket? = null
    private lateinit var client: OkHttpClient
    private val gson = Gson()
    private var isConnected = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = Int.MAX_VALUE

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var vibrator: Vibrator
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pingRunnable: Runnable? = null
    private lateinit var deviceId: String

    private val eventQueue = ConcurrentLinkedQueue<String>()
    private val receivedCount = AtomicInteger(0)
    private val processedCount = AtomicInteger(0)
    private val droppedCount = AtomicInteger(0)
    private val errorCount = AtomicInteger(0)
    private val ackedCount = AtomicInteger(0)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processingJobs = mutableListOf<Job>()
    private val isProcessing = AtomicInteger(0)
    private val maxConcurrentProcessors = 4

    private val pendingAcks = ConcurrentLinkedQueue<String>()
    private val ackBatchSize = 10
    private var ackBatchJob: Job? = null

    private var catchUpMonitorRunnable: Runnable? = null
    private val catchUpCheckInterval = 5000L
    private val stableEmptyThreshold = 3
    private val consecutiveEmptyChecks = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private var hasSubscribed = AtomicBoolean(false)
    private var lastSubscriptionTime = 0L

    // ✅ Track connection state changes
    private var lastConnectionState = false
    private var connectionStateChangeTime = 0L

    override fun onCreate() {
        super.onCreate()

        synchronized(instanceLock) {
            if (isServiceRunning.get()) {
                Log.w(TAG, "Service already running, stopping duplicate")
                PersistentLogger.logServiceLifecycle("CREATE_DUPLICATE", "Stopping duplicate instance")
                stopSelf()
                return
            }

            isServiceRunning.set(true)
            instance = this
        }

        Log.d(TAG, "Service created")
        PersistentLogger.logServiceLifecycle("CREATED", "Service onCreate called")

        deviceId = getDeviceId(this)
        Log.d(TAG, "✅ Using persistent client ID: $deviceId")
        PersistentLogger.logEvent("SYSTEM", "Client ID: $deviceId")

        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // ✅ Check if device is in doze mode at startup
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isDozing = powerManager.isDeviceIdleMode
            val isIgnoringBatteryOpt = powerManager.isIgnoringBatteryOptimizations(packageName)

            PersistentLogger.logEvent("SYSTEM",
                "Startup state - Dozing: $isDozing, Battery opt exemption: $isIgnoringBatteryOpt")

            if (isDozing) {
                PersistentLogger.logDozeMode(true, "Service starting in doze mode!")
            }
        }

        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ICCCAlertApp::WebSocketWakeLock")

        try {
            if (!wakeLock.isHeld) {
                wakeLock.acquire()
                PersistentLogger.logServiceLifecycle("WAKELOCK", "Acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
            PersistentLogger.logError("WAKELOCK", "Failed to acquire", e)
        }

        ChannelSyncState.initialize(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
        startEventProcessors()
        startAckFlusher()
        connect()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ICCC Alert Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps connection alive for real-time alerts"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ICCC Alert Service")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notifications)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(status))
    }

    private fun connect() {
        if (webSocket != null) {
            Log.w(TAG, "WebSocket already exists, ignoring connect")
            PersistentLogger.logConnection("CONNECT_IGNORED", "WebSocket already exists")
            return
        }

        Log.d(TAG, "Connecting with persistent client ID: $deviceId")
        PersistentLogger.logConnection("CONNECTING", "Attempt ${reconnectAttempts + 1}")
        updateNotification("Connecting...")

        val request = Request.Builder().url(WS_URL).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket connected")
                isConnected = true
                reconnectAttempts = 0
                hasSubscribed.set(false)

                // ✅ Log connection state change
                val now = System.currentTimeMillis()
                val downtime = if (connectionStateChangeTime > 0) (now - connectionStateChangeTime) / 1000 else 0
                PersistentLogger.logConnection("CONNECTED", "Downtime: ${downtime}s")
                lastConnectionState = true
                connectionStateChangeTime = now

                handler.post {
                    updateNotification("Connected - Monitoring alerts")

                    handler.postDelayed({
                        if (isConnected && !hasSubscribed.get()) {
                            sendSubscriptionV2()
                        }
                    }, 100)

                    startPingPong()
                    logEventStats()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                receivedCount.incrementAndGet()
                eventQueue.offer(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                hasSubscribed.set(false)
                PersistentLogger.logConnection("CLOSING", "Code: $code, Reason: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                hasSubscribed.set(false)
                this@WebSocketService.webSocket = null
                stopPingPong()

                // ✅ Log disconnection
                val now = System.currentTimeMillis()
                PersistentLogger.logConnection("CLOSED", "Code: $code, Reason: $reason")
                lastConnectionState = false
                connectionStateChangeTime = now

                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                isConnected = false
                hasSubscribed.set(false)
                this@WebSocketService.webSocket = null
                stopPingPong()

                // ✅ Log failure with details
                val now = System.currentTimeMillis()
                PersistentLogger.logError("CONNECTION", "WebSocket failure: ${t.message}", t)
                lastConnectionState = false
                connectionStateChangeTime = now

                handler.post {
                    updateNotification("Disconnected - Reconnecting...")
                    scheduleReconnect()
                }
            }
        })
    }

    private fun startEventProcessors() {
        repeat(maxConcurrentProcessors) { id ->
            processingJobs.add(serviceScope.launch { processEventsLoop(id) })
        }
        Log.d(TAG, "✅ Started $maxConcurrentProcessors event processors")
        PersistentLogger.logServiceLifecycle("PROCESSORS", "Started $maxConcurrentProcessors processors")
    }

    private fun startAckFlusher() {
        serviceScope.launch {
            while (isActive) {
                delay(200)
                if (pendingAcks.isNotEmpty()) {
                    flushAcks()
                }
            }
        }
    }

    private fun sendAck(eventId: String) {
        pendingAcks.offer(eventId)
        if (pendingAcks.size >= ackBatchSize) {
            serviceScope.launch { flushAcks() }
        }
    }

    private fun flushAcks() {
        if (!isConnected || webSocket == null) return

        val acks = mutableListOf<String>()
        while (pendingAcks.isNotEmpty() && acks.size < 50) {
            pendingAcks.poll()?.let { acks.add(it) }
        }

        if (acks.isEmpty()) return

        val ackMsg = if (acks.size == 1) {
            AckMessage(type = "ack", eventId = acks[0], clientId = deviceId)
        } else {
            AckMessage(type = "batch_ack", eventIds = acks, clientId = deviceId)
        }

        val json = gson.toJson(ackMsg)
        val sent = webSocket?.send(json) ?: false

        if (sent) {
            ackedCount.addAndGet(acks.size)
            Log.d(TAG, "✅ Sent ACK for ${acks.size} events (total: ${ackedCount.get()})")

            // ✅ Log periodic ACK stats
            if (ackedCount.get() % 100 == 0) {
                PersistentLogger.logEvent("ACK", "Total ACKs sent: ${ackedCount.get()}")
            }
        } else {
            Log.e(TAG, "❌ Failed to send ACK, re-queuing ${acks.size} events")
            PersistentLogger.logError("ACK", "Failed to send ${acks.size} ACKs")
            acks.forEach { pendingAcks.offer(it) }
        }
    }

    private suspend fun processEventsLoop(processorId: Int) {
        while (serviceScope.isActive) {
            try {
                val text = eventQueue.poll()
                if (text != null) {
                    isProcessing.incrementAndGet()
                    try {
                        processEventAsync(text)
                    } finally {
                        isProcessing.decrementAndGet()
                    }
                } else {
                    delay(10)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Processor $processorId error: ${e.message}", e)
                PersistentLogger.logError("PROCESSOR_$processorId", "Processing error", e)
                errorCount.incrementAndGet()
            }
        }
    }

    private suspend fun processEventAsync(text: String) = withContext(Dispatchers.Default) {
        try {
            if (text.contains("\"status\":\"subscribed\"")) return@withContext
            if (text.contains("\"error\"")) {
                errorCount.incrementAndGet()
                return@withContext
            }

            val event = try {
                gson.fromJson(text, Event::class.java)
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                PersistentLogger.logError("PARSE", "Failed to parse event", e)
                return@withContext
            }

            if (event?.id == null || event.area == null || event.type == null) {
                droppedCount.incrementAndGet()
                return@withContext
            }

            val channelId = "${event.area}_${event.type}"
            val requireAck = event.data["_requireAck"] as? Boolean ?: true

            if (!SubscriptionManager.isSubscribed(channelId)) {
                droppedCount.incrementAndGet()
                if (requireAck) sendAck(event.id)
                return@withContext
            }

            val sequence = try {
                when (val seqValue = event.data["_seq"]) {
                    is Number -> seqValue.toLong()
                    is String -> seqValue.toLongOrNull() ?: 0L
                    else -> 0L
                }
            } catch (e: Exception) { 0L }

            val isNew = ChannelSyncState.recordEventReceived(
                channelId = channelId,
                eventId = event.id,
                timestamp = event.timestamp,
                seq = sequence
            )

            if (!isNew && sequence > 0) {
                droppedCount.incrementAndGet()
                PersistentLogger.logEventProcessing("DUPLICATE", event.id, "seq=$sequence, channel=$channelId")
                if (requireAck) sendAck(event.id)
                return@withContext
            }

            val addedSuccessfully = SubscriptionManager.addEvent(event)

            if (addedSuccessfully) {
                processedCount.incrementAndGet()
                PersistentLogger.logEventProcessing("PROCESSED", event.id, "seq=$sequence, channel=$channelId")

                withContext(Dispatchers.Main) {
                    if (SettingsActivity.areNotificationsEnabled(this@WebSocketService)) {
                        sendAlertNotification(event)
                    }
                    sendBroadcast(Intent("com.example.iccc_alert_app.NEW_EVENT").putExtra("event_id", event.id))
                }
            } else {
                droppedCount.incrementAndGet()
                PersistentLogger.logEventProcessing("DROPPED", event.id, "Already in cache")
            }

            if (requireAck) {
                sendAck(event.id)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception processing: ${e.message}", e)
            PersistentLogger.logError("PROCESS", "Exception processing event", e)
            errorCount.incrementAndGet()
        }
    }

    private fun logEventStats() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isConnected) {
                    val stats = """
                        received=${receivedCount.get()}, queued=${eventQueue.size}, 
                        processed=${processedCount.get()}, acked=${ackedCount.get()}, 
                        dropped=${droppedCount.get()}, errors=${errorCount.get()}, 
                        pendingAcks=${pendingAcks.size}
                    """.trimIndent()

                    Log.i(TAG, "📊 STATS: $stats")

                    // ✅ Log detailed stats every 10 minutes
                    if (processedCount.get() % 100 == 0) {
                        PersistentLogger.logEvent("STATS", stats)
                    }

                    handler.postDelayed(this, 10000)
                }
            }
        }, 10000)
    }

    fun sendSubscriptionV2() {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "Cannot subscribe - not connected")
            PersistentLogger.logEvent("SUBSCRIPTION", "Failed - not connected")
            return
        }

        val now = System.currentTimeMillis()
        if (hasSubscribed.get() && (now - lastSubscriptionTime) < 5000) {
            Log.w(TAG, "Skipping duplicate subscription (sent ${now - lastSubscriptionTime}ms ago)")
            PersistentLogger.logEvent("SUBSCRIPTION", "Skipped - duplicate within 5s")
            return
        }

        val subscriptions = SubscriptionManager.getSubscriptions()
        if (subscriptions.isEmpty()) return

        val filters = subscriptions.map { SubscriptionFilter(area = it.area, eventType = it.eventType) }

        subscriptions.forEach { sub ->
            ChannelSyncState.enableCatchUpMode("${sub.area}_${sub.eventType}")
        }

        var hasSyncState = false
        val syncState = mutableMapOf<String, SyncStateInfo>()

        subscriptions.forEach { sub ->
            val channelId = "${sub.area}_${sub.eventType}"
            ChannelSyncState.getSyncInfo(channelId)?.let { info ->
                hasSyncState = true
                syncState[channelId] = SyncStateInfo(
                    lastEventId = info.lastEventId,
                    lastTimestamp = info.lastEventTimestamp,
                    lastSeq = info.highestSeq
                )
            }
        }

        val resetConsumers = !hasSyncState

        val request = SubscriptionRequestV2(
            clientId = deviceId,
            filters = filters,
            syncState = if (syncState.isNotEmpty()) syncState else null,
            resetConsumers = resetConsumers
        )

        val json = gson.toJson(request)
        Log.d(TAG, "📤 Subscription: $json")

        // ✅ Log subscription details
        PersistentLogger.logEvent("SUBSCRIPTION",
            "Mode: ${if (resetConsumers) "RESET" else "RESUME"}, Channels: ${filters.size}")

        if (resetConsumers) {
            Log.w(TAG, "⚠️ RESET MODE: Will delete old consumers and start fresh")
            PersistentLogger.logEvent("SUBSCRIPTION", "RESET MODE - deleting old consumers")
        } else {
            Log.i(TAG, "✅ RESUME MODE: Will resume from last known sequences")
            PersistentLogger.logEvent("SUBSCRIPTION", "RESUME MODE - ${syncState.size} channels with state")
        }

        if (webSocket?.send(json) == true) {
            hasSubscribed.set(true)
            lastSubscriptionTime = now
            Log.d(TAG, "✅ Subscription sent (reset=$resetConsumers)")
            PersistentLogger.logEvent("SUBSCRIPTION", "Sent successfully")
            startCatchUpMonitoring()
        } else {
            PersistentLogger.logError("SUBSCRIPTION", "Failed to send")
        }
    }

    private fun startCatchUpMonitoring() {
        stopCatchUpMonitoring()

        catchUpMonitorRunnable = object : Runnable {
            override fun run() {
                if (!isConnected) {
                    stopCatchUpMonitoring()
                    return
                }

                val subscriptions = SubscriptionManager.getSubscriptions()
                var allComplete = true
                var activeCatchUps = 0

                subscriptions.forEach { sub ->
                    val channelId = "${sub.area}_${sub.eventType}"

                    if (ChannelSyncState.isInCatchUpMode(channelId)) {
                        activeCatchUps++
                        val progress = ChannelSyncState.getCatchUpProgress(channelId)

                        if (progress > 0 && eventQueue.isEmpty() && isProcessing.get() == 0) {
                            val count = (consecutiveEmptyChecks[channelId] ?: 0) + 1
                            consecutiveEmptyChecks[channelId] = count

                            if (count >= stableEmptyThreshold) {
                                ChannelSyncState.disableCatchUpMode(channelId)
                                consecutiveEmptyChecks.remove(channelId)
                                Log.i(TAG, "✅ Catch-up complete for $channelId ($progress events)")
                                PersistentLogger.logCatchUp("Complete: $channelId - $progress events")
                            } else {
                                allComplete = false
                            }
                        } else {
                            consecutiveEmptyChecks[channelId] = 0
                            allComplete = false
                        }
                    }
                }

                if (activeCatchUps > 0 && allComplete) {
                    Log.i(TAG, "🎉 ALL CHANNELS CAUGHT UP")
                    PersistentLogger.logCatchUp("ALL COMPLETE - ${subscriptions.size} channels")
                    consecutiveEmptyChecks.clear()
                    stopCatchUpMonitoring()
                } else if (activeCatchUps > 0) {
                    handler.postDelayed(this, catchUpCheckInterval)
                }
            }
        }

        handler.postDelayed(catchUpMonitorRunnable!!, catchUpCheckInterval)
    }

    private fun stopCatchUpMonitoring() {
        catchUpMonitorRunnable?.let { handler.removeCallbacks(it) }
        catchUpMonitorRunnable = null
    }

    private fun startPingPong() {
        stopPingPong()
        pingRunnable = object : Runnable {
            override fun run() {
                if (isConnected) handler.postDelayed(this, PING_INTERVAL)
            }
        }
        handler.postDelayed(pingRunnable!!, PING_INTERVAL)
    }

    private fun stopPingPong() {
        pingRunnable?.let { handler.removeCallbacks(it) }
        pingRunnable = null
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            updateNotification("Connection failed - Tap to retry")
            PersistentLogger.logConnection("RECONNECT_FAILED", "Max attempts reached")
            return
        }

        reconnectAttempts++
        val delay = RECONNECT_DELAY * reconnectAttempts.coerceAtMost(12)
        PersistentLogger.logConnection("RECONNECT_SCHEDULED", "Attempt $reconnectAttempts in ${delay}ms")
        handler.postDelayed({ connect() }, delay)
    }

    private fun sendAlertNotification(event: Event) {
        if (event.id == null || event.areaDisplay == null || event.typeDisplay == null) return

        val channelId = "${event.area}_${event.type}"

        if (!SubscriptionManager.isSubscribed(channelId)) {
            Log.d(TAG, "Skipping notification: Not subscribed to $channelId")
            return
        }

        if (SubscriptionManager.isChannelMuted(channelId)) {
            Log.d(TAG, "Skipping notification: Channel $channelId is muted")
            return
        }

        if (!SettingsActivity.areNotificationsEnabled(this)) {
            Log.d(TAG, "Skipping notification: Notifications disabled in settings")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Notification permission not granted, cannot send notification")
                return
            }
        }

        val notificationChannelId = "iccc_alerts"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            if (nm.getNotificationChannel(notificationChannelId) == null) {
                val channel = NotificationChannel(
                    notificationChannelId,
                    "ICCC Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Critical security and operational alerts"
                    enableVibration(SettingsActivity.isVibrationEnabled(this@WebSocketService))
                    enableLights(true)
                    lightColor = android.graphics.Color.RED
                    if (SettingsActivity.isVibrationEnabled(this@WebSocketService)) {
                        vibrationPattern = longArrayOf(0, 300, 200, 300)
                    }
                    setShowBadge(true)
                }
                nm.createNotificationChannel(channel)
            }
        }

        if (SettingsActivity.isVibrationEnabled(this)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vibration error: ${e.message}")
            }
        }

        val intent = Intent(this, ChannelDetailActivity::class.java).apply {
            putExtra("CHANNEL_ID", channelId)
            putExtra("CHANNEL_AREA", event.areaDisplay)
            putExtra("CHANNEL_TYPE", event.typeDisplay)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            event.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val location = event.data["location"] as? String ?: "Unknown location"
        val eventTime = try {
            val eventTimeStr = event.data["eventTime"] as? String
            if (eventTimeStr != null) {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(eventTimeStr)
            } else {
                Date(event.timestamp * 1000)
            }
        } catch (e: Exception) {
            Date(event.timestamp * 1000)
        }

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        val notification = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("${event.areaDisplay} - ${event.typeDisplay}")
            .setContentText(location)
            .setSubText(timeFormat.format(eventTime))
            .setSmallIcon(R.drawable.ic_notifications)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            getSystemService(NotificationManager::class.java).notify(event.id.hashCode(), notification)
            Log.d(TAG, "✅ Notification sent successfully for event ${event.id} ($channelId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification: ${e.message}")
        }
        PersistentLogger.logEvent("NOTIFICATION", "Sent for ${event.id} - ${event.areaDisplay}/${event.typeDisplay}")
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PersistentLogger.logServiceLifecycle("START_COMMAND", "flags=$flags, startId=$startId")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        synchronized(instanceLock) {
            instance = null
            isServiceRunning.set(false)
        }

        stopPingPong()
        stopCatchUpMonitoring()

        runBlocking {
            flushAcks()
        }

        serviceScope.cancel()
        processingJobs.clear()

        webSocket?.close(1000, "Service stopped")
        webSocket = null

        if (wakeLock.isHeld) {
            try {
                wakeLock.release()
                PersistentLogger.logServiceLifecycle("WAKELOCK", "Released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing wake lock: ${e.message}")
                PersistentLogger.logError("WAKELOCK", "Failed to release", e)
            }
        }

        ChannelSyncState.forceSave()
        SubscriptionManager.forceSave()

        val finalStats = """
            received=${receivedCount.get()}, processed=${processedCount.get()}, 
            acked=${ackedCount.get()}, dropped=${droppedCount.get()}, errors=${errorCount.get()}
        """.trimIndent()

        Log.i(TAG, "📊 FINAL: $finalStats")
        PersistentLogger.logServiceLifecycle("DESTROYED", finalStats)
        PersistentLogger.flush()

        val restartIntent = Intent(applicationContext, WebSocketService::class.java)
        val pi = PendingIntent.getService(applicationContext, 1, restartIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pi)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        PersistentLogger.logServiceLifecycle("TASK_REMOVED", "App removed from recent apps")
    }
}