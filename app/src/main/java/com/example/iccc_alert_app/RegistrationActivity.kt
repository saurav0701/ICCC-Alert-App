package com.example.iccc_alert_app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.iccc_alert_app.auth.AuthManager
import com.example.iccc_alert_app.auth.RegistrationRequest


class RegistrationActivity : AppCompatActivity() {

    private lateinit var nameInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var areaSelectionButton: Button
    private lateinit var selectedAreasText: TextView
    private lateinit var designationInput: EditText
    private lateinit var registerButton: Button
    private lateinit var loginText: TextView
    private lateinit var progressBar: ProgressBar

    private var allAreas = listOf<Pair<String, String>>()
    private var selectedAreaValues = mutableSetOf<String>()
    private var selectedAreaDisplays = mutableSetOf<String>()
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
        areaSelectionButton = findViewById(R.id.area_selection_button)
        selectedAreasText = findViewById(R.id.selected_areas_text)
        designationInput = findViewById(R.id.designation_input)
        registerButton = findViewById(R.id.register_button)
        loginText = findViewById(R.id.login_text)
        progressBar = findViewById(R.id.progress_bar)
    }

    /**
     * ✅ Load CCL areas + HQ option
     */
    private fun loadCCLAreas() {
        val cclAreas = AvailableChannels.getAreas()

        // ✅ Add HQ as first option
        allAreas = listOf("hq" to "🏢 Headquarters (HQ)") + cclAreas

        Toast.makeText(
            this,
            "Loaded ${allAreas.size - 1} CCL areas + HQ",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setupListeners() {
        areaSelectionButton.setOnClickListener {
            showAreaSelectionDialog()
        }

        registerButton.setOnClickListener {
            if (validateInputs()) {
                registerUser()
            }
        }

        loginText.setOnClickListener {
            navigateToLogin()
        }
    }

    /**
     * ✅ Show multi-select dialog for areas with HQ option
     */
    private fun showAreaSelectionDialog() {
        val areaDisplayNames = allAreas.map { it.second }.toTypedArray()
        val selectedStates = BooleanArray(areaDisplayNames.size) { index ->
            selectedAreaDisplays.contains(areaDisplayNames[index])
        }

        AlertDialog.Builder(this)
            .setTitle("Select Areas (Multi-select)")
            .setMultiChoiceItems(
                areaDisplayNames,
                selectedStates
            ) { _, which, isChecked ->
                val areaDisplay = areaDisplayNames[which]
                val areaValue = allAreas[which].first

                // ✅ Special handling for HQ
                if (areaValue.lowercase() == "hq") {
                    if (isChecked) {
                        // HQ selected - auto-select all areas
                        selectedAreaValues.clear()
                        selectedAreaDisplays.clear()
                        selectedAreaValues.add("HQ")
                        selectedAreaDisplays.add(areaDisplay)

                        // Update all checkboxes to unchecked (except HQ)
                        for (i in selectedStates.indices) {
                            if (i != which) {
                                selectedStates[i] = false
                            }
                        }
                    } else {
                        // HQ deselected
                        selectedAreaValues.remove("HQ")
                        selectedAreaDisplays.remove(areaDisplay)
                    }
                } else {
                    // Regular area selection
                    if (isChecked) {
                        selectedAreaValues.add(areaValue)
                        selectedAreaDisplays.add(areaDisplay)
                        // Remove HQ if selecting regular areas
                        selectedAreaValues.remove("HQ")
                        selectedAreaDisplays.removeIf { it.contains("Headquarters") }
                    } else {
                        selectedAreaValues.remove(areaValue)
                        selectedAreaDisplays.remove(areaDisplay)
                    }
                }
            }
            .setPositiveButton("OK") { _, _ ->
                updateSelectedAreasDisplay()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * ✅ Update UI to show selected areas
     */
    private fun updateSelectedAreasDisplay() {
        if (selectedAreaDisplays.isEmpty()) {
            selectedAreasText.text = "No areas selected"
            selectedAreasText.setTextColor(android.graphics.Color.parseColor("#BDBDBD"))
        } else if (selectedAreaValues.contains("HQ")) {
            // ✅ HQ user can see all areas
            selectedAreasText.text = "🏢 Headquarters - All areas accessible"
            selectedAreasText.setTextColor(android.graphics.Color.parseColor("#2196F3"))
        } else if (selectedAreaDisplays.size == allAreas.size - 1) {
            // All regular areas selected (minus HQ)
            selectedAreasText.text = "All areas (${selectedAreaDisplays.size})"
            selectedAreasText.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            selectedAreasText.text = selectedAreaDisplays.sorted().joinToString(", ")
            selectedAreasText.setTextColor(android.graphics.Color.parseColor("#212121"))
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
            selectedAreaValues.isEmpty() -> {
                showError("Please select at least one area")
                false
            }
            else -> true
        }
    }

    private fun registerUser() {
        val name = nameInput.text.toString().trim()
        val phone = phoneInput.text.toString().trim()
        val designation = designationInput.text.toString().trim()

        // ✅ Join selected areas with comma
        val areasString = selectedAreaValues.sorted().joinToString(",")

        val request = RegistrationRequest(
            name = name,
            phone = phone,
            area = areasString,  // Send as comma-separated string
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
        areaSelectionButton.isEnabled = !loading
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