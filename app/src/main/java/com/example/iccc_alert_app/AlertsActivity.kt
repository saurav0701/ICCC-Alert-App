package com.example.iccc_alert_app

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class AlertsActivity : BaseDrawerActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: AlertsAdapter
    private val alerts = mutableListOf<Event>()
    private val handler = Handler(Looper.getMainLooper())

    private val eventListener: (Event) -> Unit = { event ->
        handler.post {
            alerts.add(0, event)
            if (alerts.size > 100) {
                alerts.removeAt(alerts.size - 1)
            }
            updateView()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alerts)

        supportActionBar?.title = "All Alerts"
        // Don't set selected menu item since we removed nav_all_alerts
        // setSelectedMenuItem(R.id.nav_all_alerts)

        recyclerView = findViewById(R.id.alerts_recycler)
        emptyView = findViewById(R.id.empty_alerts_view)

        adapter = AlertsAdapter(alerts)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        WebSocketManager.addEventListener(eventListener)
        updateView()
    }

    private fun updateView() {
        if (alerts.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WebSocketManager.removeEventListener(eventListener)
    }
}

class AlertsAdapter(
    private val alerts: List<Event>
) : RecyclerView.Adapter<AlertsAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val area: TextView = view.findViewById(R.id.alert_area)
        val eventType: TextView = view.findViewById(R.id.alert_type)
        val location: TextView = view.findViewById(R.id.alert_location)
        val timestamp: TextView = view.findViewById(R.id.alert_timestamp)
        val badge: View = view.findViewById(R.id.alert_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alert, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = alerts[position]

        holder.area.text = event.areaDisplay
        holder.eventType.text = event.typeDisplay

        val location = event.data["location"] as? String ?: "Unknown"
        holder.location.text = location

        val date = Date(event.timestamp * 1000)
        holder.timestamp.text = dateFormat.format(date)

        val color = when (event.type) {
            "cd" -> Color.parseColor("#FF5722")
            "id" -> Color.parseColor("#F44336")
            "ct" -> Color.parseColor("#E91E63")
            "sh" -> Color.parseColor("#FF9800")
            "vd" -> Color.parseColor("#2196F3")
            "pd" -> Color.parseColor("#4CAF50")
            "vc" -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#9E9E9E")
        }
        holder.badge.setBackgroundColor(color)
    }

    override fun getItemCount() = alerts.size
}