package com.example.iccc_alert_app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.iccc_alert_app.auth.AuthManager
import com.example.iccc_alert_app.auth.RegistrationRequest


class RegistrationActivity : AppCompatActivity() {

    private lateinit var nameInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var areaSpinner: Spinner
    private lateinit var designationInput: EditText
    private lateinit var registerButton: Button
    private lateinit var loginText: TextView
    private lateinit var progressBar: ProgressBar

    private var areas = listOf<String>()
    private var areaValues = listOf<String>()
    private val ORGANIZATION = "CCL"  // ✅ Fixed to CCL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration)

        supportActionBar?.title = "Register - CCL"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // ✅ Set organization to CCL immediately
        BackendConfig.setOrganization(ORGANIZATION)

        initializeViews()
        loadCCLAreas()
        setupListeners()
    }

    private fun initializeViews() {
        nameInput = findViewById(R.id.name_input)
        phoneInput = findViewById(R.id.phone_input)
        areaSpinner = findViewById(R.id.area_spinner)
        designationInput = findViewById(R.id.designation_input)
        registerButton = findViewById(R.id.register_button)
        loginText = findViewById(R.id.login_text)
        progressBar = findViewById(R.id.progress_bar)
    }

    /**
     * ✅ Load CCL areas only
     */
    private fun loadCCLAreas() {
        val areasData = AvailableChannels.getAreas()
        areas = areasData.map { it.second } // Display names
        areaValues = areasData.map { it.first } // Internal names

        val areaAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            areas
        )
        areaAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        areaSpinner.adapter = areaAdapter

        Toast.makeText(
            this,
            "Loaded ${areas.size} CCL areas",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setupListeners() {
        registerButton.setOnClickListener {
            if (validateInputs()) {
                registerUser()
            }
        }

        loginText.setOnClickListener {
            navigateToLogin()
        }
    }

    private fun validateInputs(): Boolean {
        val name = nameInput.text.toString().trim()
        val phone = phoneInput.text.toString().trim()
        val designation = designationInput.text.toString().trim()

        return when {
            name.isEmpty() -> {
                showError("Please enter your name")
                false
            }
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
            designation.isEmpty() -> {
                showError("Please enter your designation")
                false
            }
            else -> true
        }
    }

    private fun registerUser() {
        val name = nameInput.text.toString().trim()
        val phone = phoneInput.text.toString().trim()
        val areaIndex = areaSpinner.selectedItemPosition
        val area = areaValues[areaIndex]
        val designation = designationInput.text.toString().trim()

        val request = RegistrationRequest(
            name = name,
            phone = phone,
            area = area,
            designation = designation,
            workingFor = ORGANIZATION  // ✅ Always CCL
        )

        setLoading(true)

        AuthManager.requestRegistration(this, request) { success, message ->
            runOnUiThread {
                setLoading(false)
                if (success) {
                    Toast.makeText(this, "OTP sent to WhatsApp!", Toast.LENGTH_SHORT).show()

                    val intent = Intent(this, OTPVerificationActivity::class.java).apply {
                        putExtra("phone", phone)
                        putExtra("purpose", "registration")
                    }
                    startActivity(intent)
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                    finish()
                } else {
                    showError(message)
                }
            }
        }
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        registerButton.isEnabled = !loading
        nameInput.isEnabled = !loading
        phoneInput.isEnabled = !loading
        areaSpinner.isEnabled = !loading
        designationInput.isEnabled = !loading
        loginText.isEnabled = !loading
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        navigateToLogin()
        return true
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}