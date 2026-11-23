package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppConfig
import com.example.EmotePackApplication
import com.example.data.model.SearchOrigin
import com.example.data.model.SearchOriginType
import com.example.data.model.SevenTvEmote
import com.example.data.model.SevenTvEmoteSet
import com.example.data.model.SevenTvUser
import com.example.data.remote.ApiResult
import com.example.ui.state.SelectFirst30Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EmoteGridUiState(
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoadingSet: Boolean = false,
    val streamer: SevenTvUser? = null,
    val emoteSets: List<SevenTvEmoteSet> = emptyList(),
    val activeSetId: String? = null, // null = All loaded sets
    val searchQuery: String = "",
    val displayedEmotes: List<SevenTvEmote> = emptyList(),
    val totalCount: Int = 0,
    val currentPage: Int = 1,
    val hasMorePages: Boolean = false,
    val errorMessage: String? = null,
    val isNetworkError: Boolean = false
)

class EmoteGridViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val PAGE_SIZE = AppConfig.MAX_STICKERS_PER_COLLECTION // 30
    }

    private val app = application as EmotePackApplication
    private val repo = app.sevenTvRepository
    val session = app.selectionSession

    private val _uiState = MutableStateFlow(EmoteGridUiState())
    val uiState: StateFlow<EmoteGridUiState> = _uiState.asStateFlow()

    private var filteredStreamerPool: List<SevenTvEmote> = emptyList()
    private var revealedStreamerCount: Int = 0

    fun loadStreamer(userId: String, initialSetId: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, isNetworkError = false) }
            when (val res = repo.getStreamerWithEmoteSets(userId, targetSetId = initialSetId)) {
                is ApiResult.Success -> {
                    val user = res.data
                    val sets = user.emoteSets
                    val validSetId = if (initialSetId != null && sets.any { it.id == initialSetId }) {
                        initialSetId
                    } else null

                    session.updateOrigin(
                        SearchOrigin(
                            type = SearchOriginType.STREAMER,
                            query = user.displayName,
                            streamerId = user.id,
                            streamerName = user.displayName,
                            streamerUsername = user.username,
                            streamerAvatarUrl = user.avatarUrl,
                            emoteSetId = validSetId
                        )
                    )

                    applyStreamerSetView(user = user, sets = sets, activeSetId = validSetId, isLoading = false)
                }
                is ApiResult.Error -> {
                    // If offline while editing a streamer collection, fall back gracefully to local selected items
                    val localEditItems = if (session.editingCollectionId.value != null) {
                        session.selectedEmotes.value.values.toList()
                    } else emptyList()

                    if (localEditItems.isNotEmpty()) {
                        filteredStreamerPool = localEditItems
                        revealedStreamerCount = minOf(PAGE_SIZE, filteredStreamerPool.size)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                displayedEmotes = filteredStreamerPool.take(revealedStreamerCount),
                                totalCount = filteredStreamerPool.size,
                                hasMorePages = revealedStreamerCount < filteredStreamerPool.size,
                                errorMessage = null,
                                isNetworkError = false
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = res.message,
                                isNetworkError = res.isNetwork
                            )
                        }
                    }
                }
            }
        }
    }

    fun selectEmoteSetFilter(setId: String?) {
        val currentState = _uiState.value
        val user = currentState.streamer ?: return
        val sets = currentState.emoteSets

        val currentOrigin = session.currentOrigin.value
        if (currentOrigin != null && currentOrigin.type == SearchOriginType.STREAMER) {
            session.updateOrigin(currentOrigin.copy(emoteSetId = setId))
        }

        val targetSet = sets.firstOrNull { it.id == setId }
        if (setId != null && targetSet != null && targetSet.emotes.isEmpty()) {
            // Lazy-load the selected emote set on demand
            viewModelScope.launch {
                _uiState.update { it.copy(activeSetId = setId, isLoadingSet = true, errorMessage = null) }
                when (val res = repo.loadEmoteSetOnDemand(user.id, setId)) {
                    is ApiResult.Success -> {
                        val updatedSets = _uiState.value.emoteSets.map {
                            if (it.id == setId) res.data else it
                        }
                        val updatedUser = user.copy(emoteSets = updatedSets)
                        applyStreamerSetView(user = updatedUser, sets = updatedSets, activeSetId = setId, isLoading = false)
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoadingSet = false,
                                errorMessage = res.message,
                                isNetworkError = res.isNetwork
                            )
                        }
                    }
                }
            }
        } else {
            applyStreamerSetView(user = user, sets = sets, activeSetId = setId, isLoading = false)
        }
    }

    private fun applyStreamerSetView(
        user: SevenTvUser,
        sets: List<SevenTvEmoteSet>,
        activeSetId: String?,
        isLoading: Boolean
    ) {
        val rawPool = if (activeSetId == null) {
            sets.flatMap { it.emotes }.distinctBy { it.id }
        } else {
            sets.firstOrNull { it.id == activeSetId }?.emotes.orEmpty()
        }

        // In Edit mode, retain selected local items (including any emotes deleted from 7TV)
        val mergedPool = mergeMissingEditedEmotes(rawPool, activeSetId = activeSetId, queryMatch = true)
        filteredStreamerPool = mergedPool
        revealedStreamerCount = minOf(PAGE_SIZE, filteredStreamerPool.size)

        _uiState.update {
            it.copy(
                isLoading = isLoading,
                isLoadingSet = false,
                streamer = user,
                emoteSets = sets,
                activeSetId = activeSetId,
                displayedEmotes = filteredStreamerPool.take(revealedStreamerCount),
                totalCount = filteredStreamerPool.size,
                currentPage = 1,
                hasMorePages = revealedStreamerCount < filteredStreamerPool.size,
                errorMessage = null,
                isNetworkError = false
            )
        }
    }

    fun loadNextStreamerPage() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingSet || !state.hasMorePages) return
        if (revealedStreamerCount >= filteredStreamerPool.size) return

        revealedStreamerCount = minOf(filteredStreamerPool.size, revealedStreamerCount + PAGE_SIZE)
        _uiState.update {
            it.copy(
                displayedEmotes = filteredStreamerPool.take(revealedStreamerCount),
                hasMorePages = revealedStreamerCount < filteredStreamerPool.size,
                currentPage = (revealedStreamerCount + PAGE_SIZE - 1) / PAGE_SIZE
            )
        }
    }

    fun searchEmotes(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        val previousOrigin = session.currentOrigin.value
        val matchesEditedOrigin = session.editingCollectionId.value != null &&
            previousOrigin?.type == SearchOriginType.EMOTE &&
            previousOrigin.query.equals(trimmed, ignoreCase = true)

        session.updateOrigin(
            SearchOrigin(
                type = SearchOriginType.EMOTE,
                query = trimmed
            )
        )

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    searchQuery = trimmed,
                    currentPage = 1,
                    displayedEmotes = emptyList(),
                    hasMorePages = false,
                    errorMessage = null,
                    isNetworkError = false
                )
            }
            when (val res = repo.searchEmotes(query = trimmed, page = 1, limit = PAGE_SIZE)) {
                is ApiResult.Success -> {
                    val displayed = mergeMissingEditedEmotes(
                        apiItems = res.data.items,
                        activeSetId = null,
                        queryMatch = matchesEditedOrigin
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            displayedEmotes = displayed,
                            totalCount = maxOf(res.data.totalCount, displayed.size),
                            currentPage = 1,
                            hasMorePages = res.data.hasMore,
                            errorMessage = null
                        )
                    }
                }
                is ApiResult.Error -> {
                    val localFallback = if (session.editingCollectionId.value != null) {
                        session.selectedEmotes.value.values.toList()
                    } else emptyList()

                    if (localFallback.isNotEmpty() && matchesEditedOrigin) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                displayedEmotes = localFallback,
                                totalCount = localFallback.size,
                                currentPage = 1,
                                hasMorePages = false,
                                errorMessage = null,
                                isNetworkError = false
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = res.message,
                                isNetworkError = res.isNetwork
                            )
                        }
                    }
                }
            }
        }
    }

    fun loadNextEmotePage() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMorePages || state.searchQuery.isBlank()) return
        val nextPage = state.currentPage + 1

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            when (val res = repo.searchEmotes(query = state.searchQuery, page = nextPage, limit = PAGE_SIZE)) {
                is ApiResult.Success -> {
                    val combined = (state.displayedEmotes + res.data.items).distinctBy { it.id }
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            displayedEmotes = combined,
                            totalCount = maxOf(res.data.totalCount, combined.size),
                            currentPage = nextPage,
                            hasMorePages = res.data.hasMore
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            errorMessage = res.message,
                            isNetworkError = res.isNetwork
                        )
                    }
                }
            }
        }
    }

    /**
     * Selects up to the first 30 visible emotes strictly in 7TV order.
     * Never fetches extra pages and never downloads original files.
     */
    fun selectFirst30(): SelectFirst30Result {
        val currentDisplayed = _uiState.value.displayedEmotes
        if (currentDisplayed.isEmpty()) {
            return SelectFirst30Result(
                selectedCount = session.selectedEmotes.value.size,
                addedCount = 0,
                limitReached = false
            )
        }
        return session.selectFirst30(currentDisplayed)
    }

    /**
     * During edit mode, merges back any emotes saved in the local pack that are no longer
     * present in 7TV's live response (e.g. deleted emotes) so the user can view or remove them.
     */
    private fun mergeMissingEditedEmotes(
        apiItems: List<SevenTvEmote>,
        activeSetId: String?,
        queryMatch: Boolean
    ): List<SevenTvEmote> {
        if (session.editingCollectionId.value == null || !queryMatch) return apiItems
        val selected = session.selectedEmotes.value.values.filter { emote ->
            activeSetId == null || emote.sourceSetId == null || emote.sourceSetId == activeSetId
        }
        if (selected.isEmpty()) return apiItems
        val apiById = apiItems.associateBy { it.id }
        val deletedOrLocalOnly = selected.filter { !apiById.containsKey(it.id) }
        return if (deletedOrLocalOnly.isEmpty()) apiItems else deletedOrLocalOnly + apiItems
    }
}
