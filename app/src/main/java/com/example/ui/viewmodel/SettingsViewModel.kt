package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EmotePackApplication
import com.example.data.local.AppLanguage
import com.example.data.local.StorageStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EmotePackApplication
    private val prefs = app.preferencesRepository
    private val storage = app.localStorageManager
    private val collectionRepo = app.collectionRepository
    private val sevenTvRepo = app.sevenTvRepository

    val language: StateFlow<AppLanguage> = prefs.languageFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppLanguage.SYSTEM)

    private val _storageStats = MutableStateFlow(StorageStats(0L, 0L, 0L))
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    fun refreshStorageStats() {
        viewModelScope.launch {
            _storageStats.value = storage.calculateStorageStats()
        }
    }

    fun setLanguage(lang: AppLanguage) {
        viewModelScope.launch {
            prefs.setLanguage(lang)
        }
    }

    fun clearCache(onDone: () -> Unit) {
        viewModelScope.launch {
            sevenTvRepo.clearMemoryCache()
            collectionRepo.clearProtectedCache()
            refreshStorageStats()
            onDone()
        }
    }
}
