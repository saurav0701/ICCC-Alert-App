package com.example.iccc_alert_app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SubscriptionAdapter(
    private val onUnsubscribe: (Channel) -> Unit
) : RecyclerView.Adapter<SubscriptionAdapter.ViewHolder>() {

    private var subscriptions = listOf<Channel>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.channel_title)
        val subtitle: TextView = view.findViewById(R.id.channel_subtitle)
        val iconBackground: View = view.findViewById(R.id.channel_icon_background)
        val iconText: TextView = view.findViewById(R.id.channel_icon_text)
        val unsubscribeBtn: ImageButton = view.findViewById(R.id.unsubscribe_btn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subscription, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = subscriptions[position]
        holder.title.text = channel.areaDisplay
        holder.subtitle.text = channel.eventTypeDisplay

        // Icon based on event type with color
        val (iconText, color) = when (channel.eventType) {
            "cd" -> Pair("CD", "#FF5722")
            "id" -> Pair("ID", "#F44336")
            "ct" -> Pair("CT", "#E91E63")
            "sh" -> Pair("SH", "#FF9800")
            "vd" -> Pair("VD", "#2196F3")
            "pd" -> Pair("PD", "#4CAF50")
            "vc" -> Pair("VC", "#FFC107")
            "ii" -> Pair("II", "#2196F3")
            "ls" -> Pair("LS", "#2196F3")
            else -> Pair("II", "#9E9E9E")
        }

        holder.iconText.text = iconText
        holder.iconBackground.setBackgroundResource(R.drawable.circle_background)
        (holder.iconBackground.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor(color))

        holder.unsubscribeBtn.setOnClickListener {
            onUnsubscribe(channel)
        }
    }

    override fun getItemCount() = subscriptions.size

    fun updateSubscriptions(newSubscriptions: List<Channel>) {
        subscriptions = newSubscriptions
        notifyDataSetChanged()
    }
}