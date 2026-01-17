package com.example.iccc_alert_app

import android.content.Intent
import android.os.Bundle
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

class LoginActivity : AppCompatActivity() {

    private lateinit var phoneInput: EditText
    private lateinit var loginButton: Button
    private lateinit var registerButton: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Initialize AuthManager
        AuthManager.initialize(this)

        // Check if already logged in
        if (AuthManager.isLoggedIn()) {
            navigateToMain()
            return
        }

        // ✅ IMPORTANT: Stop WebSocket service if running (user logged out)
        WebSocketService.stop(this)

        initializeViews()
        setupListeners()
    }

    private fun initializeViews() {
        phoneInput = findViewById(R.id.phone_input)
        loginButton = findViewById(R.id.login_button)
        registerButton = findViewById(R.id.register_text)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text) // Add this to your layout

        // Format phone number as user types
        phoneInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                loginButton.isEnabled = s?.length == 10
            }
        })
    }

    private fun setupListeners() {
        loginButton.setOnClickListener {
            val phone = phoneInput.text.toString().trim()
            if (validatePhone(phone)) {
                requestLoginOTP(phone)
            }
        }

        registerButton.setOnClickListener {
            val intent = Intent(this, RegistrationActivity::class.java)
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    private fun validatePhone(phone: String): Boolean {
        return when {
            phone.isEmpty() -> {
                showError("Please enter phone number")
                false
            }
            phone.length != 10 -> {
                showError("Phone number must be 10 digits")
                false
            }
            !phone[0].isDigit() || phone[0].digitToInt() < 6 -> {
                showError("Invalid phone number")
                false
            }
            else -> true
        }
    }

    private fun requestLoginOTP(phone: String) {
        setLoading(true)
        updateStatus("Checking User...")

        AuthManager.requestLogin(phone) { success, message ->
            runOnUiThread {
                setLoading(false)
                updateStatus("")

                if (success) {
                    val org = BackendConfig.getOrganization()
                    Toast.makeText(
                        this,
                        "✓ Connected to $org\nOTP sent to WhatsApp!",
                        Toast.LENGTH_SHORT
                    ).show()

                    val intent = Intent(this, OTPVerificationActivity::class.java).apply {
                        putExtra("phone", phone)
                        putExtra("purpose", "login")
                    }
                    startActivity(intent)
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                } else {
                    showError(message)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        loginButton.isEnabled = !loading
        phoneInput.isEnabled = !loading
        registerButton.isEnabled = !loading
    }

    private fun updateStatus(message: String) {
        if (::statusText.isInitialized) {
            statusText.text = message
            statusText.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}