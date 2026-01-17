package com.example.iccc_alert_app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * ✅ Multi-Camera Manager - Manage saved quad views
 * Stores views persistently using SharedPreferences
 */
object MultiCameraManager {
    private const val TAG = "MultiCameraManager"
    private const val PREFS_NAME = "multi_camera_views"
    private const val KEY_VIEWS = "saved_views"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    private val _savedViews = mutableListOf<MultiCameraView>()
    val savedViews: List<MultiCameraView> get() = _savedViews.toList()

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadViews()
        Log.d(TAG, "✅ MultiCameraManager initialized with ${_savedViews.size} saved views")
    }

    // CRUD Operations

    fun createView(name: String, cameraIds: List<String>): MultiCameraView {
        val view = MultiCameraView(name = name, cameraIds = cameraIds)
        _savedViews.add(view)
        saveViews()
        Log.d(TAG, "✅ Created view: $name with ${cameraIds.size} cameras")
        return view
    }

    fun updateView(view: MultiCameraView) {
        val index = _savedViews.indexOfFirst { it.id == view.id }
        if (index >= 0) {
            _savedViews[index] = view
            saveViews()
            Log.d(TAG, "✅ Updated view: ${view.name}")
        }
    }

    fun deleteView(viewId: String) {
        val removed = _savedViews.removeAll { it.id == viewId }
        if (removed) {
            saveViews()
            Log.d(TAG, "🗑️ Deleted view: $viewId")
        }
    }

    fun getView(viewId: String): MultiCameraView? {
        return _savedViews.find { it.id == viewId }
    }

    // Persistence

    private fun saveViews() {
        try {
            val json = gson.toJson(_savedViews)
            prefs.edit().putString(KEY_VIEWS, json).apply()
            Log.d(TAG, "💾 Saved ${_savedViews.size} views")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving views: ${e.message}", e)
        }
    }

    private fun loadViews() {
        try {
            val json = prefs.getString(KEY_VIEWS, null) ?: return
            val type = object : TypeToken<List<MultiCameraView>>() {}.type
            val loaded: List<MultiCameraView> = gson.fromJson(json, type)
            _savedViews.clear()
            _savedViews.addAll(loaded)
            Log.d(TAG, "📦 Loaded ${_savedViews.size} saved views")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading views: ${e.message}", e)
        }
    }

    fun clear() {
        _savedViews.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "🗑️ Cleared all saved views")
    }
}