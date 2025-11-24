package com.example.iccc_alert_app.auth

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object AuthManager {
    private const val TAG = "AuthManager"
    private const val PREFS_NAME = "iccc_auth_prefs"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_TOKEN_EXPIRY = "token_expiry"
    private const val KEY_USER_DATA = "user_data"

    private const val BASE_URL = "http://192.168.29.69:9088"

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Get device ID for multi-device support
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }

    // Check if user is logged in
    fun isLoggedIn(): Boolean {
        val token = getAuthToken()
        val expiry = prefs.getLong(KEY_TOKEN_EXPIRY, 0)
        val currentTime = System.currentTimeMillis() / 1000 // Convert to seconds

        return !token.isNullOrEmpty() && expiry > currentTime
    }

    // Get stored auth token
    fun getAuthToken(): String? {
        return prefs.getString(KEY_AUTH_TOKEN, null)
    }

    // Get stored user data
    fun getCurrentUser(): User? {
        val userJson = prefs.getString(KEY_USER_DATA, null) ?: return null
        return try {
            gson.fromJson(userJson, User::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse user data", e)
            null
        }
    }

    // Save auth data
    private fun saveAuthData(authResponse: AuthResponse) {
        prefs.edit().apply {
            putString(KEY_AUTH_TOKEN, authResponse.token)
            putLong(KEY_TOKEN_EXPIRY, authResponse.expiresAt)
            putString(KEY_USER_DATA, gson.toJson(authResponse.user))
            apply()
        }
        Log.d(TAG, "✓ Auth data saved successfully")
    }

    // Clear auth data (logout)
    fun clearAuthData() {
        prefs.edit().clear().apply()
        Log.d(TAG, "✓ Auth data cleared")
    }

    // Register new user - Step 1: Send registration data and get OTP
    fun requestRegistration(
        context: Context,
        request: RegistrationRequest,
        callback: (Boolean, String) -> Unit
    ) {
        val json = gson.toJson(request)
        val body = json.toRequestBody(JSON)

        val httpRequest = Request.Builder()
            .url("$BASE_URL/auth/register/request")
            .post(body)
            .build()

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

    // Register new user - Step 2: Verify OTP
    fun verifyRegistration(
        context: Context,
        phone: String,
        otp: String,
        callback: (Boolean, String, AuthResponse?) -> Unit
    ) {
        val request = OTPVerificationRequest(
            phone = phone,
            otp = otp,
            deviceId = getDeviceId(context)
        )

        val json = gson.toJson(request)
        val body = json.toRequestBody(JSON)

        val httpRequest = Request.Builder()
            .url("$BASE_URL/auth/register/verify")
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

    // Login - Step 1: Request OTP
    fun requestLogin(
        phone: String,
        callback: (Boolean, String) -> Unit
    ) {
        val request = OTPRequest(phone = phone, purpose = "login")
        val json = gson.toJson(request)
        val body = json.toRequestBody(JSON)

        val httpRequest = Request.Builder()
            .url("$BASE_URL/auth/login/request")
            .post(body)
            .build()

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Login request failed", e)
                callback(false, "Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string()
                    Log.d(TAG, "Login request response: $responseBody")

                    if (it.isSuccessful) {
                        callback(true, "OTP sent to your WhatsApp")
                    } else {
                        val errorMsg = try {
                            val errorResponse = gson.fromJson(responseBody, ApiResponse::class.java)
                            errorResponse.error ?: "Failed to send OTP"
                        } catch (e: Exception) {
                            "Failed to send OTP: ${it.code}"
                        }
                        callback(false, errorMsg)
                    }
                }
            }
        })
    }

    // Login - Step 2: Verify OTP
    fun verifyLogin(
        context: Context,
        phone: String,
        otp: String,
        callback: (Boolean, String, AuthResponse?) -> Unit
    ) {
        val request = OTPVerificationRequest(
            phone = phone,
            otp = otp,
            deviceId = getDeviceId(context)
        )

        val json = gson.toJson(request)
        val body = json.toRequestBody(JSON)

        val httpRequest = Request.Builder()
            .url("$BASE_URL/auth/login/verify")
            .post(body)
            .build()

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

    // Logout
    fun logout(callback: (Boolean, String) -> Unit) {
        val token = getAuthToken()
        if (token == null) {
            clearAuthData()
            callback(true, "Logged out")
            return
        }

        val httpRequest = Request.Builder()
            .url("$BASE_URL/auth/logout")
            .addHeader("Authorization", "Bearer $token")
            .post("{}".toRequestBody(JSON))
            .build()

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Logout request failed", e)
                // Clear local data anyway
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