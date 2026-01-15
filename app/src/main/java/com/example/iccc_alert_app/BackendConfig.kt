package com.example.iccc_alert_app

import android.content.Context
import android.content.SharedPreferences

object BackendConfig {
    private const val PREFS_NAME = "backend_config"
    private const val KEY_ORGANIZATION = "organization"

    private lateinit var prefs: SharedPreferences

    // ✅ BCCL Backend Configuration
    private const val BCCL_HTTP_HOST = "103.208.173.227"
    private const val BCCL_HTTP_PORT = 8890        // ← API Server Port
    private const val BCCL_WS_HOST = "103.208.173.227"
    private const val BCCL_WS_PORT = 2222          // ← WebSocket Server Port

    // ✅ CCL Backend Configuration
    private const val CCL_HTTP_HOST = "192.168.29.69"
    private const val CCL_HTTP_PORT = 39071        // ← API Server Port
    private const val CCL_WS_HOST = "192.168.29.69"
    private const val CCL_WS_PORT = 39072          // ← WebSocket Server Port

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun setOrganization(organization: String) {
        require(organization == "BCCL" || organization == "CCL") {
            "Organization must be either BCCL or CCL"
        }
        prefs.edit().putString(KEY_ORGANIZATION, organization).apply()
        android.util.Log.d("BackendConfig", "✅ Organization set to: $organization")
    }

    fun getOrganization(): String {
        return prefs.getString(KEY_ORGANIZATION, "BCCL") ?: "BCCL"
    }

    fun isCCL(): Boolean {
        return getOrganization() == "CCL"
    }

    fun isBCCL(): Boolean {
        return getOrganization() == "BCCL"
    }

    fun getHttpBaseUrl(): String {
        return if (isCCL()) {
            "http://$CCL_HTTP_HOST:$CCL_HTTP_PORT"
        } else {
            "http://$BCCL_HTTP_HOST:$BCCL_HTTP_PORT"
        }
    }

    fun getWsBaseUrl(): String {
        return if (isCCL()) {
            "ws://$CCL_WS_HOST:$CCL_WS_PORT"
        } else {
            "ws://$BCCL_WS_HOST:$BCCL_WS_PORT"
        }
    }

    //20.207.231.162

    fun getWsUrl(): String {
        return "${getWsBaseUrl()}/ws"
    }

    fun getCameraApiUrl(): String {
        return "${getHttpBaseUrl()}/cameras"
    }

    /**
     * ✅ Get cameras by area endpoint
     */
    fun getCamerasByAreaUrl(area: String): String {
        return "${getHttpBaseUrl()}/cameras/area/$area"
    }

    /**
     * ✅ Get online cameras endpoint
     */
    fun getOnlineCamerasUrl(): String {
        return "${getHttpBaseUrl()}/cameras/online"
    }

    /**
     * ✅ Get camera stats endpoint
     */
    fun getCameraStatsUrl(): String {
        return "${getHttpBaseUrl()}/cameras/stats"
    }

    fun clearOrganization() {
        prefs.edit().remove(KEY_ORGANIZATION).apply()
        android.util.Log.d("BackendConfig", "Organization cleared")
    }

    /**
     * Get complete backend configuration info
     */
    fun getOrganizationInfo(): Map<String, String> {
        val org = getOrganization()
        return mapOf(
            "organization" to org,
            "httpHost" to if (isCCL()) CCL_HTTP_HOST else BCCL_HTTP_HOST,
            "httpPort" to if (isCCL()) CCL_HTTP_PORT.toString() else BCCL_HTTP_PORT.toString(),
            "wsHost" to if (isCCL()) CCL_WS_HOST else BCCL_WS_HOST,
            "wsPort" to if (isCCL()) CCL_WS_PORT.toString() else BCCL_WS_PORT.toString(),
            "httpBaseUrl" to getHttpBaseUrl(),
            "wsUrl" to getWsUrl(),
            "cameraApiUrl" to getCameraApiUrl(),
            "backend" to if (isCCL()) "CCL Backend" else "BCCL Backend"
        )
    }

    /**
     * Log current configuration
     */
    fun logConfiguration() {
        val info = getOrganizationInfo()
        android.util.Log.d("BackendConfig", """
            ========================================
            Backend Configuration
            ========================================
            Organization: ${info["organization"]}
            HTTP API: ${info["httpBaseUrl"]}
            WebSocket: ${info["wsUrl"]}
            Camera API: ${info["cameraApiUrl"]}
            ========================================
        """.trimIndent())
    }
}