package com.example.iccc_alert_app

import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay

/**
 * Shared osmdroid setup for every map in the app.
 *
 * The app previously drew Google's satellite tiles by requesting them straight
 * from mt0-3.google.com. That is outside the Maps API and not permitted by
 * Google's terms, so the maps now use OpenStreetMap's standard tiles.
 *
 * Two things OSM's tile policy requires, both handled here:
 *  - a User-Agent that identifies this app. osmdroid defaults to the package
 *    name, and generic or sample-looking agents are blocked outright, which
 *    shows up as a blank grey map.
 *  - visible "(c) OpenStreetMap contributors" attribution on the map.
 */
object MapTileConfig {

    private const val TAG = "MapTileConfig"

    /**
     * Identifies the app to OSM's tile servers. Must stay specific: a generic
     * agent gets the app blocked and every map turns grey.
     */
    private const val USER_AGENT = "ICCCAlertApp/1.0 (+https://cclai.in)"

    private const val TILE_CACHE_MAX_BYTES = 50L * 1024L * 1024L
    private const val TILE_CACHE_TRIM_BYTES = 40L * 1024L * 1024L

    /** Call once from onCreate, before any MapView is inflated or used. */
    @Suppress("DEPRECATION")
    fun initialize(context: Context) {
        try {
            val config = Configuration.getInstance()
            config.load(
                context.applicationContext,
                PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            )
            config.userAgentValue = USER_AGENT
            config.tileFileSystemCacheMaxBytes = TILE_CACHE_MAX_BYTES
            config.tileFileSystemCacheTrimBytes = TILE_CACHE_TRIM_BYTES
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing osmdroid: ${e.message}")
        }
    }

    /**
     * Applies the OSM tile source and adds the required attribution overlay.
     * Call after [initialize] and before adding your own overlays, so the
     * attribution sits underneath them.
     */
    fun applyTileSource(mapView: MapView) {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.overlays.add(CopyrightOverlay(mapView.context))
    }
}
