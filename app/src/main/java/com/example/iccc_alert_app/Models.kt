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

object ClientIdManager {
    private const val PREFS_NAME = "iccc_alert_prefs"
    private const val KEY_CLIENT_ID = "client_id"

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

    fun getCurrentClientId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CLIENT_ID, null)
    }

    fun resetClientId(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldId = prefs.getString(KEY_CLIENT_ID, null)
        prefs.edit().remove(KEY_CLIENT_ID).apply()
        android.util.Log.d("ClientIdManager", "⚠️ Client ID reset (old ID: $oldId)")
    }

    fun hasClientId(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_CLIENT_ID)
    }
}

object AvailableChannels {

    // BCCL Areas (original - 13 areas)
    private val bcclAreas = listOf(
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

    // CCL Areas (new - 14 areas)
    private val cclAreas = listOf(
        "barkasayal" to "Barka Sayal",
        "argada" to "Argada",
        "northkaranpura" to "North Karanpura",
        "bokarokargali" to "Bokaro & Kargali",
        "kathara" to "Kathara",
        "giridih" to "Giridih",
        "amrapali" to "Amrapali & Chandragupta",
        "magadh" to "Magadh & Sanghmitra",
        "rajhara" to "Rajhara",
        "kuju" to "Kuju",
        "hazaribagh" to "Hazaribagh",
        "rajrappa" to "Rajrappa",
        "dhori" to "Dhori",
        "piparwar" to "Piparwar"
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

    fun getAreas(): List<Pair<String, String>> {
        return if (BackendConfig.isCCL()) {
            cclAreas
        } else {
            bcclAreas
        }
    }

    fun getAllChannels(): List<Channel> {
        val currentAreas = getAreas()
        val channels = mutableListOf<Channel>()

        for ((area, areaDisplay) in currentAreas) {
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

        android.util.Log.d("AvailableChannels",
            "Generated ${channels.size} channels for ${BackendConfig.getOrganization()} " +
                    "(${currentAreas.size} areas × ${eventTypes.size} event types)")

        return channels
    }

    fun getBCCLChannels(): List<Channel> {
        val channels = mutableListOf<Channel>()
        for ((area, areaDisplay) in bcclAreas) {
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

    fun getCCLChannels(): List<Channel> {
        val channels = mutableListOf<Channel>()
        for ((area, areaDisplay) in cclAreas) {
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

    fun getOrganizationInfo(): String {
        val org = BackendConfig.getOrganization()
        val areaCount = getAreas().size
        val channelCount = getAllChannels().size
        return "$org - $areaCount areas, $channelCount channels"
    }

    fun isValidArea(area: String): Boolean {
        return getAreas().any { it.first == area }
    }

    fun getAreaDisplayName(area: String): String? {
        return getAreas().find { it.first == area }?.second
    }

    fun isBCCLArea(area: String): Boolean {
        return bcclAreas.any { it.first == area }
    }

    fun isCCLArea(area: String): Boolean {
        return cclAreas.any { it.first == area }
    }

    fun getAreaCodes(): List<String> {
        return getAreas().map { it.first }
    }

    fun getAreaDisplayNames(): List<String> {
        return getAreas().map { it.second }
    }

    fun getEventTypeDisplayName(eventType: String): String? {
        return eventTypes.find { it.first == eventType }?.second
    }

    fun isValidEventType(eventType: String): Boolean {
        return eventTypes.any { it.first == eventType }
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "organization" to BackendConfig.getOrganization(),
            "bcclAreas" to bcclAreas.size,
            "cclAreas" to cclAreas.size,
            "currentAreas" to getAreas().size,
            "eventTypes" to eventTypes.size,
            "totalChannels" to getAllChannels().size,
            "isCCL" to BackendConfig.isCCL(),
            "isBCCL" to BackendConfig.isBCCL()
        )
    }
}