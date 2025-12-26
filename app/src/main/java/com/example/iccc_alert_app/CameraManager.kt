package com.example.iccc_alert_app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Optimized CameraManager - Stores full camera list, only updates status
 *
 * Strategy:
 * 1. First message with cameras: Store complete list permanently
 * 2. Subsequent messages: Only update status field
 * 3. Areas dropdown: Always show all areas from stored list
 */
object CameraManager {
    private const val TAG = "CameraManager"
    private const val PREFS_NAME = "camera_manager"
    private const val KEY_CAMERAS = "cameras"
    private const val KEY_HAS_INITIAL_DATA = "has_initial_data"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    // In-memory cache - NEVER cleared after first load
    private val camerasCache = ConcurrentHashMap<String, CameraInfo>() // id -> CameraInfo
    private val areaGroupCache = ConcurrentHashMap<String, MutableList<CameraInfo>>() // area -> cameras

    private var hasInitialData = false

    // Listeners
    private val listeners = CopyOnWriteArrayList<(List<CameraInfo>) -> Unit>()

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        hasInitialData = prefs.getBoolean(KEY_HAS_INITIAL_DATA, false)

        if (hasInitialData) {
            loadCameras()
            Log.d(TAG, "✅ CameraManager initialized with ${camerasCache.size} cameras from storage")
        } else {
            Log.d(TAG, "⏳ CameraManager waiting for initial camera data")
        }

