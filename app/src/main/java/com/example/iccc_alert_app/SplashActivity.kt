package com.example.iccc_alert_app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.iccc_alert_app.auth.AuthManager

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // True edge-to-edge — no status bar tinting
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        setContentView(R.layout.activity_splash)

        val logoGroup = findViewById<LinearLayout>(R.id.splash_logo_group)
        val bottom    = findViewById<LinearLayout>(R.id.splash_bottom)

        // ── Phase 1 (0 ms): logo group scales up from 0.7 + fades in ──
        logoGroup.scaleX = 0.72f
        logoGroup.scaleY = 0.72f
        logoGroup.alpha  = 0f

        val logoScale = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logoGroup, View.SCALE_X, 0.72f, 1f),
                ObjectAnimator.ofFloat(logoGroup, View.SCALE_Y, 0.72f, 1f),
                ObjectAnimator.ofFloat(logoGroup, View.ALPHA,   0f,    1f)
            )
            duration = 650
            interpolator = OvershootInterpolator(1.15f)
            startDelay = 120
        }

        // ── Phase 2 (600 ms): bottom spinner slides up + fades in ──
        bottom.translationY = 40f
        bottom.alpha = 0f

        val bottomIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(bottom, View.TRANSLATION_Y, 40f, 0f),
                ObjectAnimator.ofFloat(bottom, View.ALPHA, 0f, 1f)
            )
            duration = 450
            interpolator = DecelerateInterpolator(2f)
            startDelay = 700
        }

        // ── Phase 3 (2100 ms): whole screen fades out then navigate ──
        val root = findViewById<View>(R.id.splash_root)
        val fadeOut = ObjectAnimator.ofFloat(root, View.ALPHA, 1f, 0f).apply {
            duration = 380
            interpolator = DecelerateInterpolator()
            startDelay = 2100
        }
        fadeOut.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                navigateNext()
            }
        })

        logoScale.start()
        bottomIn.start()
        fadeOut.start()
    }

    private fun navigateNext() {
        val dest = if (AuthManager.isLoggedIn()) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, LoginActivity::class.java)
        }
        dest.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(dest)
        // No overridePendingTransition — the fade-out above IS the transition
        finish()
    }
}
