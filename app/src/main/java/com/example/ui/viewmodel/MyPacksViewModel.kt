package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.EmotePackApplication
import com.example.data.local.StickerCollectionEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class BackupEvent {
    data class ExportSuccess(val count: Int) : BackupEvent()
    data object ExportEmpty : BackupEvent()
    data class ImportSuccess(val count: Int) : BackupEvent()
    data class Error(val message: String) : BackupEvent()
}

class MyPacksViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as EmotePackApplication).collectionRepository

    val collections: StateFlow<List<StickerCollectionEntity>> = repo.observeAllCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProcessing: StateFlow<StickerCollectionEntity?> = repo.observeActiveProcessingCollection()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _backupEvents = MutableSharedFlow<BackupEvent>()
    val backupEvents = _backupEvents.asSharedFlow()

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                if (collections.value.isEmpty()) {
                    _backupEvents.emit(BackupEvent.ExportEmpty)
                    return@launch
                }
                val count = repo.exportBackupToUri(uri)
                _backupEvents.emit(BackupEvent.ExportSuccess(count))
            } catch (e: Exception) {
                _backupEvents.emit(BackupEvent.Error(e.message ?: "Export error"))
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val count = repo.importBackupFromUri(uri)
                _backupEvents.emit(BackupEvent.ImportSuccess(count))
            } catch (e: Exception) {
                _backupEvents.emit(BackupEvent.Error(e.message ?: "Import error"))
            }
        }
    }
}
