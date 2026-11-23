package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.data.local.StickerItemEntity
import com.example.databinding.ItemLocalStickerBinding
import java.io.File

class LocalStickerAdapter : ListAdapter<StickerItemEntity, LocalStickerAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLocalStickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemLocalStickerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: StickerItemEntity) {
            val ctx = binding.root.context
            binding.tvLocalName.text = item.emoteName
            binding.ivLocalSticker.contentDescription = item.emoteName
            val kb = maxOf(1L, item.fileSizeBytes / 1024L)
            binding.tvLocalSize.text = "${kb} KB"

            if (item.isAnimated) {
                binding.tvLocalBadge.setText(R.string.badge_animated)
                binding.tvLocalBadge.setBackgroundResource(R.drawable.bg_badge_animated)
                binding.tvLocalBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_animated_fg))
            } else {
                binding.tvLocalBadge.setText(R.string.badge_static)
                binding.tvLocalBadge.setBackgroundResource(R.drawable.bg_badge_static)
                binding.tvLocalBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_static_fg))
            }

            // Always load strictly from offline local WebP file when available
            val localFile = item.finalWebpPath?.let { File(it) }?.takeIf { it.exists() }
            binding.ivLocalSticker.load(localFile ?: item.previewUrl) {
                placeholder(R.drawable.ic_emoji)
                error(R.drawable.ic_emoji)
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
