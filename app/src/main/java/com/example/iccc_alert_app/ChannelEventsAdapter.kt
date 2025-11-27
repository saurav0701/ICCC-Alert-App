package com.example.iccc_alert_app

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import com.google.gson.Gson
import java.util.*
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

class ChannelEventsAdapter(
    private val context: Context,
    private val onBackClick: () -> Unit,
    private val channelArea: String,
    private val channelType: String,
    private val channelEventType: String
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var allEvents = listOf<Event>()
    private var filteredEvents = listOf<Event>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val eventTimeParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val client = OkHttpClient()
    private val pdfGenerator = PdfGenerator(context)

    // Filter state
    private var searchQuery = ""
    private var selectedFilterOption = "All"
    private var customFromDate: Date? = null
    private var customToDate: Date? = null

    companion object {
        private const val TAG = "ChannelEventsAdapter"
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_EVENT = 1
        private const val VIEW_TYPE_GPS_EVENT = 2
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
            color = polylineColor ?: color ?: "#3388ff",
            geofenceType = geofenceMap["type"] as? String
        )
    }

    private data class GeofenceData(
        val points: List<GeoPoint>,
        val type: String,
        val color: String,
        val geofenceType: String?
    )

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val backButton: LinearLayout = view.findViewById(R.id.back_button_header)
        val channelBadge: View = view.findViewById(R.id.channel_badge)
        val channelIconText: TextView = view.findViewById(R.id.channel_icon_text)
        val channelName: TextView = view.findViewById(R.id.channel_name_header)
        val eventCount: TextView = view.findViewById(R.id.event_count)
        val dateDivider: TextView = view.findViewById(R.id.date_divider)

        val searchButton: FrameLayout = view.findViewById(R.id.search_button)
        val searchContainer: LinearLayout = view.findViewById(R.id.search_container)
        val searchInput: EditText = view.findViewById(R.id.search_input)
        val closeSearch: FrameLayout = view.findViewById(R.id.close_search)

        val filterButton: FrameLayout = view.findViewById(R.id.filter_button)
        val filterContainer: LinearLayout = view.findViewById(R.id.filter_container)
        val dateFilterSpinner: Spinner = view.findViewById(R.id.date_filter_spinner)
        val applyFilterButton: Button = view.findViewById(R.id.apply_filter_button)
        val customDateContainer: LinearLayout = view.findViewById(R.id.custom_date_container)
        val fromDateButton: Button = view.findViewById(R.id.from_date_button)
        val toDateButton: Button = view.findViewById(R.id.to_date_button)

        val downloadPdfButton: FrameLayout = view.findViewById(R.id.download_pdf_button)
    }

    class EventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val eventType: TextView = view.findViewById(R.id.event_type)
        val location: TextView = view.findViewById(R.id.event_location)
        val timestamp: TextView = view.findViewById(R.id.event_timestamp)
        val eventDate: TextView = view.findViewById(R.id.event_date)
        val badge: View = view.findViewById(R.id.event_badge)
        val iconText: TextView = view.findViewById(R.id.event_icon_text)
        val eventImage: ImageView = view.findViewById(R.id.event_image)
        val imageFrame: FrameLayout = view.findViewById(R.id.image_frame)
        val imageOverlay: View = view.findViewById(R.id.image_overlay)
        val loadingContainer: LinearLayout = view.findViewById(R.id.loading_container)
        val errorContainer: LinearLayout = view.findViewById(R.id.error_container)

        // NEW: Save functionality
        val saveEventButton: Button = view.findViewById(R.id.save_event_button)
        val moreActionsButton: Button = view.findViewById(R.id.more_actions_button)
        val savePrioritySection: LinearLayout = view.findViewById(R.id.save_priority_section)
        val prioritySpinner: Spinner = view.findViewById(R.id.priority_spinner)
        val commentInput: EditText = view.findViewById(R.id.comment_input)
        val cancelSaveButton: Button = view.findViewById(R.id.cancel_save_button)
        val confirmSaveButton: Button = view.findViewById(R.id.confirm_save_button)

        // Action buttons
        val actionButtonsContainer: LinearLayout = view.findViewById(R.id.action_buttons_container)
        val downloadImageButton: Button = view.findViewById(R.id.download_image_button)
        val shareImageButton: Button = view.findViewById(R.id.share_image_button)
        val downloadEventPdfButton: Button = view.findViewById(R.id.download_event_pdf_button)
    }

    class GpsEventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val mapPreviewFrame: FrameLayout = view.findViewById(R.id.map_preview_frame)
        val mapPreview: MapView = view.findViewById(R.id.map_preview)
        val mapLoadingOverlay: FrameLayout = view.findViewById(R.id.map_loading_overlay)
        val eventType: TextView = view.findViewById(R.id.event_type)
        val vehicleNumber: TextView = view.findViewById(R.id.vehicle_number)
        val vehicleTransporter: TextView = view.findViewById(R.id.vehicle_transporter)
        val timestamp: TextView = view.findViewById(R.id.event_timestamp)
        val eventDate: TextView = view.findViewById(R.id.event_date)
        val badge: View = view.findViewById(R.id.event_badge)
        val iconText: TextView = view.findViewById(R.id.event_icon_text)
        val viewOnMapButton: Button = view.findViewById(R.id.view_on_map_button)
        val alertSubtypeContainer: LinearLayout = view.findViewById(R.id.alert_subtype_container)
        val alertSubtype: TextView = view.findViewById(R.id.alert_subtype)
        val geofenceContainer: LinearLayout = view.findViewById(R.id.geofence_container)
        val geofenceName: TextView = view.findViewById(R.id.geofence_name)

        val saveGpsEventButton: Button = view.findViewById(R.id.save_gps_event_button)
        val moreGpsActionsButton: Button = view.findViewById(R.id.more_gps_actions_button)
        val saveGpsPrioritySection: LinearLayout = view.findViewById(R.id.save_gps_priority_section)
        val gpsPrioritySpinner: Spinner = view.findViewById(R.id.gps_priority_spinner)
        val gpsCommentInput: EditText = view.findViewById(R.id.gps_comment_input)
        val cancelGpsSaveButton: Button = view.findViewById(R.id.cancel_gps_save_button)
        val confirmGpsSaveButton: Button = view.findViewById(R.id.confirm_gps_save_button)

        // GPS Action buttons
        val gpsActionButtonsContainer: LinearLayout = view.findViewById(R.id.gps_action_buttons_container)
        val downloadGpsPdfButton: Button = view.findViewById(R.id.download_gps_pdf_button)
        val shareGpsLocationButton: Button = view.findViewById(R.id.share_gps_location_button)
    }

    private data class MapPreviewData(
        val alertLocation: GeoPoint?,
        val geofencePoints: List<GeoPoint>?,
        val geofenceType: String?,
        val geofenceColor: String?,
        val boundingBox: org.osmdroid.util.BoundingBox?
    )

    override fun getItemViewType(position: Int): Int {
        if (position == 0) return VIEW_TYPE_HEADER

        val eventIndex = position - 1
        val event = filteredEvents[eventIndex]

        return if (event.type == "off-route" || event.type == "tamper" || event.type == "overspeed") {
            VIEW_TYPE_GPS_EVENT
        } else {
            VIEW_TYPE_EVENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_events_header, parent, false)
                HeaderViewHolder(view)
            }
            VIEW_TYPE_GPS_EVENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_gps_event, parent, false)
                GpsEventViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_channel_event, parent, false)
                EventViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> setupHeader(holder)
            is GpsEventViewHolder -> {
                val eventIndex = position - 1
                val event = filteredEvents[eventIndex]
                setupGpsEvent(holder, event)
            }
            is EventViewHolder -> {
                val eventIndex = position - 1
                val event = filteredEvents[eventIndex]
                setupEvent(holder, event)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is GpsEventViewHolder) {
            try {
                holder.mapPreview.onDetach()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling map view: ${e.message}")
            }
        }
    }

    override fun getItemCount() = filteredEvents.size + 1

    fun updateEvents(newEvents: List<Event>) {
        allEvents = newEvents
        filteredEvents = newEvents
        notifyDataSetChanged()
    }

    private fun setupHeader(holder: HeaderViewHolder) {
        holder.backButton.setOnClickListener {
            onBackClick()
        }

        holder.channelName.text = "$channelArea - $channelType"

        val count = filteredEvents.size
        holder.eventCount.text = if (count == 1) "1 event" else "$count events"

        val (iconText, color) = getEventTypeIcon(channelEventType)
        holder.channelIconText.text = iconText
        holder.channelBadge.setBackgroundResource(R.drawable.circle_background)
        (holder.channelBadge.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor(color))

        if (filteredEvents.isNotEmpty()) {
            val firstEventDate = getEventDate(filteredEvents[0])
            holder.dateDivider.text = getDateDividerText(firstEventDate)
        } else {
            holder.dateDivider.text = "NO EVENTS"
        }

        setupSearch(holder)
        setupFilter(holder)
        setupPdfDownload(holder)
    }

    private fun setupPdfDownload(holder: HeaderViewHolder) {
        holder.downloadPdfButton.setOnClickListener {
            if (filteredEvents.isEmpty()) {
                Toast.makeText(context, "No events to export", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val progressDialog = android.app.ProgressDialog(context)
            progressDialog.setMessage("Generating PDF...")
            progressDialog.setCancelable(false)
            progressDialog.show()

            CoroutineScope(Dispatchers.Main).launch {
                val pdfFile = withContext(Dispatchers.IO) {
                    pdfGenerator.generateBulkEventsPdf(
                        filteredEvents,
                        channelArea,
                        channelType
                    )
                }

                progressDialog.dismiss()

                if (pdfFile != null) {
                    Toast.makeText(context, "PDF saved: ${pdfFile.name}", Toast.LENGTH_LONG).show()
                    openPdfFile(pdfFile)
                } else {
                    Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupSearch(holder: HeaderViewHolder) {
        holder.searchButton.setOnClickListener {
            holder.searchContainer.visibility = View.VISIBLE
            holder.filterContainer.visibility = View.GONE
            holder.searchInput.requestFocus()
        }

        holder.closeSearch.setOnClickListener {
            holder.searchContainer.visibility = View.GONE
            holder.searchInput.text.clear()
            searchQuery = ""
            applyFilters()
        }

        holder.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.lowercase() ?: ""
                applyFilters()
            }
        })
    }

    private fun setupFilter(holder: HeaderViewHolder) {
        holder.filterButton.setOnClickListener {
            if (holder.filterContainer.visibility == View.VISIBLE) {
                holder.filterContainer.visibility = View.GONE
            } else {
                holder.filterContainer.visibility = View.VISIBLE
                holder.searchContainer.visibility = View.GONE
            }
        }

        val filterOptions = arrayOf("All", "Today", "Yesterday", "Custom Date Range")
        val adapter = ArrayAdapter(
            holder.itemView.context,
            android.R.layout.simple_spinner_item,
            filterOptions
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        holder.dateFilterSpinner.adapter = adapter

        holder.dateFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedFilterOption = filterOptions[position]
                if (selectedFilterOption == "Custom Date Range") {
                    holder.customDateContainer.visibility = View.VISIBLE
                } else {
                    holder.customDateContainer.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        holder.fromDateButton.setOnClickListener {
            showDatePicker(holder.itemView.context) { date ->
                customFromDate = date
                holder.fromDateButton.text = displayDateFormat.format(date)
            }
        }

        holder.toDateButton.setOnClickListener {
            showDatePicker(holder.itemView.context) { date ->
                customToDate = date
                holder.toDateButton.text = displayDateFormat.format(date)
            }
        }

        holder.applyFilterButton.setOnClickListener {
            applyFilters()
            holder.filterContainer.visibility = View.GONE
        }
    }

    private fun showDatePicker(context: android.content.Context, onDateSelected: (Date) -> Unit) {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selectedCalendar = Calendar.getInstance()
                selectedCalendar.set(year, month, dayOfMonth, 0, 0, 0)
                selectedCalendar.set(Calendar.MILLISECOND, 0)
                onDateSelected(selectedCalendar.time)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun applyFilters() {
        filteredEvents = allEvents.filter { event ->
            val matchesSearch = if (searchQuery.isEmpty()) {
                true
            } else {
                val location = (event.data["location"] as? String ?: "").lowercase()
                val eventType = (event.typeDisplay ?: "").lowercase()
                location.contains(searchQuery) || eventType.contains(searchQuery)
            }

            val matchesDateFilter = when (selectedFilterOption) {
                "All" -> true
                "Today" -> isToday(getEventDate(event))
                "Yesterday" -> isYesterday(getEventDate(event))
                "Custom Date Range" -> {
                    if (customFromDate != null && customToDate != null) {
                        val eventDate = getEventDate(event)
                        val from = resetTime(customFromDate!!)
                        val to = resetTime(customToDate!!)
                        to.add(Calendar.DAY_OF_MONTH, 1)
                        eventDate >= from.time && eventDate < to.time
                    } else {
                        true
                    }
                }
                else -> true
            }

            matchesSearch && matchesDateFilter
        }

        notifyDataSetChanged()
    }

    private fun resetTime(date: Date): Calendar {
        val cal = Calendar.getInstance()
        cal.time = date
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }

    private fun isToday(date: Date): Boolean {
        val today = Calendar.getInstance()
        val eventCal = Calendar.getInstance()
        eventCal.time = date
        return isSameDay(today, eventCal)
    }

    private fun isYesterday(date: Date): Boolean {
        val yesterday = Calendar.getInstance()
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        val eventCal = Calendar.getInstance()
        eventCal.time = date
        return isSameDay(yesterday, eventCal)
    }

    private fun setupEvent(holder: EventViewHolder, event: Event) {
        holder.eventType.text = event.typeDisplay ?: "Unknown Event"

        val location = event.data["location"] as? String ?: "Unknown"
        holder.location.text = location

        val eventTimeStr = event.data["eventTime"] as? String
        val eventDateTime = if (eventTimeStr != null) {
            try {
                eventTimeParser.parse(eventTimeStr) ?: Date(event.timestamp * 1000)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing eventTime: ${e.message}")
                Date(event.timestamp * 1000)
            }
        } else {
            Date(event.timestamp * 1000)
        }

        holder.timestamp.text = timeFormat.format(eventDateTime)
        holder.eventDate.text = dateFormat.format(eventDateTime)

        val (iconText, color) = getEventTypeIcon(event.type ?: "")
        holder.iconText.text = iconText
        holder.badge.setBackgroundResource(R.drawable.circle_background)
        (holder.badge.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor(color))

        setupPrioritySpinner(holder)

        // Hide all expandable sections initially
        holder.savePrioritySection.visibility = View.GONE
        holder.actionButtonsContainer.visibility = View.GONE

        // Check if event is already saved
        val isSaved = event.id?.let { SavedMessagesManager.isMessageSaved(it) } ?: false

        if (isSaved) {
            holder.saveEventButton.text = "Saved ✓"
            holder.saveEventButton.isEnabled = false
            holder.saveEventButton.alpha = 0.6f
        } else {
            holder.saveEventButton.text = "Save"
            holder.saveEventButton.isEnabled = true
            holder.saveEventButton.alpha = 1.0f
        }

        // Save button - shows priority selection
        holder.saveEventButton.setOnClickListener {
            if (isSaved) {
                Toast.makeText(context, "Event already saved", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            holder.savePrioritySection.visibility = View.VISIBLE
            holder.actionButtonsContainer.visibility = View.GONE
        }

        // More actions button
        holder.moreActionsButton.setOnClickListener {
            if (holder.actionButtonsContainer.visibility == View.VISIBLE) {
                holder.actionButtonsContainer.visibility = View.GONE
            } else {
                holder.actionButtonsContainer.visibility = View.VISIBLE
                holder.savePrioritySection.visibility = View.GONE
            }
        }

        // Cancel save button
        holder.cancelSaveButton.setOnClickListener {
            holder.savePrioritySection.visibility = View.GONE
            holder.commentInput.text.clear()
            holder.prioritySpinner.setSelection(0)
        }

        // Confirm save button
        holder.confirmSaveButton.setOnClickListener {
            val eventId = event.id
            if (eventId == null) {
                Toast.makeText(context, "Cannot save: Invalid event", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val priorityIndex = holder.prioritySpinner.selectedItemPosition
            val priority = Priority.values()[priorityIndex]
            val comment = holder.commentInput.text.toString().trim()

            val saved = SavedMessagesManager.saveMessage(eventId, event, priority, comment)

            if (saved) {
                Toast.makeText(context, "Event saved successfully", Toast.LENGTH_SHORT).show()
                holder.saveEventButton.text = "Saved ✓"
                holder.saveEventButton.isEnabled = false
                holder.saveEventButton.alpha = 0.6f
                holder.savePrioritySection.visibility = View.GONE
                holder.commentInput.text.clear()
                holder.prioritySpinner.setSelection(0)
            } else {
                Toast.makeText(context, "Event already saved", Toast.LENGTH_SHORT).show()
            }
        }

        // Setup action buttons (download, share, PDF)
        setupActionButtons(holder, event)

        // Load image
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

    private fun setupActionButtons(holder: EventViewHolder, event: Event) {
        holder.downloadImageButton.setOnClickListener {
            downloadEventImage(event)
        }

        holder.shareImageButton.setOnClickListener {
            shareEventImage(event)
        }

        holder.downloadEventPdfButton.setOnClickListener {
            generateSingleEventPdf(event)
        }
    }
    private fun downloadEventImage(event: Event) {
        val progressDialog = android.app.ProgressDialog(context)
        progressDialog.setMessage("Downloading image...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val area = event.area ?: return@withContext null
                    val eventId = event.id ?: return@withContext null
                    val httpUrl = getHttpUrlForArea(area)
                    val imageUrl = "$httpUrl/va/event/?id=$eventId"

                    val request = Request.Builder().url(imageUrl).build()
                    val response = client.newCall(request).execute()

                    if (response.isSuccessful) {
                        val inputStream = response.body?.byteStream()
                        val bitmap = BitmapFactory.decodeStream(inputStream)

                        val fileName = "event_${eventId}_${System.currentTimeMillis()}.jpg"
                        val file = File(
                            context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),
                            fileName
                        )
                        val outputStream = FileOutputStream(file)
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                        outputStream.close()
                        file
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading image: ${e.message}")
                    null
                }
            }

            progressDialog.dismiss()

            if (file != null) {
                Toast.makeText(context, "Image saved: ${file.name}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Failed to download image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareEventImage(event: Event) {
        val progressDialog = android.app.ProgressDialog(context)
        progressDialog.setMessage("Preparing to share...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val area = event.area ?: return@withContext null
                    val eventId = event.id ?: return@withContext null
                    val httpUrl = getHttpUrlForArea(area)
                    val imageUrl = "$httpUrl/va/event/?id=$eventId"

                    val request = Request.Builder().url(imageUrl).build()
                    val response = client.newCall(request).execute()

                    if (response.isSuccessful) {
                        val inputStream = response.body?.byteStream()
                        val bitmap = BitmapFactory.decodeStream(inputStream)

                        val fileName = "event_share_${System.currentTimeMillis()}.jpg"
                        val file = File(context.cacheDir, fileName)
                        val outputStream = FileOutputStream(file)
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                        outputStream.close()
                        file
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error preparing share: ${e.message}")
                    null
                }
            }

            progressDialog.dismiss()

            if (file != null) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "Event: ${event.typeDisplay}\nLocation: ${event.data["location"]}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Event Image"))
            } else {
                Toast.makeText(context, "Failed to share image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateSingleEventPdf(event: Event) {
        val progressDialog = android.app.ProgressDialog(context)
        progressDialog.setMessage("Generating PDF...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            val pdfFile = withContext(Dispatchers.IO) {
                pdfGenerator.generateSingleEventPdf(event, channelArea, channelType)
            }

            progressDialog.dismiss()

            if (pdfFile != null) {
                Toast.makeText(context, "PDF saved: ${pdfFile.name}", Toast.LENGTH_LONG).show()
                openPdfFile(pdfFile)
            } else {
                Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openPdfFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open PDF"))
        } catch (e: Exception) {
            Log.e(TAG, "Error opening PDF: ${e.message}")
            Toast.makeText(context, "No PDF viewer found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupGpsEvent(holder: GpsEventViewHolder, event: Event) {
        holder.eventType.text = event.typeDisplay ?: "GPS Alert"

        val eventTimeStr = event.data["eventTime"] as? String
        val eventDateTime = if (eventTimeStr != null) {
            try {
                eventTimeParser.parse(eventTimeStr) ?: Date(event.timestamp * 1000)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing eventTime: ${e.message}")
                Date(event.timestamp * 1000)
            }
        } else {
            Date(event.timestamp * 1000)
        }

        holder.timestamp.text = timeFormat.format(eventDateTime)
        holder.eventDate.text = dateFormat.format(eventDateTime)

        val vehicleNum = event.vehicleNumber ?: "Unknown"
        val transporter = event.vehicleTransporter ?: "Unknown"

        holder.vehicleNumber.text = vehicleNum
        holder.vehicleTransporter.text = transporter

        val (iconText, color) = when (event.type) {
            "off-route" -> Pair("OR", "#FF5722")
            "tamper" -> Pair("TM", "#F44336")
            "overspeed" -> Pair("OS", "#FF9800")
            else -> Pair("GPS", "#9E9E9E")
        }

        holder.iconText.text = iconText
        holder.badge.setBackgroundResource(R.drawable.circle_background)
        (holder.badge.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor(color))

        val alertSubType = event.data["alertSubType"] as? String
        if (event.type == "tamper" && alertSubType != null) {
            holder.alertSubtypeContainer.visibility = View.VISIBLE
            holder.alertSubtype.text = alertSubType
        } else {
            holder.alertSubtypeContainer.visibility = View.GONE
        }

        val geofenceData = event.data["geofence"] as? Map<*, *>
        if (geofenceData != null) {
            val geofenceName = geofenceData["name"] as? String
            if (geofenceName != null) {
                holder.geofenceContainer.visibility = View.VISIBLE
                holder.geofenceName.text = geofenceName
            } else {
                holder.geofenceContainer.visibility = View.GONE
            }
        } else {
            holder.geofenceContainer.visibility = View.GONE
        }

        setupMapPreview(holder, event)
        setupGpsPrioritySpinner(holder)

        // Hide all expandable sections initially
        holder.saveGpsPrioritySection.visibility = View.GONE
        holder.gpsActionButtonsContainer.visibility = View.GONE

        // Check if GPS event is already saved
        val isSaved = event.id?.let { SavedMessagesManager.isMessageSaved(it) } ?: false

        if (isSaved) {
            holder.saveGpsEventButton.text = "Saved ✓"
            holder.saveGpsEventButton.isEnabled = false
            holder.saveGpsEventButton.alpha = 0.6f
        } else {
            holder.saveGpsEventButton.text = "Save"
            holder.saveGpsEventButton.isEnabled = true
            holder.saveGpsEventButton.alpha = 1.0f
        }

        // Save GPS event button - shows priority selection
        holder.saveGpsEventButton.setOnClickListener {
            if (isSaved) {
                Toast.makeText(context, "GPS event already saved", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            holder.saveGpsPrioritySection.visibility = View.VISIBLE
            holder.gpsActionButtonsContainer.visibility = View.GONE
        }

        // More GPS actions button
        holder.moreGpsActionsButton.setOnClickListener {
            if (holder.gpsActionButtonsContainer.visibility == View.VISIBLE) {
                holder.gpsActionButtonsContainer.visibility = View.GONE
            } else {
                holder.gpsActionButtonsContainer.visibility = View.VISIBLE
                holder.saveGpsPrioritySection.visibility = View.GONE
            }
        }

        // Cancel save button
        holder.cancelGpsSaveButton.setOnClickListener {
            holder.saveGpsPrioritySection.visibility = View.GONE
            holder.gpsCommentInput.text.clear()
            holder.gpsPrioritySpinner.setSelection(0)
        }

        // Confirm save button
        holder.confirmGpsSaveButton.setOnClickListener {
            val eventId = event.id
            if (eventId == null) {
                Toast.makeText(context, "Cannot save: Invalid GPS event", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val priorityIndex = holder.gpsPrioritySpinner.selectedItemPosition
            val priority = Priority.values()[priorityIndex]
            val comment = holder.gpsCommentInput.text.toString().trim()

            val saved = SavedMessagesManager.saveMessage(eventId, event, priority, comment)

            if (saved) {
                Toast.makeText(context, "GPS event saved successfully", Toast.LENGTH_SHORT).show()
                holder.saveGpsEventButton.text = "Saved ✓"
                holder.saveGpsEventButton.isEnabled = false
                holder.saveGpsEventButton.alpha = 0.6f
                holder.saveGpsPrioritySection.visibility = View.GONE
                holder.gpsCommentInput.text.clear()
                holder.gpsPrioritySpinner.setSelection(0)
            } else {
                Toast.makeText(context, "GPS event already saved", Toast.LENGTH_SHORT).show()
            }
        }

        // Setup GPS action buttons
        setupGpsActionButtons(holder, event)

        holder.mapPreviewFrame.setOnClickListener {
            openMapView(event)
        }

        holder.viewOnMapButton.setOnClickListener {
            openMapView(event)
        }
    }

    private fun setupGpsPrioritySpinner(holder: GpsEventViewHolder) {
        val priorities = Priority.values().map { it.displayName }
        val adapter = ArrayAdapter(
            holder.itemView.context,
            android.R.layout.simple_spinner_item,
            priorities
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        holder.gpsPrioritySpinner.adapter = adapter
    }

    private fun setupGpsActionButtons(holder: GpsEventViewHolder, event: Event) {
        holder.downloadGpsPdfButton.setOnClickListener {
            generateGpsEventPdf(event)
        }

        holder.shareGpsLocationButton.setOnClickListener {
            shareGpsLocation(event)
        }
    }

    private fun generateGpsEventPdf(event: Event) {
        val progressDialog = android.app.ProgressDialog(context)
        progressDialog.setMessage("Generating GPS Event PDF...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            val pdfFile = withContext(Dispatchers.IO) {
                pdfGenerator.generateSingleEventPdf(event, channelArea, channelType)
            }

            progressDialog.dismiss()

            if (pdfFile != null) {
                Toast.makeText(context, "PDF saved: ${pdfFile.name}", Toast.LENGTH_LONG).show()
                openPdfFile(pdfFile)
            } else {
                Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareGpsLocation(event: Event) {
        val alertLocation = extractAlertLocation(event.data)

        if (alertLocation == null) {
            Toast.makeText(context, "Location not available", Toast.LENGTH_SHORT).show()
            return
        }

        val vehicleInfo = "${event.vehicleNumber ?: "Unknown Vehicle"} - ${event.vehicleTransporter ?: "Unknown Transporter"}"
        val eventType = event.typeDisplay ?: "GPS Alert"
        val latitude = alertLocation.latitude
        val longitude = alertLocation.longitude

        val locationUrl = "https://www.google.com/maps?q=$latitude,$longitude"

        val shareText = """
        $eventType
        
        Vehicle: $vehicleInfo
        Location: $latitude, $longitude
        
        View on map: $locationUrl
        
        Time: ${timeFormat.format(Date(event.timestamp * 1000))}
        Date: ${dateFormat.format(Date(event.timestamp * 1000))}
    """.trimIndent()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "$eventType - $vehicleInfo")
            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share GPS Location"))
    }

    private fun setupMapPreview(holder: GpsEventViewHolder, event: Event) {
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
            geofencePoints = geofenceData?.points,
            geofenceType = geofenceData?.type,
            geofenceColor = geofenceData?.color,
            boundingBox = boundingBox
        )
    }

    private fun renderMapPreview(holder: GpsEventViewHolder, mapData: MapPreviewData) {
        try {
            holder.mapPreview.overlays.clear()

            if (!mapData.geofencePoints.isNullOrEmpty() && mapData.geofenceType != null) {
                val color = parseColor(mapData.geofenceColor)

                when (mapData.geofenceType) {
                    "LineString" -> {
                        val polyline = Polyline(holder.mapPreview)
                        polyline.setPoints(mapData.geofencePoints)
                        polyline.outlinePaint.color = color
                        polyline.outlinePaint.strokeWidth = 6f
                        polyline.outlinePaint.isAntiAlias = true
                        holder.mapPreview.overlays.add(polyline)
                    }
                    "Polygon" -> {
                        val polygon = Polygon(holder.mapPreview)
                        polygon.points = mapData.geofencePoints
                        polygon.fillPaint.color = Color.argb(30, Color.red(color), Color.green(color), Color.blue(color))
                        polygon.outlinePaint.color = color
                        polygon.outlinePaint.strokeWidth = 4f
                        polygon.outlinePaint.isAntiAlias = true
                        holder.mapPreview.overlays.add(polygon)
                    }
                    "Point" -> {
                        val point = mapData.geofencePoints.first()
                        val circle = Polygon(holder.mapPreview)
                        circle.points = Polygon.pointsAsCircle(point, 100.0)
                        circle.fillPaint.color = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
                        circle.outlinePaint.color = color
                        circle.outlinePaint.strokeWidth = 3f
                        holder.mapPreview.overlays.add(circle)
                    }
                }
            }

            if (mapData.alertLocation != null) {
                val marker = Marker(holder.mapPreview)
                marker.position = mapData.alertLocation
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.icon = context.getDrawable(R.drawable.ic_location_red)
                holder.mapPreview.overlays.add(marker)
            }

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

    private fun loadEventImage(event: Event, holder: EventViewHolder) {
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
                            .setDuration(250)
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

    private fun showError(holder: EventViewHolder) {
        holder.loadingContainer.visibility = View.GONE
        holder.errorContainer.visibility = View.VISIBLE
        holder.eventImage.visibility = View.GONE
        holder.imageOverlay.visibility = View.GONE
    }

    private fun getEventDate(event: Event): Date {
        val eventTimeStr = event.data["eventTime"] as? String
        return if (eventTimeStr != null) {
            try {
                eventTimeParser.parse(eventTimeStr) ?: Date(event.timestamp * 1000)
            } catch (e: Exception) {
                Date(event.timestamp * 1000)
            }
        } else {
            Date(event.timestamp * 1000)
        }
    }

    private fun getEventTypeIcon(type: String): Pair<String, String> {
        return when (type.lowercase()) {
            "cd" -> Pair("CD", "#FF5722")
            "id" -> Pair("ID", "#F44336")
            "ct" -> Pair("CT", "#E91E63")
            "sh" -> Pair("SH", "#FF9800")
            "vd" -> Pair("VD", "#2196F3")
            "pd" -> Pair("PD", "#4CAF50")
            "vc" -> Pair("VC", "#FFC107")
            "ii" -> Pair("II", "#9C27B0")
            "ls" -> Pair("LS", "#00BCD4")
            "off-route" -> Pair("OR", "#FF5722")
            "tamper" -> Pair("TM", "#F44336")
            else -> Pair("??", "#9E9E9E")
        }
    }

    private fun setupPrioritySpinner(holder: EventViewHolder) {
        val priorities = Priority.values().map { it.displayName }
        val adapter = ArrayAdapter(
            holder.itemView.context,
            android.R.layout.simple_spinner_item,
            priorities
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        holder.prioritySpinner.adapter = adapter
    }

    private fun getDateDividerText(date: Date): String {
        val calendar = Calendar.getInstance()
        val today = calendar.time

        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = calendar.time

        val eventCalendar = Calendar.getInstance()
        eventCalendar.time = date

        val todayCalendar = Calendar.getInstance()
        todayCalendar.time = today

        return when {
            isSameDay(eventCalendar, todayCalendar) -> "TODAY"
            isSameDay(eventCalendar, Calendar.getInstance().apply {
                time = yesterday
            }) -> "YESTERDAY"
            else -> dateFormat.format(date).uppercase()
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
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

}