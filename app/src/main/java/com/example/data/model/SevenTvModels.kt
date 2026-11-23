package com.example.data.model

import java.io.Serializable

enum class SearchOriginType {
    STREAMER,
    EMOTE
}

data class EmoteVariant(
    val name: String,
    val staticName: String?,
    val format: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val url: String
) : Serializable

data class SevenTvEmote(
    val id: String,
    val name: String,
    val animated: Boolean,
    val flags: Int,
    val ownerName: String?,
    val previewUrl: String,
    val highResUrl: String,
    val fallbackUrls: List<String> = emptyList(),
    val sourceSetId: String? = null,
    val sourceSetName: String? = null,
    val updatedAt: Long = 0L,
    val originalFormat: String = "",
    val localPreviewPath: String? = null
) : Serializable {
    val versionKey: String
        get() = "${updatedAt}_${highResUrl.hashCode()}"
}

data class SevenTvEmoteSet(
    val id: String,
    val name: String,
    val capacity: Int,
    val emotes: List<SevenTvEmote>
) : Serializable

data class SevenTvConnection(
    val id: String,
    val platform: String,
    val username: String,
    val displayName: String,
    val emoteSetId: String? = null
) : Serializable

data class SevenTvUser(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val connections: List<SevenTvConnection> = emptyList(),
    val emoteSets: List<SevenTvEmoteSet> = emptyList()
) : Serializable

data class EmoteSearchPage(
    val items: List<SevenTvEmote>,
    val totalCount: Int,
    val page: Int,
    val hasMore: Boolean
)

data class UserSearchPage(
    val items: List<SevenTvUser>,
    val page: Int,
    val hasMore: Boolean
)

data class SearchOrigin(
    val type: SearchOriginType,
    val query: String,
    val streamerId: String? = null,
    val streamerName: String? = null,
    val streamerUsername: String? = null,
    val streamerAvatarUrl: String? = null,
    val emoteSetId: String? = null
) : Serializable
