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
import android.animation.ObjectAnimator
import android.animation.ValueAnimator

/**
 * ✅ ENHANCED: Smart badges with priority colors and animations
 *
 * New features:
 * - Color-coded unread badges based on event recency
 * - "NEW" badge for events < 5 minutes old
 * - "LIVE" indicator for events < 1 minute old
 * - Pulsing animation for urgent events
 * - Priority-based visual hierarchy
 */
class ChannelListAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelLongClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelListAdapter.ViewHolder>() {

    private var channels = listOf<Channel>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
    private val eventTimeParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    companion object {
        private const val TAG = "ChannelListAdapter"
        private const val FIVE_MINUTES_MS = 5 * 60 * 1000L // Use 'L' to define as Long
        private const val ONE_MINUTE_MS = 60 * 1000L   // Use 'L' to define as Long
    }


    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.channel_title)
        val subtitle: TextView = view.findViewById(R.id.channel_subtitle)
        val timestamp: TextView = view.findViewById(R.id.channel_timestamp)
        val iconBackground: View = view.findViewById(R.id.channel_icon_background)
        val iconText: TextView = view.findViewById(R.id.channel_icon_text)
        val unreadBadge: TextView = view.findViewById(R.id.unread_badge)
        val pinIcon: ImageView = view.findViewById(R.id.pin_icon)
        val newIndicator: TextView? = view.findViewById(R.id.new_indicator) // Optional new badge
        val liveIndicator: TextView? = view.findViewById(R.id.live_indicator) // Optional live badge
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]

        holder.title.text = "${channel.areaDisplay} - ${channel.eventTypeDisplay}"

        val lastEvent = SubscriptionManager.getLastEvent(channel.id)

        if (lastEvent != null) {
            val location = lastEvent.data["location"] as? String ?: "Unknown"
            holder.subtitle.text = location
            holder.subtitle.setTextColor(android.graphics.Color.parseColor("#8A8A8A"))

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

            setupEventIndicators(holder, date)
        } else {
            holder.subtitle.text = "No events received"
            holder.subtitle.setTextColor(android.graphics.Color.parseColor("#BDBDBD"))
            holder.timestamp.text = "Waiting..."
            holder.timestamp.visibility = View.VISIBLE
            holder.timestamp.setTextColor(android.graphics.Color.parseColor("#BDBDBD"))
            holder.newIndicator?.visibility = View.GONE
            holder.liveIndicator?.visibility = View.GONE
        }

        val (iconText, color) = when (channel.eventType) {
            "cd" -> Pair("CD", "#FF5722")
            "id" -> Pair("ID", "#F44336")
            "ct" -> Pair("CT", "#E91E63")
            "sh" -> Pair("SH", "#FF9800")
            "vd" -> Pair("VD", "#2196F3")
            "pd" -> Pair("PD", "#4CAF50")
            "vc" -> Pair("VC", "#FFC107")
            "ls" -> Pair("LS", "#00BCD4")
            "us" -> Pair("US", "#9C27B0")
            "ii" -> Pair("II", "#607D8B")
            "off-route" -> Pair("OR", "#FF5722")
            "tamper" -> Pair("TM", "#F44336")
            else -> Pair("??", "#9E9E9E")
        }

        holder.iconText.text = iconText
        holder.iconBackground.setBackgroundResource(R.drawable.circle_background)
        (holder.iconBackground.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor(color))

        val unreadCount = SubscriptionManager.getUnreadCount(channel.id)
        setupSmartUnreadBadge(holder, unreadCount, lastEvent)

        // Show/hide pin icon
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
            ageMs < ONE_MINUTE_MS -> Color.parseColor("#F44336")        // Red - CRITICAL
            ageMs < FIVE_MINUTES_MS -> Color.parseColor("#FF9800")      // Orange - URGENT
            ageMs < 30 * 60 * 1000 -> Color.parseColor("#2196F3")       // Blue - RECENT
            else -> Color.parseColor("#757575")                          // Gray - NORMAL
        }
    }
    private fun setupEventIndicators(holder: ViewHolder, eventDate: Date) {
        val ageMs = System.currentTimeMillis() - eventDate.time

        // Show "LIVE" for events < 1 minute old
        if (ageMs < ONE_MINUTE_MS) {
            holder.liveIndicator?.visibility = View.VISIBLE
            holder.newIndicator?.visibility = View.GONE
            startPulseAnimation(holder.liveIndicator)
        }
        // Show "NEW" for events < 5 minutes old
        else if (ageMs < FIVE_MINUTES_MS) {
            holder.liveIndicator?.visibility = View.GONE
            holder.newIndicator?.visibility = View.VISIBLE
        }
        // Hide both for older events
        else {
            holder.liveIndicator?.visibility = View.GONE
            holder.newIndicator?.visibility = View.GONE
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
            val animator = ObjectAnimator.ofFloat(it, "alpha", 1f, 0.3f, 1f)
            animator.duration = 1500
            animator.repeatCount = ValueAnimator.INFINITE
            animator.start()
        }
    }

    override fun getItemCount() = channels.size

    fun updateChannels(newChannels: List<Channel>) {
        channels = newChannels.sortedWith(compareByDescending<Channel> { it.isPinned }
            .thenByDescending { channel ->
                val lastEvent = SubscriptionManager.getLastEvent(channel.id)
                lastEvent?.timestamp ?: 0L
            })
        notifyDataSetChanged()
    }

    private fun isSameDay(date1: Date, date2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}