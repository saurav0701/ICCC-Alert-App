package com.example.iccc_alert_app

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import org.osmdroid.util.GeoPoint
import java.util.*

class PdfGenerator(private val context: Context) {

    private val client = OkHttpClient()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val eventTimeParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    companion object {
        private const val TAG = "PdfGenerator"
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val MARGIN = 40
        private const val CONTENT_WIDTH = PAGE_WIDTH - (2 * MARGIN)

        // ✅ IMPROVED: Larger table image size for better quality
        private const val ROW_HEIGHT = 120f  // Increased from 100f
        private const val IMAGE_SIZE = 100   // Increased from 80
        private const val COL_SNO_WIDTH = 50f
        private const val COL_TYPE_WIDTH = 130f  // Slightly reduced to make room for image
        private const val COL_DATETIME_WIDTH = 130f  // Slightly reduced
        private const val COL_IMAGE_WIDTH = 205f  // Increased for larger images

        // Premium Color Palette (unchanged)
        private const val COLOR_PRIMARY = "#2C3E50"
        private const val COLOR_SECONDARY = "#3498DB"
        private const val COLOR_ACCENT = "#E74C3C"
        private const val COLOR_SUCCESS = "#27AE60"
        private const val COLOR_WARNING = "#F39C12"
        private const val COLOR_TEXT_PRIMARY = "#2C3E50"
        private const val COLOR_TEXT_SECONDARY = "#7F8C8D"
        private const val COLOR_BACKGROUND = "#ECF0F1"
        private const val COLOR_CARD_BG = "#FFFFFF"
        private const val COLOR_BORDER = "#BDC3C7"
        private const val COLOR_TABLE_HEADER = "#34495E"
    }

