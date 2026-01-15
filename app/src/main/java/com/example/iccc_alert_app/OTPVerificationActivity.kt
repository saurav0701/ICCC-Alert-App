package com.example.iccc_alert_app

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.iccc_alert_app.auth.AuthManager

class OTPVerificationActivity : AppCompatActivity() {

    private lateinit var phoneText: TextView
    private lateinit var otpInput: EditText
    private lateinit var verifyButton: Button
    private lateinit var resendButton: TextView
    private lateinit var timerText: TextView
    private lateinit var progressBar: ProgressBar

    private var phone: String = ""
    private var purpose: String = ""
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp_verification)

        supportActionBar?.title = "Verify OTP"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        phone = intent.getStringExtra("phone") ?: ""
        purpose = intent.getStringExtra("purpose") ?: "login"

        if (phone.isEmpty()) {
            Toast.makeText(this, "Invalid phone number", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializeViews()
        setupListeners()
        startResendTimer()
    }

    private fun initializeViews() {
        phoneText = findViewById(R.id.phone_text)
        otpInput = findViewById(R.id.otp_input)
        verifyButton = findViewById(R.id.verify_button)
        resendButton = findViewById(R.id.resend_button)
        timerText = findViewById(R.id.timer_text)
        progressBar = findViewById(R.id.progress_bar)

        phoneText.text = "OTP sent to +91 $phone via WhatsApp"

        otpInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                verifyButton.isEnabled = s?.length == 6
            }
        })
    }

    private fun setupListeners() {
        verifyButton.setOnClickListener {
            val otp = otpInput.text.toString().trim()
            if (otp.length == 6) {
                verifyOTP(otp)
            }
        }

        resendButton.setOnClickListener {
            resendOTP()
        }
    }

    private fun verifyOTP(otp: String) {
        setLoading(true)

        val verifyCallback = { success: Boolean, message: String, authResponse: com.example.iccc_alert_app.auth.AuthResponse? ->
            runOnUiThread {
                setLoading(false)
                if (success && authResponse != null) {
                    Toast.makeText(this, "Verification successful!", Toast.LENGTH_SHORT).show()

                    // ✅ Start camera manager now that organization is known
                    CameraManager.startAfterLogin()

                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                } else {
                    showError(message)
                    otpInput.text.clear()
                }
            }
        }

        if (purpose == "registration") {
            AuthManager.verifyRegistration(this, phone, otp, verifyCallback)
        } else {
            AuthManager.verifyLogin(this, phone, otp, verifyCallback)
        }
    }

    private fun resendOTP() {
        setLoading(true)

        val callback = { success: Boolean, message: String ->
            runOnUiThread {
                setLoading(false)
                if (success) {
                    Toast.makeText(this, "OTP resent successfully", Toast.LENGTH_SHORT).show()
                    startResendTimer()
                    otpInput.text.clear()
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

    private fun startResendTimer() {
        resendButton.isEnabled = false
        timerText.visibility = View.VISIBLE

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(60000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                timerText.text = "Resend OTP in ${seconds}s"
            }

            override fun onFinish() {
                resendButton.isEnabled = true
                timerText.visibility = View.GONE
            }
        }.start()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        verifyButton.isEnabled = !loading && otpInput.text.length == 6
        otpInput.isEnabled = !loading
        resendButton.isEnabled = !loading && timerText.visibility == View.GONE
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}