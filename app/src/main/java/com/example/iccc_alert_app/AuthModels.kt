package com.example.iccc_alert_app.auth

import com.google.gson.annotations.SerializedName

// Registration request
data class RegistrationRequest(
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("area") val area: String,
    @SerializedName("designation") val designation: String,
    @SerializedName("organisation") val workingFor: String // CCL or BCCL
)

// OTP request
data class OTPRequest(
    @SerializedName("phone") val phone: String,
    @SerializedName("purpose") val purpose: String // "registration" or "login"
)

// OTP verification request
data class OTPVerificationRequest(
    @SerializedName("phone") val phone: String,
    @SerializedName("otp") val otp: String,
    @SerializedName("deviceId") val deviceId: String? = null
)

// User model
data class User(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("area") val area: String,
    @SerializedName("designation") val designation: String,
    @SerializedName("organisation") val workingFor: String,
    @SerializedName("isActive") val isActive: Boolean,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("updatedAt") val updatedAt: String
)

// Auth response
data class AuthResponse(
    @SerializedName("token") val token: String,
    @SerializedName("expiresAt") val expiresAt: Long,
    @SerializedName("user") val user: User
)

// API Response wrapper
data class ApiResponse<T>(
    @SerializedName("message") val message: String? = null,
    @SerializedName("data") val data: T? = null,
    @SerializedName("error") val error: String? = null
)