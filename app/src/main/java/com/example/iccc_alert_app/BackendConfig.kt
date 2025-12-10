package com.example.iccc_alert_app

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized backend configuration based on user's organization
 * Supports both BCCL and CCL with different URLs and configurations
 */
object BackendConfig {
    private const val PREFS_NAME = "backend_config"
    private const val KEY_ORGANIZATION = "organization"

    private lateinit var prefs: SharedPreferences

    // BCCL Backend (original)
    private const val BCCL_HTTP_BASE = "http://202.140.131.90:8890"
    private const val BCCL_WS_BASE = "ws://202.140.131.90:2222"

    // CCL Backend (new)
    private const val CCL_HTTP_BASE = "http://103.215.240.243:19998"
    private const val CCL_WS_BASE = "ws://103.215.240.243:19999"

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

    /**
     * Get current organization
     */
    fun getOrganization(): String {
        return prefs.getString(KEY_ORGANIZATION, "BCCL") ?: "BCCL"
    }

    /**
     * Check if current organization is CCL
     */
    fun isCCL(): Boolean {
        return getOrganization() == "CCL"
    }

    /**
     * Check if current organization is BCCL
     */
    fun isBCCL(): Boolean {
        return getOrganization() == "BCCL"
    }

    /**
     * Get HTTP base URL for current organization
     */
    fun getHttpBaseUrl(): String {
        return if (isCCL()) CCL_HTTP_BASE else BCCL_HTTP_BASE
    }

    /**
     * Get WebSocket base URL for current organization
     */
    fun getWsBaseUrl(): String {
        return if (isCCL()) CCL_WS_BASE else BCCL_WS_BASE
    }

    /**
     * Get WebSocket URL with path
     */
    fun getWsUrl(): String {
        return "${getWsBaseUrl()}/ws"
    }

    /**
     * Clear organization (logout)
     */
    fun clearOrganization() {
        prefs.edit().remove(KEY_ORGANIZATION).apply()
        android.util.Log.d("BackendConfig", "Organization cleared")
    }

    /**
     * Get organization display info
     */
    fun getOrganizationInfo(): Map<String, String> {
        val org = getOrganization()
        return mapOf(
            "organization" to org,
            "httpUrl" to getHttpBaseUrl(),
            "wsUrl" to getWsUrl(),
            "backend" to if (isCCL()) "CCL Backend" else "BCCL Backend"
        )
    }
}