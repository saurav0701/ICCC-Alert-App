package com.example.iccc_alert_app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

/**
 * ✅ Camera Selection Adapter - For creating quad views (Fixed)
 * Prevents RecyclerView IllegalStateException by properly handling checkbox state
 */
class CameraSelectionAdapter(
    private val onCameraSelected: (CameraInfo) -> Unit,
    private val isSelected: (String) -> Boolean,
    private val canSelect: (String) -> Boolean
) : RecyclerView.Adapter<CameraSelectionAdapter.ViewHolder>() {

    private var cameras = listOf<CameraInfo>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.selection_card)
        val checkBox: CheckBox = view.findViewById(R.id.selection_checkbox)
        val nameText: TextView = view.findViewById(R.id.camera_name)
        val locationText: TextView = view.findViewById(R.id.camera_location)
        val statusText: TextView = view.findViewById(R.id.status_text)
        val selectionBorder: View = view.findViewById(R.id.selection_border)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_camera_selection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val camera = cameras[position]
        val selected = isSelected(camera.id)
        val selectable = canSelect(camera.id)

        holder.nameText.text = camera.name
        holder.locationText.text = if (camera.location.isNotEmpty()) {
            camera.location
        } else {
            camera.area.uppercase()
        }

        holder.statusText.text = "ONLINE"
        holder.statusText.setTextColor(Color.parseColor("#4CAF50"))

        // ✅ CRITICAL FIX: Remove listener before setting checked state
        // This prevents triggering the listener during layout computation
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = selected

        holder.card.alpha = if (selectable || selected) 1.0f else 0.5f

        // Handle card click
        holder.card.setOnClickListener {
            if (selectable || selected) {
                onCameraSelected(camera)
            }
        }

        // ✅ FIX: Set listener AFTER setting the checked state
        // Only trigger callback if user manually changes the state
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != selected && (selectable || selected)) {
                onCameraSelected(camera)
            }
        }

        // Visual feedback for selection
        if (selected) {
            holder.card.setCardBackgroundColor(Color.parseColor("#E3F2FD"))
            holder.card.cardElevation = 8f
            holder.selectionBorder.visibility = View.VISIBLE
        } else {
            holder.card.setCardBackgroundColor(Color.WHITE)
            holder.card.cardElevation = 3f
            holder.selectionBorder.visibility = View.GONE
        }
    }

    override fun getItemCount() = cameras.size

    fun updateCameras(newCameras: List<CameraInfo>) {
        cameras = newCameras
        notifyDataSetChanged()
    }
}