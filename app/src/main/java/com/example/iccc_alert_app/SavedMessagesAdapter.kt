package com.example.iccc_alert_app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.*

class SavedMessagesAdapter(
    private val onDeleteClick: (SavedMessage) -> Unit
) : RecyclerView.Adapter<SavedMessagesAdapter.ViewHolder>() {

    private var messages = listOf<SavedMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    // Format for parsing eventTime from backend
    private val eventTimeParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val client = OkHttpClient()

    companion object {
        private const val TAG = "SavedMessagesAdapter"
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val eventType: TextView = view.findViewById(R.id.saved_event_type)
        val location: TextView = view.findViewById(R.id.saved_event_location)
        val timestamp: TextView = view.findViewById(R.id.saved_event_timestamp)
        val badge: View = view.findViewById(R.id.saved_event_badge)
        val iconText: TextView = view.findViewById(R.id.saved_event_icon_text)
        val eventImage: ImageView = view.findViewById(R.id.saved_event_image)
        val imageOverlay: View = view.findViewById(R.id.saved_image_overlay)
        val loadingContainer: LinearLayout = view.findViewById(R.id.saved_loading_container)
        val errorContainer: LinearLayout = view.findViewById(R.id.saved_error_container)
        val priorityBadge: TextView = view.findViewById(R.id.saved_priority_badge)
        val commentText: TextView = view.findViewById(R.id.saved_comment_text)
        val deleteButton: ImageView = view.findViewById(R.id.saved_delete_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val savedMessage = messages[position]
        val event = savedMessage.event

        holder.eventType.text = event.typeDisplay ?: "Unknown Event"

        val location = event.data["location"] as? String ?: "Unknown"
        holder.location.text = location

        // Use eventTime from data if available, otherwise fall back to timestamp
        val eventTimeStr = event.data["eventTime"] as? String
        val date = if (eventTimeStr != null) {
            try {
                eventTimeParser.parse(eventTimeStr) ?: Date(event.timestamp * 1000)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing eventTime: ${e.message}")
                Date(event.timestamp * 1000)
            }
        } else {
            Date(event.timestamp * 1000)
        }

        holder.timestamp.text = "${dateFormat.format(date)} ${timeFormat.format(date)}"

        val (iconText, color) = when (event.type) {
            "cd" -> Pair("CD", "#FF5722")
            "id" -> Pair("ID", "#F44336")
            "ct" -> Pair("CT", "#E91E63")
            "sh" -> Pair("SH", "#FF9800")
            "vd" -> Pair("VD", "#2196F3")
            "pd" -> Pair("PD", "#4CAF50")
            "vc" -> Pair("VC", "#FFC107")
            "ii" -> Pair("II", "#9C27B0")
            "ls" -> Pair("LS", "#00BCD4")
            else -> Pair("??", "#9E9E9E")
        }

        holder.iconText.text = iconText
        holder.badge.setBackgroundResource(R.drawable.circle_background)
        (holder.badge.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor(color))

        holder.priorityBadge.text = savedMessage.priority.displayName
        holder.priorityBadge.setBackgroundColor(Color.parseColor(savedMessage.priority.color))

        holder.commentText.text = savedMessage.comment

        holder.deleteButton.setOnClickListener {
            onDeleteClick(savedMessage)
        }

        holder.loadingContainer.visibility = View.VISIBLE
        holder.errorContainer.visibility = View.GONE
        holder.eventImage.visibility = View.GONE
        holder.imageOverlay.visibility = View.GONE

        if (event.id != null && event.area != null) {
            loadEventImage(event, holder)
        } else {
            showError(holder)
        }
    }

    private fun loadEventImage(event: Event, holder: ViewHolder) {
        val area = event.area ?: return
        val eventId = event.id ?: return

        val httpUrl = getHttpUrlForArea(area)
        val imageUrl = "$httpUrl/va/event/?id=$eventId"

        Log.d(TAG, "Loading image: $imageUrl")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(imageUrl)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val inputStream = response.body?.byteStream()
                    val bitmap = BitmapFactory.decodeStream(inputStream)

                    withContext(Dispatchers.Main) {
                        holder.loadingContainer.visibility = View.GONE
                        holder.errorContainer.visibility = View.GONE
                        holder.eventImage.visibility = View.VISIBLE
                        holder.imageOverlay.visibility = View.VISIBLE
                        holder.eventImage.setImageBitmap(bitmap)

                        holder.eventImage.alpha = 0f
                        holder.eventImage.animate()
                            .alpha(1f)
                            .setDuration(200)
                            .start()
                    }
                } else {
                    Log.e(TAG, "Failed to load image: ${response.code}")
                    withContext(Dispatchers.Main) {
                        showError(holder)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image: ${e.message}")
                withContext(Dispatchers.Main) {
                    showError(holder)
                }
            }
        }
    }

    private fun showError(holder: ViewHolder) {
        holder.loadingContainer.visibility = View.GONE
        holder.errorContainer.visibility = View.VISIBLE
        holder.eventImage.visibility = View.GONE
        holder.imageOverlay.visibility = View.GONE
    }

    private fun getHttpUrlForArea(area: String): String {
        return when (area.lowercase()) {
            "sijua", "katras" -> "http://a5va.bccliccc.in:10050"
            "kusunda" -> "http://a6va.bccliccc.in:5050"
            "bastacolla" -> "http://a9va.bccliccc.in:5050"
            "lodna" -> "http://a10va.bccliccc.in:5050"
            "govindpur" -> "http://103.208.173.163:5050"
            "barora" -> "http://103.208.173.131:5050"
            "block 2" -> "http://103.208.173.147:5050"
            "pb area" -> "http://103.208.173.195:5050"
            "wj" -> "http://103.208.173.211:5050"
            "ccwo" -> "http://103.208.173.179:5050"
            "cv area" -> "http://103.210.88.211:5050"
            "ej" -> "http://103.210.88.194:5050"
            else -> "http://a5va.bccliccc.in:10050"
        }
    }

    override fun getItemCount() = messages.size

    fun updateMessages(newMessages: List<SavedMessage>) {
        messages = newMessages
        notifyDataSetChanged()
    }
}