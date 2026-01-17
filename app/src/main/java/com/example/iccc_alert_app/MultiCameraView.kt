package com.example.iccc_alert_app

import com.google.gson.annotations.SerializedName
import java.util.*

/**
 * ✅ Multi-Camera View - Store up to 4 cameras for quad view
 * Matches iOS implementation approach
 */
data class MultiCameraView(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("name") var name: String,
    @SerializedName("cameraIds") var cameraIds: List<String>, // Max 4 cameras
    @SerializedName("createdAt") val createdAt: Long = System.currentTimeMillis()
) {
    // Ensure max 4 cameras
    init {
        cameraIds = cameraIds.take(4)
    }

    fun getCameras(): List<CameraInfo> {
        return cameraIds.mapNotNull { id ->
            CameraManager.getCameraById(id)
        }
    }

    fun getOnlineCameras(): List<CameraInfo> {
        return getCameras().filter { it.isOnline() }
    }

    fun getTotalCount(): Int = cameraIds.size
    fun getOnlineCount(): Int = getOnlineCameras().size
}