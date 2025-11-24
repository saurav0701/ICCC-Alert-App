package com.example.iccc_alert_app

// ✅ FIXED: Consistent data class and enum definitions

enum class Priority(val displayName: String, val color: String) {
    LOW("Low", "#4CAF50"),
    MODERATE("Moderate", "#FF9800"),
    HIGH("High", "#F44336")
}

data class SavedMessage(
    val eventId: String,
    val event: Event,
    val priority: Priority,
    val comment: String,
    val savedTimestamp: Long = System.currentTimeMillis()
)