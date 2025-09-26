package io.texne.g1.hub.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.hub.R
import io.texne.g1.hub.databinding.ItemGlassesBinding
import io.texne.g1.hub.model.Repository
import com.google.android.material.color.MaterialColors

class GlassesAdapter(
    private val listener: GlassesActionListener
) : ListAdapter<GlassesAdapter.GlassesItem, GlassesAdapter.GlassesViewHolder>(DiffCallback) {

    data class GlassesItem(
        val snapshot: Repository.GlassesSnapshot,
        val retryCountdown: ApplicationViewModel.RetryCountdown?,
        val retryCount: Int
    )

    interface GlassesActionListener {
        fun onConnect(id: String)
        fun onDisconnect(id: String)
        fun onCancelRetry(id: String)
        fun onRetryNow(id: String)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GlassesViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemGlassesBinding.inflate(inflater, parent, false)
        return GlassesViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GlassesViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class GlassesViewHolder(
        private val binding: ItemGlassesBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GlassesItem) {
            val context = binding.root.context
            val snapshot = item.snapshot
            val displayName = snapshot.name.ifBlank { snapshot.id }
            binding.textGlassesName.text = displayName

            val statusText = context.getString(
                R.string.glasses_status_format,
                snapshot.status.displayName()
            )
            binding.textGlassesStatus.text = statusText
            binding.textGlassesStatus.setTextColor(
                MaterialColors.getColor(
                    binding.root,
                    statusColorAttr(snapshot.status)
                )
            )

            binding.textGlassesBattery.text = context.getString(
                R.string.glasses_battery_format,
                snapshot.batteryPercentage,
                snapshot.left.batteryPercentage,
                snapshot.right.batteryPercentage
            )

            val signal = snapshot.signalStrength?.toString()
                ?: context.getString(R.string.glasses_signal_unknown)
            binding.textGlassesSignal.text = context.getString(
                R.string.glasses_signal_format,
                signal
            )
            val rssiText = snapshot.rssi?.toString()
            if (rssiText == null) {
                binding.textGlassesRssi.visibility = View.GONE
            } else {
                binding.textGlassesRssi.visibility = View.VISIBLE
                binding.textGlassesRssi.text = context.getString(
                    R.string.glasses_rssi_format,
                    rssiText
                )
            }

            binding.textRetryCount.text = context.getString(
                R.string.glasses_retry_count,
                item.retryCount
            )

            val countdown = item.retryCountdown
            if (countdown != null) {
                binding.textRetryCountdown.isVisible = true
                binding.textRetryCountdown.text = context.getString(
                    R.string.glasses_retry_countdown,
                    countdown.secondsRemaining
                )
            } else {
                binding.textRetryCountdown.isGone = true
            }

            binding.buttonRetryNow.isVisible = countdown != null
            binding.buttonCancelRetry.isVisible = countdown != null

            val isConnected = snapshot.status == G1ServiceCommon.GlassesStatus.CONNECTED
            binding.buttonConnect.text = if (isConnected) {
                context.getString(R.string.glasses_button_disconnect)
            } else {
                context.getString(R.string.glasses_button_connect)
            }

            binding.buttonConnect.setOnClickListener {
                if (isConnected) {
                    listener.onDisconnect(snapshot.id)
                } else {
                    listener.onConnect(snapshot.id)
                }
            }
            binding.buttonCancelRetry.setOnClickListener {
                listener.onCancelRetry(snapshot.id)
            }
            binding.buttonRetryNow.setOnClickListener {
                listener.onRetryNow(snapshot.id)
            }
        }
    }

    @AttrRes
    private fun statusColorAttr(status: G1ServiceCommon.GlassesStatus): Int {
        return when (status) {
            G1ServiceCommon.GlassesStatus.CONNECTED ->
                com.google.android.material.R.attr.colorPrimary
            G1ServiceCommon.GlassesStatus.ERROR ->
                com.google.android.material.R.attr.colorError
            else -> com.google.android.material.R.attr.colorOnSurfaceVariant
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<GlassesItem>() {
        override fun areItemsTheSame(oldItem: GlassesItem, newItem: GlassesItem): Boolean =
            oldItem.snapshot.id == newItem.snapshot.id

        override fun areContentsTheSame(oldItem: GlassesItem, newItem: GlassesItem): Boolean =
            oldItem == newItem
    }
}

private fun G1ServiceCommon.GlassesStatus.displayName(): String {
    return name.replace('_', ' ').lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
