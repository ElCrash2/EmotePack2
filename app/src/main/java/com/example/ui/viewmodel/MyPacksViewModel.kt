package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EmotePackApplication
import com.example.data.local.StickerCollectionEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MyPacksViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as EmotePackApplication).collectionRepository

    val collections: StateFlow<List<StickerCollectionEntity>> = repo.observeAllCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProcessing: StateFlow<StickerCollectionEntity?> = repo.observeActiveProcessingCollection()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
