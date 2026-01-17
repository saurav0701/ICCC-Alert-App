package com.example.iccc_alert_app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

/**
 * ✅ Adapter for displaying saved multi-camera views
 */
class MultiCameraViewAdapter(
    private val onViewClick: (MultiCameraView) -> Unit,
    private val onDeleteClick: (MultiCameraView) -> Unit
) : RecyclerView.Adapter<MultiCameraViewAdapter.ViewHolder>() {

    private var views = listOf<MultiCameraView>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.view_card)
        val nameText: TextView = view.findViewById(R.id.view_name)
        val statsText: TextView = view.findViewById(R.id.view_stats)
        val cameraCountText: TextView = view.findViewById(R.id.camera_count)
        val deleteButton: ImageButton = view.findViewById(R.id.delete_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_multi_camera_view, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val view = views[position]
        val cameras = view.getCameras()
        val onlineCount = view.getOnlineCount()

        holder.nameText.text = view.name
        holder.cameraCountText.text = "${cameras.size}"

        if (onlineCount > 0) {
            holder.statsText.text = "$onlineCount camera${if (onlineCount == 1) "" else "s"} online"
            holder.statsText.setTextColor(holder.itemView.context.getColor(android.R.color.holo_green_dark))
        } else {
            holder.statsText.text = "All cameras offline"
            holder.statsText.setTextColor(holder.itemView.context.getColor(android.R.color.holo_red_dark))
        }

        holder.card.setOnClickListener {
            onViewClick(view)
        }

        holder.deleteButton.setOnClickListener {
            onDeleteClick(view)
        }
    }

    override fun getItemCount() = views.size

    fun updateViews(newViews: List<MultiCameraView>) {
        views = newViews
        notifyDataSetChanged()
    }
}