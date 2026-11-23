package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.EmotePackApplication
import com.example.data.local.CollectionWithDetails
import com.example.data.local.ItemConversionStatus
import com.example.data.model.SearchOrigin
import com.example.data.model.SearchOriginType
import com.example.worker.PackBuildWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class CreateCollectionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EmotePackApplication
    private val repo = app.collectionRepository
    val session = app.selectionSession
    private val workManager = WorkManager.getInstance(application)

    private val _activeCollectionId = MutableStateFlow<String?>(null)
    val activeCollectionId: StateFlow<String?> = _activeCollectionId.asStateFlow()

    val activeCollectionDetails: StateFlow<CollectionWithDetails?> = _activeCollectionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else repo.observeCollectionWithDetails(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun attachExistingBuild(collectionId: String) {
        _activeCollectionId.value = collectionId
    }

    fun hasEnoughStorage(): Boolean {
        val count = session.selectedEmotes.value.size
        return repo.storage.hasEnoughSpaceForBatch(count)
    }

    fun requiredSpaceMb(): Long =
        repo.storage.requiredSpaceMbForBatch(session.selectedEmotes.value.size)

    fun startBuild(collectionName: String) {
        val selected = session.selectedEmotes.value.values.toList()
        if (selected.isEmpty()) return

        val editingId = session.editingCollectionId.value
        val origin = session.currentOrigin.value ?: SearchOrigin(
            type = SearchOriginType.EMOTE,
            query = collectionName
        )

        viewModelScope.launch {
            val collectionId = repo.prepareCollectionBuild(
                existingCollectionId = editingId,
                collectionName = collectionName,
                origin = origin,
                selectedEmotes = selected
            )
            _activeCollectionId.value = collectionId
            session.clearSelection()
            enqueueWorker(collectionId, trimAll = false, trimItemIds = emptyList())
        }
    }

    fun retryFailed() {
        val id = _activeCollectionId.value ?: return
        viewModelScope.launch {
            repo.resetItemsForRetry(id, includeNeedsTrim = false)
            enqueueWorker(id, trimAll = false, trimItemIds = emptyList())
        }
    }

    fun trimAndRetryItem(itemId: String) {
        val id = _activeCollectionId.value ?: return
        viewModelScope.launch {
            enqueueWorker(id, trimAll = false, trimItemIds = listOf(itemId))
        }
    }

    fun trimAllOver10s() {
        val id = _activeCollectionId.value ?: return
        viewModelScope.launch {
            enqueueWorker(id, trimAll = true, trimItemIds = emptyList())
        }
    }

    fun skipTrimItem(itemId: String) {
        val details = activeCollectionDetails.value ?: return
        val item = details.items.firstOrNull { it.id == itemId } ?: return
        viewModelScope.launch {
            repo.updateItem(
                item.copy(
                    status = ItemConversionStatus.FAILED,
                    errorMessage = "Skipped (>10s)"
                )
            )
            val updated = repo.getCollectionWithDetails(details.collection.id)
            val anyStillPending = updated?.items?.any {
                it.status == ItemConversionStatus.PENDING ||
                    it.status == ItemConversionStatus.DOWNLOADING ||
                    it.status == ItemConversionStatus.CONVERTING ||
                    it.status == ItemConversionStatus.VALIDATING ||
                    it.status == ItemConversionStatus.NEEDS_TRIM
            } ?: false
            if (!anyStillPending) {
                repo.finalizeCollectionBuild(details.collection.id, cancelled = false)
            }
        }
    }

    fun cancelBuild() {
        val id = _activeCollectionId.value ?: return
        workManager.cancelUniqueWork(workName(id))
    }

    fun discardOrRevertBuild(onDone: () -> Unit) {
        val id = _activeCollectionId.value
        if (id == null) {
            session.clearSelection()
            onDone()
            return
        }
        workManager.cancelUniqueWork(workName(id))
        viewModelScope.launch {
            repo.discardCreationOrRevertEdit(id)
            session.clearSelection()
            onDone()
        }
    }

    private fun enqueueWorker(collectionId: String, trimAll: Boolean, trimItemIds: List<String>) {
        val input = Data.Builder()
            .putString(PackBuildWorker.KEY_COLLECTION_ID, collectionId)
            .putBoolean(PackBuildWorker.KEY_TRIM_ALL_OVER_10S, trimAll)
            .putStringArray(PackBuildWorker.KEY_TRIM_ITEM_IDS, trimItemIds.toTypedArray())
            .build()

        val request = OneTimeWorkRequestBuilder<PackBuildWorker>()
            .setInputData(input)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        workManager.enqueueUniqueWork(
            workName(collectionId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun workName(collectionId: String) = "pack_build_$collectionId"
}
