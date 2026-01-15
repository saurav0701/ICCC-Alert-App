package com.example.iccc_alert_app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.iccc_alert_app.auth.AuthManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * ✅ OPTIMIZED CameraManager - Two-phase initialization
 *
 * Phase 1: initializeContext() - Initialize context only (MyApplication)
 * Phase 2: startAfterLogin() - Start polling (MainActivity after login)
 */
object CameraManager {
    private const val TAG = "CameraManager"
    private const val PREFS_NAME = "camera_manager"
    private const val KEY_CAMERAS = "cameras"
    private const val KEY_HAS_INITIAL_DATA = "has_initial_data"
    private const val KEY_LAST_UPDATE = "last_update"

    private const val POLLING_INTERVAL_MS = 10 * 60 * 1000L  // 10 minutes

    private lateinit var prefs: SharedPreferences
    private lateinit var context: Context
    private val gson = Gson()

    private val camerasCache = ConcurrentHashMap<String, CameraInfo>()
    private val areaGroupCache = ConcurrentHashMap<String, MutableList<CameraInfo>>()

    private var hasInitialData = false
    private var lastUpdateTime = 0L

    private val listeners = CopyOnWriteArrayList<(List<CameraInfo>) -> Unit>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val pollingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    /**
     * ✅ Stage 1: Initialize context only (called from MyApplication)
     * Does NOT start polling or fetch data
     */
    fun initializeContext(context: Context) {
        this.context = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        hasInitialData = prefs.getBoolean(KEY_HAS_INITIAL_DATA, false)
        lastUpdateTime = prefs.getLong(KEY_LAST_UPDATE, 0L)

        Log.d(TAG, "✅ CameraManager context initialized (polling deferred until login)")
        PersistentLogger.logEvent("CAMERA", "Context initialized - waiting for login")
    }

    /**
     * ✅ Stage 2: Start polling (called from MainActivity AFTER login)
     */
    fun startAfterLogin() {
        if (!AuthManager.isLoggedIn()) {
            Log.w(TAG, "⚠️ Cannot start - user not logged in")
            return
        }

        val org = BackendConfig.getOrganization()
        if (org.isEmpty()) {
            Log.w(TAG, "⚠️ Organization not set")
            return
        }

        Log.i(TAG, "🚀 Starting CameraManager for $org organization")
        PersistentLogger.logEvent("CAMERA", "Starting after login for $org")

        // Load from storage first (if exists)
        if (hasInitialData) {
            loadCameras()
            Log.d(TAG, "✅ Loaded ${camerasCache.size} cameras from storage")

            // Notify UI immediately with cached data
            notifyListeners()
        }

        // ✅ Fetch immediately from backend (backend has persistent cache)
        pollingScope.launch {
            Log.i(TAG, "🔄 Fetching fresh camera data immediately...")
            fetchCameraList()

            // Start background polling after initial fetch
            startPolling()
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()

        pollingJob = pollingScope.launch {
            // ✅ Background polling loop (fetch already happened in startAfterLogin)
            while (isActive) {
                delay(POLLING_INTERVAL_MS)

                try {
                    Log.d(TAG, "🔄 Background poll: Fetching camera list from REST API...")
                    fetchCameraList()
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.message}", e)
                    PersistentLogger.logError("CAMERA", "Polling error", e)
                }
            }
        }

        Log.i(TAG, "✅ Started camera REST API polling (every 10 minutes)")
    }

    private suspend fun fetchCameraList() = withContext(Dispatchers.IO) {
        try {
            val apiUrl = BackendConfig.getCameraApiUrl()

            Log.d(TAG, "📡 Fetching from: $apiUrl")

            val request = Request.Builder()
                .url(apiUrl)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "❌ API error: ${response.code}")
                return@withContext
            }

            val responseBody = response.body?.string() ?: return@withContext

            val apiResponse = gson.fromJson(responseBody, CameraApiResponse::class.java)

