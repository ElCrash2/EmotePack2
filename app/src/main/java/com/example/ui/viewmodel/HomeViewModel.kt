package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EmotePackApplication
import com.example.data.model.SearchOriginType
import com.example.data.model.SevenTvUser
import com.example.data.remote.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val activeMode: SearchOriginType = SearchOriginType.STREAMER,
    val streamerQuery: String = "",
    val emoteQuery: String = "",
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val streamers: List<SevenTvUser> = emptyList(),
    val hasMoreStreamers: Boolean = false,
    val currentPage: Int = 1,
    val lastSearchedStreamerQuery: String = "",
    val errorMessage: String? = null,
    val isNetworkError: Boolean = false,
    val hasSearchedStreamer: Boolean = false
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as EmotePackApplication).sevenTvRepository

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onStreamerFieldActivated() {
        _uiState.update { state ->
            state.copy(
                activeMode = SearchOriginType.STREAMER,
                emoteQuery = ""
            )
        }
    }

    fun onEmoteFieldActivated() {
        _uiState.update { state ->
            state.copy(
                activeMode = SearchOriginType.EMOTE,
                streamerQuery = ""
            )
        }
    }

    fun updateStreamerQuery(text: String) {
        _uiState.update { it.copy(streamerQuery = text, activeMode = SearchOriginType.STREAMER, emoteQuery = "") }
    }

    fun updateEmoteQuery(text: String) {
        _uiState.update { it.copy(emoteQuery = text, activeMode = SearchOriginType.EMOTE, streamerQuery = "") }
    }

    fun searchStreamers() {
        val query = _uiState.value.streamerQuery.trim()
        if (query.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    isNetworkError = false,
                    lastSearchedStreamerQuery = query,
                    currentPage = 1,
                    hasSearchedStreamer = true
                )
            }
            when (val res = repo.searchStreamers(query = query, page = 1)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            streamers = res.data.items,
                            hasMoreStreamers = res.data.hasMore,
                            currentPage = res.data.page,
                            errorMessage = null
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            streamers = emptyList(),
                            hasMoreStreamers = false,
                            errorMessage = res.message,
                            isNetworkError = res.isNetwork
                        )
                    }
                }
            }
        }
    }

    fun loadMoreStreamers() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMoreStreamers) return
        val nextPage = state.currentPage + 1

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            when (val res = repo.searchStreamers(query = state.lastSearchedStreamerQuery, page = nextPage)) {
                is ApiResult.Success -> {
                    val combined = (state.streamers + res.data.items).distinctBy { it.id }
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            streamers = combined,
                            hasMoreStreamers = res.data.hasMore,
                            currentPage = nextPage
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
}