        PersistentLogger.logEvent("CAMERA", "Initialized - hasData: $hasInitialData, cached: ${camerasCache.size}")
    }

    /**
     * Handle camera list message from WebSocket
     * - First message: Store complete camera list permanently
     * - Subsequent messages: Only update status fields
     */
    fun handleCameraListMessage(cameraListMessage: CameraListMessage) {
        if (cameraListMessage.cameras.isEmpty()) {
            Log.w(TAG, "⚠️ Received empty camera list, ignoring")
            return
        }

        if (!hasInitialData || camerasCache.isEmpty()) {
            // FIRST TIME: Store complete camera list
            performInitialLoad(cameraListMessage)
        } else {
            // SUBSEQUENT: Only update status
            performStatusUpdate(cameraListMessage)
        }
    }

    /**
     * Initial load: Store complete camera list permanently
     */
    private fun performInitialLoad(cameraListMessage: CameraListMessage) {
        Log.d(TAG, "📹 INITIAL LOAD: Storing ${cameraListMessage.cameras.size} cameras permanently")

        camerasCache.clear()
        areaGroupCache.clear()

        var validCameras = 0
        cameraListMessage.cameras.forEach { camera ->
            if (camera.id.isNotEmpty()) {
                camerasCache[camera.id] = camera
                validCameras++
            }
        }

        hasInitialData = true
        prefs.edit().putBoolean(KEY_HAS_INITIAL_DATA, true).apply()

        rebuildAreaGroups()
        saveCameras()

        val areas = getAreas()
        Log.d(TAG, "📹 Initial load complete: $validCameras cameras, ${getOnlineCount()} online, ${areas.size} areas")
        Log.d(TAG, "📍 Available areas: $areas")

        notifyListeners()

        PersistentLogger.logEvent("CAMERA", "Initial load: $validCameras cameras, areas: ${areas.size}")
    }

    /**
     * Status update: Only update online/offline status
     */
    private fun performStatusUpdate(cameraListMessage: CameraListMessage) {
        var updatedCount = 0
        var newCameras = 0

        cameraListMessage.cameras.forEach { newCamera ->
            val existingCamera = camerasCache[newCamera.id]

            if (existingCamera != null) {
                // Update status if changed
                if (existingCamera.status != newCamera.status ||
                    existingCamera.lastUpdate != newCamera.lastUpdate) {

                    val updated = existingCamera.copy(
                        status = newCamera.status,
                        lastUpdate = newCamera.lastUpdate
                    )
                    camerasCache[newCamera.id] = updated
                    updatedCount++
                }
            } else {
                // New camera discovered - add it
                if (newCamera.id.isNotEmpty()) {
                    camerasCache[newCamera.id] = newCamera
                    newCameras++
                    Log.i(TAG, "➕ New camera added: ${newCamera.name} (${newCamera.area})")
                }
            }
        }

        if (updatedCount > 0 || newCameras > 0) {
            Log.d(TAG, "📹 Status update: $updatedCount changed, $newCameras new, online=${getOnlineCount()}")

            if (newCameras > 0) {
                rebuildAreaGroups()
                saveCameras() // Save when new cameras added
            } else {
                rebuildAreaGroups()
            }

            notifyListeners()

            if (updatedCount % 10 == 0) {
                PersistentLogger.logEvent("CAMERA", "Status update: $updatedCount changed, $newCameras new")
            }
        }
    }

    /**
     * Get cameras by area - ALWAYS works because we have persistent data
     */
    fun getCamerasByArea(area: String): List<CameraInfo> {
        val areaKey = area.lowercase()
        val cameras = areaGroupCache[areaKey]?.toList() ?: emptyList()

        if (cameras.isEmpty() && hasInitialData) {
            Log.w(TAG, "⚠️ No cameras found for area: $area (available: ${getAreas()})")
        }

        return cameras
    }

    /**
     * Get online cameras by area
     */
    fun getOnlineCamerasByArea(area: String): List<CameraInfo> {
        return getCamerasByArea(area).filter { it.isOnline() }
    }

    /**
     * Get all cameras
     */
    fun getAllCameras(): List<CameraInfo> {
        return camerasCache.values.toList()
    }

    /**
     * Get camera by ID
     */
    fun getCameraById(cameraId: String): CameraInfo? {
        return camerasCache[cameraId]
    }

    /**
     * Get all areas - ALWAYS returns complete list from stored data
     */
    fun getAreas(): List<String> {
        val areas = areaGroupCache.keys.sorted()

        if (areas.isEmpty() && hasInitialData) {
            Log.e(TAG, "❌ CRITICAL: No areas found but we have data! Cache size: ${camerasCache.size}")
            rebuildAreaGroups()
            return areaGroupCache.keys.sorted()
        }

        return areas
    }

    /**
     * Get online cameras only (all areas)
     */
    fun getOnlineCameras(): List<CameraInfo> {
        return camerasCache.values.filter { it.isOnline() }
    }

    private fun getOnlineCount(): Int {
        return camerasCache.values.count { it.isOnline() }
    }

    /**
     * Get camera statistics
     */
    fun getStatistics(): CameraStatistics {
        val total = camerasCache.size
        val online = getOnlineCount()
        val offline = total - online

        val areaStats = areaGroupCache.mapValues { (_, cameras) ->
            AreaStatistics(
                total = cameras.size,
                online = cameras.count { it.isOnline() },
                offline = cameras.count { !it.isOnline() }
            )
        }

        return CameraStatistics(
            totalCameras = total,
            onlineCameras = online,
            offlineCameras = offline,
            areaStatistics = areaStats
        )
    }

    /**
     * Get statistics for specific area
     */
    fun getAreaStatistics(area: String): AreaStatistics {
        val cameras = getCamerasByArea(area)
        return AreaStatistics(
            total = cameras.size,
            online = cameras.count { it.isOnline() },
            offline = cameras.count { !it.isOnline() }
        )
    }

    /**
     * Check if we have camera data
     */
    fun hasData(): Boolean {
        return hasInitialData && camerasCache.isNotEmpty()
    }

    /**
     * Add listener for camera updates
     */
    fun addListener(listener: (List<CameraInfo>) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Remove listener
     */
    fun removeListener(listener: (List<CameraInfo>) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Rebuild area groups from camera cache
     */
    private fun rebuildAreaGroups() {
        areaGroupCache.clear()

        camerasCache.values.forEach { camera ->
            val areaKey = camera.area.lowercase()
            areaGroupCache.getOrPut(areaKey) { mutableListOf() }.add(camera)
        }

        Log.d(TAG, "🗂️ Area groups rebuilt: ${areaGroupCache.size} areas, ${camerasCache.size} cameras")
    }

    /**
     * Save cameras to persistent storage
     */
    private fun saveCameras() {
        try {
            val camerasList = camerasCache.values.toList()
            val json = gson.toJson(camerasList)
            prefs.edit().putString(KEY_CAMERAS, json).apply()
            Log.d(TAG, "💾 Saved ${camerasList.size} cameras to storage")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving cameras: ${e.message}", e)
            PersistentLogger.logError("CAMERA", "Failed to save cameras", e)
        }
    }

    /**
     * Load cameras from persistent storage
     */
    private fun loadCameras() {
        try {
            val json = prefs.getString(KEY_CAMERAS, null)
            if (json == null) {
                Log.w(TAG, "⚠️ No saved cameras found")
                return
            }

            val type = object : TypeToken<List<CameraInfo>>() {}.type
            val cameras: List<CameraInfo> = gson.fromJson(json, type)

            camerasCache.clear()
            cameras.forEach { camera ->
                if (camera.id.isNotEmpty()) {
                    camerasCache[camera.id] = camera
                }
            }

            rebuildAreaGroups()
            Log.d(TAG, "✅ Loaded ${cameras.size} cameras from storage")
            Log.d(TAG, "📍 Areas available: ${getAreas()}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading cameras: ${e.message}", e)
            PersistentLogger.logError("CAMERA", "Failed to load cameras", e)
        }
    }

    /**
     * Notify all listeners of camera updates
     */
    private fun notifyListeners() {
        val cameras = getAllCameras()
        listeners.forEach { listener ->
            try {
                listener(cameras)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener: ${e.message}", e)
            }
        }
    }

    /**
     * Clear all data (use with caution)
     */
    fun clear() {
        camerasCache.clear()
        areaGroupCache.clear()
        hasInitialData = false
        prefs.edit().clear().apply()
        notifyListeners()
        Log.d(TAG, "🗑️ Cleared all cameras")
    }

    /**
     * Force refresh area groups (call if dropdown seems empty)
     */
    fun refreshAreaGroups() {
        if (camerasCache.isNotEmpty()) {
            rebuildAreaGroups()
            Log.d(TAG, "🔄 Force refreshed area groups: ${getAreas()}")
        }
    }
}

data class CameraStatistics(
    val totalCameras: Int,
    val onlineCameras: Int,
    val offlineCameras: Int,
    val areaStatistics: Map<String, AreaStatistics>
)

data class AreaStatistics(
    val total: Int,
    val online: Int,
    val offline: Int
)