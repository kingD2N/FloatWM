package com.floatwm.launcher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.floatwm.launcher.R
import com.floatwm.launcher.util.LaunchableApp

class AppGridAdapter(
    private val onAppTapped: (LaunchableApp) -> Unit
) : RecyclerView.Adapter<AppGridAdapter.ViewHolder>() {

    private val items = mutableListOf<LaunchableApp>()

    fun submit(apps: List<LaunchableApp>) {
        items.clear()
        items.addAll(apps)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onAppTapped)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon = itemView.findViewById<ImageView>(R.id.appIcon)
        private val label = itemView.findViewById<TextView>(R.id.appLabel)

        fun bind(app: LaunchableApp, onAppTapped: (LaunchableApp) -> Unit) {
            icon.setImageDrawable(app.icon)
            label.text = app.label
            itemView.setOnClickListener { onAppTapped(app) }
        }
    }
}
