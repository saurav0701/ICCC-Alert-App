package com.example.iccc_alert_app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.*

/**
 * Main RecyclerView Adapter for displaying channel events
 * Refactored into modular components for better maintainability
 */
class ChannelEventsAdapter(
    private val context: Context,
    private val onBackClick: () -> Unit,
    private val onMuteClick: () -> Unit,
    private val channelArea: String,
    private val channelType: String,
    private val channelEventType: String
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var allEvents = listOf<Event>()
    private var filteredEvents = listOf<Event>()
    private var eventBitmaps = mutableMapOf<String, Bitmap>()
    private var isMuted = false

    // Filter state
    private var searchQuery = ""
    private var selectedFilterOption = "All"
    private var customFromDate: Date? = null
    private var customToDate: Date? = null

    // Helper classes for binding logic
    private val bindingHelpers = EventBindingHelpers(
        context,
        channelArea,
        channelType,
        channelEventType
    )

    private val gpsEventHandler = GpsEventHandler(context, bindingHelpers)

    companion object {
        private const val TAG = "ChannelEventsAdapter"
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_EVENT = 1
        private const val VIEW_TYPE_GPS_EVENT = 2
    }

    // ==================== ADAPTER CORE METHODS ====================

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

                // ✅ FIX FOR CURVED DISPLAYS - Apply window insets to header
                ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

                    // Calculate safe padding for curved edges (12dp minimum)
                    val leftPadding = maxOf(systemBars.left, displayCutout.left, dpToPx(12))
                    val rightPadding = maxOf(systemBars.right, displayCutout.right, dpToPx(12))

                    // Apply padding to avoid curved edges
                    v.setPadding(leftPadding, v.paddingTop, rightPadding, v.paddingBottom)

                    insets
                }

                // Request to apply insets immediately
                ViewCompat.requestApplyInsets(view)

                EventViewHolders.HeaderViewHolder(view)
            }
            VIEW_TYPE_GPS_EVENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_gps_event, parent, false)
                EventViewHolders.GpsEventViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_channel_event, parent, false)
                EventViewHolders.EventViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is EventViewHolders.HeaderViewHolder -> setupHeader(holder)
            is EventViewHolders.GpsEventViewHolder -> {
                val eventIndex = position - 1
                val event = filteredEvents[eventIndex]
                gpsEventHandler.setupGpsEvent(holder, event)
            }
            is EventViewHolders.EventViewHolder -> {
                val eventIndex = position - 1
                val event = filteredEvents[eventIndex]
                setupEvent(holder, event)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is EventViewHolders.GpsEventViewHolder) {
            try {
                // Cancel any active map rendering for this holder
                gpsEventHandler.cancelRendering(holder)

                // Clean up the map preview frame
                holder.mapPreviewFrame.removeAllViews()
                holder.mapLoadingOverlay.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling GPS event view: ${e.message}")
            }
        }
    }

    override fun getItemCount() = filteredEvents.size + 1

    fun updateEvents(newEvents: List<Event>) {
        allEvents = newEvents
        filteredEvents = newEvents
        notifyDataSetChanged()
    }

    fun updateMuteState(muted: Boolean) {
        isMuted = muted
        notifyItemChanged(0) // Update header only
    }

    fun clearImageCache() {
        eventBitmaps.clear()
        gpsEventHandler.clearCache()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    // ==================== HEADER SETUP ====================

    private fun setupHeader(holder: EventViewHolders.HeaderViewHolder) {
        holder.backButton.setOnClickListener {
            onBackClick()
        }

        // ✅ MUTE BUTTON CLICK HANDLER
        holder.muteButton.setOnClickListener {
            onMuteClick()
        }

        // ✅ UPDATE MUTE ICON BASED ON STATE
        if (isMuted) {
            holder.muteButton.setImageResource(R.drawable.ic_notifications_off)
            holder.muteButton.contentDescription = "Unmute notifications"
        } else {
            holder.muteButton.setImageResource(R.drawable.ic_notifications)
            holder.muteButton.contentDescription = "Mute notifications"
        }

        holder.channelName.text = "$channelArea - $channelType"

        val count = filteredEvents.size
        holder.eventCount.text = if (count == 1) "1 event" else "$count events"

        val (iconText, color) = bindingHelpers.getEventTypeIcon(channelEventType)
        holder.channelIconText.text = iconText
        holder.channelBadge.setBackgroundResource(R.drawable.circle_background)
        (holder.channelBadge.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor(color))

        if (filteredEvents.isNotEmpty()) {
            val firstEventDate = bindingHelpers.getEventDate(filteredEvents[0])
            holder.dateDivider.text = bindingHelpers.getDateDividerText(firstEventDate)
        } else {
            holder.dateDivider.text = "NO EVENTS"
        }

        holder.menuButton.setOnClickListener {
            showMenuPopup(holder)
        }

        setupSearch(holder)
        setupFilter(holder)
    }

    private fun showMenuPopup(holder: EventViewHolders.HeaderViewHolder) {
        val popup = PopupMenu(holder.itemView.context, holder.menuButton)
        popup.inflate(R.menu.channel_events_menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> {
                    holder.searchContainer.visibility = View.VISIBLE
                    holder.filterContainer.visibility = View.GONE
                    holder.searchInput.requestFocus()
                    true
                }
                R.id.action_filter -> {
                    if (holder.filterContainer.visibility == View.VISIBLE) {
                        holder.filterContainer.visibility = View.GONE
                    } else {
                        holder.filterContainer.visibility = View.VISIBLE
                        holder.searchContainer.visibility = View.GONE
                    }
                    true
                }
                R.id.action_download_pdf -> {
                    handleBulkPdfDownload()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun handleBulkPdfDownload() {
        if (filteredEvents.isEmpty()) {
            Toast.makeText(context, "No events to export", Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = android.app.ProgressDialog(context)
        progressDialog.setMessage("Generating PDF...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            val pdfFile = bindingHelpers.generateBulkEventsPdf(filteredEvents)

            progressDialog.dismiss()

            if (pdfFile != null) {
                Toast.makeText(context, "PDF saved: ${pdfFile.name}", Toast.LENGTH_LONG).show()
                bindingHelpers.openPdfFile(pdfFile)
            } else {
                Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== SEARCH & FILTER SETUP ====================

    private fun setupSearch(holder: EventViewHolders.HeaderViewHolder) {
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

    private fun setupFilter(holder: EventViewHolders.HeaderViewHolder) {
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
            bindingHelpers.showDatePicker(true, customFromDate, customToDate) { date ->
                customFromDate = date
                holder.fromDateButton.text = bindingHelpers.displayDateFormat.format(date)
            }
        }

        holder.toDateButton.setOnClickListener {
            bindingHelpers.showDatePicker(false, customFromDate, customToDate) { date ->
                customToDate = date
                holder.toDateButton.text = bindingHelpers.displayDateFormat.format(date)
            }
        }

        holder.applyFilterButton.setOnClickListener {
            applyFilters()
            holder.filterContainer.visibility = View.GONE
        }
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
                "Today" -> bindingHelpers.isToday(bindingHelpers.getEventDate(event))
                "Yesterday" -> bindingHelpers.isYesterday(bindingHelpers.getEventDate(event))
                "Custom Date Range" -> {
                    if (customFromDate != null && customToDate != null) {
                        val eventDate = bindingHelpers.getEventDate(event)
                        val from = bindingHelpers.resetTime(customFromDate!!)
                        val to = bindingHelpers.resetTime(customToDate!!)
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

    // ==================== EVENT ITEM SETUP ====================

    private fun setupEvent(holder: EventViewHolders.EventViewHolder, event: Event) {
        holder.eventType.text = event.typeDisplay ?: "Unknown Event"

        val location = event.data["location"] as? String ?: "Unknown"
        holder.location.text = location

        val eventDateTime = bindingHelpers.getEventDate(event)
        holder.timestamp.text = bindingHelpers.timeFormat.format(eventDateTime)
        holder.eventDate.text = bindingHelpers.dateFormat.format(eventDateTime)

        val (iconText, color) = bindingHelpers.getEventTypeIcon(event.type ?: "")
        holder.iconText.text = iconText
        holder.badge.setBackgroundResource(R.drawable.circle_background)
        (holder.badge.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor(color))

        bindingHelpers.setupPrioritySpinner(holder.prioritySpinner)

        holder.savePrioritySection.visibility = View.GONE
        holder.actionButtonsContainer.visibility = View.GONE

        setupSaveButton(holder, event)
        setupMoreActionsButton(holder, event)
        setupEventActionButtons(holder, event)

        loadEventImage(event, holder)
    }

    private fun setupSaveButton(holder: EventViewHolders.EventViewHolder, event: Event) {
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

        holder.saveEventButton.setOnClickListener {
            if (isSaved) {
                Toast.makeText(context, "Event already saved", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            holder.savePrioritySection.visibility = View.VISIBLE
            holder.actionButtonsContainer.visibility = View.GONE
        }

        holder.cancelSaveButton.setOnClickListener {
            holder.savePrioritySection.visibility = View.GONE
            holder.commentInput.text.clear()
            holder.prioritySpinner.setSelection(0)
        }

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
    }

    private fun setupMoreActionsButton(holder: EventViewHolders.EventViewHolder, event: Event) {
        holder.moreActionsButton.setOnClickListener {
            if (holder.actionButtonsContainer.visibility == View.VISIBLE) {
                holder.actionButtonsContainer.visibility = View.GONE
            } else {
                holder.actionButtonsContainer.visibility = View.VISIBLE
                holder.savePrioritySection.visibility = View.GONE
            }
        }
    }

    private fun setupEventActionButtons(holder: EventViewHolders.EventViewHolder, event: Event) {
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

    // ==================== IMAGE OPERATIONS ====================

    private fun loadEventImage(event: Event, holder: EventViewHolders.EventViewHolder) {
        val area = event.area ?: return
        val eventId = event.id ?: return

        if (eventBitmaps.containsKey(eventId)) {
            val cachedBitmap = eventBitmaps[eventId]
            if (cachedBitmap != null) {
                displayEventImage(holder, event, cachedBitmap)
                return
            }
        }

        holder.loadingContainer.visibility = View.VISIBLE
        holder.errorContainer.visibility = View.GONE
        holder.eventImage.visibility = View.GONE
        holder.imageOverlay.visibility = View.GONE

        CoroutineScope(Dispatchers.Main).launch {
            val bitmap = bindingHelpers.loadEventImageBitmap(area, eventId)

            if (bitmap != null) {
                eventBitmaps[eventId] = bitmap
                displayEventImage(holder, event, bitmap)
            } else {
                showError(holder)
            }
        }
    }

    private fun displayEventImage(holder: EventViewHolders.EventViewHolder, event: Event, bitmap: Bitmap) {
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

        holder.imageFrame.setOnClickListener {
            bindingHelpers.openImageViewer(event, bitmap)
        }
    }

    private fun showError(holder: EventViewHolders.EventViewHolder) {
        holder.loadingContainer.visibility = View.GONE
        holder.errorContainer.visibility = View.VISIBLE
        holder.eventImage.visibility = View.GONE
        holder.imageOverlay.visibility = View.GONE
    }

    private fun downloadEventImage(event: Event) {
        val progressDialog = android.app.ProgressDialog(context)
        progressDialog.setMessage("Downloading image...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        CoroutineScope(Dispatchers.Main).launch {
            val file = bindingHelpers.downloadEventImage(event)

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
            val file = bindingHelpers.prepareShareImage(event)

            progressDialog.dismiss()

            if (file != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
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
            val pdfFile = bindingHelpers.generateSingleEventPdf(event)

            progressDialog.dismiss()

            if (pdfFile != null) {
                Toast.makeText(context, "PDF saved: ${pdfFile.name}", Toast.LENGTH_LONG).show()
                bindingHelpers.openPdfFile(pdfFile)
            } else {
                Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Note: GPS event handling is in GpsEventHandler.kt
}