package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.data.local.CollectionStatus
import com.example.data.local.StickerCollectionEntity
import com.example.databinding.ItemCollectionPackBinding
import java.io.File
import java.text.DateFormat
import java.util.Date

class CollectionPackAdapter(
    private val onClick: (StickerCollectionEntity) -> Unit
) : ListAdapter<StickerCollectionEntity, CollectionPackAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCollectionPackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemCollectionPackBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(col: StickerCollectionEntity) {
            val ctx = binding.root.context
            binding.tvPackName.text = col.name
            binding.tvPackCounts.text = ctx.getString(
                R.string.pack_card_counts,
                col.totalCount,
                col.animatedCount,
                col.staticCount
            )
            val dateStr = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(col.createdAt))
            binding.tvPackDate.text = ctx.getString(R.string.pack_card_date, dateStr)

            val iconFile = col.trayIconPath?.let { File(it) }?.takeIf { it.exists() }
            if (iconFile != null) {
                binding.ivPackIcon.load(iconFile) {
                    placeholder(R.drawable.ic_launcher_art)
                    error(R.drawable.ic_launcher_art)
                }
            } else {
                binding.ivPackIcon.setImageResource(R.drawable.ic_launcher_art)
            }

            when (col.status) {
                CollectionStatus.READY -> {
                    binding.tvPackStatus.setText(R.string.pack_status_ready)
                    binding.tvPackStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_success))
                }
                CollectionStatus.DRAFT_TOO_FEW -> {
                    binding.tvPackStatus.setText(R.string.pack_status_draft)
                    binding.tvPackStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_warning))
                }
                CollectionStatus.PROCESSING -> {
                    binding.tvPackStatus.setText(R.string.pack_status_processing)
                    binding.tvPackStatus.setTextColor(ContextCompat.getColor(ctx, R.color.accent_cyan))
                }
                else -> {
                    binding.tvPackStatus.setText(R.string.pack_status_partial)
                    binding.tvPackStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_warning))
                }
            }

            binding.root.setOnClickListener { onClick(col) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<StickerCollectionEntity>() {
            override fun areItemsTheSame(oldItem: StickerCollectionEntity, newItem: StickerCollectionEntity) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: StickerCollectionEntity, newItem: StickerCollectionEntity) =
                oldItem == newItem
        }
    }
}
