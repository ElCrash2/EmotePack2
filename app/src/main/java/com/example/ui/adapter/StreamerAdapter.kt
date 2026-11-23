package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.data.model.SevenTvUser
import com.example.databinding.ItemStreamerBinding

class StreamerAdapter(
    private val onClick: (SevenTvUser) -> Unit
) : ListAdapter<SevenTvUser, StreamerAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStreamerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemStreamerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(user: SevenTvUser) {
            val ctx = binding.root.context
            binding.tvStreamerDisplayName.text = user.displayName.ifBlank { user.username }
            binding.tvStreamerUsername.text = ctx.getString(R.string.streamer_username_handle, user.username)

            val platforms = user.connections.map { it.platform.uppercase() }.distinct().joinToString(" • ")
            if (platforms.isNotBlank()) {
                binding.tvStreamerPlatforms.visibility = View.VISIBLE
                binding.tvStreamerPlatforms.text = platforms
            } else {
                binding.tvStreamerPlatforms.visibility = View.GONE
            }

            if (!user.avatarUrl.isNullOrBlank()) {
                binding.ivStreamerAvatar.load(user.avatarUrl) {
                    placeholder(R.drawable.ic_person)
                    error(R.drawable.ic_person)
                }
            } else {
                binding.ivStreamerAvatar.setImageResource(R.drawable.ic_person)
            }

            binding.root.setOnClickListener { onClick(user) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SevenTvUser>() {
            override fun areItemsTheSame(oldItem: SevenTvUser, newItem: SevenTvUser) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: SevenTvUser, newItem: SevenTvUser) = oldItem == newItem
        }
    }
}
