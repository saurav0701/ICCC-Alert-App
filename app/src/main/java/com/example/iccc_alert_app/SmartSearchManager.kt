
package com.example.iccc_alert_app

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.*

/**
 * ✅ Smart Search Manager with real-time filtering
 */
class SmartSearchManager(
    private val context: Context,
    private val searchView: EditText,
    private val chipGroup: ChipGroup,
    private val suggestionsRecycler: RecyclerView,
    private val onSearch: (SearchQuery) -> Unit
) {

    data class SearchQuery(
        val text: String,
        val filters: Set<SearchFilter> = emptySet()
    )

    enum class SearchFilter(val displayName: String) {
        TODAY("Today"),
        THIS_WEEK("This Week"),
        CRITICAL("Critical"),
        SAVED("Saved"),
        HAS_IMAGE("Has Image"),
        GPS_EVENTS("GPS Events")
    }

    private val selectedFilters = mutableSetOf<SearchFilter>()
    private val searchHistory = mutableListOf<String>()
    private val suggestionAdapter = SearchSuggestionAdapter()

    private var searchJob: Job? = null
    private val searchScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        setupSearchView()
        setupChipGroup()
        setupSuggestions()
        loadSearchHistory()
    }

    private fun setupSearchView() {
        searchView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""

                // Debounce search
                searchJob?.cancel()
                searchJob = searchScope.launch {
                    delay(300) // Wait 300ms after user stops typing

                    if (query.length >= 2) {
                        updateSuggestions(query)
                        performSearch(query)
                    } else if (query.isEmpty()) {
                        showSearchHistory()
                    }
                }
            }
        })

        // Clear button
        searchView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showSearchHistory()
            }
        }
    }

    private fun setupChipGroup() {
        // Add filter chips
        SearchFilter.values().forEach { filter ->
            val chip = LayoutInflater.from(context)
                .inflate(R.layout.filter_chip_item, chipGroup, false) as Chip

            chip.text = filter.displayName
            chip.isCheckable = true
            chip.isChecked = false

            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedFilters.add(filter)
                } else {
                    selectedFilters.remove(filter)
                }

                // Trigger search with filters
                performSearch(searchView.text.toString())
            }

            chipGroup.addView(chip)
        }
    }

    private fun setupSuggestions() {
        suggestionsRecycler.adapter = suggestionAdapter

        suggestionAdapter.onSuggestionClick = { suggestion ->
            searchView.setText(suggestion)
            searchView.setSelection(suggestion.length)
            addToHistory(suggestion)
        }
    }

    private fun performSearch(query: String) {
        val searchQuery = SearchQuery(
            text = query,
            filters = selectedFilters
        )

        onSearch(searchQuery)
    }

    private suspend fun updateSuggestions(query: String) = withContext(Dispatchers.Default) {
        // Generate smart suggestions based on query
        val suggestions = mutableListOf<String>()

        // Add event type suggestions
        val eventTypes = listOf(
            "Vehicle Detection", "Intrusion Detection", "Crowd Threat",
            "Shoplifting", "Parking Detection", "Violence Detection"
        )

        suggestions.addAll(
            eventTypes.filter { it.contains(query, ignoreCase = true) }
        )

        // Add location suggestions
        val locations = listOf(
            "Gate 1", "Gate 2", "Parking Area", "Main Entrance",
            "Loading Dock", "Office Block"
        )

        suggestions.addAll(
            locations.filter { it.contains(query, ignoreCase = true) }
        )

        // Add history that matches
        suggestions.addAll(
            searchHistory.filter { it.contains(query, ignoreCase = true) }
        )

        withContext(Dispatchers.Main) {
            suggestionAdapter.updateSuggestions(suggestions.distinct().take(5))
        }
    }

    private fun showSearchHistory() {
        suggestionAdapter.updateSuggestions(searchHistory.take(5))
    }

    private fun addToHistory(query: String) {
        if (query.isNotBlank() && !searchHistory.contains(query)) {
            searchHistory.add(0, query)
            if (searchHistory.size > 10) {
                searchHistory.removeAt(searchHistory.size - 1)
            }
            saveSearchHistory()
        }
    }

    private fun loadSearchHistory() {
        val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("history", "[]")
        // Parse JSON to list
        // For simplicity, using comma-separated
        searchHistory.addAll(
            historyJson?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        )
    }

    private fun saveSearchHistory() {
        val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("history", searchHistory.joinToString(","))
            .apply()
    }

    fun clearHistory() {
        searchHistory.clear()
        saveSearchHistory()
        showSearchHistory()
    }

    fun cleanup() {
        searchJob?.cancel()
        searchScope.cancel()
    }
}

/**
 * ✅ Search Suggestion Adapter
 */
class SearchSuggestionAdapter : RecyclerView.Adapter<SearchSuggestionAdapter.ViewHolder>() {

    private val suggestions = mutableListOf<String>()
    var onSuggestionClick: ((String) -> Unit)? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.suggestion_icon)
        val text: TextView = view.findViewById(R.id.suggestion_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val suggestion = suggestions[position]
        holder.text.text = suggestion

        holder.itemView.setOnClickListener {
            onSuggestionClick?.invoke(suggestion)
        }
    }

    override fun getItemCount() = suggestions.size

    fun updateSuggestions(newSuggestions: List<String>) {
        suggestions.clear()
        suggestions.addAll(newSuggestions)
        notifyDataSetChanged()
    }
}