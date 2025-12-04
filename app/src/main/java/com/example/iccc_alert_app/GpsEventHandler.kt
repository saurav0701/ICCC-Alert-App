package com.example.iccc_alert_app

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Optimized GPS event handler with lightweight map preview
 * Eliminates ANR by avoiding heavy MapView instances in RecyclerView
 */
class GpsEventHandler(
    private val context: Context,
    private val bindingHelpers: EventBindingHelpers
) {
    companion object {
        private const val TAG = "GpsEventHandler"
    }

    // Cache for map preview images
    private val mapPreviewCache = ConcurrentHashMap<String, Bitmap>()

    // Track active jobs
    private val activeJobs = ConcurrentHashMap<Int, Job>()

    // ==================== GPS EVENT SETUP ====================

    fun setupGpsEvent(holder: EventViewHolders.GpsEventViewHolder, event: Event) {
        // Cancel any existing job for this holder
        activeJobs[holder.hashCode()]?.cancel()

        holder.eventType.text = event.typeDisplay ?: "GPS Alert"

        val eventDateTime = bindingHelpers.getEventDate(event)
        holder.timestamp.text = bindingHelpers.timeFormat.format(eventDateTime)
        holder.eventDate.text = bindingHelpers.dateFormat.format(eventDateTime)

        val vehicleNum = event.vehicleNumber ?: "Unknown"
        val transporter = event.vehicleTransporter ?: "Unknown"

        holder.vehicleNumber.text = vehicleNum
        holder.vehicleTransporter.text = transporter

        // Handle alert subtype for tamper events
        val alertSubType = event.data["alertSubType"] as? String
        if (event.type == "tamper" && alertSubType != null) {
            holder.alertSubtypeContainer.visibility = View.VISIBLE
            holder.alertSubtype.text = alertSubType
        } else {
            holder.alertSubtypeContainer.visibility = View.GONE
        }

        // Handle geofence display
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

        // Use lightweight location card instead of map
        setupLocationCard(holder, event)

        bindingHelpers.setupPrioritySpinner(holder.gpsPrioritySpinner)

        holder.saveGpsPrioritySection.visibility = View.GONE
        holder.gpsActionButtonsContainer.visibility = View.GONE

        setupGpsSaveButton(holder, event)
        setupGpsMoreActionsButton(holder, event)
        setupGpsActionButtons(holder, event)

        holder.mapPreviewFrame.setOnClickListener {
            bindingHelpers.openMapView(event)
        }

        holder.viewOnMapButton.setOnClickListener {
            bindingHelpers.openMapView(event)
        }
    }

    fun cancelRendering(holder: EventViewHolders.GpsEventViewHolder) {
        activeJobs[holder.hashCode()]?.cancel()
        activeJobs.remove(holder.hashCode())
    }

    // ==================== LIGHTWEIGHT LOCATION CARD ====================

    /**
     * Creates a simple, lightweight location preview card
     * Much faster than rendering a MapView
     */
    private fun setupLocationCard(holder: EventViewHolders.GpsEventViewHolder, event: Event) {
        val cacheKey = event.id ?: return

        // Hide the MapView - we don't need it
        holder.mapPreview.visibility = View.GONE
        holder.mapLoadingOverlay.visibility = View.GONE

        // Check cache
        if (mapPreviewCache.containsKey(cacheKey)) {
            displayLocationCard(holder, mapPreviewCache[cacheKey]!!)
            return
        }

        // Generate location card asynchronously
        val job = CoroutineScope(Dispatchers.Main).launch {
            try {
                val mapData = withContext(Dispatchers.Default) {
                    bindingHelpers.prepareMapPreviewData(event)
                }

                val cardBitmap = withContext(Dispatchers.Default) {
                    generateLocationCardBitmap(mapData, event)
                }

                if (cardBitmap != null) {
                    mapPreviewCache[cacheKey] = cardBitmap

                    if (isActive) {
                        displayLocationCard(holder, cardBitmap)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Location card generation cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error generating location card: ${e.message}", e)
            } finally {
                activeJobs.remove(holder.hashCode())
            }
        }

        activeJobs[holder.hashCode()] = job
    }

    private fun displayLocationCard(holder: EventViewHolders.GpsEventViewHolder, bitmap: Bitmap) {
        val imageView = ImageView(context)
        imageView.setImageBitmap(bitmap)
        imageView.scaleType = ImageView.ScaleType.FIT_XY

        holder.mapPreviewFrame.removeAllViews()
        holder.mapPreviewFrame.addView(imageView)
    }

    private fun generateLocationCardBitmap(
        mapData: EventBindingHelpers.MapPreviewData,
        event: Event
    ): Bitmap {
        val width = 800
        val height = 400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background gradient
        val gradient = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            Color.parseColor("#667eea"),
            Color.parseColor("#764ba2"),
            Shader.TileMode.CLAMP
        )
        val bgPaint = Paint().apply {
            shader = gradient
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Semi-transparent overlay with pattern
        drawLocationPattern(canvas, width, height)

        // Location icon at top
        drawLocationIcon(canvas, width / 2f, 100f)

        // Coordinates
        if (mapData.alertLocation != null) {
            val lat = mapData.alertLocation.latitude
            val lng = mapData.alertLocation.longitude

            val coordPaint = Paint().apply {
                color = Color.WHITE
                textSize = 32f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
                setShadowLayer(4f, 0f, 2f, Color.argb(100, 0, 0, 0))
            }

            canvas.drawText(
                String.format("%.6f, %.6f", lat, lng),
                width / 2f,
                190f,
                coordPaint
            )
        }

        // Event type indicator
        val typePaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
            alpha = 200
        }

        val eventTypeText = when (event.type) {
            "off-route" -> "📍 Off-Route Alert"
            "tamper" -> "⚠️ Tamper Alert"
            "overspeed" -> "⚡ Overspeed Alert"
            else -> "📍 GPS Alert"
        }

        canvas.drawText(eventTypeText, width / 2f, 250f, typePaint)

        // "Tap to view on map" hint
        val hintPaint = Paint().apply {
            color = Color.WHITE
            textSize = 20f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            alpha = 180
        }

        canvas.drawText("Tap to view full map", width / 2f, 320f, hintPaint)

        // Geofence indicator if present
        if (mapData.geofencePoints != null) {
            val geofencePaint = Paint().apply {
                color = Color.WHITE
                textSize = 18f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                alpha = 160
            }
            canvas.drawText("🔷 Geofence Area Defined", width / 2f, 355f, geofencePaint)
        }

        return bitmap
    }

    private fun drawLocationPattern(canvas: Canvas, width: Int, height: Int) {
        val patternPaint = Paint().apply {
            color = Color.WHITE
            alpha = 20
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        // Draw subtle grid pattern
        val spacing = 50f
        for (x in 0 until width step spacing.toInt()) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), patternPaint)
        }
        for (y in 0 until height step spacing.toInt()) {
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), patternPaint)
        }

        // Draw concentric circles around center
        val centerX = width / 2f
        val centerY = height / 2f

        patternPaint.alpha = 15
        for (radius in 50..200 step 50) {
            canvas.drawCircle(centerX, centerY, radius.toFloat(), patternPaint)
        }
    }

    private fun drawLocationIcon(canvas: Canvas, x: Float, y: Float) {
        val iconPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
            setShadowLayer(8f, 0f, 4f, Color.argb(150, 0, 0, 0))
        }

        val iconPath = Path().apply {
            // Draw a location pin shape
            moveTo(x, y - 30f)
            lineTo(x - 15f, y + 5f)
            lineTo(x, y + 15f)
            lineTo(x + 15f, y + 5f)
            close()
        }

        canvas.drawPath(iconPath, iconPaint)

        // Inner circle
        iconPaint.color = Color.parseColor("#4CAF50")
        canvas.drawCircle(x, y - 15f, 8f, iconPaint)
    }


    private fun setupGpsSaveButton(holder: EventViewHolders.GpsEventViewHolder, event: Event) {
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

        holder.saveGpsEventButton.setOnClickListener {
            if (isSaved) {
                Toast.makeText(context, "GPS event already saved", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            holder.saveGpsPrioritySection.visibility = View.VISIBLE
            holder.gpsActionButtonsContainer.visibility = View.GONE
        }

        holder.cancelGpsSaveButton.setOnClickListener {
            holder.saveGpsPrioritySection.visibility = View.GONE
            holder.gpsCommentInput.text.clear()
            holder.gpsPrioritySpinner.setSelection(0)
        }

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
    }

    private fun setupGpsMoreActionsButton(holder: EventViewHolders.GpsEventViewHolder, event: Event) {
        holder.moreGpsActionsButton.setOnClickListener {
            if (holder.gpsActionButtonsContainer.visibility == View.VISIBLE) {
                holder.gpsActionButtonsContainer.visibility = View.GONE
            } else {
                holder.gpsActionButtonsContainer.visibility = View.VISIBLE
                holder.saveGpsPrioritySection.visibility = View.GONE
            }
        }
    }

    // ==================== ACTION BUTTONS ====================

    private fun setupGpsActionButtons(holder: EventViewHolders.GpsEventViewHolder, event: Event) {
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

    private fun shareGpsLocation(event: Event) {
        val alertLocation = bindingHelpers.extractAlertLocation(event.data)

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
        
        Time: ${bindingHelpers.timeFormat.format(java.util.Date(event.timestamp * 1000))}
        Date: ${bindingHelpers.dateFormat.format(java.util.Date(event.timestamp * 1000))}
    """.trimIndent()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "$eventType - $vehicleInfo")
            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share GPS Location"))
    }

    fun clearCache() {
        mapPreviewCache.clear()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }
}