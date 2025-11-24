package com.example.iccc_alert_app

import android.content.Context
import com.google.gson.annotations.SerializedName
import java.util.UUID

data class Event(
    @SerializedName("id") val id: String? = null,
    @SerializedName("timestamp") val timestamp: Long = 0L,
    @SerializedName("source") val source: String? = null,
    @SerializedName("area") val area: String? = null,
    @SerializedName("areaDisplay") val areaDisplay: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("typeDisplay") val typeDisplay: String? = null,
    @SerializedName("groupId") val groupId: String? = null,
    @SerializedName("vehicleNumber") val vehicleNumber: String? = null,
    @SerializedName("vehicleTransporter") val vehicleTransporter: String? = null,
    @SerializedName("data") val data: Map<String, Any> = emptyMap()
)

data class Channel(
    val id: String,
    val area: String,
    val areaDisplay: String,
    val eventType: String,
    val eventTypeDisplay: String,
    val description: String,
    var isSubscribed: Boolean = false,
    var isMuted: Boolean = false,
    var isPinned: Boolean = false
)

// ✅ Subscription filter for specific area + eventType pair
data class SubscriptionFilter(
    @SerializedName("area") val area: String,
    @SerializedName("eventType") val eventType: String
)

data class SubscriptionRequestV2(
    @SerializedName("clientId") val clientId: String,
    @SerializedName("filters") val filters: List<SubscriptionFilter>,
    @SerializedName("syncState") val syncState: Map<String, SyncStateInfo>? = null,
    @SerializedName("resetConsumers") val resetConsumers: Boolean = false
)

data class SyncStateInfo(
    @SerializedName("lastEventId") val lastEventId: String?,
    @SerializedName("lastTimestamp") val lastTimestamp: Long,
    @SerializedName("lastSeq") val lastSeq: Long
)

// ✅ ClientID Manager - generates and stores persistent client ID
object ClientIdManager {
    private const val PREFS_NAME = "iccc_alert_prefs"
    private const val KEY_CLIENT_ID = "client_id"

    /**
     * Gets or creates a persistent client ID for this device.
     * The ID is stored in SharedPreferences and will survive:
     * - App restarts
     * - Network reconnections
     * - Device reboots
     *
     * Format: android-{androidId}-{randomUuid}
     * Example: android-b1217ba45902f0e5-a3f4b2c1
     *
     * This ID is used by JetStream to identify durable consumers,
     * allowing the server to resume delivery of missed messages.
     */
    fun getOrCreateClientId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check if we already have a client ID
        var clientId = prefs.getString(KEY_CLIENT_ID, null)

        if (clientId == null) {
            // Generate new stable client ID
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"

            // Use short UUID for uniqueness
            val uuid = UUID.randomUUID().toString().substring(0, 8)

            // ✅ CRITICAL: NO timestamp - this must be stable across reconnections
            clientId = "android-$androidId-$uuid"

            // Save it permanently
            prefs.edit().putString(KEY_CLIENT_ID, clientId).apply()

            android.util.Log.d("ClientIdManager", "✅ Created new persistent client ID: $clientId")
        } else {
            android.util.Log.d("ClientIdManager", "✅ Using existing persistent client ID: $clientId")
        }

        return clientId
    }

    /**
     * Gets the current client ID without creating a new one.
     * Returns null if no client ID exists yet.
     */
    fun getCurrentClientId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CLIENT_ID, null)
    }

    /**
     * Resets the client ID - useful for:
     * - Debugging
     * - User logout
     * - Testing different scenarios
     *
     * WARNING: This will create NEW durable consumers on next connection,
     * and old durable consumers will be abandoned (but not deleted from NATS).
     */
    fun resetClientId(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldId = prefs.getString(KEY_CLIENT_ID, null)
        prefs.edit().remove(KEY_CLIENT_ID).apply()
        android.util.Log.d("ClientIdManager", "⚠️ Client ID reset (old ID: $oldId)")
    }

    /**
     * Check if a client ID already exists
     */
    fun hasClientId(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_CLIENT_ID)
    }
}

object AvailableChannels {
    val areas = listOf(
        "sijua" to "Sijua",
        "kusunda" to "Kusunda",
        "bastacolla" to "Bastacolla",
        "lodna" to "Lodna",
        "govindpur" to "Govindpur",
        "barora" to "Barora",
        "ccwo" to "CCWO",
        "ej" to "EJ",
        "cvarea" to "CV Area",
        "wjarea" to "WJ Area",
        "pbarea" to "PB Area",
        "block2" to "Block 2",
        "katras" to "Katras"
    )

    val eventTypes = listOf(
        "cd" to "Crowd Detection",
        "vd" to "Vehicle Detection",
        "pd" to "Person Detection",
        "id" to "Intrusion Detection",
        "vc" to "Vehicle Congestion",
        "ls" to "Loading Status",
        "us" to "Unloading Status",
        "ct" to "Camera Tampering",
        "sh" to "Safety Hazard",
        "ii" to "Insufficient Illumination",
        "off-route" to "Off-Route Alert",
        "tamper" to "Tamper Alert"
    )

    fun getAllChannels(): List<Channel> {
        val channels = mutableListOf<Channel>()
        for ((area, areaDisplay) in areas) {
            for ((eventType, eventTypeDisplay) in eventTypes) {
                channels.add(
                    Channel(
                        id = "${area}_${eventType}",
                        area = area,
                        areaDisplay = areaDisplay,
                        eventType = eventType,
                        eventTypeDisplay = eventTypeDisplay,
                        description = "$areaDisplay - $eventTypeDisplay"
                    )
                )
            }
        }
        return channels
    }
}