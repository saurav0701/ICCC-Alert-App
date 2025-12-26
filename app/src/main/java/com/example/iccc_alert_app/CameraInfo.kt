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
    @SerializedName("area") val area: String = "",
    @SerializedName("transporter") val transporter: String = "",
    @SerializedName("location") val location: String = "",
    @SerializedName("lastUpdate") val lastUpdate: String = ""
) {

    fun isOnline(): Boolean = status == "online"

    fun getLastUpdateDate(): Date? {
        if (lastUpdate.isEmpty()) return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(lastUpdate)
        } catch (e: Exception) {
            null
        }
    }

    fun getLastUpdateTimestamp(): Long {
        return getLastUpdateDate()?.time ?: System.currentTimeMillis()
    }

    fun getStreamURL(): String {
        val serverURL = getServerURLForGroup(groupId)
        if (serverURL.isEmpty()) {
            android.util.Log.w("CameraInfo", "No server URL configured for groupId: $groupId (camera: $name)")
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
            return when (groupId) {
                5 -> "http://103.208.173.131:8888"
                6 -> "http://103.208.173.147:8888"
                7 -> "http://103.208.173.163:8888"
                8 -> "http://a5va.bccliccc.in:8888"  // KATRAS
                9 -> "http://a5va.bccliccc.in:8888"  // SIJUA
                10 -> "http://a6va.bccliccc.in:8888" // KUSUNDA
                11 -> "http://103.208.173.195:8888"  // PB Area
                12 -> "http://a9va.bccliccc.in:8888" // BASTACOLLA
                13 -> "http://a10va.bccliccc.in:8888" // LODNA
                14 -> "http://103.210.88.195:8888"   // EJ Area
                15 -> "http://103.210.88.211:8888"   // CV Area
                16 -> "http://103.208.173.179:8888"  // CCWO Area
                22 -> "http://103.208.173.211:8888"  // WJ Area
                else -> ""
            }
        }

        fun getAreaNameForGroup(groupId: Int): String {
            return when (groupId) {
                5 -> "BARORA"
                6 -> "BLOCK2"
                7 -> "GOVINDPUR"
                8 -> "KATRAS"
                9 -> "SIJUA"
                10 -> "KUSUNDA"
                11 -> "PB Area"
                12 -> "BASTACOLLA"
                13 -> "LODNA"
                14 -> "EJ Area"
                15 -> "CV Area"
                16 -> "CCWO Area"
                22 -> "WJ Area"
                else -> "Unknown"
            }
        }
    }
}

data class CameraListMessage(
    @SerializedName("cameras") val cameras: List<CameraInfo> = emptyList(),
    @SerializedName("message") val message: String? = null,
    @SerializedName("type") val type: String? = null
)