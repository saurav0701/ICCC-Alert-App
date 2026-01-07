package com.example.iccc_alert_app

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

/**
 * Camera List Adapter - Displays cameras in a clean list format
 * Shows camera name, location, area, status, and last update time
 */
class CameraListAdapter(
    private val context: Context,
    private val onCameraClick: (CameraInfo) -> Unit
) : RecyclerView.Adapter<CameraListAdapter.CameraViewHolder>() {

    private var cameras = listOf<CameraInfo>()

    class CameraViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.camera_card)
        val cameraIcon: ImageView = view.findViewById(R.id.camera_icon)
        val cameraName: TextView = view.findViewById(R.id.camera_name)
        val cameraLocation: TextView = view.findViewById(R.id.camera_location)
        val cameraArea: TextView = view.findViewById(R.id.camera_area)
        val statusIndicator: View = view.findViewById(R.id.status_indicator)
        val statusText: TextView = view.findViewById(R.id.status_text)
        val lastUpdate: TextView? = try {
            view.findViewById(R.id.camera_last_update)
        } catch (e: Exception) {
            null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CameraViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_camera_card, parent, false)
        return CameraViewHolder(view)
    }

    override fun onBindViewHolder(holder: CameraViewHolder, position: Int) {
        val camera = cameras[position]

        // Set camera information
        holder.cameraName.text = camera.name
        holder.cameraLocation.text = if (camera.location.isNotEmpty()) {
            camera.location
        } else {
            "Location unavailable"
        }
        holder.cameraArea.text = camera.area.uppercase()
        holder.lastUpdate?.text = camera.getFormattedLastUpdate()

        // Set online/offline status with visual feedback
        if (camera.isOnline()) {
            holder.statusIndicator.setBackgroundResource(R.drawable.status_indicator_online)
            holder.statusText.text = "ONLINE"
            holder.statusText.setTextColor(Color.parseColor("#4CAF50"))
            holder.card.alpha = 1.0f
            holder.card.cardElevation = 6f
            holder.cameraIcon.alpha = 1.0f
        } else {
            holder.statusIndicator.setBackgroundResource(R.drawable.status_indicator_offline)
            holder.statusText.text = "OFFLINE"
            holder.statusText.setTextColor(Color.parseColor("#F44336"))
            holder.card.alpha = 0.7f
            holder.card.cardElevation = 2f
            holder.cameraIcon.alpha = 0.5f
        }

        // Click listener with validation
        holder.card.setOnClickListener {
            when {
                !camera.isOnline() -> {
                    Toast.makeText(
                        context,
                        "${camera.name} is currently offline",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                !camera.hasValidStreamURL() -> {
                    Toast.makeText(
                        context,
                        "Stream not available for ${camera.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                else -> {
                    onCameraClick(camera)
                }
            }
        }

        // Long click for detailed camera info
        holder.card.setOnLongClickListener {
            showCameraInfo(camera)
            true
        }

        // Enable/disable card based on camera status
        holder.card.isEnabled = camera.isOnline() && camera.hasValidStreamURL()
    }

    private fun showCameraInfo(camera: CameraInfo) {
        val info = buildString {
            append("📹 ${camera.name}\n")
            append("📍 Area: ${camera.area}\n")
            append("🔌 Status: ${if (camera.isOnline()) "Online ✅" else "Offline ❌"}\n")
            append("📌 Location: ${camera.location.ifEmpty { "N/A" }}\n")
            append("🕒 Last Update: ${camera.getFormattedLastUpdate()}\n")
            if (camera.ip.isNotEmpty()) {
                append("🌐 IP: ${camera.ip}")
            }
        }

        Toast.makeText(context, info, Toast.LENGTH_LONG).show()
    }

    override fun getItemCount() = cameras.size

    fun updateCameras(newCameras: List<CameraInfo>) {
        cameras = newCameras
        notifyDataSetChanged()
    }

    fun pauseStreams() {
        // No active streams in list view
    }

    fun cleanup() {
        // Nothing to clean up
    }
}