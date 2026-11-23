package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EmotePackApplication
import com.example.data.local.CollectionWithDetails
import com.example.data.model.SearchOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EmotePackApplication
    private val repo = app.collectionRepository
    private val session = app.selectionSession

    private val collectionIdFlow = MutableStateFlow<String?>(null)

    val details: StateFlow<CollectionWithDetails?> = collectionIdFlow
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else repo.observeCollectionWithDetails(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun load(collectionId: String) {
        collectionIdFlow.value = collectionId
    }

    fun beginEditSession(): SearchOrigin? {
        val current = details.value ?: return null
        return session.startEditSession(current)
    }

    fun markSubpackAdded(subpackId: String, added: Boolean) {
        viewModelScope.launch {
            repo.markSubpackAddedToWhatsApp(subpackId, added)
        }
    }

    fun hasPendingEditSnapshot(): Boolean {
        val id = collectionIdFlow.value ?: return false
        return repo.hasPendingEditSnapshot(id)
    }

    fun commitCreationSummary() {
        val id = collectionIdFlow.value ?: return
        repo.commitCreationSummary(id)
    }

    fun cancelCreationOrRevertEdit(onDone: () -> Unit) {
        val id = collectionIdFlow.value ?: return
        viewModelScope.launch {
            repo.discardCreationOrRevertEdit(id)
            session.clearSelection()
            onDone()
        }
    }

    fun deleteCollection(onDeleted: () -> Unit) {
        val id = collectionIdFlow.value ?: return
        viewModelScope.launch {
            repo.deleteCollection(id)
            onDeleted()
        }
    }
}
