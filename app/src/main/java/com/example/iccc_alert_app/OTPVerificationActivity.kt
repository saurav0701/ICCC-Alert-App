package com.example.iccc_alert_app

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.iccc_alert_app.auth.AuthManager

class OTPVerificationActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var phoneText: TextView
    private lateinit var verifyButton: Button
    private lateinit var resendButton: TextView
    private lateinit var timerText: TextView
    private lateinit var progressBar: ProgressBar

    // Six individual digit boxes
    private lateinit var boxes: List<EditText>

    // ── State ─────────────────────────────────────────────────────────────────
    private var phone: String = ""
    private var purpose: String = ""
    private var countDownTimer: CountDownTimer? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp_verification)

        supportActionBar?.title = "Verify OTP"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        phone   = intent.getStringExtra("phone")   ?: ""
        purpose = intent.getStringExtra("purpose") ?: "login"

        if (phone.isEmpty()) {
            Toast.makeText(this, "Invalid phone number", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindViews()
        setupOtpBoxes()
        setupButtons()
        startResendTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    // ── View binding ──────────────────────────────────────────────────────────
    private fun bindViews() {
        phoneText    = findViewById(R.id.phone_text)
        verifyButton = findViewById(R.id.verify_button)
        resendButton = findViewById(R.id.resend_button)
        timerText    = findViewById(R.id.timer_text)
        progressBar  = findViewById(R.id.progress_bar)

        boxes = listOf(
            findViewById(R.id.otp_box_1),
            findViewById(R.id.otp_box_2),
            findViewById(R.id.otp_box_3),
            findViewById(R.id.otp_box_4),
            findViewById(R.id.otp_box_5),
            findViewById(R.id.otp_box_6)
        )

        phoneText.text = "OTP sent to +91 $phone via WhatsApp"
        boxes.first().requestFocus()
    }

    // ── OTP box wiring ────────────────────────────────────────────────────────
    private fun setupOtpBoxes() {
        boxes.forEachIndexed { index, box ->

            // Auto-advance on digit entry / auto-retreat on backspace
            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1) {
                        // Move to next box
                        val next = boxes.getOrNull(index + 1)
                        if (next != null) next.requestFocus()
                        else box.clearFocus()   // last box — dismiss or let verify button take over
                    }
                    checkAllFilled()
                }
            })

            // Backspace on empty box → move to previous
            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL
                    && event.action == KeyEvent.ACTION_DOWN
                    && box.text.isNullOrEmpty()
                ) {
                    val prev = boxes.getOrNull(index - 1)
                    if (prev != null) {
                        prev.requestFocus()
                        prev.text.clear()
                    }
                    return@setOnKeyListener true
                }
                false
            }
        }
    }

    private fun checkAllFilled() {
        val allFilled = boxes.all { it.text.length == 1 }
        verifyButton.isEnabled = allFilled
        // Auto-submit when the last digit is entered
        if (allFilled) verifyOTP(collectOtp())
    }

    private fun collectOtp(): String = boxes.joinToString("") { it.text.toString() }

    private fun clearBoxes() {
        boxes.forEach { it.text.clear() }
        boxes.first().requestFocus()
    }

    // ── Buttons ───────────────────────────────────────────────────────────────
    private fun setupButtons() {
        verifyButton.setOnClickListener {
            val otp = collectOtp()
            if (otp.length == 6) verifyOTP(otp)
        }
        resendButton.setOnClickListener { resendOTP() }
    }

    // ── OTP verification ──────────────────────────────────────────────────────
    private fun verifyOTP(otp: String) {
        setLoading(true)

        val callback = { success: Boolean, message: String, authResponse: com.example.iccc_alert_app.auth.AuthResponse? ->
            runOnUiThread {
                setLoading(false)
                if (success && authResponse != null) {
                    Toast.makeText(this, "Verification successful!", Toast.LENGTH_SHORT).show()
                    CameraManager.startAfterLogin()
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                } else {
                    showError(message)
                    clearBoxes()
                }
            }
        }

        if (purpose == "registration") {
            AuthManager.verifyRegistration(this, phone, otp, callback)
        } else {
            AuthManager.verifyLogin(this, phone, otp, callback)
        }
    }

    private fun resendOTP() {
        setLoading(true)
        val callback = { success: Boolean, message: String ->
            runOnUiThread {
                setLoading(false)
                if (success) {
                    Toast.makeText(this, "OTP resent successfully", Toast.LENGTH_SHORT).show()
                    clearBoxes()
                    startResendTimer()
                } else {
                    showError(message)
                }
            }
        }
        if (purpose == "registration") {
            showError("Please go back and register again")
        } else {
            AuthManager.requestLogin(phone, callback)
        }
    }

    // ── Timer ─────────────────────────────────────────────────────────────────
    private fun startResendTimer() {
        resendButton.isEnabled = false
        timerText.visibility = View.VISIBLE
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(60_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                timerText.text = "Resend code in ${millisUntilFinished / 1_000}s"
            }
            override fun onFinish() {
                resendButton.isEnabled = true
                timerText.visibility = View.GONE
            }
        }.start()
    }

    // ── Loading state ─────────────────────────────────────────────────────────
    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        verifyButton.isEnabled = !loading && boxes.all { it.text.length == 1 }
        boxes.forEach { it.isEnabled = !loading }
        resendButton.isEnabled = !loading && timerText.visibility == View.GONE
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
