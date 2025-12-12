package com.example.iccc_alert_app

import android.animation.ValueAnimator
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * ✅ Custom Shimmer Effect for Loading States
 *
 * Usage:
 * val shimmer = ShimmerEffect(view)
 * shimmer.start()
 * // When done loading:
 * shimmer.stop()
 */
class ShimmerEffect(private val view: View) {

    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shimmerAlpha = 0f

    private val gradient: LinearGradient
        get() = LinearGradient(
            0f,
            0f,
            view.width.toFloat(),
            0f,
            intArrayOf(
                Color.parseColor("#33E0E0E0"),
                Color.parseColor("#66FFFFFF"),
                Color.parseColor("#33E0E0E0")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

    fun start() {
        animator?.cancel()

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()

            addUpdateListener { animation ->
                shimmerAlpha = animation.animatedValue as Float
                view.invalidate()
            }

            start()
        }

        // Set custom draw overlay
        view.overlay.add(object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: Canvas) {
                paint.shader = gradient
                paint.alpha = (shimmerAlpha * 255).toInt()

                val translateX = -view.width + (view.width * 2 * shimmerAlpha)
                canvas.save()
                canvas.translate(translateX, 0f)
                canvas.drawRect(0f, 0f, view.width.toFloat(), view.height.toFloat(), paint)
                canvas.restore()
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        })
    }

    fun stop() {
        animator?.cancel()
        animator = null
        view.overlay.clear()
        view.invalidate()
    }
}

/**
 * Extension function for easy usage
 */
fun View.startShimmer(): ShimmerEffect {
    return ShimmerEffect(this).apply { start() }
}

fun View.stopShimmer() {
    // Remove shimmer overlay
    overlay.clear()
    invalidate()
}