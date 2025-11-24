package com.example.iccc_alert_app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AreaGroupAdapter(
    private val onSubscribeToggle: (Channel) -> Unit
) : RecyclerView.Adapter<AreaGroupAdapter.ViewHolder>() {

    private var areaGroups = listOf<AreaGroup>()
    private val expandedStates = mutableMapOf<String, Boolean>()

    data class AreaGroup(
        val area: String,
        val areaDisplay: String,
        val channels: List<Channel>
    )

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val areaTitle: TextView = view.findViewById(R.id.area_title)
        val expandIcon: ImageView = view.findViewById(R.id.expand_icon)
        val subscriptionCount: TextView = view.findViewById(R.id.subscription_count)
        val detectionTypesContainer: LinearLayout = view.findViewById(R.id.detection_types_container)
        val headerLayout: View = view.findViewById(R.id.header_layout)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_area_group, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = areaGroups[position]
        val isExpanded = expandedStates[group.area] ?: false

        holder.areaTitle.text = group.areaDisplay

        // Count subscribed channels in this area
        val subscribedCount = group.channels.count { it.isSubscribed }
        if (subscribedCount > 0) {
            holder.subscriptionCount.visibility = View.VISIBLE
            holder.subscriptionCount.text = "$subscribedCount subscribed"
        } else {
            holder.subscriptionCount.visibility = View.GONE
        }

        // Toggle expansion
        holder.headerLayout.setOnClickListener {
            expandedStates[group.area] = !isExpanded
            notifyItemChanged(position)
        }

        // Rotate expand icon
        holder.expandIcon.rotation = if (isExpanded) 180f else 0f

        // Show/hide detection types
        if (isExpanded) {
            holder.detectionTypesContainer.visibility = View.VISIBLE
            holder.detectionTypesContainer.removeAllViews()

            // Add each detection type
            for (channel in group.channels) {
                val detectionView = LayoutInflater.from(holder.itemView.context)
                    .inflate(R.layout.item_detection_type, holder.detectionTypesContainer, false)

                val iconBackground = detectionView.findViewById<View>(R.id.detection_icon_background)
                val iconText = detectionView.findViewById<TextView>(R.id.detection_icon_text)
                val detectionName = detectionView.findViewById<TextView>(R.id.detection_name)
                val subscribeBtn = detectionView.findViewById<TextView>(R.id.subscribe_btn)

                // Set icon and color
                val (icon, color) = when (channel.eventType) {
                    "cd" -> Pair("CD", "#FF5722")
                    "id" -> Pair("ID", "#F44336")
                    "ct" -> Pair("CT", "#E91E63")
                    "sh" -> Pair("SH", "#FF9800")
                    "vd" -> Pair("VD", "#2196F3")
                    "pd" -> Pair("PD", "#4CAF50")
                    "vc" -> Pair("VC", "#FFC107")
                    "ls" -> Pair("LS", "#9C27B0")
                    "us" -> Pair("US", "#673AB7")
                    "ii" -> Pair("II", "#607D8B")
                    "off-route" -> Pair("OR", "#FF5722")
                    "tamper" -> Pair("TM", "#F44336")
                    else -> Pair("??", "#9E9E9E")
                }

                iconText.text = icon
                iconBackground.setBackgroundResource(R.drawable.circle_background)
                (iconBackground.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor(color))

                detectionName.text = channel.eventTypeDisplay

                // Update subscribe button state
                updateSubscribeButton(subscribeBtn, channel.isSubscribed)

                subscribeBtn.setOnClickListener {
                    onSubscribeToggle(channel)
                    // Update button immediately
                    updateSubscribeButton(subscribeBtn, !channel.isSubscribed)
                }

                holder.detectionTypesContainer.addView(detectionView)
            }
        } else {
            holder.detectionTypesContainer.visibility = View.GONE
        }
    }

    private fun updateSubscribeButton(button: TextView, isSubscribed: Boolean) {
        if (isSubscribed) {
            button.text = "Subscribed"
            button.setBackgroundResource(R.drawable.button_subscribed)
            button.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            button.text = "Subscribe"
            button.setBackgroundResource(R.drawable.button_subscribe)
            button.setTextColor(Color.WHITE)
        }
    }

    override fun getItemCount() = areaGroups.size

    fun updateAreaGroups(channels: List<Channel>) {
        // Group channels by area
        val grouped = channels.groupBy { it.area }
        areaGroups = grouped.map { (area, channelList) ->
            AreaGroup(
                area = area,
                areaDisplay = channelList.first().areaDisplay,
                channels = channelList.sortedBy { it.eventTypeDisplay }
            )
        }.sortedBy { it.areaDisplay }

        notifyDataSetChanged()
    }
}