    suspend fun generateSingleEventPdf(
        event: Event,
        channelArea: String,
        channelType: String
    ): File? = withContext(Dispatchers.IO) {
        try {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Draw premium background
            drawPremiumBackground(canvas, paint)

            var yPosition = MARGIN.toFloat() + 20

            // Header Section with gradient effect
            drawHeaderSection(canvas, paint, yPosition, "Event Report")
            yPosition += 80

            // Channel Information Card
            yPosition = drawChannelInfoCard(canvas, paint, channelArea, channelType, yPosition)
            yPosition += 30

            // Event Details Card
            yPosition = drawEventDetailsCard(canvas, paint, event, yPosition)
            yPosition += 25


            val bitmap = if (event.type == "off-route" || event.type == "tamper" || event.type == "overspeed") {
                // For GPS events, try to capture map preview
                captureMapPreview(event) ?: loadEventImage(event)
            } else {
                // For regular events, load event image
                loadEventImage(event)
            }

            if (bitmap != null) {
                yPosition = drawImageWithShadow(canvas, paint, bitmap, yPosition)
            } else {
                yPosition = drawImagePlaceholder(canvas, paint, yPosition, 320)
            }

            // Footer
            drawFooter(canvas, paint, 1, 1)

            pdfDocument.finishPage(page)

            val fileName = "event_report_${fileNameFormat.format(Date())}.pdf"

            // Save to Downloads folder using MediaStore
            val uri = saveToDownloads(pdfDocument, fileName)
            pdfDocument.close()

            if (uri != null) {
                Log.d(TAG, "Premium PDF saved to Downloads: $fileName")
                // Return a dummy file with the name for compatibility
                File(fileName)
            } else {
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error generating PDF: ${e.message}", e)
            null
        }
    }

    suspend fun generateBulkEventsPdf(
        events: List<Event>,
        channelArea: String,
        channelType: String
    ): File? = withContext(Dispatchers.IO) {
        try {
            val pdfDocument = PdfDocument()
            var pageNumber = 1
            var currentPage: PdfDocument.Page? = null
            var canvas: Canvas? = null
            var yPosition = MARGIN.toFloat()
            var tableHeaderDrawn = false

            val rowsPerPage = 6
            val totalPages = (events.size / rowsPerPage) + if (events.size % rowsPerPage > 0) 1 else 0

            fun startNewPage() {
                currentPage?.let {
                    drawFooter(canvas!!, Paint(Paint.ANTI_ALIAS_FLAG), pageNumber - 1, totalPages)
                    pdfDocument.finishPage(it)
                }
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create()
                currentPage = pdfDocument.startPage(pageInfo)
                canvas = currentPage!!.canvas

                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                drawPremiumBackground(canvas!!, paint)

                yPosition = MARGIN.toFloat() + 20
                tableHeaderDrawn = false
            }

            startNewPage()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Title Section
            drawHeaderSection(canvas!!, paint, yPosition, "Events Report - $channelArea")
            yPosition += 80

            // Summary info
            paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
            paint.textSize = 11f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            canvas!!.drawText("Channel: $channelArea - $channelType | Total Events: ${events.size}",
                MARGIN.toFloat(), yPosition, paint)
            yPosition += 25

            // Draw table header
            drawTableHeader(canvas!!, paint, yPosition)
            yPosition += 35f
            tableHeaderDrawn = true

            // Draw each event as a table row
            events.forEachIndexed { index, event ->
                // Check if we need a new page
                if (yPosition + ROW_HEIGHT > PAGE_HEIGHT - MARGIN - 50) {
                    startNewPage()
                    yPosition += 60
                    drawTableHeader(canvas!!, paint, yPosition)
                    yPosition += 35f
                    tableHeaderDrawn = true
                }

                // Call drawTableRow as suspend function
                yPosition = drawTableRow(canvas!!, paint, event, yPosition, index + 1)
            }

            currentPage?.let {
                drawFooter(canvas!!, paint, pageNumber - 1, totalPages)
                pdfDocument.finishPage(it)
            }

            val fileName = "events_table_${channelArea}_${fileNameFormat.format(Date())}.pdf"

            val uri = saveToDownloads(pdfDocument, fileName)
            pdfDocument.close()

            if (uri != null) {
                Log.d(TAG, "Tabular PDF saved to Downloads: $fileName")
                File(fileName)
            } else {
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error generating bulk PDF: ${e.message}", e)
            null
        }
    }

    private fun drawTableHeader(canvas: Canvas, paint: Paint, yStart: Float) {
        var x = MARGIN.toFloat()

        // Draw header background
        paint.color = Color.parseColor(COLOR_TABLE_HEADER)
        canvas.drawRect(x, yStart, (PAGE_WIDTH - MARGIN).toFloat(), yStart + 30f, paint)

        // Header text
        paint.color = Color.WHITE
        paint.textSize = 11f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        val textY = yStart + 19f

        // S.No
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("S.No", x + COL_SNO_WIDTH / 2, textY, paint)
        x += COL_SNO_WIDTH

        // Vertical divider
        paint.color = Color.WHITE
        paint.alpha = 100
        canvas.drawLine(x, yStart, x, yStart + 30f, paint)
        paint.alpha = 255
        paint.color = Color.WHITE

        // Event Type
        canvas.drawText("Event Type", x + COL_TYPE_WIDTH / 2, textY, paint)
        x += COL_TYPE_WIDTH

        // Vertical divider
        paint.color = Color.WHITE
        paint.alpha = 100
        canvas.drawLine(x, yStart, x, yStart + 30f, paint)
        paint.alpha = 255
        paint.color = Color.WHITE

        // Date & Time
        canvas.drawText("Date & Time", x + COL_DATETIME_WIDTH / 2, textY, paint)
        x += COL_DATETIME_WIDTH

        // Vertical divider
        paint.color = Color.WHITE
        paint.alpha = 100
        canvas.drawLine(x, yStart, x, yStart + 30f, paint)
        paint.alpha = 255
        paint.color = Color.WHITE

        // Event Image
        canvas.drawText("Event Image", x + COL_IMAGE_WIDTH / 2, textY, paint)

        paint.textAlign = Paint.Align.LEFT
    }

    private suspend fun drawTableRow(canvas: Canvas, paint: Paint, event: Event,
                                     yStart: Float, rowNumber: Int): Float {
        var x = MARGIN.toFloat()
        val rowEnd = yStart + ROW_HEIGHT

        // Draw row border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawRect(MARGIN.toFloat(), yStart, (PAGE_WIDTH - MARGIN).toFloat(), rowEnd, paint)
        paint.style = Paint.Style.FILL

        // Alternate row background
        if (rowNumber % 2 == 0) {
            paint.color = Color.parseColor("#F8F9FA")
            paint.alpha = 128
            canvas.drawRect(MARGIN.toFloat(), yStart, (PAGE_WIDTH - MARGIN).toFloat(), rowEnd, paint)
            paint.alpha = 255
        }

        val textY = yStart + ROW_HEIGHT / 2 + 5

        // S.No
        paint.color = Color.parseColor(COLOR_TEXT_PRIMARY)
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(rowNumber.toString(), x + COL_SNO_WIDTH / 2, textY, paint)
        x += COL_SNO_WIDTH

        // Vertical divider
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawLine(x, yStart, x, rowEnd, paint)
        paint.style = Paint.Style.FILL

        // Event Type
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 10f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val eventType = event.typeDisplay ?: "Unknown Event"
        val wrappedType = wrapText(eventType, COL_TYPE_WIDTH - 10, paint)
        var typeY = yStart + (ROW_HEIGHT - (wrappedType.size * 12)) / 2 + 12
        wrappedType.forEach { line ->
            canvas.drawText(line, x + 5, typeY, paint)
            typeY += 12
        }
        x += COL_TYPE_WIDTH

        // Vertical divider
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawLine(x, yStart, x, rowEnd, paint)
        paint.style = Paint.Style.FILL

        // Date & Time
        val eventDate = getEventDate(event)
        val dateStr = dateFormat.format(eventDate)
        val timeStr = timeFormat.format(eventDate)

        var dateY = yStart + (ROW_HEIGHT / 2) - 5
        paint.textSize = 9f
        canvas.drawText(dateStr, x + 5, dateY, paint)
        dateY += 14
        canvas.drawText(timeStr, x + 5, dateY, paint)
        x += COL_DATETIME_WIDTH

        // Vertical divider
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawLine(x, yStart, x, rowEnd, paint)
        paint.style = Paint.Style.FILL

        // ✅ IMPROVED: Event Image with better quality rendering
        val bitmap = when (event.type) {
            "off-route", "tamper", "overspeed" -> {
                captureMapPreview(event) ?: loadEventImage(event)
            }
            else -> {
                loadEventImage(event)
            }
        }

        val imgX = x + 10
        val imgY = yStart + (ROW_HEIGHT - IMAGE_SIZE) / 2

        if (bitmap != null) {
            val scaledBitmap = scaleBitmapToFit(bitmap, IMAGE_SIZE, IMAGE_SIZE)

            // ✅ Enable high-quality rendering
            paint.isAntiAlias = true
            paint.isFilterBitmap = true

            // Draw image border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = Color.parseColor(COLOR_BORDER)
            canvas.drawRect(imgX, imgY, imgX + scaledBitmap.width,
                imgY + scaledBitmap.height, paint)
            paint.style = Paint.Style.FILL

            // ✅ Draw image with high-quality paint
            canvas.save()
            canvas.drawBitmap(scaledBitmap, imgX, imgY, paint)
            canvas.restore()
        } else {
            // Placeholder
            paint.color = Color.parseColor("#ECF0F1")
            canvas.drawRect(imgX, imgY, imgX + IMAGE_SIZE, imgY + IMAGE_SIZE, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = Color.parseColor(COLOR_BORDER)
            canvas.drawRect(imgX, imgY, imgX + IMAGE_SIZE, imgY + IMAGE_SIZE, paint)
            paint.style = Paint.Style.FILL

            paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
            paint.textSize = 8f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("No Image", imgX + IMAGE_SIZE / 2, imgY + IMAGE_SIZE / 2, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        return rowEnd
    }

    private fun wrapText(text: String, maxWidth: Float, paint: Paint): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""

        words.forEach { word ->
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine = testLine
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                }
                currentLine = word
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        return lines.take(3) // Max 3 lines to fit in row
    }

    private fun drawPremiumBackground(canvas: Canvas, paint: Paint) {
        paint.color = Color.parseColor("#F8F9FA")
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), paint)
    }

    private fun drawHeaderSection(canvas: Canvas, paint: Paint, yStart: Float, title: String) {
        paint.color = Color.parseColor(COLOR_PRIMARY)
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), yStart + 60, paint)

        paint.color = Color.parseColor(COLOR_SECONDARY)
        paint.strokeWidth = 4f
        canvas.drawLine(MARGIN.toFloat(), yStart + 55, (PAGE_WIDTH - MARGIN).toFloat(), yStart + 55, paint)

        paint.color = Color.WHITE
        paint.textSize = 24f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.style = Paint.Style.FILL
        canvas.drawText(title, MARGIN.toFloat(), yStart + 30, paint)

        paint.textSize = 11f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.alpha = 200
        canvas.drawText("Generated on ${dateFormat.format(Date())}", MARGIN.toFloat(), yStart + 50, paint)
        paint.alpha = 255
    }

