package com.example.iccc_alert_app

import android.content.Context
import android.content.ContentValues
import android.graphics.*
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
import org.osmdroid.util.GeoPoint
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class PdfGenerator(private val context: Context) {

    private val client = OkHttpClient()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    private val eventTimeParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    companion object {
        private const val TAG = "PdfGenerator"

        // Page dimensions (A4 Landscape for better table view)
        private const val PAGE_WIDTH = 11f * 72  // 792 points
        private const val PAGE_HEIGHT = 8.5f * 72  // 612 points
        private const val MARGIN = 30f
        private const val CONTENT_WIDTH = PAGE_WIDTH - (2 * MARGIN)

        // Enhanced table dimensions
        private const val ROW_HEIGHT = 135f
        private const val IMAGE_SIZE = 115  // Larger images

        // Optimized column widths for landscape
        private const val COL_SNO_WIDTH = 50f
        private const val COL_TYPE_WIDTH = 115f
        private const val COL_LOCATION_WIDTH = 190f
        private const val COL_DATETIME_WIDTH = 115f
        private const val COL_IMAGE_WIDTH = 262f  // Remaining space

        // Premium color palette
        private const val COLOR_PRIMARY = "#1976D2"  // Material Blue
        private const val COLOR_PRIMARY_LIGHT = "#E3F2FD"
        private const val COLOR_ACCENT = "#FF5722"
        private const val COLOR_SUCCESS = "#4CAF50"
        private const val COLOR_WARNING = "#FF9800"
        private const val COLOR_TEXT_PRIMARY = "#212121"
        private const val COLOR_TEXT_SECONDARY = "#757575"
        private const val COLOR_BACKGROUND = "#FAFAFA"
        private const val COLOR_CARD_BG = "#FFFFFF"
        private const val COLOR_BORDER = "#E0E0E0"
        private const val COLOR_TABLE_HEADER = "#1976D2"

        private const val EVENTS_PER_PAGE = 3
    }

    suspend fun generateSingleEventPdf(
        event: Event,
        channelArea: String,
        channelType: String
    ): File? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "📄 Generating single event PDF...")

            val pdfDocument = PdfDocument()

            // Use portrait A4 for single event (more traditional)
            val pageWidth = 595  // A4 width in points
            val pageHeight = 842  // A4 height in points

            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // Draw premium background
            paint.color = Color.WHITE
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), paint)

            var yPosition = MARGIN + 20

            // Header Section
            drawSingleEventHeader(canvas, paint, channelArea, channelType, yPosition)
            yPosition = 130f

            // Event Details Card
            yPosition = drawSingleEventDetailsCard(canvas, paint, event, yPosition, pageWidth.toFloat())
            yPosition += 20

            // Load and draw image/map with high quality
            val bitmap = when (event.type) {
                "off-route", "tamper", "overspeed" -> {
                    captureMapPreview(event) ?: loadEventImage(event)
                }
                else -> loadEventImage(event)
            }

            if (bitmap != null) {
                Log.d(TAG, "Original bitmap size: ${bitmap.width}x${bitmap.height}, config: ${bitmap.config}")
                yPosition = drawLargeImageHighQuality(canvas, paint, bitmap, yPosition, pageWidth.toFloat())
            } else {
                Log.w(TAG, "No bitmap available for event")
                yPosition = drawImagePlaceholder(canvas, paint, yPosition, 350f, pageWidth.toFloat())
            }

            // Footer
            drawSingleEventFooter(canvas, paint, pageWidth.toFloat(), pageHeight.toFloat())

            pdfDocument.finishPage(page)

            val fileName = "Event_${event.id}_${fileNameFormat.format(Date())}.pdf"
            val uri = saveToDownloads(pdfDocument, fileName)
            pdfDocument.close()

            if (uri != null) {
                Log.d(TAG, "✅ Single event PDF saved: $fileName")
                File(fileName)
            } else {
                Log.e(TAG, "❌ Failed to save single event PDF")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error generating single event PDF: ${e.message}", e)
            null
        }
    }

    suspend fun generateBulkEventsPdf(
        events: List<Event>,
        channelArea: String,
        channelType: String
    ): File? = withContext(Dispatchers.IO) {
        try {
            if (events.isEmpty()) {
                Log.e(TAG, "No events to generate PDF")
                return@withContext null
            }

            Log.d(TAG, "📄 Generating PDF for ${events.size} events...")

            val pdfDocument = PdfDocument()
            var pageNumber = 1
            val totalPages = (events.size + EVENTS_PER_PAGE - 1) / EVENTS_PER_PAGE

            events.chunked(EVENTS_PER_PAGE).forEach { pageEvents ->
                val pageInfo = PdfDocument.PageInfo.Builder(
                    PAGE_WIDTH.toInt(),
                    PAGE_HEIGHT.toInt(),
                    pageNumber
                ).create()

                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)

                // Draw premium background
                drawPremiumBackground(canvas, paint)

                var yPosition = MARGIN + 20

                // Draw enhanced header
                drawEnhancedHeader(
                    canvas, paint, channelArea, channelType,
                    events.size, yPosition
                )
                yPosition = 170f  // Header takes 170pt

                // Draw each event row
                pageEvents.forEachIndexed { index, event ->
                    val eventNumber = (pageNumber - 1) * EVENTS_PER_PAGE + index + 1
                    yPosition = drawEnhancedEventRow(
                        canvas, paint, event, eventNumber, yPosition
                    )

                    // Draw separator line between events
                    if (index < pageEvents.size - 1) {
                        paint.color = Color.parseColor(COLOR_BORDER)
                        paint.strokeWidth = 0.5f
                        canvas.drawLine(
                            MARGIN, yPosition,
                            PAGE_WIDTH - MARGIN, yPosition,
                            paint
                        )
                    }
                }

                // Draw enhanced footer
                drawEnhancedFooter(canvas, paint, pageNumber, totalPages)

                pdfDocument.finishPage(page)
                pageNumber++
            }

            val fileName = "Events_${channelArea}_${fileNameFormat.format(Date())}.pdf"
            val uri = saveToDownloads(pdfDocument, fileName)
            pdfDocument.close()

            if (uri != null) {
                Log.d(TAG, "✅ PDF saved successfully: $fileName")
                File(fileName)
            } else {
                Log.e(TAG, "❌ Failed to save PDF")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error generating PDF: ${e.message}", e)
            null
        }
    }

    private fun drawSingleEventHeader(
        canvas: Canvas,
        paint: Paint,
        channelArea: String,
        channelType: String,
        yStart: Float
    ) {
        var y = yStart

        // Header background with gradient effect
        paint.color = Color.parseColor(COLOR_PRIMARY)
        canvas.drawRect(0f, 0f, 595f, y + 60, paint)

        // Accent line
        paint.color = Color.parseColor(COLOR_ACCENT)
        paint.strokeWidth = 4f
        canvas.drawLine(MARGIN, y + 55, 595f - MARGIN, y + 55, paint)

        // Title
        paint.color = Color.WHITE
        paint.textSize = 24f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.style = Paint.Style.FILL
        canvas.drawText("Event Report", MARGIN, y + 25, paint)

        // Subtitle
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.alpha = 200
        canvas.drawText("$channelType - $channelArea", MARGIN, y + 45, paint)
        paint.alpha = 255

        // Date (right aligned)
        val dateText = dateTimeFormat.format(Date())
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(dateText, 595f - MARGIN, y + 45, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawSingleEventDetailsCard(
        canvas: Canvas,
        paint: Paint,
        event: Event,
        yStart: Float,
        pageWidth: Float
    ): Float {
        var y = yStart
        val cardHeight = 200f  // Reduced from 220f for better spacing
        val cardLeft = MARGIN
        val cardRight = pageWidth - MARGIN

        // Card shadow
        paint.color = Color.argb(30, 0, 0, 0)
        canvas.drawRoundRect(cardLeft + 3, y + 3, cardRight + 3, y + cardHeight + 3, 8f, 8f, paint)

        // Card background
        paint.color = Color.WHITE
        canvas.drawRoundRect(cardLeft, y, cardRight, y + cardHeight, 8f, 8f, paint)

        // Card border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawRoundRect(cardLeft, y, cardRight, y + cardHeight, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        y += 20

        // Event type badge
        val eventType = event.typeDisplay ?: event.type ?: "Unknown"
        val (icon, badgeColor) = getEventTypeIconAndColor(event.type ?: "")

        val badgeText = eventType  // Remove icon from badge text
        paint.textSize = 13f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val badgeWidth = paint.measureText(badgeText) + 24

        // Badge background
        paint.color = Color.parseColor(badgeColor)
        paint.alpha = 40  // Slightly more opacity
        val badgeRect = RectF(cardLeft + 20, y - 2, cardLeft + 20 + badgeWidth, y + 20)
        canvas.drawRoundRect(badgeRect, 10f, 10f, paint)
        paint.alpha = 255

        // Badge text
        paint.color = Color.parseColor(badgeColor)
        canvas.drawText(badgeText, cardLeft + 32, y + 13, paint)

        y += 35

        // Location section
        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        paint.textSize = 9f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("LOCATION", cardLeft + 20, y, paint)

        y += 16
        paint.color = Color.BLACK
        paint.textSize = 13f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val location = event.data["location"] as? String ?: "Unknown"
        val locationLines = wrapText(location, pageWidth - MARGIN * 2 - 40, paint)
        locationLines.take(2).forEach { line ->
            canvas.drawText(line, cardLeft + 20, y, paint)
            y += 16
        }

        y += 8

        // GPS coordinates if available
        val alertLoc = extractAlertLocation(event.data)
        if (alertLoc != null) {
            paint.textSize = 10f
            paint.color = Color.parseColor(COLOR_PRIMARY)
            val coordText = String.format("%.6f, %.6f", alertLoc.latitude, alertLoc.longitude)
            canvas.drawText(coordText, cardLeft + 20, y, paint)
            y += 20
        } else {
            y += 10
        }

        // Date & Time section
        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        paint.textSize = 9f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("DATE & TIME", cardLeft + 20, y, paint)

        y += 16
        val eventDate = getEventDate(event)
        paint.color = Color.BLACK
        paint.textSize = 13f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val dateStr = dateFormat.format(eventDate)
        val timeStr = timeFormat.format(eventDate)
        canvas.drawText("$dateStr at $timeStr", cardLeft + 20, y, paint)

        y += 20

        // Vehicle number for GPS events
        if (event.type in listOf("off-route", "tamper", "overspeed")) {
            val vehicleNum = event.data["vehicleNumber"] as? String
            if (vehicleNum != null) {
                paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
                paint.textSize = 9f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText("VEHICLE", cardLeft + 20, y, paint)

                y += 16
                paint.color = Color.BLACK
                paint.textSize = 13f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                canvas.drawText(vehicleNum, cardLeft + 20, y, paint)
            }
        }

        return yStart + cardHeight
    }

    private fun drawLargeImageHighQuality(
        canvas: Canvas,
        paint: Paint,
        bitmap: Bitmap,
        yStart: Float,
        pageWidth: Float
    ): Float {
        val maxWidth = (pageWidth - MARGIN * 2 - 40).toInt()
        val maxHeight = 400

        // Calculate scaled dimensions maintaining aspect ratio
        val aspectRatio = bitmap.width.toFloat() / bitmap.height
        var newWidth = maxWidth
        var newHeight = (newWidth / aspectRatio).toInt()

        if (newHeight > maxHeight) {
            newHeight = maxHeight
            newWidth = (newHeight * aspectRatio).toInt()
        }

        Log.d(TAG, "Scaling bitmap from ${bitmap.width}x${bitmap.height} to ${newWidth}x${newHeight}")

        val imgX = MARGIN + 20
        val imgY = yStart

        // Create matrix for high-quality scaling
        val matrix = Matrix()
        val scaleX = newWidth.toFloat() / bitmap.width
        val scaleY = newHeight.toFloat() / bitmap.height
        matrix.setScale(scaleX, scaleY)

        // Create high-quality scaled bitmap using matrix
        val scaledBitmap = Bitmap.createBitmap(
            bitmap,
            0, 0,
            bitmap.width, bitmap.height,
            matrix,
            true  // filter = true for high quality
        )

        Log.d(TAG, "Scaled bitmap created: ${scaledBitmap.width}x${scaledBitmap.height}")

        // Image shadow
        paint.color = Color.argb(30, 0, 0, 0)
        val shadowRect = RectF(imgX + 3, imgY + 3, imgX + scaledBitmap.width + 3, imgY + scaledBitmap.height + 3)
        canvas.drawRoundRect(shadowRect, 8f, 8f, paint)

        // Reset paint for image drawing with maximum quality
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.isDither = false

        canvas.save()

        // Clip to rounded rectangle
        val path = Path()
        val imageRect = RectF(imgX, imgY, imgX + scaledBitmap.width.toFloat(), imgY + scaledBitmap.height.toFloat())
        path.addRoundRect(imageRect, 8f, 8f, Path.Direction.CW)
        canvas.clipPath(path)

        // Draw bitmap directly at position (no additional scaling)
        canvas.drawBitmap(scaledBitmap, imgX, imgY, paint)

        canvas.restore()

        // Image border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawRoundRect(imageRect, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        Log.d(TAG, "Image drawn successfully at position ($imgX, $imgY)")

        return imgY + scaledBitmap.height + 20
    }

    private fun drawLargeImage(
        canvas: Canvas,
        paint: Paint,
        bitmap: Bitmap,
        yStart: Float,
        pageWidth: Float
    ): Float {
        val maxWidth = (pageWidth - MARGIN * 2 - 40).toInt()
        val maxHeight = 400  // Slightly reduced for better fit

        // Calculate scaled dimensions maintaining aspect ratio
        val aspectRatio = bitmap.width.toFloat() / bitmap.height
        var newWidth = maxWidth
        var newHeight = (newWidth / aspectRatio).toInt()

        if (newHeight > maxHeight) {
            newHeight = maxHeight
            newWidth = (newHeight * aspectRatio).toInt()
        }

        val imgX = MARGIN + 20
        val imgY = yStart

        // Create high-quality scaled bitmap
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)

        // Image shadow
        paint.color = Color.argb(30, 0, 0, 0)
        val shadowRect = RectF(imgX + 3, imgY + 3, imgX + newWidth + 3, imgY + newHeight + 3)
        canvas.drawRoundRect(shadowRect, 8f, 8f, paint)

        // Enable high-quality rendering
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.isDither = false

        canvas.save()

        // Clip to rounded rectangle
        val path = Path()
        val imageRect = RectF(imgX, imgY, imgX + newWidth.toFloat(), imgY + newHeight.toFloat())
        path.addRoundRect(imageRect, 8f, 8f, Path.Direction.CW)
        canvas.clipPath(path)

        // Draw bitmap with high quality paint
        canvas.drawBitmap(scaledBitmap, imgX, imgY, paint)

        canvas.restore()

        // Image border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawRoundRect(imageRect, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        return imgY + newHeight + 20
    }

    private fun drawImagePlaceholder(
        canvas: Canvas,
        paint: Paint,
        yStart: Float,
        height: Float,
        pageWidth: Float
    ): Float {
        val imgX = MARGIN + 20
        val imgWidth = pageWidth - MARGIN * 2 - 40

        paint.color = Color.parseColor("#F5F5F5")
        val rect = RectF(imgX, yStart, imgX + imgWidth, yStart + height)
        canvas.drawRoundRect(rect, 8f, 8f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawRoundRect(rect, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        val placeholderText = "Image Unavailable"
        paint.textSize = 14f
        paint.color = Color.GRAY
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(placeholderText, imgX + imgWidth / 2, yStart + height / 2, paint)
        paint.textAlign = Paint.Align.LEFT

        return yStart + height + 20
    }

    private fun drawSingleEventFooter(
        canvas: Canvas,
        paint: Paint,
        pageWidth: Float,
        pageHeight: Float
    ) {
        val footerY = pageHeight - 40

        // Separator line
        paint.color = Color.parseColor(COLOR_BORDER)
        paint.strokeWidth = 1f
        canvas.drawLine(MARGIN, footerY, pageWidth - MARGIN, footerY, paint)

        // Footer text
        paint.textSize = 9f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)

        canvas.drawText("ICCC Event Manager", MARGIN, footerY + 15, paint)

        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Generated on ${dateTimeFormat.format(Date())}", pageWidth / 2, footerY + 15, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        canvas.drawText("Confidential", pageWidth - MARGIN, footerY + 15, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun getEventTypeIconAndColor(type: String): Pair<String, String> {
        return when (type.lowercase()) {
            "cd" -> Pair("🔥", "#FF5722")
            "id" -> Pair("🚪", "#F44336")
            "ct" -> Pair("👥", "#E91E63")
            "sh" -> Pair("🛡️", "#FF9800")
            "vd" -> Pair("🚗", "#2196F3")
            "pd" -> Pair("👤", "#4CAF50")
            "vc" -> Pair("📊", "#FFC107")
            "ii" -> Pair("🔍", "#9C27B0")
            "ls" -> Pair("📉", "#00BCD4")
            "off-route" -> Pair("🗺️", "#FF5722")
            "tamper" -> Pair("⚠️", "#F44336")
            "overspeed" -> Pair("⚡", "#FF9800")
            else -> Pair("❓", "#9E9E9E")
        }
    }

    private fun drawPremiumBackground(canvas: Canvas, paint: Paint) {
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, PAGE_WIDTH, PAGE_HEIGHT, paint)
    }

    private fun drawEnhancedHeader(
        canvas: Canvas,
        paint: Paint,
        channelArea: String,
        channelType: String,
        totalEvents: Int,
        yStart: Float
    ) {
        var y = yStart

        // Top bar background - light blue like iOS
        paint.color = Color.parseColor("#E3F2FD")
        canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 80, paint)

        // Company text (left side) - bold blue
        y += 20
        paint.color = Color.parseColor(COLOR_PRIMARY)
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("ICCC Event Manager", MARGIN + 15, y, paint)

        // Generated date (right side)
        val dateText = "Generated: ${dateTimeFormat.format(Date())}"
        paint.textSize = 10f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(dateText, PAGE_WIDTH - MARGIN - 15, y, paint)
        paint.textAlign = Paint.Align.LEFT

        y += 30

        // Main title (centered)
        val titleText = "Events Report"
        paint.textSize = 20f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.color = Color.BLACK
        val titleWidth = paint.measureText(titleText)
        canvas.drawText(titleText, (PAGE_WIDTH - titleWidth) / 2, y, paint)

        y += 25

        // Channel info (centered)
        val channelText = "$channelType - $channelArea"
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        val channelWidth = paint.measureText(channelText)
        canvas.drawText(channelText, (PAGE_WIDTH - channelWidth) / 2, y, paint)

        y += 25

        // Event count badge (centered) - matching iOS style
        val countText = "Total Events: $totalEvents"
        paint.textSize = 11f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val countWidth = paint.measureText(countText)
        val badgeWidth = countWidth + 30
        val badgeX = (PAGE_WIDTH - badgeWidth) / 2
        val badgeY = y - 7

        // Badge background - light blue
        paint.color = Color.parseColor("#E3F2FD")
        val badgeRect = RectF(badgeX, badgeY, badgeX + badgeWidth, badgeY + 22)
        canvas.drawRoundRect(badgeRect, 6f, 6f, paint)

        // Badge text - blue
        paint.color = Color.parseColor(COLOR_PRIMARY)
        canvas.drawText(countText, badgeX + 15, y + 8, paint)

        y += 25

        // Separator line - blue with shadow (matching iOS)
        paint.color = Color.parseColor(COLOR_PRIMARY)
        paint.strokeWidth = 2f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, paint)

        // Shadow line
        paint.color = Color.parseColor(COLOR_PRIMARY)
        paint.alpha = 76
        paint.strokeWidth = 1f
        canvas.drawLine(MARGIN, y + 2, PAGE_WIDTH - MARGIN, y + 2, paint)
        paint.alpha = 255
    }

    private suspend fun drawEnhancedEventRow(
        canvas: Canvas,
        paint: Paint,
        event: Event,
        eventNumber: Int,
        startY: Float
    ): Float {
        var currentX = MARGIN
        val rowEnd = startY + ROW_HEIGHT

        // Alternating row background
        if (eventNumber % 2 == 0) {
            paint.color = Color.parseColor("#F8F9FA")
            canvas.drawRect(MARGIN, startY, PAGE_WIDTH - MARGIN, rowEnd, paint)
        }

        // Subtle top shadow
        val shadowGradient = LinearGradient(
            0f, startY, 0f, startY + 4,
            intArrayOf(Color.parseColor("#10000000"), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        paint.shader = shadowGradient
        canvas.drawRect(MARGIN, startY, PAGE_WIDTH - MARGIN, startY + 4, paint)
        paint.shader = null

        // Column 1: Event Number with circular badge
        val numberText = "#$eventNumber"
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.color = Color.parseColor(COLOR_PRIMARY)

        val circleDiameter = 36f
        val circleX = currentX + (COL_SNO_WIDTH - circleDiameter) / 2
        val circleY = startY + (ROW_HEIGHT - circleDiameter) / 2

        // Circle background
        paint.color = Color.parseColor(COLOR_PRIMARY_LIGHT)
        canvas.drawCircle(circleX + circleDiameter / 2, circleY + circleDiameter / 2, circleDiameter / 2, paint)

        // Number text
        paint.color = Color.parseColor(COLOR_PRIMARY)
        val numberWidth = paint.measureText(numberText)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            numberText,
            currentX + COL_SNO_WIDTH / 2,
            startY + ROW_HEIGHT / 2 + 5,
            paint
        )
        paint.textAlign = Paint.Align.LEFT
        currentX += COL_SNO_WIDTH

        // Vertical separator
        drawVerticalLine(canvas, paint, currentX, startY + 5, ROW_HEIGHT - 10)
        currentX += 8

        // Column 2: Event Type
        val eventType = event.typeDisplay ?: event.type ?: "Unknown"
        paint.textSize = 11f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.color = Color.parseColor(COLOR_TEXT_PRIMARY)

        val typeLines = wrapText(eventType, COL_TYPE_WIDTH - 16, paint)
        var typeY = startY + (ROW_HEIGHT - (typeLines.size * 14)) / 2 + 14
        typeLines.forEach { line ->
            canvas.drawText(line, currentX, typeY, paint)
            typeY += 14
        }

        // Vehicle number for GPS events
        if (event.type in listOf("off-route", "tamper", "overspeed")) {
            val vehicleNum = event.data["vehicleNumber"] as? String
            if (vehicleNum != null) {
                paint.textSize = 9f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                paint.color = Color.parseColor(COLOR_PRIMARY)
                canvas.drawText("🚗 $vehicleNum", currentX, typeY + 5, paint)
            }
        }

        currentX += COL_TYPE_WIDTH

        // Vertical separator
        drawVerticalLine(canvas, paint, currentX, startY + 5, ROW_HEIGHT - 10)
        currentX += 8

        // Column 3: Location
        paint.textSize = 9f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        canvas.drawText("LOCATION", currentX, startY + 10, paint)

        paint.textSize = 10f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = Color.BLACK
        val location = event.data["location"] as? String ?: "Unknown"
        val locationLines = wrapText(location, COL_LOCATION_WIDTH - 16, paint)
        var locY = startY + 25
        locationLines.take(2).forEach { line ->
            canvas.drawText(line, currentX, locY, paint)
            locY += 12
        }

        // GPS coordinates for GPS events
        val alertLoc = extractAlertLocation(event.data)
        if (alertLoc != null) {
            paint.textSize = 9f
            paint.color = Color.parseColor(COLOR_PRIMARY)
            val coordText = String.format("📍 %.6f, %.6f", alertLoc.latitude, alertLoc.longitude)
            canvas.drawText(coordText, currentX, startY + 75, paint)
        }

        currentX += COL_LOCATION_WIDTH

        // Vertical separator
        drawVerticalLine(canvas, paint, currentX, startY + 5, ROW_HEIGHT - 10)
        currentX += 8

        // Column 4: Date & Time
        paint.textSize = 9f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        canvas.drawText("DATE & TIME", currentX, startY + 10, paint)

        val eventDate = getEventDate(event)
        paint.textSize = 10f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = Color.BLACK

        val dateStr = "📅 ${dateFormat.format(eventDate)}"
        val timeStr = "🕒 ${timeFormat.format(eventDate)}"

        canvas.drawText(dateStr, currentX, startY + 30, paint)

        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        canvas.drawText(timeStr, currentX, startY + 50, paint)

        currentX += COL_DATETIME_WIDTH

        // Vertical separator
        drawVerticalLine(canvas, paint, currentX, startY + 5, ROW_HEIGHT - 10)
        currentX += 8

        // Column 5: Image/Map
        val imageRect = RectF(
            currentX,
            startY + 8,
            currentX + COL_IMAGE_WIDTH - 16,
            startY + ROW_HEIGHT - 8
        )

        // Image border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawRoundRect(imageRect, 4f, 4f, paint)
        paint.style = Paint.Style.FILL

        // Load and draw image
        val bitmap = when (event.type) {
            "off-route", "tamper", "overspeed" -> {
                captureMapPreview(event) ?: loadEventImage(event)
            }
            else -> loadEventImage(event)
        }

        if (bitmap != null) {
            drawImageInRect(canvas, paint, bitmap, imageRect)
        } else {
            // Placeholder
            paint.color = Color.parseColor("#F5F5F5")
            canvas.drawRoundRect(imageRect, 4f, 4f, paint)

            val placeholderText = if (event.isGpsEvent) "🗺️ Map unavailable" else "📷 Image unavailable"
            paint.textSize = 9f
            paint.color = Color.GRAY
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                placeholderText,
                imageRect.centerX(),
                imageRect.centerY(),
                paint
            )
            paint.textAlign = Paint.Align.LEFT
        }

        // Row border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawRect(MARGIN, startY, PAGE_WIDTH - MARGIN, rowEnd, paint)
        paint.style = Paint.Style.FILL

        return rowEnd
    }

    private fun drawImageInRect(canvas: Canvas, paint: Paint, bitmap: Bitmap, rect: RectF) {
        // Calculate aspect-fit dimensions
        val aspectRatio = bitmap.width.toFloat() / bitmap.height
        val rectAspect = rect.width() / rect.height()

        var drawRect = RectF(rect)
        if (aspectRatio > rectAspect) {
            // Image is wider
            val newHeight = rect.width() / aspectRatio
            drawRect.top += (rect.height() - newHeight) / 2
            drawRect.bottom = drawRect.top + newHeight
        } else {
            // Image is taller
            val newWidth = rect.height() * aspectRatio
            drawRect.left += (rect.width() - newWidth) / 2
            drawRect.right = drawRect.left + newWidth
        }

        // Create high-quality scaled bitmap
        val targetWidth = drawRect.width().toInt()
        val targetHeight = drawRect.height().toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

        canvas.save()

        // Clip to rounded rectangle
        val path = Path()
        path.addRoundRect(drawRect, 4f, 4f, Path.Direction.CW)
        canvas.clipPath(path)

        // Enable high-quality rendering
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.isDither = false

        canvas.drawBitmap(scaledBitmap, drawRect.left, drawRect.top, paint)

        canvas.restore()
    }

    private fun drawEnhancedFooter(
        canvas: Canvas,
        paint: Paint,
        pageNumber: Int,
        totalPages: Int
    ) {
        val footerY = PAGE_HEIGHT - 45

        // Background bar
        paint.color = Color.parseColor("#FAFAFA")
        canvas.drawRect(MARGIN, footerY - 5, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 5, paint)

        // Separator line
        paint.color = Color.parseColor(COLOR_PRIMARY)
        paint.alpha = 128
        paint.strokeWidth = 1.5f
        canvas.drawLine(MARGIN, footerY, PAGE_WIDTH - MARGIN, footerY, paint)
        paint.alpha = 255

        // Page number (left)
        val pageText = "Page $pageNumber of $totalPages"
        paint.textSize = 10f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.color = Color.parseColor(COLOR_TEXT_SECONDARY)
        canvas.drawText(pageText, MARGIN + 10, footerY + 12, paint)

        // Company text (center)
        val companyText = "ICCC Event Manager"
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.color = Color.parseColor(COLOR_PRIMARY)
        val companyWidth = paint.measureText(companyText)
        canvas.drawText(companyText, (PAGE_WIDTH - companyWidth) / 2, footerY + 12, paint)

        // Confidential text (right)
        val confidentialText = "Confidential"
        paint.textSize = 9f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        paint.color = Color.GRAY
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(confidentialText, PAGE_WIDTH - MARGIN - 10, footerY + 12, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawVerticalLine(canvas: Canvas, paint: Paint, x: Float, y: Float, height: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.parseColor(COLOR_BORDER)
        canvas.drawLine(x, y, x, y + height, paint)
        paint.style = Paint.Style.FILL
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

        return lines.take(3)
    }

    private fun extractAlertLocation(data: Map<String, Any>?): GeoPoint? {
        if (data == null) return null
        val alertLoc = data["alertLocation"] as? Map<*, *> ?: return null
        val lat = (alertLoc["lat"] as? Number)?.toDouble() ?: return null
        val lng = (alertLoc["lng"] as? Number)?.toDouble() ?: return null
        return GeoPoint(lat, lng)
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

    private fun loadEventImage(event: Event): Bitmap? {
        return try {
            val area = event.area ?: return null
            val eventId = event.id ?: return null
            val httpUrl = getHttpUrlForArea(area)
            val imageUrl = "$httpUrl/va/event/?id=$eventId"

            Log.d(TAG, "Loading image: $imageUrl")

            val request = Request.Builder()
                .url(imageUrl)
                .addHeader("Accept", "image/*")
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val bytes = response.body?.bytes() ?: return null

                // Decode with high quality settings
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                    inDither = false
                    inPreferQualityOverSpeed = true
                    inMutable = false
                }

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                if (bitmap != null) {
                    Log.d(TAG, "✅ Image loaded successfully: ${bitmap.width}x${bitmap.height}")
                } else {
                    Log.e(TAG, "❌ Failed to decode image")
                }

                bitmap
            } else {
                Log.e(TAG, "Failed to load image: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image: ${e.message}", e)
            null
        }
    }

    private suspend fun captureMapPreview(event: Event): Bitmap? = withContext(Dispatchers.Default) {
        try {
            val alertLoc = extractAlertLocation(event.data) ?: return@withContext null

            // High resolution for better quality
            val mapWidth = 800
            val mapHeight = 500
            val bitmap = Bitmap.createBitmap(mapWidth, mapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
            }

            // Background
            paint.color = Color.parseColor("#F5F7FA")
            canvas.drawRect(0f, 0f, mapWidth.toFloat(), mapHeight.toFloat(), paint)

            // Extract geofence data
            val geofenceData = extractGeofenceData(event.data)

            // Calculate bounds
            val allPoints = mutableListOf(alertLoc)
            geofenceData?.points?.let { allPoints.addAll(it) }

            val minLat = allPoints.minOf { it.latitude }
            val maxLat = allPoints.maxOf { it.latitude }
            val minLng = allPoints.minOf { it.longitude }
            val maxLng = allPoints.maxOf { it.longitude }

            val latRange = maxLat - minLat
            val lngRange = maxLng - minLng

            val padding = 0.5
            val paddedLatRange = latRange * (1 + padding)
            val paddedLngRange = lngRange * (1 + padding)

            val centerLat = (minLat + maxLat) / 2
            val centerLng = (minLng + maxLng) / 2

            val mapPadding = 60f
            val scaleX = (mapWidth - 2 * mapPadding) / paddedLngRange
            val scaleY = (mapHeight - 2 * mapPadding) / paddedLatRange
            val scale = minOf(scaleX, scaleY)

            fun toCanvasX(lng: Double): Float {
                return ((lng - centerLng) * scale + mapWidth / 2).toFloat()
            }

            fun toCanvasY(lat: Double): Float {
                return (mapHeight / 2 - (lat - centerLat) * scale).toFloat()
            }

            // Draw geofence
            geofenceData?.let { geoData ->
                val color = try {
                    Color.parseColor(if (geoData.color.startsWith("#")) geoData.color else "#${geoData.color}")
                } catch (e: Exception) {
                    Color.parseColor("#3388ff")
                }

                when (geoData.type) {
                    "LineString" -> {
                        paint.color = color
                        paint.strokeWidth = 5f
                        paint.style = Paint.Style.STROKE
                        paint.strokeCap = Paint.Cap.ROUND

                        val path = Path()
                        geoData.points.forEachIndexed { index, point ->
                            val x = toCanvasX(point.longitude)
                            val y = toCanvasY(point.latitude)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        canvas.drawPath(path, paint)
                    }
                    "Polygon" -> {
                        // Fill
                        paint.color = Color.argb(30, Color.red(color), Color.green(color), Color.blue(color))
                        paint.style = Paint.Style.FILL

                        val path = Path()
                        geoData.points.forEachIndexed { index, point ->
                            val x = toCanvasX(point.longitude)
                            val y = toCanvasY(point.latitude)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.close()
                        canvas.drawPath(path, paint)

                        // Stroke
                        paint.color = color
                        paint.strokeWidth = 4f
                        paint.style = Paint.Style.STROKE
                        canvas.drawPath(path, paint)
                    }
                    "Point" -> {
                        val point = geoData.points.first()
                        val x = toCanvasX(point.longitude)
                        val y = toCanvasY(point.latitude)

                        paint.color = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(x, y, 40f, paint)

                        paint.color = color
                        paint.strokeWidth = 3f
                        paint.style = Paint.Style.STROKE
                        canvas.drawCircle(x, y, 40f, paint)
                    }
                }
            }

            // Draw alert pin (on top)
            val alertX = toCanvasX(alertLoc.longitude)
            val alertY = toCanvasY(alertLoc.latitude)

            paint.style = Paint.Style.FILL

            // Pin shadow
            paint.color = Color.argb(100, 0, 0, 0)
            canvas.drawCircle(alertX + 3, alertY + 3, 16f, paint)

            // Red pin
            paint.color = Color.RED
            canvas.drawCircle(alertX, alertY, 16f, paint)

            // White center
            paint.color = Color.WHITE
            canvas.drawCircle(alertX, alertY, 8f, paint)

            // Coordinates label
            val coordText = String.format("%.6f, %.6f", alertLoc.latitude, alertLoc.longitude)
            paint.textSize = 12f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.color = Color.WHITE
            paint.setShadowLayer(3f, 0f, 0f, Color.BLACK)

            val textWidth = paint.measureText(coordText)
            val textBgRect = RectF(
                alertX - textWidth / 2 - 8,
                alertY + 20,
                alertX + textWidth / 2 + 8,
                alertY + 40
            )

            paint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            paint.color = Color.argb(153, 0, 0, 0)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(textBgRect, 4f, 4f, paint)

            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(coordText, alertX, alertY + 35, paint)
            paint.textAlign = Paint.Align.LEFT
            paint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing map preview: ${e.message}", e)
            null
        }
    }

    private data class GeofenceData(
        val points: List<GeoPoint>,
        val type: String,
        val color: String
    )

    private fun extractGeofenceData(data: Map<String, Any>?): GeofenceData? {
        if (data == null) return null

        val geofenceMap = data["geofence"] as? Map<*, *> ?: return null
        val geojsonMap = geofenceMap["geojson"] as? Map<*, *> ?: return null
        val type = geojsonMap["type"] as? String ?: return null
        val coordinates = geojsonMap["coordinates"] ?: return null

        val attributesMap = geofenceMap["attributes"] as? Map<*, *>
        val color = attributesMap?.get("color") as? String
        val polylineColor = attributesMap?.get("polylineColor") as? String
        val finalColor = polylineColor ?: color ?: "#3388ff"

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

        return GeofenceData(points, type, finalColor)
    }

    private fun getHttpUrlForArea(area: String): String {
        val normalizedArea = area.lowercase().replace(" ", "").replace("_", "")

        if (BackendConfig.isCCL()) {
            return when (normalizedArea) {
                "barkasayal" -> "https://barkasayal.cclai.in/api"
                "argada" -> "https://argada.cclai.in/api"
                "northkaranpura" -> "https://nk.cclai.in/api"
                "bokarokargali" -> "https://bk.cclai.in/api"
                "kathara" -> "https://kathara.cclai.in/api"
                "giridih" -> "https://giridih.cclai.in/api"
                "amrapali" -> "https://amrapali.cclai.in/api"
                "magadh" -> "https://magadh.cclai.in/api"
                "rajhara" -> "https://rajhara.cclai.in/api"
                "kuju" -> "https://kuju.cclai.in/api"
                "hazaribagh" -> "https://hazaribagh.cclai.in/api"
                "rajrappa" -> "https://rajrappa.cclai.in/api"
                "dhori" -> "https://dhori.cclai.in/api"
                "piparwar" -> "https://piparwar.cclai.in/api"
                else -> {
                    Log.w(TAG, "Unknown CCL area: $area, using default")
                    "https://barkasayal.cclai.in/api"
                }
            }
        }

        return when (normalizedArea) {
            "sijua", "katras" -> "http://a5va.bccliccc.in:10050"
            "kusunda" -> "http://a6va.bccliccc.in:5050"
            "bastacolla" -> "http://a9va.bccliccc.in:5050"
            "lodna" -> "http://a10va.bccliccc.in:5050"
            "govindpur" -> "http://103.208.173.163:5050"
            "barora" -> "http://103.208.173.131:5050"
            "block2" -> "http://103.208.173.147:5050"
            "pbarea" -> "http://103.208.173.195:5050"
            "wjarea" -> "http://103.208.173.211:5050"
            "ccwo" -> "http://103.208.173.179:5050"
            "cvarea" -> "http://103.210.88.211:5050"
            "ej" -> "http://103.210.88.194:5050"
            else -> {
                Log.w(TAG, "Unknown BCCL area: $area, using default")
                "http://a5va.bccliccc.in:10050"
            }
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

                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to Downloads: ${e.message}", e)
            null
        }
    }

    // Helper property to check if event is GPS type
    private val Event.isGpsEvent: Boolean
        get() = type in listOf("off-route", "tamper", "overspeed")
}