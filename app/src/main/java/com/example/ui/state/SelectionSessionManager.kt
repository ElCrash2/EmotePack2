package com.example.ui.state

import com.example.AppConfig
import com.example.data.local.CollectionWithDetails
import com.example.data.model.SearchOrigin
import com.example.data.model.SearchOriginType
import com.example.data.model.SevenTvEmote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ToggleSelectionResult {
    ADDED,
    REMOVED,
    LIMIT_REACHED
}

data class SelectFirst30Result(
    val selectedCount: Int,
    val addedCount: Int,
    val limitReached: Boolean
)

/**
 * Shared selection session preserving selected emotes across screen transitions,
 * set filter switches, and collection edits while strictly enforcing the 30-sticker cap.
 */
class SelectionSessionManager {

    private val _selectedEmotes = MutableStateFlow<Map<String, SevenTvEmote>>(emptyMap())
    val selectedEmotes: StateFlow<Map<String, SevenTvEmote>> = _selectedEmotes.asStateFlow()

    private val _editingCollectionId = MutableStateFlow<String?>(null)
    val editingCollectionId: StateFlow<String?> = _editingCollectionId.asStateFlow()

    private val _editingCollectionName = MutableStateFlow<String?>(null)
    val editingCollectionName: StateFlow<String?> = _editingCollectionName.asStateFlow()

    private val _currentOrigin = MutableStateFlow<SearchOrigin?>(null)
    val currentOrigin: StateFlow<SearchOrigin?> = _currentOrigin.asStateFlow()

    fun startFreshSession(origin: SearchOrigin) {
        _editingCollectionId.value = null
        _editingCollectionName.value = null
        _currentOrigin.value = origin
        _selectedEmotes.value = linkedMapOf()
    }

    fun startEditSession(details: CollectionWithDetails): SearchOrigin {
        val col = details.collection
        val type = runCatching { SearchOriginType.valueOf(col.searchType) }
            .getOrDefault(SearchOriginType.EMOTE)

        val origin = SearchOrigin(
            type = type,
            query = col.searchQuery,
            streamerId = col.streamerId,
            streamerName = col.streamerName,
            streamerUsername = col.streamerUsername,
            streamerAvatarUrl = col.streamerAvatarUrl,
            emoteSetId = col.sourceEmoteSetId
        )

        val initialMap = LinkedHashMap<String, SevenTvEmote>()
        details.items
            .sortedBy { it.sortOrder }
            .take(AppConfig.MAX_STICKERS_PER_COLLECTION)
            .forEach { item ->
                initialMap[item.emoteId] = SevenTvEmote(
                    id = item.emoteId,
                    name = item.emoteName,
                    animated = item.isAnimated,
                    flags = 0,
                    ownerName = col.streamerName,
                    previewUrl = item.previewUrl,
                    highResUrl = item.highResUrl,
                    fallbackUrls = item.fallbackUrlsCsv.split("|").filter { it.isNotBlank() },
                    sourceSetId = item.sourceSetId,
                    sourceSetName = item.sourceSetName,
                    updatedAt = item.versionUpdatedAt,
                    originalFormat = "",
                    localPreviewPath = item.finalWebpPath
                )
            }

        _editingCollectionId.value = col.id
        _editingCollectionName.value = col.name
        _currentOrigin.value = origin
        _selectedEmotes.value = initialMap
        return origin
    }

    fun updateOrigin(origin: SearchOrigin) {
        _currentOrigin.value = origin
    }

    fun toggleEmote(emote: SevenTvEmote): ToggleSelectionResult {
        var result = ToggleSelectionResult.ADDED
        _selectedEmotes.update { current ->
            val mutable = LinkedHashMap(current)
            if (mutable.containsKey(emote.id)) {
                mutable.remove(emote.id)
                result = ToggleSelectionResult.REMOVED
                mutable
            } else if (mutable.size >= AppConfig.MAX_STICKERS_PER_COLLECTION) {
                result = ToggleSelectionResult.LIMIT_REACHED
                current
            } else {
                mutable[emote.id] = emote
                result = ToggleSelectionResult.ADDED
                mutable
            }
        }
        return result
    }

    /**
     * Selects strictly up to the first 30 results in 7TV order.
     * In edit mode, keeps existing selections and fills remaining slots up to 30.
     * In fresh mode, selects exclusively the first 30 (or all if fewer than 30).
     */
    fun selectFirst30(orderedResults: List<SevenTvEmote>): SelectFirst30Result {
        val isEditing = _editingCollectionId.value != null
        val candidates = orderedResults.take(AppConfig.MAX_STICKERS_PER_COLLECTION)
        var added = 0
        var limitReached = false

        _selectedEmotes.update { current ->
            val mutable = if (isEditing) LinkedHashMap(current) else LinkedHashMap()
            for (emote in candidates) {
                if (mutable.size >= AppConfig.MAX_STICKERS_PER_COLLECTION) {
                    if (!mutable.containsKey(emote.id)) {
                        limitReached = true
                    }
                    break
                }
                if (!mutable.containsKey(emote.id)) {
                    mutable[emote.id] = emote
                    added++
                }
            }
            mutable
        }

        val finalSize = _selectedEmotes.value.size
        if (isEditing && finalSize >= AppConfig.MAX_STICKERS_PER_COLLECTION && added == 0) {
            limitReached = true
        }
        return SelectFirst30Result(
            selectedCount = finalSize,
            addedCount = added,
            limitReached = limitReached
        )
    }

    fun removeSelected(emoteId: String) {
        _selectedEmotes.update { current ->
            val mutable = LinkedHashMap(current)
            mutable.remove(emoteId)
            mutable
        }
    }

    fun clearSelection() {
        _selectedEmotes.value = emptyMap()
        _editingCollectionId.value = null
        _editingCollectionName.value = null
    }
}
