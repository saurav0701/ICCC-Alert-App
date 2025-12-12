package com.example.iccc_alert_app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.*
import android.widget.ImageView
import android.widget.TextView

/**
 * ✅ Empty State Animator - Beautiful entry animations
 */
class EmptyStateAnimator(private val emptyView: View) {

    private val pulseRingOuter: View? = emptyView.findViewById(R.id.pulse_ring_outer)
    private val pulseRingInner: View? = emptyView.findViewById(R.id.pulse_ring_inner)
    private val emptyIcon: ImageView? = emptyView.findViewById(R.id.empty_icon)
    private val emptyTitle: TextView? = emptyView.findViewById(R.id.empty_title)
    private val emptyDescription: TextView? = emptyView.findViewById(R.id.empty_description)
    private val emptyActionButton: View? = emptyView.findViewById(R.id.empty_action_button)
    private val emptyHint: View? = emptyView.findViewById(R.id.empty_hint)

    private var isAnimating = false
    private val animators = mutableListOf<ValueAnimator>()

    fun animate() {
        if (isAnimating) return
        isAnimating = true

        // Clear any previous animations
        stopAll()

        // Reset alphas
        listOf(emptyTitle, emptyDescription, emptyActionButton, emptyHint).forEach {
            it?.alpha = 0f
        }

        // Start animations in sequence
        animatePulseRings()
        animateIcon()
        animateText()
        animateButton()
    }

    private fun animatePulseRings() {
        // Outer ring
        pulseRingOuter?.let { ring ->
            val scaleAnimator = ObjectAnimator.ofFloat(ring, "scaleX", 1f, 1.3f).apply {
                duration = 1500
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
            }

            val scaleYAnimator = ObjectAnimator.ofFloat(ring, "scaleY", 1f, 1.3f).apply {
                duration = 1500
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
            }

            val alphaAnimator = ObjectAnimator.ofFloat(ring, "alpha", 0.5f, 0f).apply {
                duration = 1500
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
            }

            AnimatorSet().apply {
                playTogether(scaleAnimator, scaleYAnimator, alphaAnimator)
                start()
            }

            animators.addAll(listOf(scaleAnimator, scaleYAnimator, alphaAnimator))
        }

        // Inner ring (offset)
        pulseRingInner?.let { ring ->
            val scaleAnimator = ObjectAnimator.ofFloat(ring, "scaleX", 1f, 1.2f).apply {
                duration = 1500
                startDelay = 500
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
            }

            val scaleYAnimator = ObjectAnimator.ofFloat(ring, "scaleY", 1f, 1.2f).apply {
                duration = 1500
                startDelay = 500
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
            }

            val alphaAnimator = ObjectAnimator.ofFloat(ring, "alpha", 0.5f, 0f).apply {
                duration = 1500
                startDelay = 500
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
            }

            AnimatorSet().apply {
                playTogether(scaleAnimator, scaleYAnimator, alphaAnimator)
                start()
            }

            animators.addAll(listOf(scaleAnimator, scaleYAnimator, alphaAnimator))
        }
    }

    private fun animateIcon() {
        emptyIcon?.let { icon ->
            // Bounce in animation
            icon.scaleX = 0f
            icon.scaleY = 0f

            val scaleX = ObjectAnimator.ofFloat(icon, "scaleX", 0f, 1.1f, 0.9f, 1f).apply {
                duration = 600
                interpolator = OvershootInterpolator()
            }

            val scaleY = ObjectAnimator.ofFloat(icon, "scaleY", 0f, 1.1f, 0.9f, 1f).apply {
                duration = 600
                interpolator = OvershootInterpolator()
            }

            AnimatorSet().apply {
                playTogether(scaleX, scaleY)
                startDelay = 200
                start()
            }

            animators.addAll(listOf(scaleX, scaleY))

            // Add gentle breathing animation
            val breatheAnimator = ObjectAnimator.ofFloat(icon, "scaleX", 1f, 1.05f, 1f).apply {
                duration = 2000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
                startDelay = 800
            }

            val breatheYAnimator = ObjectAnimator.ofFloat(icon, "scaleY", 1f, 1.05f, 1f).apply {
                duration = 2000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
                startDelay = 800
            }

            breatheAnimator.start()
            breatheYAnimator.start()
            animators.addAll(listOf(breatheAnimator, breatheYAnimator))
        }
    }

    private fun animateText() {
        // Title - slide up and fade in
        emptyTitle?.let { title ->
            title.translationY = 30f
            ObjectAnimator.ofFloat(title, "translationY", 30f, 0f).apply {
                duration = 500
                startDelay = 400
                interpolator = DecelerateInterpolator()
                start()
            }.also { animators.add(it) }

            ObjectAnimator.ofFloat(title, "alpha", 0f, 1f).apply {
                duration = 500
                startDelay = 400
                start()
            }.also { animators.add(it) }
        }

        // Description - slide up and fade in
        emptyDescription?.let { desc ->
            desc.translationY = 30f
            ObjectAnimator.ofFloat(desc, "translationY", 30f, 0f).apply {
                duration = 500
                startDelay = 500
                interpolator = DecelerateInterpolator()
                start()
            }.also { animators.add(it) }

            ObjectAnimator.ofFloat(desc, "alpha", 0f, 1f).apply {
                duration = 500
                startDelay = 500
                start()
            }.also { animators.add(it) }
        }

        // Hint - fade in slowly
        emptyHint?.let { hint ->
            ObjectAnimator.ofFloat(hint, "alpha", 0f, 0.6f).apply {
                duration = 800
                startDelay = 1200
                start()
            }.also { animators.add(it) }
        }
    }

    private fun animateButton() {
        emptyActionButton?.let { button ->
            button.translationY = 20f
            button.scaleX = 0.9f
            button.scaleY = 0.9f

            val translateAnimator = ObjectAnimator.ofFloat(button, "translationY", 20f, 0f).apply {
                duration = 500
                startDelay = 600
                interpolator = OvershootInterpolator()
            }

            val scaleXAnimator = ObjectAnimator.ofFloat(button, "scaleX", 0.9f, 1f).apply {
                duration = 500
                startDelay = 600
                interpolator = OvershootInterpolator()
            }

            val scaleYAnimator = ObjectAnimator.ofFloat(button, "scaleY", 0.9f, 1f).apply {
                duration = 500
                startDelay = 600
                interpolator = OvershootInterpolator()
            }

            val alphaAnimator = ObjectAnimator.ofFloat(button, "alpha", 0f, 1f).apply {
                duration = 500
                startDelay = 600
            }

            AnimatorSet().apply {
                playTogether(translateAnimator, scaleXAnimator, scaleYAnimator, alphaAnimator)
                start()
            }

            animators.addAll(listOf(translateAnimator, scaleXAnimator, scaleYAnimator, alphaAnimator))
        }
    }

    fun stopAll() {
        animators.forEach { it.cancel() }
        animators.clear()
        isAnimating = false
    }
}