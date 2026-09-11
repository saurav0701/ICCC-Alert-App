package com.example.iccc_alert_app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ─── Filter constants ────────────────────────────────────────────────────────
private const val FILTER_ALL       = "all"
private const val FILTER_VA        = "va"
private const val FILTER_VTS       = "vts"
private const val FILTER_CROWD     = "cd"
private const val FILTER_INTRUSION = "id"
private const val FILTER_VEHICLE   = "vd"
private const val FILTER_PERSON    = "pd"

private val VA_TYPES  = setOf("cd", "id", "ct", "sh", "pd", "ii")
private val VTS_TYPES = setOf("vd", "vc", "ls", "us") + VtsAlertTypes.ALL

// ─── AlertsActivity ──────────────────────────────────────────────────────────
class AlertsActivity : BaseDrawerActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var connectionStatusText: TextView
    private lateinit var liveBadge: TextView
    private lateinit var liveDot: View
    private lateinit var alertCountBadge: TextView
    private lateinit var filterChipGroup: ChipGroup
    private lateinit var adapter: AlertsAdapter

    private val allAlerts  = mutableListOf<Event>()      // master list
    private val shownAlerts = mutableListOf<Event>()     // filtered list shown in RecyclerView
    private var currentFilter = FILTER_ALL
    private val handler = Handler(Looper.getMainLooper())
    private var livePulseAnimator: ObjectAnimator? = null
    private var isConnected = false

    // WebSocket event listener ─ runs on background thread, post to main
    private val eventListener: (Event) -> Unit = { event ->
        handler.post {
            // Deduplicate: don't add if this event is already in the list
            if (allAlerts.none { it.id == event.id }) {
                allAlerts.add(0, event)
                if (allAlerts.size > 500) allAlerts.removeAt(allAlerts.lastIndex)
            }
            markConnected()
            applyFilter()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alerts)
        supportActionBar?.title = "Live Alerts"

        bindViews()
        setupRecycler()
        setupFilterChips()
        setupSwipeRefresh()
        startLivePulse()

        setSelectedMenuItem(R.id.nav_alerts)

        // Seed with all existing events from every subscribed channel, newest first
        val existingEvents = SubscriptionManager.getSubscriptions()
            .flatMap { channel -> SubscriptionManager.getEventsForChannel(channel.id) }
            .sortedByDescending { it.timestamp }
        allAlerts.addAll(existingEvents)

        WebSocketManager.addEventListener(eventListener)
        updateConnectionStatus(connected = false)
        applyFilter()
    }

    override fun onResume() {
        super.onResume()
        setSelectedMenuItem(R.id.nav_alerts)
    }

    override fun onDestroy() {
        super.onDestroy()
        WebSocketManager.removeEventListener(eventListener)
        livePulseAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    // ── View binding ──────────────────────────────────────────────────────────
    private fun bindViews() {
        recyclerView         = findViewById(R.id.alerts_recycler)
        emptyView            = findViewById(R.id.empty_alerts_view)
        swipeRefresh         = findViewById(R.id.swipe_refresh)
        connectionStatusText = findViewById(R.id.connection_status_text)
        liveBadge            = findViewById(R.id.live_badge)
        liveDot              = findViewById(R.id.live_dot)
        alertCountBadge      = findViewById(R.id.alert_count_badge)
        filterChipGroup      = findViewById(R.id.filter_chip_group)
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────
    private fun setupRecycler() {
        adapter = AlertsAdapter(shownAlerts)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null   // avoids flicker on prepend
    }

    // ── Filter chips ──────────────────────────────────────────────────────────
    private fun setupFilterChips() {
        val chipMap = mapOf(
            R.id.chip_all       to FILTER_ALL,
            R.id.chip_va        to FILTER_VA,
            R.id.chip_vts       to FILTER_VTS,
            R.id.chip_crowd     to FILTER_CROWD,
            R.id.chip_intrusion to FILTER_INTRUSION,
            R.id.chip_vehicle   to FILTER_VEHICLE,
            R.id.chip_person    to FILTER_PERSON
        )
        filterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            currentFilter = chipMap[id] ?: FILTER_ALL
            applyFilter()
        }
    }

    // ── SwipeRefresh ─────────────────────────────────────────────────────────
    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.colorPrimary)
        )
        swipeRefresh.setOnRefreshListener {
            // Just re-render the current data; a full backend re-pull would
            // require a WebSocket re-subscribe call — that can be added later.
            handler.postDelayed({
                swipeRefresh.isRefreshing = false
                applyFilter()
            }, 600)
        }
    }

    // ── Filter logic ──────────────────────────────────────────────────────────
    private fun applyFilter() {
        shownAlerts.clear()
        val filtered = when (currentFilter) {
            FILTER_ALL -> allAlerts
            FILTER_VA  -> allAlerts.filter { VA_TYPES.contains(it.type) }
            FILTER_VTS -> allAlerts.filter { VTS_TYPES.contains(it.type) }
            else       -> allAlerts.filter { it.type == currentFilter }
        }
        shownAlerts.addAll(filtered)
        adapter.notifyDataSetChanged()

        // Scroll to top when new alert arrives and we're at the top already
        val lm = recyclerView.layoutManager as? LinearLayoutManager
        if (lm?.findFirstVisibleItemPosition() == 0 && shownAlerts.isNotEmpty()) {
            recyclerView.scrollToPosition(0)
        }

        updateEmptyState()
        updateCountBadge()
    }

    private fun updateEmptyState() {
        val isEmpty = shownAlerts.isEmpty()
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyView.visibility   = if (isEmpty) View.VISIBLE else View.GONE

        // Tailor the subtitle based on current filter
        val subtitle = emptyView.findViewById<TextView>(R.id.empty_subtitle)
        subtitle?.text = if (currentFilter == FILTER_ALL)
            "Alerts will appear here in real time as events are detected"
        else
            "No events match the selected filter. Try switching to \"All\"."
    }

    private fun updateCountBadge() {
        val count = shownAlerts.size
        alertCountBadge.text = if (count == 0) "0 alerts" else "$count alert${if (count == 1) "" else "s"}"
    }

    // ── Connection status ─────────────────────────────────────────────────────
    private fun markConnected() {
        if (!isConnected) {
            isConnected = true
            updateConnectionStatus(connected = true)
        }
    }

    private fun updateConnectionStatus(connected: Boolean) {
        if (connected) {
            connectionStatusText.text = "Connected"
            connectionStatusText.setTextColor(
                ContextCompat.getColor(this, R.color.colorSuccess)
            )
        } else {
            connectionStatusText.text = "Connecting…"
            connectionStatusText.setTextColor(
                ContextCompat.getColor(this, R.color.textColorSecondary)
            )
        }
    }

    // ── Live dot pulse animation ──────────────────────────────────────────────
    private fun startLivePulse() {
        livePulseAnimator = ObjectAnimator.ofFloat(liveDot, "alpha", 1f, 0.2f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }
}

