package com.example.iccc_alert_app.auth

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.example.iccc_alert_app.BackendConfig
import com.example.iccc_alert_app.ChannelSyncState
import com.example.iccc_alert_app.PersistentLogger
import com.example.iccc_alert_app.SavedMessagesManager
import com.example.iccc_alert_app.SubscriptionManager
import com.example.iccc_alert_app.WebSocketService
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AuthManager {
    private const val TAG = "AuthManager"
    private const val PREFS_NAME = "iccc_auth_prefs"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_TOKEN_EXPIRY = "token_expiry"
    private const val KEY_USER_DATA = "user_data"
    private const val KEY_IS_LOGGED_OUT = "is_logged_out"
    private const val REQUEST_TIMEOUT = 8L

    private lateinit var prefs: SharedPreferences
    private lateinit var context: Context
    private val client = OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun initialize(context: Context) {
        this.context = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        BackendConfig.initialize(context)
    }

    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }

    fun isLoggedIn(): Boolean {
        // ✅ Check if user manually logged out
        val isLoggedOut = prefs.getBoolean(KEY_IS_LOGGED_OUT, false)
        if (isLoggedOut) {
            return false
        }

        val token = getAuthToken()
        val expiry = prefs.getLong(KEY_TOKEN_EXPIRY, 0)
        val currentTime = System.currentTimeMillis() / 1000
        return !token.isNullOrEmpty() && expiry > currentTime
    }

    fun getAuthToken(): String? {
        return prefs.getString(KEY_AUTH_TOKEN, null)
    }

    fun getCurrentUser(): User? {
        val userJson = prefs.getString(KEY_USER_DATA, null) ?: return null
        return try {
            gson.fromJson(userJson, User::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse user data", e)
            null
        }
    }

    private fun saveAuthData(authResponse: AuthResponse) {
        prefs.edit().apply {
            putString(KEY_AUTH_TOKEN, authResponse.token)
            putLong(KEY_TOKEN_EXPIRY, authResponse.expiresAt)
            putString(KEY_USER_DATA, gson.toJson(authResponse.user))
            putBoolean(KEY_IS_LOGGED_OUT, false)  // ✅ Clear logout flag on login
            apply()
        }
        BackendConfig.setOrganization(authResponse.user.workingFor)
        Log.d(TAG, "✓ Auth data saved for ${authResponse.user.workingFor}")
    }

    fun clearAuthData() {
        prefs.edit().clear().apply()
        BackendConfig.clearOrganization()
        Log.d(TAG, "✓ Auth data cleared")
    }

    fun requestRegistration(
        context: Context,
        request: RegistrationRequest,
        callback: (Boolean, String) -> Unit
    ) {
        BackendConfig.setOrganization(request.workingFor)
        val baseUrl = BackendConfig.getHttpBaseUrl()

        val json = gson.toJson(request)
        val body = json.toRequestBody(JSON)

        val httpRequest = Request.Builder()
            .url("$baseUrl/auth/register/request")
            .post(body)
            .build()

        Log.d(TAG, "Registration request to: $baseUrl (${request.workingFor})")

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Registration request failed", e)
                callback(false, "Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string()
                    Log.d(TAG, "Registration response: $responseBody")

                    if (it.isSuccessful) {
                        callback(true, "OTP sent to your WhatsApp")
                    } else {
                        val errorMsg = try {
                            val errorResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                            errorResponse.error ?: "Registration failed"
                        } catch (e: Exception) {
                            "Registration failed: ${it.code}"
                        }
                        callback(false, errorMsg)
                    }
                }
            }
        })
    }

    fun verifyRegistration(
        context: Context,
        phone: String,
        otp: String,
        callback: (Boolean, String, AuthResponse?) -> Unit
    ) {
        val baseUrl = BackendConfig.getHttpBaseUrl()

        val request = OTPVerificationRequest(
            phone = phone,
            otp = otp,
            deviceId = getDeviceId(context)
        )

        val json = gson.toJson(request)
        val body = json.toRequestBody(JSON)

        val httpRequest = Request.Builder()
            .url("$baseUrl/auth/register/verify")
            .post(body)
            .build()

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Registration verification failed", e)
                callback(false, "Network error: ${e.message}", null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string()
                    Log.d(TAG, "Verification response: $responseBody")

                    if (it.isSuccessful) {
                        try {
                            val apiResponse = gson.fromJson(
                                responseBody,
                                object : com.google.gson.reflect.TypeToken<ApiResponse<AuthResponse>>() {}.type
                            ) as ApiResponse<AuthResponse>

                            apiResponse.data?.let { authResp ->
                                saveAuthData(authResp)
                                callback(true, "Registration successful", authResp)
                            } ?: run {
                                callback(false, "Invalid response from server", null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse response", e)
                            callback(false, "Failed to parse response", null)
                        }
                    } else {
                        val errorMsg = try {
                            val errorResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                            errorResponse.error ?: "Verification failed"
                        } catch (e: Exception) {
                            "Verification failed: ${it.code}"
                        }
                        callback(false, errorMsg, null)
                    }
                }
            }
        })
    }

    fun requestLogin(
        phone: String,
        callback: (Boolean, String) -> Unit
    ) {
        Log.d(TAG, "Starting multi-backend login for: $phone")

        val callbackInvoked = AtomicBoolean(false)

        tryLoginOnBackend(phone, "BCCL") { bcclSuccess, bcclMessage ->
            if (bcclSuccess) {
                if (callbackInvoked.compareAndSet(false, true)) {
                    Log.d(TAG, "✓ User found on BCCL")
                    BackendConfig.setOrganization("BCCL")
                    callback(true, bcclMessage)
                }
            } else {
                Log.d(TAG, "BCCL failed: $bcclMessage, trying CCL...")

                tryLoginOnBackend(phone, "CCL") { cclSuccess, cclMessage ->
                    if (callbackInvoked.compareAndSet(false, true)) {
                        if (cclSuccess) {
                            Log.d(TAG, "✓ User found on CCL")
                            BackendConfig.setOrganization("CCL")
                            callback(true, cclMessage)
                        } else {
                            Log.d(TAG, "✗ User not found on either backend")
                            callback(false, "User not registered. Please register first.")
                        }
                    }
                }
            }
        }
    }

    private fun tryLoginOnBackend(
        phone: String,
        organization: String,
        callback: (Boolean, String) -> Unit
    ) {
        val baseUrl = if (organization == "CCL") {
            "http://20.207.231.162:39071"
        } else {
            "http://103.208.173.227:8890"
        }

        val request = OTPRequest(phone = phone, purpose = "login")
        val json = gson.toJson(request)
        val body = json.toRequestBody(JSON)

        val httpRequest = Request.Builder()
            .url("$baseUrl/auth/login/request")
            .post(body)
            .build()

        Log.d(TAG, "Trying login on $organization: $baseUrl")

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "$organization login request failed: ${e.message}")
                callback(false, "Network error on $organization: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string()
                    Log.d(TAG, "$organization response: ${it.code} - $responseBody")

                    if (it.isSuccessful) {
                        BackendConfig.setOrganization(organization)
                        Log.d(TAG, "✅ Set organization to $organization based on user lookup")
                        callback(true, "OTP sent to your WhatsApp")
                    } else {
                        val errorMsg = try {
                            val errorResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                            errorResponse.error ?: "Failed on $organization"
                        } catch (e: Exception) {
                            "Failed on $organization: ${it.code}"
                        }
                        callback(false, errorMsg)
                    }
                }
            }
        })
    }

    fun verifyLogin(
        context: Context,
        phone: String,
        otp: String,
        callback: (Boolean, String, AuthResponse?) -> Unit
    ) {
        val baseUrl = BackendConfig.getHttpBaseUrl()

        val request = OTPVerificationRequest(
            phone = phone,
            otp = otp,
            deviceId = getDeviceId(context)
        )

        val json = gson.toJson(request)
        val body = json.toRequestBody(JSON)

        val httpRequest = Request.Builder()
            .url("$baseUrl/auth/login/verify")
            .post(body)
            .build()

        Log.d(TAG, "Verifying login on: $baseUrl")

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Login verification failed", e)
                callback(false, "Network error: ${e.message}", null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string()
                    Log.d(TAG, "Login verification response: $responseBody")

                    if (it.isSuccessful) {
                        try {
                            val apiResponse = gson.fromJson(
                                responseBody,
                                object : com.google.gson.reflect.TypeToken<ApiResponse<AuthResponse>>() {}.type
                            ) as ApiResponse<AuthResponse>

                            apiResponse.data?.let { authResp ->
                                saveAuthData(authResp)
                                callback(true, "Login successful", authResp)
                            } ?: run {
                                callback(false, "Invalid response from server", null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse response", e)
                            callback(false, "Failed to parse response", null)
                        }
                    } else {
                        val errorMsg = try {
                            val errorResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                            errorResponse.error ?: "Login failed"
                        } catch (e: Exception) {
                            "Login failed: ${it.code}"
                        }
                        callback(false, errorMsg, null)
                    }
                }
            }
        })
    }

    // ✅ NEW: Logout that preserves all data (like iOS)
    fun logout(callback: (Boolean, String) -> Unit) {
        Log.d(TAG, "🚪 Starting logout process...")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "LOGOUT PROCESS:")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // ✅ CRITICAL: Save all current data BEFORE logout
        try {
            SubscriptionManager.forceSave()
            ChannelSyncState.forceSave()

            val subscriptions = SubscriptionManager.getSubscriptions().size
            val events = SubscriptionManager.getTotalEventCount()
            val saved = SavedMessagesManager.getSavedMessages().size

            Log.d(TAG, "✓ Saved current state:")
            Log.d(TAG, "  - Subscriptions: $subscriptions")
            Log.d(TAG, "  - Events: $events")
            Log.d(TAG, "  - Saved messages: $saved")
            PersistentLogger.logEvent("LOGOUT", "Saved state: $subscriptions channels, $events events")

        } catch (e: Exception) {
            Log.e(TAG, "Error saving state before logout: ${e.message}", e)
            PersistentLogger.logError("LOGOUT", "Failed to save state", e)
        }

        // ✅ Get token before marking logout (for API call)
        val token = getAuthToken()

        // ✅ CRITICAL: Only set logout flag, do NOT clear any data
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_OUT, true)
            .apply()

        Log.d(TAG, "✓ Set logged out flag (all data preserved)")

        // ✅ Disconnect WebSocket (CRITICAL - stops receiving new events)
        try {
            WebSocketService.stop(context)
            Log.d(TAG, "✓ WebSocket disconnected")
            PersistentLogger.logEvent("LOGOUT", "WebSocket disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket: ${e.message}", e)
        }

        // ✅ Optional: Call backend logout endpoint
        if (token != null) {
            val baseUrl = BackendConfig.getHttpBaseUrl()

            val httpRequest = Request.Builder()
                .url("$baseUrl/auth/logout")
                .addHeader("Authorization", "Bearer $token")
                .post("{}".toRequestBody(JSON))
                .build()

            client.newCall(httpRequest).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "Logout API call failed (not critical): ${e.message}")
                    finishLogout(callback)
                }

                override fun onResponse(call: Call, response: Response) {
                    Log.d(TAG, "✓ Backend logout API called: ${response.code}")
                    finishLogout(callback)
                }
            })
        } else {
            finishLogout(callback)
        }
    }

    private fun finishLogout(callback: (Boolean, String) -> Unit) {
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "✅ LOGOUT COMPLETE")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "DATA PRESERVATION:")
        Log.d(TAG, "  ✓ Token preserved: ${getAuthToken() != null}")
        Log.d(TAG, "  ✓ User data: ${getCurrentUser()?.name}")
        Log.d(TAG, "  ✓ Client ID: ${getDeviceId(context)}")
        Log.d(TAG, "  ✓ Subscriptions: ${SubscriptionManager.getSubscriptions().size}")
        Log.d(TAG, "  ✓ Events: ${SubscriptionManager.getTotalEventCount()}")
        Log.d(TAG, "  ✓ Saved messages: ${SavedMessagesManager.getSavedMessages().size}")
        Log.d(TAG, "")
        Log.d(TAG, "WHAT HAPPENS NEXT:")
        Log.d(TAG, "  → User will see login screen")
        Log.d(TAG, "  → WebSocket is disconnected")
        Log.d(TAG, "  → All data is preserved")
        Log.d(TAG, "  → On re-login with same phone:")
        Log.d(TAG, "    • Same clientId will be used (android-${getDeviceId(context)})")
        Log.d(TAG, "    • ChannelSyncState has last position for each channel")
        Log.d(TAG, "    • Backend will send all pending events via catch-up")
        Log.d(TAG, "    • Events will be ACKed as processed")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        PersistentLogger.logEvent("LOGOUT", "Complete - all data preserved")
        PersistentLogger.flush()

        callback(true, "Logged out successfully")
    }
}