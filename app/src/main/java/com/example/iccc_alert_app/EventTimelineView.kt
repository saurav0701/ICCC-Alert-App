package com.example.iccc_alert_app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import android.util.Log

class EventTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var events: List<Event> = emptyList()
    private var timelineMode = TimelineMode.DAY
    private var selectedDay: Long? = null // For HOUR mode
    private val eventsByPeriod = mutableMapOf<String, MutableList<Event>>()
    private val densityByPeriod = mutableMapOf<String, Int>()

    private val dayFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
    private val hourFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val weekFormat = SimpleDateFormat("'Week' w", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())

    private val periodPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val densityPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var periodWidth = 0f
    private var periodHeight = 0f
    private var timelineHeight = 0f
    private var densityBarHeight = 0f

    private var scrollX = 0f
    private var targetScrollX = 0f
    private var scale = 1f
    private var targetScale = 1f
    private var minScale = 0.5f
    private var maxScale = 3f

    private var selectedPeriod: String? = null
    private var hoveredPeriod: String? = null

    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector
    private var isScrolling = false
    private var isFling = false

    private val primaryColor = ContextCompat.getColor(context, R.color.colorPrimary)
    private val accentColor = ContextCompat.getColor(context, R.color.colorAccent)
    private val textColorPrimary = Color.parseColor("#1A1A1A")
    private val textColorSecondary = Color.parseColor("#757575")
    private val backgroundColor = Color.parseColor("#F8F9FA")
    private val selectedBackgroundColor = Color.parseColor("#E3F2FD")

    private val densityColors = arrayOf(
        Color.parseColor("#E8F5E9"),
        Color.parseColor("#A5D6A7"),
        Color.parseColor("#66BB6A"),
        Color.parseColor("#43A047"),
        Color.parseColor("#2E7D32")
    )

    private var animationProgress = 0f
    private val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 300
        addUpdateListener {
            animationProgress = it.animatedValue as Float
            scrollX = scrollX + (targetScrollX - scrollX) * animationProgress
            scale = scale + (targetScale - scale) * animationProgress
            invalidate()
        }
    }

    var onPeriodSelected: ((String, List<Event>) -> Unit)? = null
    var onTimelineModeChanged: ((TimelineMode) -> Unit)? = null
    var onDaySelected: ((Long?) -> Unit)? = null

    enum class TimelineMode {
        HOUR, DAY, WEEK, MONTH
    }

    init {
        periodPaint.style = Paint.Style.FILL
        periodPaint.color = Color.WHITE

        textPaint.textSize = dpToPx(12f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = textColorPrimary

        densityPaint.style = Paint.Style.FILL
        selectedPaint.style = Paint.Style.FILL
        selectedPaint.color = selectedBackgroundColor

        dividerPaint.style = Paint.Style.STROKE
        dividerPaint.strokeWidth = dpToPx(1f)
        dividerPaint.color = Color.parseColor("#E0E0E0")

        periodWidth = dpToPx(80f)
        periodHeight = dpToPx(60f)
        timelineHeight = dpToPx(80f)
        densityBarHeight = dpToPx(6f)

        gestureDetector = GestureDetector(context, TimelineGestureListener())
        scaleDetector = ScaleGestureDetector(context, TimelineScaleListener())

        setBackgroundColor(backgroundColor)
    }

    fun setEvents(newEvents: List<Event>) {
        events = newEvents
        groupEventsByPeriod()
        calculateDensity()
        post { scrollToNow(animated = false) }
        invalidate()
    }

    fun setTimelineMode(mode: TimelineMode) {
        if (timelineMode != mode) {
            timelineMode = mode
            if (mode == TimelineMode.HOUR) {
                selectedDay = null
                onDaySelected?.invoke(null)
            } else {
                selectedDay = null
            }
            groupEventsByPeriod()
            calculateDensity()
            scrollToNow(animated = true)
            onTimelineModeChanged?.invoke(mode)
            invalidate()
        }
    }

    private fun groupEventsByPeriod() {
        eventsByPeriod.clear()
        val calendar = Calendar.getInstance()
        val now = System.currentTimeMillis()
        val eventTimeParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        events.forEach { event ->
            val eventTimeMs = try {
                val eventTimeStr = event.data["eventTime"] as? String
                if (eventTimeStr != null) {
                    eventTimeParser.parse(eventTimeStr)?.time ?: (event.timestamp * 1000)
                } else {
                    event.timestamp * 1000
                }
            } catch (e: Exception) {
                event.timestamp * 1000
            }

            val eventTime = Date(eventTimeMs)
            val fiveMinutesInFuture = now + (5 * 60 * 1000)
            if (eventTimeMs > fiveMinutesInFuture) {
                Log.w("EventTimelineView", "Skipping far-future event: ${event.id}")
                return@forEach
            }

            calendar.time = eventTime

            val periodKey = when (timelineMode) {
                TimelineMode.HOUR -> {
                    if (selectedDay == null) {
                        calendar.set(Calendar.HOUR_OF_DAY, 0)
                        calendar.set(Calendar.MINUTE, 0)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        calendar.time.time.toString()
                    } else {
                        val dayStart = Calendar.getInstance()
                        dayStart.timeInMillis = selectedDay!!
                        val eventDay = Calendar.getInstance()
                        eventDay.time = eventTime
                        eventDay.set(Calendar.HOUR_OF_DAY, 0)
                        eventDay.set(Calendar.MINUTE, 0)
                        eventDay.set(Calendar.SECOND, 0)
                        eventDay.set(Calendar.MILLISECOND, 0)
                        if (dayStart.timeInMillis == eventDay.timeInMillis) {
                            calendar.set(Calendar.MINUTE, 0)
                            calendar.set(Calendar.SECOND, 0)
                            calendar.set(Calendar.MILLISECOND, 0)
                            calendar.time.time.toString()
                        } else {
                            return@forEach
                        }
                    }
                }
                TimelineMode.DAY -> {
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    calendar.time.time.toString()
                }
                TimelineMode.WEEK -> {
                    calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    calendar.time.time.toString()
                }
                TimelineMode.MONTH -> {
                    calendar.set(Calendar.DAY_OF_MONTH, 1)
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    calendar.time.time.toString()
                }
            }

            eventsByPeriod.getOrPut(periodKey) { mutableListOf() }.add(event)
        }

        Log.d("EventTimelineView", "Grouped ${events.size} events into ${eventsByPeriod.size} periods")
    }

    private fun calculateDensity() {
        densityByPeriod.clear()
        eventsByPeriod.forEach { (period, events) ->
            densityByPeriod[period] = events.size
        }
    }

    private fun getDensityColor(count: Int): Int {
        if (count == 0) return densityColors[0]
        val maxDensity = densityByPeriod.values.maxOrNull() ?: 1
        val ratio = count.toFloat() / maxDensity.toFloat()
        return when {
            ratio >= 0.8f -> densityColors[4]
            ratio >= 0.6f -> densityColors[3]
            ratio >= 0.4f -> densityColors[2]
            ratio >= 0.2f -> densityColors[1]
            else -> densityColors[0]
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (eventsByPeriod.isEmpty()) {
            drawEmptyState(canvas)
            return
        }

        val sortedPeriods = eventsByPeriod.keys.sortedBy { it.toLong() }
        val startX = -scrollX * scale

        sortedPeriods.forEachIndexed { index, period ->
            val x = startX + index * periodWidth * scale
            if (x + periodWidth * scale < 0 || x > width) return@forEachIndexed
            val isSelected = period == selectedPeriod
            val isHovered = period == hoveredPeriod
            drawPeriod(canvas, period, x, isSelected, isHovered)
        }

        drawCurrentTimeIndicator(canvas, sortedPeriods, startX)
    }

    private fun drawPeriod(canvas: Canvas, period: String, x: Float, isSelected: Boolean, isHovered: Boolean) {
        val periodEvents = eventsByPeriod[period] ?: return
        val density = densityByPeriod[period] ?: 0

        if (isSelected) {
            canvas.drawRect(x, 0f, x + periodWidth * scale, timelineHeight, selectedPaint)
        }

        densityPaint.color = getDensityColor(density)
        canvas.drawRect(x + dpToPx(4f), dpToPx(4f), x + periodWidth * scale - dpToPx(4f), dpToPx(4f) + densityBarHeight, densityPaint)

        val periodDate = Date(period.toLong())
        val label = when (timelineMode) {
            TimelineMode.HOUR -> if (selectedDay == null) dayFormat.format(periodDate) else hourFormat.format(periodDate)
            TimelineMode.DAY -> dayFormat.format(periodDate)
            TimelineMode.WEEK -> weekFormat.format(periodDate)
            TimelineMode.MONTH -> monthFormat.format(periodDate)
        }

        textPaint.textSize = dpToPx(12f) * scale
        textPaint.color = if (isSelected) primaryColor else textColorPrimary
        textPaint.isFakeBoldText = isSelected
        canvas.drawText(label, x + periodWidth * scale / 2, dpToPx(28f), textPaint)

        textPaint.textSize = dpToPx(16f) * scale
        textPaint.color = if (isSelected) accentColor else textColorSecondary
        textPaint.isFakeBoldText = true
        canvas.drawText(density.toString(), x + periodWidth * scale / 2, dpToPx(52f), textPaint)
        textPaint.isFakeBoldText = false

        canvas.drawLine(x + periodWidth * scale, dpToPx(12f), x + periodWidth * scale, timelineHeight - dpToPx(12f), dividerPaint)

        if (isHovered && !isSelected) {
            val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            hoverPaint.color = Color.parseColor("#40000000")
            hoverPaint.style = Paint.Style.FILL
            canvas.drawRect(x, 0f, x + periodWidth * scale, timelineHeight, hoverPaint)
        }

        if (isSelected) {
            drawPeriodInfoOverlay(canvas, period, x)
        }

        if (timelineMode == TimelineMode.HOUR && selectedDay == null && isHovered) {
            drawTapHint(canvas, x)
        }
    }

    private fun drawPeriodInfoOverlay(canvas: Canvas, period: String, x: Float) {
        val periodEvents = eventsByPeriod[period] ?: return
        val count = periodEvents.size

        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A1A")
            style = Paint.Style.FILL
        }

        val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dpToPx(11f)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        val bubbleText = "$count ${if (count == 1) "event" else "events"}"
        val bubbleWidth = dpToPx(60f)
        val bubbleHeight = dpToPx(24f)
        val bubbleX = x + periodWidth * scale / 2
        val bubbleY = dpToPx(50f)

        val rectF = RectF(bubbleX - bubbleWidth / 2, bubbleY - bubbleHeight / 2, bubbleX + bubbleWidth / 2, bubbleY + bubbleHeight / 2)
        canvas.drawRoundRect(rectF, dpToPx(12f), dpToPx(12f), bubblePaint)
        canvas.drawText(bubbleText, bubbleX, bubbleY + dpToPx(4f), infoPaint)
    }

    private fun drawTapHint(canvas: Canvas, x: Float) {
        val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80000000")
            textSize = dpToPx(10f)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Tap to view hours", x + periodWidth * scale / 2, dpToPx(70f), hintPaint)
    }

    private fun drawCurrentTimeIndicator(canvas: Canvas, sortedPeriods: List<String>, startX: Float) {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now

        when (timelineMode) {
            TimelineMode.HOUR -> {
                if (selectedDay == null) {
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                } else {
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                }
            }
            TimelineMode.DAY -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
            }
            TimelineMode.WEEK -> {
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
            }
            TimelineMode.MONTH -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
            }
        }

        val currentPeriod = calendar.time.time.toString()
        val index = sortedPeriods.indexOf(currentPeriod)

        if (index >= 0) {
            val x = startX + index * periodWidth * scale

            val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            indicatorPaint.color = accentColor
            indicatorPaint.strokeWidth = dpToPx(2f)
            indicatorPaint.style = Paint.Style.STROKE

            canvas.drawLine(x, dpToPx(10f), x, timelineHeight - dpToPx(10f), indicatorPaint)

            textPaint.textSize = dpToPx(10f)
            textPaint.color = accentColor
            textPaint.isFakeBoldText = true
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("NOW", x + dpToPx(4f), dpToPx(20f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.isFakeBoldText = false
        }
    }

    private fun drawEmptyState(canvas: Canvas) {
        textPaint.textSize = dpToPx(14f)
        textPaint.color = textColorSecondary
        textPaint.textAlign = Paint.Align.CENTER

        val message = if (timelineMode == TimelineMode.HOUR && selectedDay == null) {
            "Tap a day to view hourly events"
        } else {
            "No events to display"
        }

        canvas.drawText(message, width / 2f, height / 2f, textPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = timelineHeight.toInt()
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> min(desiredHeight, heightSize)
            else -> desiredHeight
        }

        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled

        when (event.action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isFling) {
                    snapToNearestPeriod()
                }
                isScrolling = false
                hoveredPeriod = null
                invalidate()
            }
        }

        return handled || super.onTouchEvent(event)
    }

    private inner class TimelineGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            isFling = false
            animator.cancel()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val period = getPeriodAtX(e.x)
            if (period != null) {
                selectPeriod(period)
                performHapticFeedback()
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            targetScale = if (scale > 1.5f) 1f else 2f
            animator.start()
            performHapticFeedback()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (abs(distanceX) > abs(distanceY)) {
                isScrolling = true
                scrollX += distanceX / scale
                constrainScroll()
                hoveredPeriod = getPeriodAtX(e2.x)
                invalidate()
                return true
            }
            return false
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (abs(velocityX) > abs(velocityY)) {
                isFling = true
                val flingDistance = -velocityX / 10f
                targetScrollX = scrollX + flingDistance
                constrainTargetScroll()
                animator.start()
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            val period = getPeriodAtX(e.x)
            if (period != null) {
                performHapticFeedback()
                hoveredPeriod = period
                invalidate()
            }
        }
    }

    private inner class TimelineScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            scale *= scaleFactor
            scale = max(minScale, min(scale, maxScale))

            val focusX = detector.focusX
            scrollX = (scrollX + focusX / width) * scaleFactor - focusX / width

            constrainScroll()
            invalidate()
            return true
        }
    }

    private fun getPeriodAtX(x: Float): String? {
        val sortedPeriods = eventsByPeriod.keys.sortedBy { it.toLong() }
        val startX = -scrollX * scale

        sortedPeriods.forEachIndexed { index, period ->
            val periodX = startX + index * periodWidth * scale
            if (x >= periodX && x <= periodX + periodWidth * scale) {
                return period
            }
        }

        return null
    }

    private fun selectPeriod(period: String) {
        if (timelineMode == TimelineMode.HOUR && selectedDay == null) {
            selectedDay = period.toLong()
            selectedPeriod = null
            groupEventsByPeriod()
            calculateDensity()
            performHapticFeedback()
            onDaySelected?.invoke(selectedDay)
            val date = Date(selectedDay!!)
            android.widget.Toast.makeText(context, "Showing hours for ${dayFormat.format(date)}", android.widget.Toast.LENGTH_SHORT).show()
            invalidate()
            return
        }

        val wasSelected = (selectedPeriod == period)

        if (wasSelected) {
            if (timelineMode == TimelineMode.HOUR && selectedDay != null) {
                selectedDay = null
                selectedPeriod = null
                groupEventsByPeriod()
                calculateDensity()
                performHapticFeedback()
                onDaySelected?.invoke(null)
                android.widget.Toast.makeText(context, "Select a day to view hours", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                selectedPeriod = null
                performHapticFeedback()
                android.widget.Toast.makeText(context, "Showing all events", android.widget.Toast.LENGTH_SHORT).show()
            }
            invalidate()
        } else {
            selectedPeriod = period
            val events = eventsByPeriod[period] ?: emptyList()
            performHapticFeedback()
            onPeriodSelected?.invoke(period, events)
            invalidate()
        }
    }

    private fun scrollToNow(animated: Boolean) {
        val sortedPeriods = eventsByPeriod.keys.sortedBy { it.toLong() }
        if (sortedPeriods.isEmpty()) return

        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now

        when (timelineMode) {
            TimelineMode.HOUR -> {
                if (selectedDay == null) {
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                } else {
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                }
            }
            TimelineMode.DAY -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
            }
            TimelineMode.WEEK -> {
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
            }
            TimelineMode.MONTH -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
            }
        }

        val currentPeriod = calendar.time.time.toString()
        val index = sortedPeriods.indexOf(currentPeriod)

        if (index >= 0) {
            targetScrollX = index * periodWidth - width / 2f + periodWidth / 2f

            if (animated) {
                animator.start()
            } else {
                scrollX = targetScrollX
                invalidate()
            }
        }
    }

    private fun snapToNearestPeriod() {
        val sortedPeriods = eventsByPeriod.keys.sortedBy { it.toLong() }
        if (sortedPeriods.isEmpty()) return

        val centerX = scrollX + width / (2f * scale)
        val nearestIndex = (centerX / periodWidth).toInt()

        targetScrollX = nearestIndex * periodWidth - width / 2f + periodWidth / 2f
        constrainTargetScroll()
        animator.start()
    }

    private fun constrainScroll() {
        val sortedPeriods = eventsByPeriod.keys.sortedBy { it.toLong() }
        if (sortedPeriods.isEmpty()) {
            scrollX = 0f
            return
        }

        val maxScroll = sortedPeriods.size * periodWidth - width / scale
        scrollX = max(0f, min(scrollX, maxScroll))
    }

    private fun constrainTargetScroll() {
        val sortedPeriods = eventsByPeriod.keys.sortedBy { it.toLong() }
        if (sortedPeriods.isEmpty()) {
            targetScrollX = 0f
            return
        }

        val maxScroll = sortedPeriods.size * periodWidth - width / scale
        targetScrollX = max(0f, min(targetScrollX, maxScroll))
    }

    private fun performHapticFeedback() {
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }

    fun clearSelection() {
        selectedPeriod = null
        if (timelineMode == TimelineMode.HOUR && selectedDay != null) {
            selectedDay = null
            onDaySelected?.invoke(null)
            groupEventsByPeriod()
            calculateDensity()
        }
        invalidate()
    }

    fun resetView() {
        selectedPeriod = null
        selectedDay = null
        if (timelineMode == TimelineMode.HOUR) {
            groupEventsByPeriod()
            calculateDensity()
        }
        scrollToNow(animated = true)
    }

    fun getSelectedDay(): Long? = selectedDay
    fun getCurrentMode() = timelineMode
}