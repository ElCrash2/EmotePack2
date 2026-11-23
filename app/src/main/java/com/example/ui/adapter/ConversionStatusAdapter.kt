package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.data.local.ItemConversionStatus
import com.example.data.local.StickerItemEntity
import com.example.databinding.ItemConversionStatusBinding
import java.io.File

class ConversionStatusAdapter(
    private val onTrimRequested: (StickerItemEntity) -> Unit
) : ListAdapter<StickerItemEntity, ConversionStatusAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversionStatusBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemConversionStatusBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: StickerItemEntity) {
            val ctx = binding.root.context
            binding.tvConvName.text = item.emoteName

            val localFile = item.finalWebpPath?.let { File(it) }?.takeIf { it.exists() }
            binding.ivConvThumb.load(localFile ?: item.previewUrl) {
                placeholder(R.drawable.ic_emoji)
                error(R.drawable.ic_emoji)
            }

            binding.pbConvItem.visibility = View.GONE
            binding.btnConvAction.visibility = View.GONE

            when (item.status) {
                ItemConversionStatus.PENDING -> {
                    binding.tvConvStatus.setText(R.string.status_pending)
                    binding.tvConvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                }
                ItemConversionStatus.DOWNLOADING -> {
                    binding.tvConvStatus.setText(R.string.status_downloading)
                    binding.tvConvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.accent_cyan))
                    binding.pbConvItem.visibility = View.VISIBLE
                }
                ItemConversionStatus.CONVERTING -> {
                    binding.tvConvStatus.setText(R.string.status_converting)
                    binding.tvConvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.accent_cyan))
                    binding.pbConvItem.visibility = View.VISIBLE
                }
                ItemConversionStatus.VALIDATING -> {
                    binding.tvConvStatus.setText(R.string.status_validating)
                    binding.tvConvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.accent_cyan))
                    binding.pbConvItem.visibility = View.VISIBLE
                }
                ItemConversionStatus.COMPLETED -> {
                    val kb = maxOf(1L, item.fileSizeBytes / 1024L).toInt()
                    binding.tvConvStatus.text = ctx.getString(R.string.status_completed, kb)
                    binding.tvConvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_success))
                }
                ItemConversionStatus.NEEDS_TRIM -> {
                    val secs = item.durationMs / 1000f
                    binding.tvConvStatus.text = ctx.getString(R.string.status_needs_trim, secs)
                    binding.tvConvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_warning))
                    binding.btnConvAction.visibility = View.VISIBLE
                    binding.btnConvAction.setText(R.string.choose_over_10s_action)
                    binding.btnConvAction.setOnClickListener { onTrimRequested(item) }
                }
                ItemConversionStatus.CANCELLED -> {
                    binding.tvConvStatus.setText(R.string.status_cancelled)
                    binding.tvConvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                }
                ItemConversionStatus.FAILED -> {
                    binding.tvConvStatus.text = ctx.getString(
                        R.string.status_failed,
                        item.errorMessage ?: "Error"
                    )
                    binding.tvConvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_error))
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<StickerItemEntity>() {
            override fun areItemsTheSame(oldItem: StickerItemEntity, newItem: StickerItemEntity) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: StickerItemEntity, newItem: StickerItemEntity) =
                oldItem == newItem
        }
    }
}
