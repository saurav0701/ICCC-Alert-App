package com.example.iccc_alert_app

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.*

data class CameraInfo(
    @SerializedName("category") val category: String = "camera",
    @SerializedName("id") val id: String = "",
    @SerializedName("ip") val ip: String = "",
    @SerializedName("Id") val idNum: Int = 0,
    @SerializedName("device_id") val deviceId: Int = 0,
    @SerializedName("Name") val name: String = "",
    @SerializedName("name") val nameAlt: String = "",
    @SerializedName("latitude") val latitude: String = "",
    @SerializedName("longitude") val longitude: String = "",
    @SerializedName("status") val status: String = "offline",
    @SerializedName("groupId") val groupId: Int = 0,
    @SerializedName("area") private val _area: String = "",
    @SerializedName("transporter") val transporter: String = "",
    @SerializedName("location") val location: String = "",
    @SerializedName("lastUpdate") val lastUpdate: String? = null  // ✅ NULLABLE & OPTIONAL
) {

    val area: String
        get() = if (_area.isNotEmpty()) _area else getAreaNameForGroup(groupId)

    fun isOnline(): Boolean = status == "online"

    // ✅ FIXED: Handle null/empty lastUpdate gracefully - ignore if not present
    fun getLastUpdateDate(): Date? {
        // Return null immediately if lastUpdate is null or empty - don't crash
        if (lastUpdate.isNullOrEmpty()) return null

        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(lastUpdate)
        } catch (e: Exception) {
            try {
                val altFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                altFormat.parse(lastUpdate)
            } catch (e2: Exception) {
                // Silently return null instead of crashing
                null
            }
        }
    }

    fun getLastUpdateTimestamp(): Long {
        return getLastUpdateDate()?.time ?: System.currentTimeMillis()
    }

    fun getStreamURL(): String {
        val serverURL = getServerURLForGroup(groupId)
        if (serverURL.isEmpty()) {
            android.util.Log.w("CameraInfo",
                "No stream server for groupId: $groupId (${BackendConfig.getOrganization()})")
            return ""
        }
        return "$serverURL/$id/index.m3u8"
    }

    fun hasValidStreamURL(): Boolean {
        return getStreamURL().isNotEmpty()
    }

    fun getRTSPUrl(): String {
        if (ip.isEmpty()) return ""
        return "rtsp://$ip:554/stream"
    }

    // ✅ FIXED: Handle null lastUpdate in formatted display
    fun getFormattedLastUpdate(): String {
        val date = getLastUpdateDate() ?: return "Never"
        val now = System.currentTimeMillis()
        val diff = now - date.time

        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000} minutes ago"
            diff < 86400_000 -> "${diff / 3600_000} hours ago"
            else -> SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(date)
        }
    }

    companion object {
        fun getServerURLForGroup(groupId: Int): String {
            return if (BackendConfig.isCCL()) {
                when (groupId) {
                    1 -> "https://barkasayal.cclai.in/stream"
                    2 -> "https://argada.cclai.in/stream"
                    3 -> "https://nk.cclai.in/stream"
                    4 -> "https://bk.cclai.in/stream"
                    5 -> "https://kathara.cclai.in/stream"
                    6 -> "https://giridih.cclai.in/stream"
                    7 -> "https://amrapali.cclai.in/stream"
                    8 -> "https://magadh.cclai.in/stream"
                    9 -> "https://rajhara.cclai.in/stream"
                    10 -> "https://kuju.cclai.in/stream"
                    11 -> "https://hazaribagh.cclai.in/stream"
                    12 -> "https://rajrappa.cclai.in/stream"
                    13 -> "https://dhori.cclai.in/stream"
                    14 -> "https://piparwar.cclai.in/stream"
                    else -> ""
                }
            } else {
                when (groupId) {
                    5 -> "http://103.208.173.131:8888"
                    6 -> "http://103.208.173.147:8888"
                    7 -> "http://103.208.173.163:8888"
                    8 -> "http://a5va.bccliccc.in:8888"
                    9 -> "http://a5va.bccliccc.in:8888"
                    10 -> "http://a6va.bccliccc.in:8888"
                    11 -> "http://103.208.173.195:8888"
                    12 -> "http://a9va.bccliccc.in:8888"
                    13 -> "http://a10va.bccliccc.in:8888"
                    14 -> "http://103.210.88.195:8888"
                    15 -> "http://103.210.88.211:8888"
                    16 -> "http://103.208.173.179:8888"
                    22 -> "http://103.208.173.211:8888"
                    else -> ""
                }
            }
        }

        fun getAreaNameForGroup(groupId: Int): String {
            return if (BackendConfig.isCCL()) {
                when (groupId) {
                    1 -> "barka sayal"
                    2 -> "argada"
                    3 -> "north karanpura"
                    4 -> "bokaro & kargali"
                    5 -> "kathara"
                    6 -> "giridih"
                    7 -> "amrapali & chandragupta"
                    8 -> "magadh & sanghmitra"
                    9 -> "rajhara"
                    10 -> "kuju"
                    11 -> "hazaribagh"
                    12 -> "rajrappa"
                    13 -> "dhori"
                    14 -> "piparwar"
                    else -> "unknown"
                }
            } else {
                when (groupId) {
                    5 -> "barora"
                    6 -> "block2"
                    7 -> "govindpur"
                    8 -> "katras"
                    9 -> "sijua"
                    10 -> "kusunda"
                    11 -> "pb area"
                    12 -> "bastacolla"
                    13 -> "lodna"
                    14 -> "ej area"
                    15 -> "cv area"
                    16 -> "ccwo area"
                    22 -> "wj area"
                    else -> "unknown"
                }
            }
        }
    }
}

data class CameraListMessage(
    @SerializedName("cameras") val cameras: List<CameraInfo> = emptyList(),
    @SerializedName("message") val message: String? = null,
    @SerializedName("type") val type: String? = null
)