// ─── AlertsAdapter ───────────────────────────────────────────────────────────
class AlertsAdapter(
    private val alerts: List<Event>
) : RecyclerView.Adapter<AlertsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val severityBar: View     = view.findViewById(R.id.severity_bar)
        val badge:       View     = view.findViewById(R.id.alert_badge)
        val typeAbbrev:  TextView = view.findViewById(R.id.alert_type_abbrev)
        val eventType:   TextView = view.findViewById(R.id.alert_type)
        val area:        TextView = view.findViewById(R.id.alert_area)
        val location:    TextView = view.findViewById(R.id.alert_location)
        val timestamp:   TextView = view.findViewById(R.id.alert_timestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alert, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = alerts[position]

        // Resolve type color, severity color & abbreviation
        val (colorHex, abbrev) = typeStyle(event.type)
        val badgeColor = Color.parseColor(colorHex)
        val severityColor = Color.parseColor(severityColor(event.type))

        // Severity left border
        holder.severityBar.setBackgroundColor(severityColor)

        holder.badge.background.setTint(badgeColor)
        holder.typeAbbrev.text = abbrev
        holder.typeAbbrev.background.setTint(badgeColor)

        holder.eventType.text = event.displayLabel
        holder.area.text      = event.areaDisplay ?: event.area ?: "—"
        holder.location.text  = event.data["location"] as? String ?: "Unknown location"
        holder.timestamp.text = relativeTime(event.timestamp)
    }

    override fun getItemCount() = alerts.size

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Severity left-bar color: red = critical, amber = warning, blue = info */
    private fun severityColor(type: String?): String = when (type) {
        "cd", "id", "ct", "off-route", "off-area", "tamper" -> "#EF4444"  // critical — red
        "sh", "vc", "overspeed", "stoppage"      -> "#F59E0B"  // warning — amber
        "vd", "pd", "ls", "us", "ii"            -> "#3B82F6"  // info — blue
        else                                     -> "#94A3B8"  // neutral — slate
    }

    private fun typeStyle(type: String?): Pair<String, String> = when (type) {
        "cd"        -> "#EF4444" to "CD"
        "id"        -> "#DC2626" to "ID"
        "ct"        -> "#EC4899" to "CT"
        "sh"        -> "#F59E0B" to "SH"
        "vd"        -> "#3B82F6" to "VD"
        "pd"        -> "#16A34A" to "PD"
        "vc"        -> "#F59E0B" to "VC"
        "ls"        -> "#0EA5E9" to "LS"
        "us"        -> "#0D9488" to "US"
        "ii"        -> "#7C3AED" to "II"
        "off-route" -> "#EF4444" to "OR"
        "off-area"  -> "#FF1744" to "OA"
        "tamper"    -> "#EC4899" to "TP"
        "overspeed" -> "#F97316" to "OS"
        "stoppage"  -> "#D97706" to "ST"
        else        -> "#94A3B8" to "??"
    }

    private fun relativeTime(epochSeconds: Long): String {
        val diffMs = System.currentTimeMillis() - (epochSeconds * 1000)
        return when {
            diffMs < 0                              -> "Just now"
            diffMs < TimeUnit.MINUTES.toMillis(1)   -> "Just now"
            diffMs < TimeUnit.HOURS.toMillis(1)     -> "${TimeUnit.MILLISECONDS.toMinutes(diffMs)}m ago"
            diffMs < TimeUnit.DAYS.toMillis(1)      -> "${TimeUnit.MILLISECONDS.toHours(diffMs)}h ago"
            else -> {
                val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
                sdf.format(Date(epochSeconds * 1000))
            }
        }
    }
}
