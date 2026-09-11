package com.example.iccc_alert_app

import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*


class ChannelListAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelLongClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelListAdapter.ViewHolder>() {

    private var channels = listOf<Channel>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
    private val eventTimeParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // ✅ FIX #1: Cache last event to avoid querying during sort
    private val lastEventCache = mutableMapOf<String, Event?>()

    companion object {
        private const val TAG = "ChannelListAdapter"
        private const val FIVE_MINUTES_MS = 5 * 60 * 1000L
        private const val ONE_MINUTE_MS = 60 * 1000L
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.channel_title)
        val subtitle: TextView = view.findViewById(R.id.channel_subtitle)
        val timestamp: TextView = view.findViewById(R.id.channel_timestamp)
        val iconBackground: View = view.findViewById(R.id.channel_icon_background)
        val iconText: TextView = view.findViewById(R.id.channel_icon_text)
        val unreadBadge: TextView = view.findViewById(R.id.unread_badge)
        val pinIcon: ImageView = view.findViewById(R.id.pin_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]

        holder.title.text = "${channel.areaDisplay} - ${channel.eventTypeDisplay}"

        // ✅ Use cached last event instead of querying again
        val lastEvent = lastEventCache[channel.id]

        if (lastEvent != null) {
            val location = lastEvent.data["location"] as? String ?: "Unknown"
            holder.subtitle.text = location
            holder.subtitle.setTextColor(android.graphics.Color.parseColor("#64748B"))

            val eventTimeStr = lastEvent.data["eventTime"] as? String
            val date = if (eventTimeStr != null) {
                try {
                    eventTimeParser.parse(eventTimeStr) ?: Date(lastEvent.timestamp * 1000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing eventTime: ${e.message}")
                    Date(lastEvent.timestamp * 1000)
                }
            } else {
                Date(lastEvent.timestamp * 1000)
            }

            val calendar = Calendar.getInstance()
            val today = calendar.time
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            val yesterday = calendar.time

            holder.timestamp.text = when {
                isSameDay(date, today) -> timeFormat.format(date)
                isSameDay(date, yesterday) -> "Yesterday"
                else -> dateFormat.format(date)
            }
            holder.timestamp.visibility = View.VISIBLE
        } else {
            holder.subtitle.text = "No events received"
            holder.subtitle.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            holder.timestamp.text = "Waiting..."
            holder.timestamp.visibility = View.VISIBLE
            holder.timestamp.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        }

        val (iconText, color) = when (channel.eventType) {
            "cd"        -> Pair("CD", "#DC2626")   // Crowd Detection    – red
            "id"        -> Pair("ID", "#B91C1C")   // Intrusion Detect   – deep red
            "ct"        -> Pair("CT", "#9333EA")   // Camera Tamper      – purple
            "sh"        -> Pair("SH", "#D97706")   // Speed / Speeding   – amber
            "vd"        -> Pair("VD", "#1D4ED8")   // Vehicle Detect     – blue
            "pd"        -> Pair("PD", "#059669")   // Person Detect      – green
            "vc"        -> Pair("VC", "#0891B2")   // Vehicle Count      – cyan
            "ls"        -> Pair("LS", "#0284C7")   // Line Stop          – sky blue
            "us"        -> Pair("US", "#7C3AED")   // Unknown / Signal   – violet
            "ii"        -> Pair("II", "#475569")   // Info / Inactive    – slate
            "off-route" -> Pair("OR", "#C2410C")   // Off-Route          – orange-red
            "off-area"  -> Pair("OA", "#E11D48")   // Off-Area           – crimson
            "tamper"    -> Pair("TM", "#BE123C")   // Tamper             – rose
            "overspeed" -> Pair("OS", "#EA580C")   // Over Speed         – amber-orange
            "stoppage"  -> Pair("ST", "#B45309")   // Unauth. Stoppage   – bronze
            else        -> Pair("??", "#64748B")   // Unknown            – neutral slate
        }

        holder.iconText.text = iconText
        // Severity bar: solid color strip on left edge of card
        holder.iconBackground.setBackgroundColor(Color.parseColor(color))

        val unreadCount = SubscriptionManager.getUnreadCount(channel.id)
        setupSmartUnreadBadge(holder, unreadCount, lastEvent)

        if (channel.isPinned) {
            holder.pinIcon.visibility = View.VISIBLE
        } else {
            holder.pinIcon.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            SubscriptionManager.markAsRead(channel.id)
            onChannelClick(channel)
        }

        holder.itemView.setOnLongClickListener {
            onChannelLongClick(channel)
            true
        }
    }

