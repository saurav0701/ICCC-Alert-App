package com.example.iccc_alert_app

import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.chrisbanes.photoview.PhotoView

class ImageViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        val photoView = findViewById<PhotoView>(R.id.photo_view)
        val closeButton = findViewById<ImageView>(R.id.close_button)
        val eventType = findViewById<TextView>(R.id.event_type_text)
        val eventLocation = findViewById<TextView>(R.id.event_location_text)

        val imageUriString = intent.getStringExtra("IMAGE_URI")
        val type = intent.getStringExtra("EVENT_TYPE")
        val location = intent.getStringExtra("EVENT_LOCATION")

        eventType.text = type
        eventLocation.text = location

        if (imageUriString != null) {
            val uri = Uri.parse(imageUriString)
            photoView.setImageURI(uri)
        }

        closeButton.setOnClickListener {
            finish()
        }
    }
}