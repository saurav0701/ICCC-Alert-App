package com.example.iccc_alert_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

object WebSocketManager {

    private const val TAG = "WebSocketManager"
    private lateinit var context: Context
    private val eventListeners = mutableListOf<(Event) -> Unit>()

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.iccc_alert_app.NEW_EVENT") {
                val eventId = intent.getStringExtra("event_id") ?: return

                // Find the event and notify listeners
                SubscriptionManager.getSubscriptions().forEach { channel ->
                    val event = SubscriptionManager.getLastEvent(channel.id)
                    if (event?.id == eventId) {
                        eventListeners.forEach { it(event) }
                    }
                }
            }
        }
    }

    fun initialize(ctx: Context) {
        context = ctx.applicationContext

        // Register broadcast receiver
        val filter = IntentFilter("com.example.iccc_alert_app.NEW_EVENT")
        context.registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Start the WebSocket service
        WebSocketService.start(context)

        Log.d(TAG, "WebSocketManager initialized, service started")
    }

    fun updateSubscription() {
        Log.d(TAG, "====== updateSubscription() called ======")
        val subscriptions = SubscriptionManager.getSubscriptions()
        Log.d(TAG, "Current subscriptions count: ${subscriptions.size}")

        if (subscriptions.isEmpty()) {
            Log.w(TAG, "No subscriptions to send!")
        } else {
            subscriptions.forEach {
                Log.d(TAG, "  Subscription: ${it.id} -> area=${it.area}, type=${it.eventType}")
            }
        }

        // Directly call the service to update subscriptions
        Log.d(TAG, "Calling WebSocketService.updateSubscriptions()")
        WebSocketService.updateSubscriptions()
        Log.d(TAG, "========================================")
    }

    fun addEventListener(listener: (Event) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (Event) -> Unit) {
        eventListeners.remove(listener)
    }

    fun disconnect() {
        // Don't actually stop the service - it should run continuously
        // Only unregister broadcast receiver
        try {
            context.unregisterReceiver(eventReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
    }

    fun stopService() {
        // Only call this if user explicitly wants to stop receiving alerts
        WebSocketService.stop(context)
    }
}

