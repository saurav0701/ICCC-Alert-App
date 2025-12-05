package com.example.iccc_alert_app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Helper class containing all binding logic and utility methods for ChannelEventsAdapter
 */
class EventBindingHelpers(
    private val context: Context,
    private val channelArea: String,
    private val channelType: String,
    private val channelEventType: String
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val eventTimeParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val displayDateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val displayDateTimeFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    private val client = OkHttpClient()
    private val pdfGenerator = PdfGenerator(context)

    companion object {
        private const val TAG = "EventBindingHelpers"
    }

    // Data classes
    data class GeofenceData(
        val points: List<GeoPoint>,
        val type: String,
        val color: String,
        val geofenceType: String?
    )

    data class MapPreviewData(
        val alertLocation: GeoPoint?,
        val geofencePoints: List<GeoPoint>?,
        val geofenceType: String?,
        val geofenceColor: String?,
        val boundingBox: org.osmdroid.util.BoundingBox?
    )

    // ==================== UTILITY METHODS ====================

    fun getEventTypeIcon(type: String): Pair<String, String> {
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
            "overspeed" -> Pair("OS", "#FF9800")
            else -> Pair("??", "#9E9E9E")
        }
    }

    fun getEventDate(event: Event): Date {
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

    fun getDateDividerText(date: Date): String {
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

    fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    fun isToday(date: Date): Boolean {
        val today = Calendar.getInstance()
        val eventCal = Calendar.getInstance()
        eventCal.time = date
        return isSameDay(today, eventCal)
    }

    fun isYesterday(date: Date): Boolean {
        val yesterday = Calendar.getInstance()
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        val eventCal = Calendar.getInstance()
        eventCal.time = date
        return isSameDay(yesterday, eventCal)
    }

    fun resetTime(date: Date): Calendar {
        val cal = Calendar.getInstance()
        cal.time = date
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }

    fun getHttpUrlForArea(area: String): String {
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

    // ==================== GEOFENCE & MAP DATA ====================

    fun extractAlertLocation(data: Map<String, Any>?): GeoPoint? {
        if (data == null) return null

        val alertLoc = data["alertLocation"] as? Map<*, *> ?: return null
        val lat = (alertLoc["lat"] as? Number)?.toDouble() ?: return null
        val lng = (alertLoc["lng"] as? Number)?.toDouble() ?: return null

        return GeoPoint(lat, lng)
    }

    fun extractGeofenceData(data: Map<String, Any>?): GeofenceData? {
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

    fun prepareMapPreviewData(event: Event): MapPreviewData {
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

    fun parseColor(colorStr: String?): Int {
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

    // ==================== PDF & FILE OPERATIONS ====================

    fun openPdfFile(file: File) {
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

    fun openImageViewer(event: Event, bitmap: Bitmap) {
        try {
            val intent = Intent(context, ImageViewerActivity::class.java)

            val file = File(context.cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            outputStream.close()

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            intent.putExtra("IMAGE_URI", uri.toString())
            intent.putExtra("EVENT_TYPE", event.typeDisplay ?: "Event")
            intent.putExtra("EVENT_LOCATION", event.data["location"] as? String ?: "Unknown")
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening image viewer: ${e.message}")
            Toast.makeText(context, "Could not open image", Toast.LENGTH_SHORT).show()
        }
    }

    fun openMapView(event: Event) {
        val gpsEvent = event.toGpsEvent()
        if (gpsEvent == null) {
            Toast.makeText(context, "Invalid GPS event data", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(context, MapActivity::class.java)
        intent.putExtra(MapActivity.EXTRA_GPS_EVENT, Gson().toJson(gpsEvent))
        context.startActivity(intent)
    }

    // ==================== IMAGE LOADING ====================

    suspend fun loadEventImageBitmap(area: String, eventId: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val httpUrl = getHttpUrlForArea(area)
                val imageUrl = "$httpUrl/va/event/?id=$eventId"

                Log.d(TAG, "Loading image: $imageUrl")

                val request = Request.Builder().url(imageUrl).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val inputStream = response.body?.byteStream()
                    BitmapFactory.decodeStream(inputStream)
                } else {
                    Log.e(TAG, "Failed to load image: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image: ${e.message}")
                null
            }
        }
    }

    // ==================== ACTION OPERATIONS ====================

    suspend fun downloadEventImage(event: Event): File? {
        return withContext(Dispatchers.IO) {
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
    }

    suspend fun prepareShareImage(event: Event): File? {
        return withContext(Dispatchers.IO) {
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
    }

    suspend fun generateSingleEventPdf(event: Event): File? {
        return withContext(Dispatchers.IO) {
            pdfGenerator.generateSingleEventPdf(event, channelArea, channelType)
        }
    }

    suspend fun generateBulkEventsPdf(events: List<Event>): File? {
        return withContext(Dispatchers.IO) {
            pdfGenerator.generateBulkEventsPdf(events, channelArea, channelType)
        }
    }

    // ==================== DATE TIME PICKER ====================

    /**
     * Show DateTimePicker with both date and time selection
     */
    fun showDateTimePicker(
        isFromDate: Boolean,
        currentDateTime: Date?,
        customFromDate: Date?,
        customToDate: Date?,
        onDateTimeSelected: (Date) -> Unit
    ) {
        val calendar = Calendar.getInstance()
        if (currentDateTime != null) {
            calendar.time = currentDateTime
        }

        // First show date picker
        val datePicker = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selectedCalendar = Calendar.getInstance()
                selectedCalendar.set(year, month, dayOfMonth)

                // Then show time picker
                val timePicker = TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        selectedCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        selectedCalendar.set(Calendar.MINUTE, minute)
                        selectedCalendar.set(Calendar.SECOND, 0)
                        selectedCalendar.set(Calendar.MILLISECOND, 0)

                        // Validation
                        if (isFromDate && customToDate != null) {
                            if (selectedCalendar.time.after(customToDate)) {
                                Toast.makeText(
                                    context,
                                    "From date/time cannot be after To date/time",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@TimePickerDialog
                            }
                        } else if (!isFromDate && customFromDate != null) {
                            if (selectedCalendar.time.before(customFromDate)) {
                                Toast.makeText(
                                    context,
                                    "To date/time cannot be before From date/time",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@TimePickerDialog
                            }
                        }

                        onDateTimeSelected(selectedCalendar.time)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true // 24-hour format
                )
                timePicker.show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        // Set max date to current time
        datePicker.datePicker.maxDate = System.currentTimeMillis()

        // Set constraints based on from/to selection
        if (!isFromDate && customFromDate != null) {
            datePicker.datePicker.minDate = customFromDate.time
        }
        if (isFromDate && customToDate != null) {
            datePicker.datePicker.maxDate = customToDate.time
        }

        datePicker.show()
    }

    fun setupPrioritySpinner(spinner: Spinner) {
        val priorities = Priority.values().map { it.displayName }
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            priorities
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }
}