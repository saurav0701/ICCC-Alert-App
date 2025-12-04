package com.example.iccc_alert_app

import android.view.View
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import org.osmdroid.views.MapView

/**
 * Container for all ViewHolder classes used in ChannelEventsAdapter
 */
object EventViewHolders {

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val backButton: ImageView = view.findViewById(R.id.back_button_header)
        val channelBadge: View = view.findViewById(R.id.channel_badge)
        val channelIconText: TextView = view.findViewById(R.id.channel_icon_text)
        val channelName: TextView = view.findViewById(R.id.channel_name_header)
        val eventCount: TextView = view.findViewById(R.id.event_count)
        val dateDivider: TextView = view.findViewById(R.id.date_divider)
        val muteButton: ImageView = view.findViewById(R.id.mute_button_header)
        val menuButton: ImageView = view.findViewById(R.id.menu_button)

        // Search UI elements
        val searchContainer: LinearLayout = view.findViewById(R.id.search_container)
        val searchInput: EditText = view.findViewById(R.id.search_input)
        val closeSearch: FrameLayout = view.findViewById(R.id.close_search)

        // Filter UI elements
        val filterContainer: LinearLayout = view.findViewById(R.id.filter_container)
        val dateFilterSpinner: Spinner = view.findViewById(R.id.date_filter_spinner)
        val applyFilterButton: Button = view.findViewById(R.id.apply_filter_button)
        val customDateContainer: LinearLayout = view.findViewById(R.id.custom_date_container)
        val fromDateButton: Button = view.findViewById(R.id.from_date_button)
        val toDateButton: Button = view.findViewById(R.id.to_date_button)
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

        // Save functionality
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
        val viewOnMapButton: Button = view.findViewById(R.id.view_on_map_button)
        val alertSubtypeContainer: LinearLayout = view.findViewById(R.id.alert_subtype_container)
        val alertSubtype: TextView = view.findViewById(R.id.alert_subtype)
        val geofenceContainer: LinearLayout = view.findViewById(R.id.geofence_container)
        val geofenceName: TextView = view.findViewById(R.id.geofence_name)

        // Save functionality
        val saveGpsEventButton: Button = view.findViewById(R.id.save_gps_event_button)
        val moreGpsActionsButton: Button = view.findViewById(R.id.more_gps_actions_button)
        val saveGpsPrioritySection: LinearLayout = view.findViewById(R.id.save_gps_priority_section)
        val gpsPrioritySpinner: Spinner = view.findViewById(R.id.gps_priority_spinner)
        val gpsCommentInput: EditText = view.findViewById(R.id.gps_comment_input)
        val cancelGpsSaveButton: Button = view.findViewById(R.id.cancel_gps_save_button)
        val confirmGpsSaveButton: Button = view.findViewById(R.id.confirm_gps_save_button)

        // Action buttons
        val gpsActionButtonsContainer: LinearLayout = view.findViewById(R.id.gps_action_buttons_container)
        val downloadGpsPdfButton: Button = view.findViewById(R.id.download_gps_pdf_button)
        val shareGpsLocationButton: Button = view.findViewById(R.id.share_gps_location_button)
    }
}