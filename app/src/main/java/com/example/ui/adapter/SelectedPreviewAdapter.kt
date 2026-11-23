package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.data.model.SevenTvEmote
import com.example.databinding.ItemSelectedEmotePreviewBinding

class SelectedPreviewAdapter(
    private val onRemove: (SevenTvEmote) -> Unit
) : ListAdapter<SevenTvEmote, SelectedPreviewAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectedEmotePreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSelectedEmotePreviewBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(emote: SevenTvEmote) {
            val ctx = binding.root.context
            binding.tvPreviewName.text = emote.name
            binding.btnRemovePreview.contentDescription = ctx.getString(R.string.remove_emote_cd, emote.name)
            val localFile = emote.localPreviewPath?.let { java.io.File(it) }?.takeIf { it.exists() }
            binding.ivPreviewEmote.load(localFile ?: emote.previewUrl) {
                placeholder(R.drawable.ic_emoji)
                error(R.drawable.ic_emoji)
            }

            if (emote.animated) {
                binding.tvPreviewBadge.setText(R.string.badge_animated)
                binding.tvPreviewBadge.setBackgroundResource(R.drawable.bg_badge_animated)
                binding.tvPreviewBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_animated_fg))
            } else {
                binding.tvPreviewBadge.setText(R.string.badge_static)
                binding.tvPreviewBadge.setBackgroundResource(R.drawable.bg_badge_static)
                binding.tvPreviewBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_static_fg))
            }

            binding.btnRemovePreview.setOnClickListener { onRemove(emote) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SevenTvEmote>() {
            override fun areItemsTheSame(oldItem: SevenTvEmote, newItem: SevenTvEmote) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: SevenTvEmote, newItem: SevenTvEmote) = oldItem == newItem
        }
    }
}