            if (apiResponse.success && apiResponse.data?.cameras != null) {
                val cameras = apiResponse.data.cameras

                Log.i(TAG, "📹 Fetched ${cameras.size} cameras from REST API")

                if (!hasInitialData || camerasCache.isEmpty()) {
                    performInitialLoad(cameras)
                } else {
                    performStatusUpdate(cameras)
                }

                lastUpdateTime = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_UPDATE, lastUpdateTime).apply()

            } else {
                Log.w(TAG, "⚠️ Empty or invalid API response")
            }

        } catch (e: IOException) {
            Log.e(TAG, "❌ Network error fetching cameras: ${e.message}")
            PersistentLogger.logError("CAMERA", "Network error", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing camera response: ${e.message}", e)
            PersistentLogger.logError("CAMERA", "Parse error", e)
        }
    }

    fun handleCameraListMessage(cameraListMessage: CameraListMessage) {
        if (cameraListMessage.cameras.isEmpty()) {
            Log.w(TAG, "⚠️ Received empty camera list from WebSocket, ignoring")
            return
        }

        if (!hasInitialData || camerasCache.isEmpty()) {
            Log.i(TAG, "📹 Using WebSocket camera list as fallback")
            performInitialLoad(cameraListMessage.cameras)
        } else {
            performStatusUpdate(cameraListMessage.cameras)
        }
    }

    private fun performInitialLoad(cameras: List<CameraInfo>) {
        Log.d(TAG, "📹 INITIAL LOAD: Storing ${cameras.size} cameras permanently")

        camerasCache.clear()
        areaGroupCache.clear()

        var validCameras = 0
        cameras.forEach { camera ->
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

        notifyListeners()
        PersistentLogger.logEvent("CAMERA", "Initial load: $validCameras cameras, areas: ${areas.size}")
    }

    private fun performStatusUpdate(cameras: List<CameraInfo>) {
        var updatedCount = 0
        var newCameras = 0

        cameras.forEach { newCamera ->
            val existingCamera = camerasCache[newCamera.id]

            if (existingCamera != null) {
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
                saveCameras()
            } else {
                rebuildAreaGroups()
            }

            notifyListeners()
        }
    }

    fun getCamerasByArea(area: String): List<CameraInfo> {
        val areaKey = area.lowercase()
        return areaGroupCache[areaKey]?.toList() ?: emptyList()
    }

    fun getOnlineCamerasByArea(area: String): List<CameraInfo> {
        return getCamerasByArea(area).filter { it.isOnline() }
    }

    fun getAllCameras(): List<CameraInfo> {
        return camerasCache.values.toList()
    }

    fun getCameraById(cameraId: String): CameraInfo? {
        return camerasCache[cameraId]
    }

    fun getAreas(): List<String> {
        return areaGroupCache.keys.sorted()
    }

    fun getOnlineCameras(): List<CameraInfo> {
        return camerasCache.values.filter { it.isOnline() }
    }

    private fun getOnlineCount(): Int {
        return camerasCache.values.count { it.isOnline() }
    }

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

    fun getAreaStatistics(area: String): AreaStatistics {
        val cameras = getCamerasByArea(area)
        return AreaStatistics(
            total = cameras.size,
            online = cameras.count { it.isOnline() },
            offline = cameras.count { !it.isOnline() }
        )
    }

    fun hasData(): Boolean {
        return hasInitialData && camerasCache.isNotEmpty()
    }

    fun getLastUpdateTime(): Long {
        return lastUpdateTime
    }

    fun getTimeSinceLastUpdate(): Long {
        return System.currentTimeMillis() - lastUpdateTime
    }

    fun addListener(listener: (List<CameraInfo>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (List<CameraInfo>) -> Unit) {
        listeners.remove(listener)
    }

    private fun rebuildAreaGroups() {
        areaGroupCache.clear()

        camerasCache.values.forEach { camera ->
            val areaKey = camera.area.lowercase()
            areaGroupCache.getOrPut(areaKey) { mutableListOf() }.add(camera)
        }
    }

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

    private fun loadCameras() {
        try {
            val json = prefs.getString(KEY_CAMERAS, null) ?: return

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

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading cameras: ${e.message}", e)
            PersistentLogger.logError("CAMERA", "Failed to load cameras", e)
        }
    }

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

    fun clear() {
        camerasCache.clear()
        areaGroupCache.clear()
        hasInitialData = false
        lastUpdateTime = 0L
        prefs.edit().clear().apply()
        notifyListeners()
        Log.d(TAG, "🗑️ Cleared all cameras")
    }

    fun refreshAreaGroups() {
        if (camerasCache.isNotEmpty()) {
            rebuildAreaGroups()
            Log.d(TAG, "🔄 Force refreshed area groups")
        }
    }

    fun forceRefresh() {
        pollingScope.launch {
            Log.i(TAG, "🔄 Force refresh requested")
            fetchCameraList()
        }
    }

    fun shutdown() {
        pollingJob?.cancel()
        pollingScope.cancel()
        Log.d(TAG, "CameraManager shutdown")
    }
}

data class CameraApiResponse(
    val success: Boolean,
    val data: CameraData?
)

data class CameraData(
    val cameras: List<CameraInfo>,
    val total: Int,
    val online: Int,
    val offline: Int,
    val timestamp: Long,
    val cacheAge: Double?
)

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