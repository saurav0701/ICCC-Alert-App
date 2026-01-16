package com.example.iccc_alert_app

import android.util.Log

/**
 * ✅ CRITICAL: Normalize area names to handle mismatches
 * Backend: "barka sayal", "magadh & sanghmitra"
 * Profile: "barkasayal", "magadh"
 */
object AreaNormalizer {
    private const val TAG = "AreaNormalizer"

    // Mapping of profile area names to backend area names
    private val areaMapping = mapOf(
        // CCL areas - handle both with/without spaces and partial names
        "barkasayal" to "barka sayal",
        "barka sayal" to "barka sayal",
        "argada" to "argada",
        "northkaranpura" to "north karanpura",
        "north karanpura" to "north karanpura",
        "bokarokargali" to "bokaro & kargali",
        "bokaro & kargali" to "bokaro & kargali",
        "kathara" to "kathara",
        "giridih" to "giridih",
        "amrapali" to "amrapali & chandragupta",
        "amrapali & chandragupta" to "amrapali & chandragupta",
        "magadh" to "magadh & sanghmitra",  // ✅ KEY FIX: Map "magadh" to "magadh & sanghmitra"
        "magadh & sanghmitra" to "magadh & sanghmitra",
        "rajhara" to "rajhara",
        "kuju" to "kuju",
        "hazaribagh" to "hazaribagh",
        "rajrappa" to "rajrappa",
        "dhori" to "dhori",
        "piparwar" to "piparwar"
    )

    /**
     * ✅ Normalize user area name to match backend area names
     */
    fun normalizeUserArea(userArea: String): String {
        val normalized = userArea.lowercase().trim()

        // Remove all spaces/special chars for fuzzy matching
        val cleaned = normalized.replace(Regex("[\\s&]"), "")

        // Try exact match first
        areaMapping[normalized]?.let {
            Log.d(TAG, "✅ Exact match: '$userArea' -> '$it'")
            return it
        }

        // Try fuzzy match by removing spaces/special chars
        for ((key, value) in areaMapping) {
            val keyClean = key.replace(Regex("[\\s&]"), "")
            if (keyClean == cleaned) {
                Log.d(TAG, "✅ Fuzzy match: '$userArea' -> '$value'")
                return value
            }
        }

        // Fallback: return as-is (lowercase)
        Log.w(TAG, "⚠️ No mapping found for '$userArea', using as-is: '$normalized'")
        return normalized
    }

    /**
     * ✅ Normalize list of user areas
     */
    fun normalizeUserAreas(userAreas: List<String>): List<String> {
        return userAreas.map { normalizeUserArea(it) }.distinct()
    }

    /**
     * ✅ Check if a backend area matches a user area (with normalization)
     */
    fun areasMatch(userArea: String, backendArea: String): Boolean {
        val normalizedUser = normalizeUserArea(userArea).lowercase()
        val normalizedBackend = backendArea.lowercase()

        return normalizedUser == normalizedBackend
    }

    /**
     * ✅ Get all valid backend area names
     */
    fun getAllBackendAreas(): List<String> {
        return areaMapping.values.distinct().sorted()
    }
}