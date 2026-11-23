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
import com.example.data.model.SevenTvEmote
import com.example.databinding.ItemEmoteGridBinding

class EmoteGridAdapter(
    private val onEmoteClick: (SevenTvEmote) -> Unit
) : ListAdapter<SevenTvEmote, EmoteGridAdapter.ViewHolder>(DIFF) {

    private var selectedIds: Set<String> = emptySet()

    fun updateSelection(newSelectedIds: Set<String>) {
        if (newSelectedIds == selectedIds) return
        val oldIds = selectedIds
        selectedIds = newSelectedIds
        currentList.forEachIndexed { index, emote ->
            val wasSelected = emote.id in oldIds
            val isSelected = emote.id in newSelectedIds
            if (wasSelected != isSelected) {
                notifyItemChanged(index, PAYLOAD_SELECTION)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEmoteGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id in selectedIds)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelectionOnly(getItem(position).id in selectedIds)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class ViewHolder(private val binding: ItemEmoteGridBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(emote: SevenTvEmote, isSelected: Boolean) {
            val ctx = binding.root.context
            binding.tvEmoteName.text = emote.name
            binding.ivEmote.contentDescription = emote.name

            if (emote.animated) {
                binding.tvEmoteTypeBadge.setText(R.string.badge_animated)
                binding.tvEmoteTypeBadge.setBackgroundResource(R.drawable.bg_badge_animated)
                binding.tvEmoteTypeBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_animated_fg))
            } else {
                binding.tvEmoteTypeBadge.setText(R.string.badge_static)
                binding.tvEmoteTypeBadge.setBackgroundResource(R.drawable.bg_badge_static)
                binding.tvEmoteTypeBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_static_fg))
            }

            val localFile = emote.localPreviewPath?.let { java.io.File(it) }?.takeIf { it.exists() }
            binding.ivEmote.load(localFile ?: emote.previewUrl) {
                placeholder(R.drawable.ic_emoji)
                error(R.drawable.ic_emoji)
            }

            bindSelectionOnly(isSelected)
            binding.emoteCardRoot.setOnClickListener { onEmoteClick(emote) }
        }

        fun bindSelectionOnly(isSelected: Boolean) {
            binding.emoteCardRoot.setBackgroundResource(
                if (isSelected) R.drawable.bg_emote_card_selected else R.drawable.bg_emote_card_normal
            )
            binding.ivSelectedCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"

        private val DIFF = object : DiffUtil.ItemCallback<SevenTvEmote>() {
            override fun areItemsTheSame(oldItem: SevenTvEmote, newItem: SevenTvEmote) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: SevenTvEmote, newItem: SevenTvEmote) = oldItem == newItem
        }
    }
}
