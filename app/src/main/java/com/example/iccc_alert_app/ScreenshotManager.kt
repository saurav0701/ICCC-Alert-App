package com.example.iccc_alert_app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.WebView
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ScreenshotManager(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val cameraName: String,
    private val cameraArea: String
) {

    companion object {
        private const val TAG = "ScreenshotManager"
    }

    fun captureScreenshot(callback: (success: Boolean, uri: Uri?, message: String?) -> Unit) {
        // For Android O+ (API 26+), use PixelCopy API which works with hardware acceleration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            captureWithPixelCopy(callback)
        } else {
            // Fallback for older versions
            captureWithCanvas(callback)
        }
    }

    /**
     * PixelCopy API - Works with hardware-accelerated WebView video playback
     * This is the CORRECT way to capture WebView content with video
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun captureWithPixelCopy(callback: (success: Boolean, uri: Uri?, message: String?) -> Unit) {
        try {
            val bitmap = Bitmap.createBitmap(
                webView.width,
                webView.height,
                Bitmap.Config.ARGB_8888
            )

            val location = IntArray(2)
            webView.getLocationInWindow(location)

            // PixelCopy works with hardware acceleration and captures actual rendered content
            android.view.PixelCopy.request(
                activity.window,
                android.graphics.Rect(
                    location[0],
                    location[1],
                    location[0] + webView.width,
                    location[1] + webView.height
                ),
                bitmap,
                { copyResult ->
                    if (copyResult == android.view.PixelCopy.SUCCESS) {
                        Log.d(TAG, "📸 PixelCopy successful")

                        // Verify we got actual content
                        if (isBlackBitmap(bitmap)) {
                            Log.w(TAG, "⚠️ Bitmap still appears black after PixelCopy")
                            callback(false, null, "Unable to capture video content")
                        } else {
                            val uri = saveToGallery(bitmap)
                            if (uri != null) {
                                Log.d(TAG, "📸 Screenshot saved: $uri")
                                callback(true, uri, "Screenshot saved successfully")
                            } else {
                                callback(false, null, "Failed to save screenshot")
                            }
                        }
                    } else {
                        Log.e(TAG, "❌ PixelCopy failed with result: $copyResult")
                        callback(false, null, "Screenshot capture failed")
                    }
                },
                Handler(Looper.getMainLooper())
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Screenshot failed: ${e.message}", e)
            callback(false, null, "Screenshot failed: ${e.message}")
        }
    }

    /**
     * Fallback method for Android < O
     */
    private fun captureWithCanvas(callback: (success: Boolean, uri: Uri?, message: String?) -> Unit) {
        try {
            val bitmap = Bitmap.createBitmap(
                webView.width,
                webView.height,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(bitmap)
            webView.draw(canvas)

            if (isBlackBitmap(bitmap)) {
                Log.w(TAG, "⚠️ Canvas capture resulted in black bitmap")
                callback(false, null, "Unable to capture video content. Try using screen recording instead.")
                return
            }

            val uri = saveToGallery(bitmap)

            if (uri != null) {
                Log.d(TAG, "📸 Screenshot saved: $uri")
                callback(true, uri, "Screenshot saved successfully")
            } else {
                callback(false, null, "Failed to save screenshot")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Canvas capture failed: ${e.message}", e)
            callback(false, null, "Screenshot capture failed")
        }
    }

    private fun isBlackBitmap(bitmap: Bitmap): Boolean {
        // Sample pixels to check if the bitmap is mostly black
        val width = bitmap.width
        val height = bitmap.height
        var nonBlackPixels = 0
        val sampleSize = 100 // Check 100 random pixels

        for (i in 0 until sampleSize) {
            val x = (Math.random() * width).toInt().coerceIn(0, width - 1)
            val y = (Math.random() * height).toInt().coerceIn(0, height - 1)
            val pixel = bitmap.getPixel(x, y)

            // Check if pixel is not black and not fully transparent
            if (pixel != android.graphics.Color.BLACK &&
                pixel != android.graphics.Color.TRANSPARENT &&
                android.graphics.Color.alpha(pixel) > 50) {
                nonBlackPixels++
            }
        }

        // If less than 10% of sampled pixels are non-black, consider it a black bitmap
        return nonBlackPixels < (sampleSize * 0.1)
    }

    private fun saveToGallery(bitmap: Bitmap): Uri? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Camera_${cameraName.replace(" ", "_")}_$timestamp.jpg"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - Use MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ICCC_Cameras")
                    put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                }

                val uri = activity.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                uri?.let {
                    activity.contentResolver.openOutputStream(it)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                    }
                }
                uri
            } else {
                // Android 9 and below - Use app-specific external storage with FileProvider
                val picturesDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                val cameraDir = File(picturesDir, "ICCC_Cameras")
                if (!cameraDir.exists()) {
                    cameraDir.mkdirs()
                }

                val file = File(cameraDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }

                // Get FileProvider URI for the saved file
                FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    file
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving screenshot: ${e.message}", e)
            null
        }
    }

    fun showScreenshotOptions(uri: Uri) {
        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.dialog_screenshot_options, null)

        val shareButton = view.findViewById<Button>(R.id.btn_share)
        val copyButton = view.findViewById<Button>(R.id.btn_copy)
        val viewButton = view.findViewById<Button>(R.id.btn_view)
        val closeButton = view.findViewById<ImageButton>(R.id.btn_close)

        shareButton.setOnClickListener {
            shareScreenshot(uri)
            dialog.dismiss()
        }

        copyButton.setOnClickListener {
            copyToClipboard(uri)
            dialog.dismiss()
        }

        viewButton.setOnClickListener {
            viewScreenshot(uri)
            dialog.dismiss()
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()

        // Auto-dismiss after 10 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }, 10000)
    }

    private fun shareScreenshot(uri: Uri) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Camera: $cameraName\nArea: $cameraArea\nTime: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            activity.startActivity(Intent.createChooser(shareIntent, "Share Screenshot"))
            Log.d(TAG, "📤 Screenshot shared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share: ${e.message}", e)
            Toast.makeText(activity, "Failed to share screenshot", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(uri: Uri) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newUri(activity.contentResolver, "Camera Screenshot", uri)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(activity, "Screenshot copied to clipboard", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "📋 Screenshot copied to clipboard")
    }

    private fun viewScreenshot(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to view screenshot: ${e.message}", e)
            Toast.makeText(activity, "No app found to view image", Toast.LENGTH_SHORT).show()
        }
    }
}