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
import android.annotation.SuppressLint
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import android.Manifest
import java.text.SimpleDateFormat
import java.util.*

// ACK message to send back to server
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

        private var instance: WebSocketService? = null

        fun start(context: Context) {
            val intent = Intent(context, WebSocketService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebSocketService::class.java))
        }

        fun updateSubscriptions() {
            instance?.sendSubscriptionV2()
        }

        @SuppressLint("HardwareIds")
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

    // Event processing
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

    // ACK batching system
    private val pendingAcks = ConcurrentLinkedQueue<String>()
    private val ackBatchSize = 10
    private val ackBatchDelayMs = 100L
    private var ackBatchJob: Job? = null

    // Catch-up monitoring
    private var catchUpMonitorRunnable: Runnable? = null
    private val catchUpCheckInterval = 5000L
    private val stableEmptyThreshold = 3
    private val consecutiveEmptyChecks = ConcurrentHashMap<String, Int>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        instance = this
        deviceId = getDeviceId(this)
        Log.d(TAG, "✅ Using persistent client ID: $deviceId")

        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ICCCAlertApp::WebSocketWakeLock")
        wakeLock.acquire()

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
        if (webSocket != null) return

        Log.d(TAG, "Connecting with persistent client ID: $deviceId")
        updateNotification("Connecting...")

        val request = Request.Builder().url(WS_URL).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket connected")
                isConnected = true
                reconnectAttempts = 0

                handler.post {
                    updateNotification("Connected - Monitoring alerts")
                    sendSubscriptionV2()
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
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                this@WebSocketService.webSocket = null
                stopPingPong()
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                isConnected = false
                this@WebSocketService.webSocket = null
                stopPingPong()
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
        } else {
            Log.e(TAG, "❌ Failed to send ACK, re-queuing ${acks.size} events")
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
                if (requireAck) sendAck(event.id)
                return@withContext
            }

            val addedSuccessfully = SubscriptionManager.addEvent(event)

            if (addedSuccessfully) {
                processedCount.incrementAndGet()

                withContext(Dispatchers.Main) {
                    if (SettingsActivity.areNotificationsEnabled(this@WebSocketService)) {
                        sendAlertNotification(event)
                    }
                    sendBroadcast(Intent("com.example.iccc_alert_app.NEW_EVENT").putExtra("event_id", event.id))
                }
            } else {
                droppedCount.incrementAndGet()
            }

            if (requireAck) {
                sendAck(event.id)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception processing: ${e.message}", e)
            errorCount.incrementAndGet()
        }
    }

    private fun logEventStats() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isConnected) {
                    Log.i(TAG, """
                        📊 STATS: received=${receivedCount.get()}, queued=${eventQueue.size}, 
                        processed=${processedCount.get()}, acked=${ackedCount.get()}, 
                        dropped=${droppedCount.get()}, errors=${errorCount.get()}, 
                        pendingAcks=${pendingAcks.size}
                    """.trimIndent())
                    handler.postDelayed(this, 10000)
                }
            }
        }, 10000)
    }

    fun sendSubscriptionV2() {
        if (!isConnected || webSocket == null) return

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

        if (resetConsumers) {
            Log.w(TAG, """
            ⚠️ RESET MODE ACTIVE:
            - No sync state found (fresh start or cleared data)
            - Server will DELETE all old durable consumers for this client
            - Server will CREATE NEW consumers from current position
            - You will receive current/recent events as a new subscriber
        """.trimIndent())
        } else {
            Log.i(TAG, """
            ✅ RESUME MODE:
            - Sync state exists (${syncState.size} channels)
            - Server will RESUME existing durable consumers
            - You will receive missed events since last connection
        """.trimIndent())
        }

        if (webSocket?.send(json) == true) {
            Log.d(TAG, "✅ Subscription sent (reset=$resetConsumers)")
            startCatchUpMonitoring()
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

                subscriptions.forEach { sub ->
                    val channelId = "${sub.area}_${sub.eventType}"

                    if (ChannelSyncState.isInCatchUpMode(channelId)) {
                        val progress = ChannelSyncState.getCatchUpProgress(channelId)

                        if (progress > 0 && eventQueue.isEmpty() && isProcessing.get() == 0) {
                            val count = (consecutiveEmptyChecks[channelId] ?: 0) + 1
                            consecutiveEmptyChecks[channelId] = count

                            if (count >= stableEmptyThreshold) {
                                ChannelSyncState.disableCatchUpMode(channelId)
                                consecutiveEmptyChecks.remove(channelId)
                                Log.i(TAG, "✅ Catch-up complete for $channelId ($progress events)")
                            } else {
                                allComplete = false
                            }
                        } else {
                            consecutiveEmptyChecks[channelId] = 0
                            allComplete = false
                        }
                    }
                }

                if (allComplete) {
                    Log.i(TAG, "🎉 ALL CHANNELS CAUGHT UP")
                    consecutiveEmptyChecks.clear()
                    stopCatchUpMonitoring()
                } else {
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
            return
        }

        reconnectAttempts++
        val delay = RECONNECT_DELAY * reconnectAttempts.coerceAtMost(12)
        handler.postDelayed({ connect() }, delay)
    }

    private fun sendAlertNotification(event: Event) {
        if (event.id == null || event.areaDisplay == null || event.typeDisplay == null) return

        val channelId = "${event.area}_${event.type}"

        // ✅ CRITICAL CHECKS: Only notify if subscribed AND not muted
        if (!SubscriptionManager.isSubscribed(channelId)) {
            Log.d(TAG, "Skipping notification: Not subscribed to $channelId")
            return
        }

        if (SubscriptionManager.isChannelMuted(channelId)) {
            Log.d(TAG, "Skipping notification: Channel $channelId is muted")
            return
        }

        // ✅ Check if notifications are enabled in settings
        if (!SettingsActivity.areNotificationsEnabled(this)) {
            Log.d(TAG, "Skipping notification: Notifications disabled in settings")
            return
        }

        // ✅ Check notification permission (Android 13+)
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
            Log.d(TAG, "✅ Notification sent for event ${event.id} ($channelId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopPingPong()
        stopCatchUpMonitoring()

        runBlocking {
            flushAcks()
        }

        serviceScope.cancel()
        processingJobs.clear()

        webSocket?.close(1000, "Service stopped")
        webSocket = null

        if (wakeLock.isHeld) wakeLock.release()

        ChannelSyncState.forceSave()
        SubscriptionManager.forceSave()

        Log.i(TAG, """
            📊 FINAL: received=${receivedCount.get()}, processed=${processedCount.get()}, 
            acked=${ackedCount.get()}, dropped=${droppedCount.get()}, errors=${errorCount.get()}
        """.trimIndent())

        val restartIntent = Intent(applicationContext, WebSocketService::class.java)
        val pi = PendingIntent.getService(applicationContext, 1, restartIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pi)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }
}