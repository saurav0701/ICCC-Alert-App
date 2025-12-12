package com.example.iccc_alert_app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class ChannelsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var adapter: ChannelListAdapter
    private lateinit var activeFiltersText: TextView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    // Filter state
    private var selectedArea: String = "All Areas"
    private var selectedDetectionType: String = "All Types"

    private val subscriptionListener = {
        loadChannels()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_channels, container, false)

        // Initialize views
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout)
        recyclerView = view.findViewById(R.id.channels_recycler)
        emptyView = view.findViewById(R.id.empty_view)
        activeFiltersText = view.findViewById(R.id.active_filters_text)

        // Setup SwipeRefreshLayout
        setupSwipeRefresh()

        // Setup adapter
        adapter = ChannelListAdapter(
            onChannelClick = { channel ->
                val intent = Intent(requireContext(), ChannelDetailActivity::class.java)
                intent.putExtra("CHANNEL_ID", channel.id)
                intent.putExtra("CHANNEL_AREA", channel.areaDisplay)
                intent.putExtra("CHANNEL_TYPE", channel.eventTypeDisplay)
                startActivity(intent)
            },
            onChannelLongClick = { channel ->
                showPinUnpinDialog(channel)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Add subscription listener
        SubscriptionManager.addListener(subscriptionListener)

        // Initial load
        loadChannels()

        return view
    }

    /**
     * Setup pull-to-refresh functionality
     */
    private fun setupSwipeRefresh() {
        // Set refresh colors to match app theme
        swipeRefreshLayout.setColorSchemeResources(
            R.color.colorPrimary,
            R.color.colorAccent,
            R.color.colorPrimaryDark
        )

        // Set refresh listener
        swipeRefreshLayout.setOnRefreshListener {
            refreshChannels()
        }

        // Adjust progress view positioning for better UX
        swipeRefreshLayout.setProgressViewOffset(
            false,
            0,
            (resources.displayMetrics.density * 64).toInt()
        )
    }

    private fun refreshChannels() {
        try {
            // Force reload from SubscriptionManager
            // This will include any new events received via WebSocket
            loadChannels()

            // Show success feedback
            Toast.makeText(
                requireContext(),
                "Channels refreshed",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            // Handle refresh error
            Toast.makeText(
                requireContext(),
                "Failed to refresh channels",
                Toast.LENGTH_SHORT
            ).show()
        } finally {
            // Stop refresh animation
            swipeRefreshLayout.isRefreshing = false
        }
    }

    // Method to be called from MainActivity toolbar menu
    fun showFilterDialog() {
        val allChannels = SubscriptionManager.getSubscriptions()

        val areas = mutableSetOf("All Areas")
        val detectionTypes = mutableSetOf("All Types")

        allChannels.forEach { channel ->
            areas.add(channel.areaDisplay)
            detectionTypes.add(channel.eventTypeDisplay)
        }

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_filter, null)

        val areaSpinner = dialogView.findViewById<Spinner>(R.id.area_spinner)
        val detectionTypeSpinner = dialogView.findViewById<Spinner>(R.id.detection_type_spinner)

        // Setup Area Spinner
        val areaAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            areas.sorted()
        )
        areaAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        areaSpinner.adapter = areaAdapter

        // Set current selection
        val areaPosition = areas.sorted().indexOf(selectedArea)
        if (areaPosition >= 0) areaSpinner.setSelection(areaPosition)

        // Setup Detection Type Spinner
        val typeAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            detectionTypes.sorted()
        )
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        detectionTypeSpinner.adapter = typeAdapter

        // Set current selection
        val typePosition = detectionTypes.sorted().indexOf(selectedDetectionType)
        if (typePosition >= 0) detectionTypeSpinner.setSelection(typePosition)

        AlertDialog.Builder(requireContext())
            .setTitle("Filter Channels")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                selectedArea = areaSpinner.selectedItem.toString()
                selectedDetectionType = detectionTypeSpinner.selectedItem.toString()
                applyFilters()
                updateActiveFiltersText()
            }
            .setNegativeButton("Clear") { _, _ ->
                selectedArea = "All Areas"
                selectedDetectionType = "All Types"
                applyFilters()
                updateActiveFiltersText()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun updateActiveFiltersText() {
        val filters = mutableListOf<String>()

        if (selectedArea != "All Areas") {
            filters.add(selectedArea)
        }

        if (selectedDetectionType != "All Types") {
            filters.add(selectedDetectionType)
        }

        if (filters.isEmpty()) {
            activeFiltersText.visibility = View.GONE
        } else {
            activeFiltersText.visibility = View.VISIBLE
            activeFiltersText.text = "Filters: ${filters.joinToString(", ")}"
        }
    }

    private fun applyFilters() {
        val allChannels = SubscriptionManager.getSubscriptions()

        val filteredChannels = allChannels.filter { channel ->
            val matchesArea = selectedArea == "All Areas" || channel.areaDisplay == selectedArea
            val matchesType = selectedDetectionType == "All Types" || channel.eventTypeDisplay == selectedDetectionType

            matchesArea && matchesType
        }

        if (filteredChannels.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.updateChannels(filteredChannels)
        }
    }

    private fun showPinUnpinDialog(channel: Channel) {
        val options = if (channel.isPinned) {
            arrayOf("Unpin Channel", "Cancel")
        } else {
            arrayOf("Pin Channel", "Cancel")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("${channel.areaDisplay} - ${channel.eventTypeDisplay}")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> {
                        channel.isPinned = !channel.isPinned
                        SubscriptionManager.updateChannel(channel)

                        val message = if (channel.isPinned) {
                            "Channel pinned to top"
                        } else {
                            "Channel unpinned"
                        }
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

                        applyFilters()
                    }
                }
            }
            .show()
    }

    private fun loadChannels() {
        applyFilters()
    }

    override fun onResume() {
        super.onResume()
        loadChannels()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        SubscriptionManager.removeListener(subscriptionListener)
    }
}