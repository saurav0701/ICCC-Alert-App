package com.example.iccc_alert_app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import android.widget.FrameLayout

class SavedMessagesAdapter(
    private val context: Context,
    private val onDeleteClick: (SavedMessage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var messages = listOf<SavedMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val eventTimeParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val client = OkHttpClient()

    // Cache for loaded images
    private val imageCache = mutableMapOf<String, Bitmap>()

    companion object {
        private const val TAG = "SavedMessagesAdapter"
        private const val VIEW_TYPE_VA_EVENT = 0
        private const val VIEW_TYPE_GPS_EVENT = 1
    }

    class VAEventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
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

    class GPSEventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val mapPreviewFrame: FrameLayout = view.findViewById(R.id.saved_map_preview_frame)
        val mapPreview: MapView = view.findViewById(R.id.saved_map_preview)
        val mapLoadingOverlay: FrameLayout = view.findViewById(R.id.saved_map_loading_overlay)
        val eventType: TextView = view.findViewById(R.id.saved_gps_event_type)
        val vehicleNumber: TextView = view.findViewById(R.id.saved_vehicle_number)
        val vehicleTransporter: TextView = view.findViewById(R.id.saved_vehicle_transporter)
        val timestamp: TextView = view.findViewById(R.id.saved_gps_timestamp)
        val badge: View = view.findViewById(R.id.saved_gps_badge)
        val iconText: TextView = view.findViewById(R.id.saved_gps_icon_text)
        val priorityBadge: TextView = view.findViewById(R.id.saved_gps_priority_badge)
        val commentText: TextView = view.findViewById(R.id.saved_gps_comment_text)
        val viewOnMapButton: android.widget.Button = view.findViewById(R.id.saved_view_on_map_button)
        val deleteButton: ImageView = view.findViewById(R.id.saved_gps_delete_button)
    }

    override fun getItemViewType(position: Int): Int {
        val event = messages[position].event
        return if (event.type == "off-route" || event.type == "tamper" || event.type == "overspeed") {
            VIEW_TYPE_GPS_EVENT
        } else {
            VIEW_TYPE_VA_EVENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_GPS_EVENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_saved_gps_message, parent, false)
                GPSEventViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_saved_message, parent, false)
                VAEventViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is VAEventViewHolder -> bindVAEvent(holder, messages[position])
            is GPSEventViewHolder -> bindGPSEvent(holder, messages[position])
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is GPSEventViewHolder) {
            try {
                holder.mapPreview.onDetach()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling map view: ${e.message}")
            }
        }
    }

    private fun bindVAEvent(holder: VAEventViewHolder, savedMessage: SavedMessage) {
        val event = savedMessage.event

        holder.eventType.text = event.typeDisplay ?: "Unknown Event"
        val location = event.data["location"] as? String ?: "Unknown"
        holder.location.text = location

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
            loadEventImage(event, holder, savedMessage)
        } else {
            showVAError(holder)
        }
    }

    private fun bindGPSEvent(holder: GPSEventViewHolder, savedMessage: SavedMessage) {
        val event = savedMessage.event

        holder.eventType.text = event.typeDisplay ?: "GPS Alert"

        val vehicleNum = event.vehicleNumber ?: "Unknown"
        val transporter = event.vehicleTransporter ?: "Unknown"

        holder.vehicleNumber.text = vehicleNum
        holder.vehicleTransporter.text = transporter

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
            "off-route" -> Pair("OR", "#FF5722")
            "tamper" -> Pair("TM", "#F44336")
            "overspeed" -> Pair("OS", "#FF9800")
            else -> Pair("GPS", "#9E9E9E")
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

        setupMapPreview(holder, event)

        // Click on map preview to open full map view
        holder.mapPreviewFrame.setOnClickListener {
            openMapView(event)
        }

        // View on Map button
        holder.viewOnMapButton.setOnClickListener {
            openMapView(event)
        }
    }

    private fun setupMapPreview(holder: GPSEventViewHolder, event: Event) {
        try {
            val baseUrls = arrayOf(
                "https://mt0.google.com/vt/lyrs=y&hl=en",
                "https://mt1.google.com/vt/lyrs=y&hl=en",
                "https://mt2.google.com/vt/lyrs=y&hl=en",
                "https://mt3.google.com/vt/lyrs=y&hl=en"
            )

            holder.mapPreview.setTileSource(object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
                "Google-Hybrid",
                0, 22, 256, ".png",
                baseUrls
            ) {
                override fun getTileURLString(pMapTileIndex: Long): String {
                    val zoom = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
                    val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
                    val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
                    val serverIndex = (x + y) % baseUrls.size
                    return "${baseUrls[serverIndex]}&x=$x&y=$y&z=$zoom&s=Ga"
                }
            })

            holder.mapPreview.setMultiTouchControls(false)
            holder.mapPreview.setBuiltInZoomControls(false)
            holder.mapPreview.isClickable = false
            holder.mapPreview.isFocusable = false

            holder.mapLoadingOverlay.visibility = View.VISIBLE

            CoroutineScope(Dispatchers.Main).launch {
                val mapData = withContext(Dispatchers.Default) {
                    prepareMapPreviewData(event)
                }

                withContext(Dispatchers.Main) {
                    renderMapPreview(holder, mapData)
                    holder.mapLoadingOverlay.visibility = View.GONE
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up map preview: ${e.message}", e)
            holder.mapLoadingOverlay.visibility = View.GONE
        }
    }

    private data class GeofenceData(
        val points: List<GeoPoint>,
        val type: String,
        val color: String
    )

    private data class MapPreviewData(
        val alertLocation: GeoPoint?,
        val geofenceData: GeofenceData?,
        val boundingBox: org.osmdroid.util.BoundingBox?
    )

    private fun prepareMapPreviewData(event: Event): MapPreviewData {
        val alertLocation = extractAlertLocation(event.data)
        val geofenceData = extractGeofenceData(event.data)

        val allPoints = mutableListOf<GeoPoint>()
        alertLocation?.let { allPoints.add(it) }
        geofenceData?.points?.let { allPoints.addAll(it) }

        val boundingBox = if (allPoints.isNotEmpty()) {
            if (event.type == "tamper" && geofenceData == null && alertLocation != null) {
                allPoints.add(GeoPoint(alertLocation.latitude + 0.002, alertLocation.longitude))
                allPoints.add(GeoPoint(alertLocation.latitude - 0.002, alertLocation.longitude))
                allPoints.add(GeoPoint(alertLocation.latitude, alertLocation.longitude + 0.002))
                allPoints.add(GeoPoint(alertLocation.latitude, alertLocation.longitude - 0.002))
            }
            org.osmdroid.util.BoundingBox.fromGeoPoints(allPoints)
        } else null

        return MapPreviewData(
            alertLocation = alertLocation,
            geofenceData = geofenceData,
            boundingBox = boundingBox
        )
    }

    private fun extractAlertLocation(data: Map<String, Any>?): GeoPoint? {
        if (data == null) return null
        val alertLoc = data["alertLocation"] as? Map<*, *> ?: return null
        val lat = (alertLoc["lat"] as? Number)?.toDouble() ?: return null
        val lng = (alertLoc["lng"] as? Number)?.toDouble() ?: return null
        return GeoPoint(lat, lng)
    }

    private fun extractGeofenceData(data: Map<String, Any>?): GeofenceData? {
        if (data == null) return null

        val geofenceMap = data["geofence"] as? Map<*, *> ?: return null
        val geojsonMap = geofenceMap["geojson"] as? Map<*, *> ?: return null
        val type = geojsonMap["type"] as? String ?: return null
        val coordinates = geojsonMap["coordinates"] ?: return null

        val attributesMap = geofenceMap["attributes"] as? Map<*, *>
        val color = attributesMap?.get("color") as? String
        val polylineColor = attributesMap?.get("polylineColor") as? String

        val points = when (type) {
            "Point" -> {
                val coord = coordinates as? List<*> ?: return null
                listOf(GeoPoint(
                    (coord[1] as? Number)?.toDouble() ?: return null,
                    (coord[0] as? Number)?.toDouble() ?: return null
                ))
            }
            "LineString" -> {
                val coords = coordinates as? List<*> ?: return null
                coords.mapNotNull { coordPair ->
                    val pair = coordPair as? List<*> ?: return@mapNotNull null
                    if (pair.size < 2) return@mapNotNull null
                    GeoPoint(
                        (pair[1] as? Number)?.toDouble() ?: return@mapNotNull null,
                        (pair[0] as? Number)?.toDouble() ?: return@mapNotNull null
                    )
                }
            }
            "Polygon" -> {
                val rings = coordinates as? List<*> ?: return null
                val outerRing = rings.firstOrNull() as? List<*> ?: return null
                outerRing.mapNotNull { coordPair ->
                    val pair = coordPair as? List<*> ?: return@mapNotNull null
                    if (pair.size < 2) return@mapNotNull null
                    GeoPoint(
                        (pair[1] as? Number)?.toDouble() ?: return@mapNotNull null,
                        (pair[0] as? Number)?.toDouble() ?: return@mapNotNull null
                    )
                }
            }
            else -> return null
        }

        if (points.isEmpty()) return null

        return GeofenceData(
            points = points,
            type = type,
            color = polylineColor ?: color ?: "#3388ff"
        )
    }

    private fun renderMapPreview(holder: GPSEventViewHolder, mapData: MapPreviewData) {
        try {
            holder.mapPreview.overlays.clear()

            // Draw geofence
            if (mapData.geofenceData != null) {
                val color = parseColor(mapData.geofenceData.color)

                when (mapData.geofenceData.type) {
                    "LineString" -> {
                        val polyline = Polyline(holder.mapPreview)
                        polyline.setPoints(mapData.geofenceData.points)
                        polyline.outlinePaint.color = color
                        polyline.outlinePaint.strokeWidth = 6f
                        polyline.outlinePaint.isAntiAlias = true
                        holder.mapPreview.overlays.add(polyline)
                    }
                    "Polygon" -> {
                        val polygon = Polygon(holder.mapPreview)
                        polygon.points = mapData.geofenceData.points
                        polygon.fillPaint.color = Color.argb(30, Color.red(color), Color.green(color), Color.blue(color))
                        polygon.outlinePaint.color = color
                        polygon.outlinePaint.strokeWidth = 4f
                        polygon.outlinePaint.isAntiAlias = true
                        holder.mapPreview.overlays.add(polygon)
                    }
                    "Point" -> {
                        val point = mapData.geofenceData.points.first()
                        val circle = Polygon(holder.mapPreview)
                        circle.points = Polygon.pointsAsCircle(point, 100.0)
                        circle.fillPaint.color = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
                        circle.outlinePaint.color = color
                        circle.outlinePaint.strokeWidth = 3f
                        holder.mapPreview.overlays.add(circle)
                    }
                }
            }

            // Draw alert location marker
            if (mapData.alertLocation != null) {
                val marker = Marker(holder.mapPreview)
                marker.position = mapData.alertLocation
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.icon = holder.itemView.context.getDrawable(R.drawable.ic_location_red)
                holder.mapPreview.overlays.add(marker)
            }

            // Set map bounds
            if (mapData.boundingBox != null) {
                holder.mapPreview.post {
                    try {
                        holder.mapPreview.zoomToBoundingBox(mapData.boundingBox, true, 50)
                        if (holder.mapPreview.zoomLevelDouble < 13.0) {
                            holder.mapPreview.controller.setZoom(13.0)
                        }
                        if (holder.mapPreview.zoomLevelDouble > 18.0) {
                            holder.mapPreview.controller.setZoom(18.0)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting map bounds: ${e.message}")
                        mapData.alertLocation?.let {
                            holder.mapPreview.controller.setZoom(15.0)
                            holder.mapPreview.controller.setCenter(it)
                        }
                    }
                }
            } else if (mapData.alertLocation != null) {
                holder.mapPreview.controller.setZoom(15.0)
                holder.mapPreview.controller.setCenter(mapData.alertLocation)
            }

            holder.mapPreview.invalidate()

        } catch (e: Exception) {
            Log.e(TAG, "Error rendering map preview: ${e.message}", e)
        }
    }

    private fun parseColor(colorStr: String?): Int {
        if (colorStr == null) return Color.parseColor("#3388ff")
        return try {
            if (colorStr.startsWith("#")) {
                Color.parseColor(colorStr)
            } else {
                Color.parseColor("#$colorStr")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse color: $colorStr, using default")
            Color.parseColor("#3388ff")
        }
    }

    private fun loadEventImage(event: Event, holder: VAEventViewHolder, savedMessage: SavedMessage) {
        val area = event.area ?: return
        val eventId = event.id ?: return

        // Check cache first
        val cacheKey = "$area-$eventId"
        if (imageCache.containsKey(cacheKey)) {
            displayCachedImage(holder, imageCache[cacheKey]!!, event, savedMessage)
            return
        }

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

                    // Cache the bitmap
                    imageCache[cacheKey] = bitmap

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

                        // Add click listener to open ImageViewerActivity
                        holder.eventImage.setOnClickListener {
                            openImageViewer(bitmap, event)
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to load image: ${response.code}")
                    withContext(Dispatchers.Main) {
                        showVAError(holder)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image: ${e.message}")
                withContext(Dispatchers.Main) {
                    showVAError(holder)
                }
            }
        }
    }

    private fun displayCachedImage(holder: VAEventViewHolder, bitmap: Bitmap, event: Event, savedMessage: SavedMessage) {
        holder.loadingContainer.visibility = View.GONE
        holder.errorContainer.visibility = View.GONE
        holder.eventImage.visibility = View.VISIBLE
        holder.imageOverlay.visibility = View.VISIBLE
        holder.eventImage.setImageBitmap(bitmap)

        // Add click listener to open ImageViewerActivity
        holder.eventImage.setOnClickListener {
            openImageViewer(bitmap, event)
        }
    }

    private fun openImageViewer(bitmap: Bitmap, event: Event) {
        try {
            // Save bitmap to temporary file
            val tempFile = File(context.cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            // Get URI using FileProvider
            val imageUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )

            val intent = Intent(context, ImageViewerActivity::class.java)
            intent.putExtra("IMAGE_URI", imageUri.toString())
            intent.putExtra("EVENT_TYPE", event.typeDisplay ?: "Event")
            intent.putExtra("EVENT_LOCATION", event.data["location"] as? String ?: "Unknown")
            context.startActivity(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Error opening image viewer: ${e.message}", e)
            Toast.makeText(context, "Unable to open image viewer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showVAError(holder: VAEventViewHolder) {
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

    private fun openMapView(event: Event) {
        val gpsEvent = event.toGpsEvent()
        if (gpsEvent == null) {
            Toast.makeText(context, "Invalid GPS event data", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(context, MapActivity::class.java)
        intent.putExtra(MapActivity.EXTRA_GPS_EVENT, Gson().toJson(gpsEvent))
        context.startActivity(intent)
    }

    override fun getItemCount() = messages.size

    fun updateMessages(newMessages: List<SavedMessage>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    // Clean up cache when needed
    fun clearImageCache() {
        imageCache.clear()
    }
}