package com.example.iccc_alert_app

/**
 * Single source of truth for the five VTS (vehicle tracking) alert types.
 *
 * All five arrive from one socket (wss://vtsalerts.cclai.in). The socket
 * reports area-fence breaches as "off-route"; the backend reclassifies them
 * to "off-area" before publishing, so by the time an event reaches the app
 * the type is already final.
 *
 * Labels match the dashboard's VEHICLE_ALERT_TYPE_MAP so the same alert reads
 * identically in the app and the CCL Alert Dashboard.
 */
object VtsAlertTypes {

    const val OFF_ROUTE = "off-route"
    const val OFF_AREA = "off-area"
    const val TAMPER = "tamper"
    const val OVERSPEED = "overspeed"
    const val STOPPAGE = "stoppage"

    /** Every VTS type, in the order they should appear in channel lists. */
    val ALL = listOf(OFF_ROUTE, OFF_AREA, TAMPER, OVERSPEED, STOPPAGE)

    private val ALL_SET = ALL.toSet()

    /** True when the event came from the VTS socket rather than video analytics. */
    fun isVtsAlert(type: String?): Boolean = type != null && type in ALL_SET

    /** Human-readable label, matching the dashboard exactly. */
    fun displayName(type: String?): String = when (type) {
        OFF_ROUTE -> "Off-Route Alert"
        OFF_AREA -> "Off-Area Alert"
        TAMPER -> "Tamper Alert"
        OVERSPEED -> "Over Speed Alert"
        STOPPAGE -> "Unauthorized Stoppage"
        else -> "GPS Alert"
    }

    /** Short label used on map markers and PDF headers. */
    fun locationLabel(type: String?): String = when (type) {
        OFF_ROUTE -> "Off-Route Location"
        OFF_AREA -> "Off-Area Location"
        TAMPER -> "Tamper Location"
        OVERSPEED -> "Overspeed Location"
        STOPPAGE -> "Stoppage Location"
        else -> "Alert Location"
    }

    fun emoji(type: String?): String = when (type) {
        OFF_ROUTE -> "📍"
        OFF_AREA -> "📍"
        TAMPER -> "⚠️"
        OVERSPEED -> "🚨"
        STOPPAGE -> "🛑"
        else -> "📍"
    }

    /**
     * Two-letter badge code and colour.
     * "ST" is used for stoppage because "US" already means Unloading Status.
     */
    fun badge(type: String?): Pair<String, String>? = when (type) {
        OFF_ROUTE -> "OR" to "#FF5722"
        OFF_AREA -> "OA" to "#FF1744"
        TAMPER -> "TM" to "#F44336"
        OVERSPEED -> "OS" to "#FF9800"
        STOPPAGE -> "ST" to "#E67E22"
        else -> null
    }

    /**
     * The `:type` path segment used by the bbstate reports API
     * (https://bbstate.cclai.in/api/reports/{type}/...), which backs
     * VTS alert comments. Returns null for non-VTS events.
     */
    fun apiType(type: String?): String? = when (type) {
        OFF_ROUTE -> "offroute"
        OFF_AREA -> "offarea"
        TAMPER -> "tamper"
        OVERSPEED -> "overspeed"
        STOPPAGE -> "stoppage"
        else -> null
    }

    /**
     * Alerts that carry a single geofence worth drawing on a map.
     * Stoppage and overspeed have no meaningful fence.
     */
    fun hasGeofence(type: String?): Boolean =
        type == OFF_ROUTE || type == OFF_AREA || type == TAMPER
}

/**
 * The label to show the user for an event.
 *
 * For VTS alerts this prefers the app's own table over the backend's
 * typeDisplay. The two can disagree: an older backend deployment sends
 * "Overspeed Alert" where the dashboard and this app both say "Over Speed
 * Alert", which showed up as a channel titled one way and the card inside it
 * titled another. Preferring the local table keeps every screen consistent
 * regardless of which backend build is live.
 *
 * Video-analytics events keep using the backend's label, which is the only
 * source for them.
 */
val Event.displayLabel: String
    get() = if (VtsAlertTypes.isVtsAlert(type)) {
        VtsAlertTypes.displayName(type)
    } else {
        typeDisplay ?: type ?: "Event"
    }