    private fun drawChannelInfoCard(canvas: Canvas, paint: Paint, area: String, type: String, yStart: Float): Float {
        var y = yStart

        drawCardShadow(canvas, paint, MARGIN.toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y + 70)

        paint.color = Color.WHITE
        canvas.drawRoundRect(MARGIN.toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y + 70, 8f, 8f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawRoundRect(MARGIN.toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y + 70, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        y += 25

        paint.color = Color.parseColor(COLOR_SECONDARY)
        paint.alpha = 30
        canvas.drawCircle((MARGIN + 25).toFloat(), y + 5, 18f, paint)
        paint.alpha = 255

        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        paint.textSize = 11f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("CHANNEL INFORMATION", (MARGIN + 50).toFloat(), y, paint)

        y += 22

        paint.color = Color.parseColor(COLOR_TEXT_PRIMARY)
        paint.textSize = 16f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("$area - $type", (MARGIN + 50).toFloat(), y, paint)

        return yStart + 70
    }

    private fun drawEventDetailsCard(canvas: Canvas, paint: Paint, event: Event, yStart: Float): Float {
        var y = yStart
        val cardHeight = 180f

        drawCardShadow(canvas, paint, MARGIN.toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y + cardHeight)

        paint.color = Color.WHITE
        canvas.drawRoundRect(MARGIN.toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y + cardHeight, 8f, 8f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawRoundRect(MARGIN.toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y + cardHeight, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        y += 25

        val eventType = event.typeDisplay ?: "Unknown Event"
        drawBadge(canvas, paint, (MARGIN + 20).toFloat(), y - 8, eventType, COLOR_ACCENT)
        y += 30

        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        paint.textSize = 11f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("LOCATION", (MARGIN + 20).toFloat(), y, paint)

        y += 18
        paint.color = Color.parseColor(COLOR_TEXT_PRIMARY)
        paint.textSize = 14f
        val location = event.data["location"] as? String ?: "Unknown"
        canvas.drawText(location, (MARGIN + 20).toFloat(), y, paint)

        y += 25

        val eventDate = getEventDate(event)

        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        paint.textSize = 11f
        canvas.drawText("DATE & TIME", (MARGIN + 20).toFloat(), y, paint)

        y += 18
        paint.color = Color.parseColor(COLOR_TEXT_PRIMARY)
        paint.textSize = 14f
        val dateStr = dateFormat.format(eventDate)
        val timeStr = timeFormat.format(eventDate)
        canvas.drawText("$dateStr at $timeStr", (MARGIN + 20).toFloat(), y, paint)

        event.id?.let { eventId ->
            if (SavedMessagesManager.isMessageSaved(eventId)) {
                val savedMessage = SavedMessagesManager.getSavedMessages().find { it.eventId == eventId }
                savedMessage?.let {
                    y += 25
                    drawStatusBadge(canvas, paint, (MARGIN + 20).toFloat(), y - 8, it.priority.displayName)

                    if (it.comment.isNotEmpty()) {
                        y += 25
                        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
                        paint.textSize = 11f
                        canvas.drawText("NOTE", (MARGIN + 20).toFloat(), y, paint)

                        y += 18
                        paint.color = Color.parseColor(COLOR_TEXT_PRIMARY)
                        paint.textSize = 12f
                        canvas.drawText(it.comment.take(50) + if (it.comment.length > 50) "..." else "",
                            (MARGIN + 20).toFloat(), y, paint)
                    }
                }
            }
        }

        return yStart + cardHeight
    }

    private fun drawImageWithShadow(canvas: Canvas, paint: Paint, bitmap: Bitmap,
                                    yStart: Float, maxHeight: Int = 400): Float {  // Increased from 320
        val scaledBitmap = scaleBitmapToFit(bitmap, CONTENT_WIDTH - 40, maxHeight)
        val imgX = MARGIN + 20f
        val imgY = yStart

        // Shadow
        paint.color = Color.parseColor("#000000")
        paint.alpha = 20
        canvas.drawRoundRect(imgX + 2, imgY + 2, imgX + scaledBitmap.width + 2,
            imgY + scaledBitmap.height + 2, 6f, 6f, paint)
        paint.alpha = 255

        // ✅ Enable anti-aliasing and filtering for better quality
        paint.isAntiAlias = true
        paint.isFilterBitmap = true

        canvas.save()
        val path = android.graphics.Path()
        path.addRoundRect(android.graphics.RectF(imgX, imgY, imgX + scaledBitmap.width,
            imgY + scaledBitmap.height), 6f, 6f, android.graphics.Path.Direction.CW)
        canvas.clipPath(path)

        // ✅ Draw with high-quality paint
        canvas.drawBitmap(scaledBitmap, imgX, imgY, paint)
        canvas.restore()

        return imgY + scaledBitmap.height + 20
    }

    private fun drawImagePlaceholder(canvas: Canvas, paint: Paint, yStart: Float, height: Int): Float {
        val imgX = MARGIN + 20f
        val imgWidth = CONTENT_WIDTH - 40f

        paint.color = Color.parseColor("#ECF0F1")
        canvas.drawRoundRect(imgX, yStart, imgX + imgWidth, yStart + height, 6f, 6f, paint)

        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        paint.textSize = 12f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Image Unavailable", imgX + imgWidth / 2, yStart + height / 2, paint)
        paint.textAlign = Paint.Align.LEFT

        return yStart + height + 20
    }

    private fun drawBadge(canvas: Canvas, paint: Paint, x: Float, y: Float, text: String, color: String) {
        paint.color = Color.parseColor(color)
        paint.alpha = 30
        val textWidth = paint.measureText(text) + 20
        canvas.drawRoundRect(x, y, x + textWidth, y + 20, 10f, 10f, paint)

        paint.alpha = 255
        paint.color = Color.parseColor(color)
        paint.textSize = 11f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(text, x + 10, y + 14, paint)
    }

    private fun drawStatusBadge(canvas: Canvas, paint: Paint, x: Float, y: Float, status: String) {
        val color = when (status.lowercase()) {
            "high" -> COLOR_ACCENT
            "medium", "moderate" -> COLOR_WARNING
            else -> COLOR_SUCCESS
        }
        drawBadge(canvas, paint, x, y, "Priority: $status", color)
    }

    private fun drawCardShadow(canvas: Canvas, paint: Paint, left: Float, top: Float,
                               right: Float, bottom: Float) {
        paint.color = Color.parseColor("#000000")
        paint.alpha = 15
        canvas.drawRoundRect(left + 2, top + 2, right + 2, bottom + 2, 8f, 8f, paint)
        paint.alpha = 255
    }

    private fun drawFooter(canvas: Canvas, paint: Paint, currentPage: Int, totalPages: Int) {
        val y = PAGE_HEIGHT - 25f

        paint.color = Color.parseColor(COLOR_BORDER)
        paint.strokeWidth = 1f
        canvas.drawLine(MARGIN.toFloat(), y - 15, (PAGE_WIDTH - MARGIN).toFloat(), y - 15, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        paint.textSize = 10f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Page $currentPage of $totalPages", (PAGE_WIDTH / 2).toFloat(), y, paint)

        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("ICCC Alert System", MARGIN.toFloat(), y, paint)

        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Confidential", (PAGE_WIDTH - MARGIN).toFloat(), y, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun loadEventImage(event: Event): Bitmap? {
        return try {
            val area = event.area ?: return null
            val eventId = event.id ?: return null
            val httpUrl = getHttpUrlForArea(area)
            val imageUrl = "$httpUrl/va/event/?id=$eventId"

            val request = Request.Builder().url(imageUrl).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val inputStream = response.body?.byteStream()

                // ✅ Use BitmapFactory.Options for high-quality decoding
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888  // Best quality
                    inScaled = false  // Don't scale during decode
                    inDither = false  // No dithering
                    inPreferQualityOverSpeed = true  // Prefer quality
                }

                val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                bitmap
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image for PDF: ${e.message}")
            null
        }
    }

    private suspend fun captureMapPreview(event: Event): Bitmap? = withContext(Dispatchers.Default) {
        try {
            // Check if it's a GPS event
            if (event.type != "off-route" && event.type != "tamper" && event.type != "overspeed") {
                return@withContext null
            }

            // Extract map data (same as before)
            val alertLoc = (event.data["alertLocation"] as? Map<*, *>)?.let {
                val lat = (it["lat"] as? Number)?.toDouble() ?: return@withContext null
                val lng = (it["lng"] as? Number)?.toDouble() ?: return@withContext null
                GeoPoint(lat, lng)
            } ?: return@withContext null

            // Extract geofence data (same as before - code unchanged)
            val geofenceMap = event.data["geofence"] as? Map<*, *>
            val geofencePoints = mutableListOf<GeoPoint>()
            var geofenceType: String? = null
            var geofenceColor = "#3388ff"

            if (geofenceMap != null) {
                val geojsonMap = geofenceMap["geojson"] as? Map<*, *>
                val type = geojsonMap?.get("type") as? String
                val coordinates = geojsonMap?.get("coordinates")

                val attributesMap = geofenceMap["attributes"] as? Map<*, *>
                val color = attributesMap?.get("color") as? String
                val polylineColor = attributesMap?.get("polylineColor") as? String
                geofenceColor = polylineColor ?: color ?: "#3388ff"

                if (type != null && coordinates != null) {
                    geofenceType = type
                    val points = when (type) {
                        "Point" -> {
                            val coord = coordinates as? List<*> ?: return@withContext null
                            listOf(GeoPoint(
                                (coord[1] as? Number)?.toDouble() ?: return@withContext null,
                                (coord[0] as? Number)?.toDouble() ?: return@withContext null
                            ))
                        }
                        "LineString" -> {
                            val coords = coordinates as? List<*> ?: return@withContext null
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
                            val rings = coordinates as? List<*> ?: return@withContext null
                            val outerRing = rings.firstOrNull() as? List<*> ?: return@withContext null
                            outerRing.mapNotNull { coordPair ->
                                val pair = coordPair as? List<*> ?: return@mapNotNull null
                                if (pair.size < 2) return@mapNotNull null
                                GeoPoint(
                                    (pair[1] as? Number)?.toDouble() ?: return@mapNotNull null,
                                    (pair[0] as? Number)?.toDouble() ?: return@mapNotNull null
                                )
                            }
                        }
                        else -> emptyList()
                    }
                    geofencePoints.addAll(points)
                }
            }

            // ✅ IMPROVED: Create higher resolution bitmap
            val mapWidth = 800   // Increased from 500
            val mapHeight = 500  // Increased from 300
            val bitmap = Bitmap.createBitmap(mapWidth, mapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // ✅ Enable high-quality rendering
            paint.isAntiAlias = true
            paint.isFilterBitmap = true

            // Draw background
            paint.color = Color.parseColor("#F5F7FA")
            canvas.drawRect(0f, 0f, mapWidth.toFloat(), mapHeight.toFloat(), paint)

            // Calculate bounds and scale (same logic as before)
            val allPoints = mutableListOf(alertLoc)
            allPoints.addAll(geofencePoints)

            val minLat = allPoints.minOf { it.latitude }
            val maxLat = allPoints.maxOf { it.latitude }
            val minLng = allPoints.minOf { it.longitude }
            val maxLng = allPoints.maxOf { it.longitude }

            val latRange = maxLat - minLat
            val lngRange = maxLng - minLng

            val padding = 0.2
            val paddedLatRange = latRange * (1 + padding)
            val paddedLngRange = lngRange * (1 + padding)

            val centerLat = (minLat + maxLat) / 2
            val centerLng = (minLng + maxLng) / 2

            val mapPadding = 60f  // Increased from 40f
            val scaleX = (mapWidth - 2 * mapPadding) / paddedLngRange
            val scaleY = (mapHeight - 2 * mapPadding) / paddedLatRange
            val scale = minOf(scaleX, scaleY)

            fun toCanvasX(lng: Double): Float {
                return ((lng - centerLng) * scale + mapWidth / 2).toFloat()
            }

            fun toCanvasY(lat: Double): Float {
                return (mapHeight / 2 - (lat - centerLat) * scale).toFloat()
            }

            // Draw geofence (rendering logic same as before but with better quality)
            if (geofencePoints.isNotEmpty() && geofenceType != null) {
                val geoColor = try {
                    Color.parseColor(if (geofenceColor.startsWith("#")) geofenceColor else "#$geofenceColor")
                } catch (e: Exception) {
                    Color.parseColor("#3388ff")
                }

                when (geofenceType) {
                    "LineString" -> {
                        paint.color = geoColor
                        paint.strokeWidth = 5f  // Increased from 4f
                        paint.style = Paint.Style.STROKE
                        paint.strokeCap = Paint.Cap.ROUND
                        paint.strokeJoin = Paint.Join.ROUND
                        val path = android.graphics.Path()
                        geofencePoints.forEachIndexed { index, point ->
                            val x = toCanvasX(point.longitude)
                            val y = toCanvasY(point.latitude)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        canvas.drawPath(path, paint)
                    }
                    "Polygon" -> {
                        // Fill
                        paint.color = Color.argb(30, Color.red(geoColor), Color.green(geoColor), Color.blue(geoColor))
                        paint.style = Paint.Style.FILL
                        val path = android.graphics.Path()
                        geofencePoints.forEachIndexed { index, point ->
                            val x = toCanvasX(point.longitude)
                            val y = toCanvasY(point.latitude)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.close()
                        canvas.drawPath(path, paint)

                        // Stroke
                        paint.color = geoColor
                        paint.strokeWidth = 4f  // Increased from 3f
                        paint.style = Paint.Style.STROKE
                        paint.strokeCap = Paint.Cap.ROUND
                        paint.strokeJoin = Paint.Join.ROUND
                        canvas.drawPath(path, paint)
                    }
                    "Point" -> {
                        val point = geofencePoints.first()
                        val x = toCanvasX(point.longitude)
                        val y = toCanvasY(point.latitude)

                        // Fill
                        paint.color = Color.argb(50, Color.red(geoColor), Color.green(geoColor), Color.blue(geoColor))
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(x, y, 40f, paint)  // Increased from 30f

                        // Stroke
                        paint.color = geoColor
                        paint.strokeWidth = 3f  // Increased from 2f
                        paint.style = Paint.Style.STROKE
                        canvas.drawCircle(x, y, 40f, paint)
                    }
                }
            }

            // ✅ IMPROVED: Draw alert location marker with better quality
            paint.style = Paint.Style.FILL
            val alertX = toCanvasX(alertLoc.longitude)
            val alertY = toCanvasY(alertLoc.latitude)

            // Marker shadow
            paint.color = Color.argb(100, 0, 0, 0)
            canvas.drawCircle(alertX + 3, alertY + 3, 16f, paint)  // Increased from 12f

            // Marker
            paint.color = Color.parseColor("#FF5722")
            canvas.drawCircle(alertX, alertY, 16f, paint)  // Increased from 12f

            // Marker border
            paint.color = Color.WHITE
            paint.strokeWidth = 3f  // Increased from 2f
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(alertX, alertY, 16f, paint)

            // ✅ IMPROVED: Add labels with better rendering
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 14f  // Increased from 11f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

            // Alert label
            val labelText = "Alert Location"
            val labelWidth = paint.measureText(labelText) + 24
            val labelX = alertX - labelWidth / 2
            val labelY = alertY - 30

            paint.color = Color.parseColor("#FF5722")
            canvas.drawRoundRect(labelX, labelY - 18, labelX + labelWidth, labelY + 6, 5f, 5f, paint)

            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(labelText, alertX, labelY, paint)
            paint.textAlign = Paint.Align.LEFT

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing map preview: ${e.message}", e)
            null
        }
    }

    private fun scaleBitmapToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        var newWidth = maxWidth
        var newHeight = (newWidth / aspectRatio).toInt()

        if (newHeight > maxHeight) {
            newHeight = maxHeight
            newWidth = (newHeight * aspectRatio).toInt()
        }

        // ✅ Use FILTER_BITMAP flag for better quality
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.isFilterBitmap = true

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
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

    private fun saveToDownloads(pdfDocument: PdfDocument, fileName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 and above - Use MediaStore API
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        pdfDocument.writeTo(outputStream)
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(it, contentValues, null, null)
                }

                uri
            } else {
                // Android 9 and below - Direct file access
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }

                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    pdfDocument.writeTo(outputStream)
                }

                // Return URI for the file
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to Downloads: ${e.message}", e)
            null
        }
    }
}