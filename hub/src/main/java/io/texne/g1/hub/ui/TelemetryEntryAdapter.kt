package io.texne.g1.hub.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.hub.R
import io.texne.g1.hub.databinding.ItemTelemetryEntryBinding

class TelemetryEntryAdapter :
    ListAdapter<ApplicationViewModel.TelemetryEntry, TelemetryEntryAdapter.TelemetryViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TelemetryViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemTelemetryEntryBinding.inflate(inflater, parent, false)
        return TelemetryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TelemetryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class TelemetryViewHolder(
        private val binding: ItemTelemetryEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: ApplicationViewModel.TelemetryEntry) {
            val context = binding.root.context
            binding.textTelemetryName.text = entry.name.ifBlank { entry.id }

            binding.textTelemetryStatus.text = context.getString(
                R.string.telemetry_status_format,
                entry.status.displayName()
            )
            binding.textTelemetryStatus.setTextColor(
                ContextCompat.getColor(context, statusColor(entry.status))
            )

            binding.textTelemetryLeft.text = context.getString(
                R.string.telemetry_eye_status_format,
                context.getString(R.string.label_left_eye),
                entry.leftStatus.displayName()
            )
            binding.textTelemetryLeft.setTextColor(
                ContextCompat.getColor(context, statusColor(entry.leftStatus))
            )

            binding.textTelemetryRight.text = context.getString(
                R.string.telemetry_eye_status_format,
                context.getString(R.string.label_right_eye),
                entry.rightStatus.displayName()
            )
            binding.textTelemetryRight.setTextColor(
                ContextCompat.getColor(context, statusColor(entry.rightStatus))
            )

            binding.textTelemetryBattery.text = context.getString(
                R.string.glasses_battery_format,
                entry.batteryPercentage,
                entry.leftBatteryPercentage,
                entry.rightBatteryPercentage
            )

            val signalText = entry.signalStrength?.toString()
                ?: context.getString(R.string.telemetry_signal_unknown)
            val rssiText = entry.rssi?.toString()
                ?: context.getString(R.string.telemetry_signal_unknown)
            binding.textTelemetrySignal.text = context.getString(
                R.string.telemetry_signal_detail,
                signalText,
                rssiText
            )

            binding.textTelemetryRetryCount.text = context.getString(
                R.string.telemetry_retry_count,
                entry.retryCount
            )

            val updatedAt = android.text.format.DateFormat.getTimeFormat(context)
                .format(entry.lastUpdatedAt)
            binding.textTelemetryUpdated.text = context.getString(
                R.string.telemetry_updated_format,
                updatedAt
            )
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ApplicationViewModel.TelemetryEntry>() {
        override fun areItemsTheSame(
            oldItem: ApplicationViewModel.TelemetryEntry,
            newItem: ApplicationViewModel.TelemetryEntry
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: ApplicationViewModel.TelemetryEntry,
            newItem: ApplicationViewModel.TelemetryEntry
        ): Boolean = oldItem == newItem
    }
}

private fun G1ServiceCommon.GlassesStatus.displayName(): String {
    return name.replace('_', ' ').lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

private fun statusColor(status: G1ServiceCommon.GlassesStatus): Int = when (status) {
    G1ServiceCommon.GlassesStatus.CONNECTED -> R.color.telemetry_success
    G1ServiceCommon.GlassesStatus.ERROR -> R.color.telemetry_error
    else -> R.color.telemetry_info
}