    private fun setupSmartUnreadBadge(holder: ViewHolder, unreadCount: Int, lastEvent: Event?) {
        if (unreadCount > 0) {
            holder.unreadBadge.visibility = View.VISIBLE
            holder.unreadBadge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
            holder.title.setTypeface(null, android.graphics.Typeface.BOLD)
            holder.subtitle.setTypeface(null, android.graphics.Typeface.BOLD)

            val badgeColor = getUnreadBadgeColor(lastEvent)
            holder.unreadBadge.setBackgroundResource(R.drawable.badge_background)
            (holder.unreadBadge.background as? android.graphics.drawable.GradientDrawable)?.setColor(badgeColor)

            if (isEventRecent(lastEvent, FIVE_MINUTES_MS)) {
                startPulseAnimation(holder.unreadBadge)
            }
        } else {
            holder.unreadBadge.visibility = View.GONE
            holder.title.setTypeface(null, android.graphics.Typeface.NORMAL)
            holder.subtitle.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    private fun getUnreadBadgeColor(lastEvent: Event?): Int {
        if (lastEvent == null) return Color.parseColor("#757575")

        val eventTime = getEventTime(lastEvent)
        val ageMs = System.currentTimeMillis() - eventTime

        return when {
            ageMs < ONE_MINUTE_MS   -> Color.parseColor("#DC2626")  // Critical – red
            ageMs < FIVE_MINUTES_MS -> Color.parseColor("#D97706")  // Urgent   – amber
            ageMs < 30 * 60 * 1000 -> Color.parseColor("#1D4ED8")  // Recent   – blue
            else                    -> Color.parseColor("#64748B")  // Normal   – slate
        }
    }

    private fun isEventRecent(lastEvent: Event?, thresholdMs: Long): Boolean {
        if (lastEvent == null) return false
        val eventTime = getEventTime(lastEvent)
        val ageMs = System.currentTimeMillis() - eventTime
        return ageMs < thresholdMs
    }

    private fun getEventTime(event: Event): Long {
        val eventTimeStr = event.data["eventTime"] as? String
        return if (eventTimeStr != null) {
            try {
                eventTimeParser.parse(eventTimeStr)?.time ?: (event.timestamp * 1000)
            } catch (e: Exception) {
                event.timestamp * 1000
            }
        } else {
            event.timestamp * 1000
        }
    }

    private fun startPulseAnimation(view: View?) {
        view?.let {
            val animator = android.animation.ObjectAnimator.ofFloat(it, "alpha", 1f, 0.3f, 1f)
            animator.duration = 1500
            animator.repeatCount = android.animation.ValueAnimator.INFINITE
            animator.start()
        }
    }

    override fun getItemCount() = channels.size

    // ✅ FIX #1: Safe comparator that doesn't query during sort
    fun updateChannels(newChannels: List<Channel>) {
        try {
            // Build cache BEFORE sorting
            lastEventCache.clear()
            newChannels.forEach { channel ->
                lastEventCache[channel.id] = SubscriptionManager.getLastEvent(channel.id)
            }

            // Now sort using cached values
            channels = newChannels.sortedWith { a, b ->
                // Priority 1: Pinned channels first
                val pinnedCmp = b.isPinned.compareTo(a.isPinned)
                if (pinnedCmp != 0) return@sortedWith pinnedCmp

                // Priority 2: Most recent events first (using cache)
                val timestampA = lastEventCache[a.id]?.timestamp ?: 0L
                val timestampB = lastEventCache[b.id]?.timestamp ?: 0L

                val timestampCmp = timestampB.compareTo(timestampA)  // Descending
                if (timestampCmp != 0) return@sortedWith timestampCmp

                // Priority 3: Alphabetical by name (fallback)
                (a.areaDisplay ?: "").compareTo(b.areaDisplay ?: "")
            }

            notifyDataSetChanged()
            Log.d(TAG, "✅ Channels sorted safely: ${channels.size} channels")

        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "❌ Sort error detected - using fallback sort", e)
            try {
                // Fallback: Sort without comparing last event
                channels = newChannels.sortedWith { a, b ->
                    val pinnedCmp = b.isPinned.compareTo(a.isPinned)
                    if (pinnedCmp != 0) return@sortedWith pinnedCmp
                    (a.areaDisplay ?: "").compareTo(b.areaDisplay ?: "")
                }
                notifyDataSetChanged()
                Log.d(TAG, "⚠️ Fallback sort successful")
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Both sorts failed - using original order", e2)
                channels = newChannels
                notifyDataSetChanged()
            }
        }
    }

    private fun isSameDay(date1: Date, date2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}