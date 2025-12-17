package com.example.iccc_alert_app.auth

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.example.iccc_alert_app.BackendConfig
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
    private const val REQUEST_TIMEOUT = 8L // seconds per backend

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun initialize(context: Context) {
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

    // Registration (knows organization upfront)
    fun requestRegistration(
        context: Context,
        request: RegistrationRequest,
        callback: (Boolean, String) -> Unit
    ) {
        // Set organization before making request
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

    /**
     * ✅ NEW: Multi-backend login request
     * Tries BCCL first, then CCL if not found or timeout
     */
    fun requestLogin(
        phone: String,
        callback: (Boolean, String) -> Unit
    ) {
        Log.d(TAG, "Starting multi-backend login for: $phone")

        val callbackInvoked = AtomicBoolean(false)

        // Try BCCL first
        tryLoginOnBackend(phone, "BCCL") { bcclSuccess, bcclMessage ->
            if (bcclSuccess) {
                // Found on BCCL
                if (callbackInvoked.compareAndSet(false, true)) {
                    Log.d(TAG, "✓ User found on BCCL")
                    BackendConfig.setOrganization("BCCL")
                    callback(true, bcclMessage)
                }
            } else {
                // Not found or error on BCCL, try CCL
                Log.d(TAG, "BCCL failed: $bcclMessage, trying CCL...")

                tryLoginOnBackend(phone, "CCL") { cclSuccess, cclMessage ->
                    if (callbackInvoked.compareAndSet(false, true)) {
                        if (cclSuccess) {
                            Log.d(TAG, "✓ User found on CCL")
                            BackendConfig.setOrganization("CCL")
                            callback(true, cclMessage)
                        } else {
                            // Not found on either backend
                            Log.d(TAG, "✗ User not found on either backend")
                            callback(false, "User not registered. Please register first.")
                        }
                    }
                }
            }
        }
    }

    /**
     * ✅ NEW: Try login on specific backend
     */
    private fun tryLoginOnBackend(
        phone: String,
        organization: String,
        callback: (Boolean, String) -> Unit
    ) {
        val baseUrl = if (organization == "CCL") {
            "http://192.168.29.70:19998"
        } else {
            "http://192.168.29.70:8890"
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
                        // ✅ CRITICAL: Set organization as soon as we find the user
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

    /**
     * ✅ UPDATED: Verify login uses the already-set organization
     */
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

    fun logout(callback: (Boolean, String) -> Unit) {
        val token = getAuthToken()
        if (token == null) {
            clearAuthData()
            callback(true, "Logged out")
            return
        }

        val baseUrl = BackendConfig.getHttpBaseUrl()

        val httpRequest = Request.Builder()
            .url("$baseUrl/auth/logout")
            .addHeader("Authorization", "Bearer $token")
            .post("{}".toRequestBody(JSON))
            .build()

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Logout request failed", e)
                clearAuthData()
                callback(true, "Logged out (offline)")
            }

            override fun onResponse(call: Call, response: Response) {
                clearAuthData()
                callback(true, "Logged out successfully")
            }
        })
    }
}