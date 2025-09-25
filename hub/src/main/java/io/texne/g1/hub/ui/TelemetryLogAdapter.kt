package io.texne.g1.hub.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.texne.g1.hub.R
import io.texne.g1.hub.databinding.ItemTelemetryLogBinding

class TelemetryLogAdapter :
    ListAdapter<ApplicationViewModel.TelemetryLogEntry, TelemetryLogAdapter.LogViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemTelemetryLogBinding.inflate(inflater, parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(
        private val binding: ItemTelemetryLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: ApplicationViewModel.TelemetryLogEntry) {
            val context = binding.root.context
            val formatter = android.text.format.DateFormat.getTimeFormat(context)
            binding.textTelemetryLogTimestamp.text = formatter.format(entry.timestampMillis)
            binding.textTelemetryLogMessage.text = entry.message
            val colorRes = when (entry.type) {
                ApplicationViewModel.TelemetryLogType.ERROR -> R.color.telemetry_error
                ApplicationViewModel.TelemetryLogType.SUCCESS -> R.color.telemetry_success
                ApplicationViewModel.TelemetryLogType.INFO -> R.color.telemetry_info
            }
            binding.textTelemetryLogMessage.setTextColor(
                ContextCompat.getColor(context, colorRes)
            )
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ApplicationViewModel.TelemetryLogEntry>() {
        override fun areItemsTheSame(
            oldItem: ApplicationViewModel.TelemetryLogEntry,
            newItem: ApplicationViewModel.TelemetryLogEntry
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: ApplicationViewModel.TelemetryLogEntry,
            newItem: ApplicationViewModel.TelemetryLogEntry
        ): Boolean = oldItem == newItem
    }
}
