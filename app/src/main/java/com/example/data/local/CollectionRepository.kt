package com.example.data.local

import android.content.Context
import android.net.Uri
import com.example.AppConfig
import com.example.conversion.StickerConverter
import com.example.data.model.SearchOrigin
import com.example.data.model.SevenTvEmote
import com.example.whatsapp.StickerContentProvider
import com.example.whatsapp.WhatsAppPackBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CollectionRepository(
    private val context: Context,
    private val db: AppDatabase,
    val storage: LocalStorageManager
) {

    private val collectionDao = db.collectionDao()
    private val itemDao = db.stickerItemDao()
    private val subpackDao = db.subpackDao()
    private val emoteCacheDao = db.emoteCacheDao()

    /**
     * Holds pre-edit snapshots so "Cancel creation" / "Cancelar creación" while editing
     * reverts the collection back to its exact previous state without touching shared cache.
     */
    private val editSnapshots = ConcurrentHashMap<String, CollectionWithDetails>()
    private val activeEditIds = ConcurrentHashMap.newKeySet<String>()

    fun observeAllCollections(): Flow<List<StickerCollectionEntity>> =
        collectionDao.observeAllCollections()

    fun observeActiveProcessingCollection(): Flow<StickerCollectionEntity?> =
        collectionDao.observeActiveProcessingCollection()

    fun observeCollectionWithDetails(collectionId: String): Flow<CollectionWithDetails?> =
        collectionDao.observeCollectionWithDetails(collectionId)

    suspend fun getCollectionWithDetails(collectionId: String): CollectionWithDetails? =
        collectionDao.getCollectionWithDetails(collectionId)

    suspend fun getAllReadySubpacks(): List<WhatsAppSubpackEntity> =
        subpackDao.getAllReadySubpacks()

    suspend fun getSubpackById(subpackId: String): WhatsAppSubpackEntity? =
        subpackDao.getSubpackById(subpackId)

    suspend fun getCompletedStickersForSubpack(subpackId: String): List<StickerItemEntity> =
        itemDao.getCompletedItemsForSubpack(subpackId)

    suspend fun getEmoteCache(cacheKey: String): EmoteCacheEntity? =
        emoteCacheDao.getByCacheKey(cacheKey)

    suspend fun getLatestEmoteCache(emoteId: String): EmoteCacheEntity? =
        emoteCacheDao.getLatestForEmote(emoteId)

    suspend fun upsertEmoteCache(entry: EmoteCacheEntity) {
        emoteCacheDao.upsert(entry)
    }

    suspend fun touchEmoteCache(cacheKey: String, timestamp: Long = System.currentTimeMillis()) {
        emoteCacheDao.touch(cacheKey, timestamp)
    }

    suspend fun markSubpackAddedToWhatsApp(subpackId: String, added: Boolean) {
        subpackDao.markAddedToWhatsApp(subpackId, added)
    }

    suspend fun getCollectionsCount(): Int =
        collectionDao.getAllCollectionsWithDetails().size

    /**
     * Prepares a new or edited single-pack collection (max 30 stickers) in Room before starting
     * background WorkManager conversion. Reuses valid shared-cache variants & preserved edits.
     */
    suspend fun prepareCollectionBuild(
        existingCollectionId: String?,
        collectionName: String,
        origin: SearchOrigin,
        selectedEmotes: List<SevenTvEmote>
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cappedEmotes = selectedEmotes.take(AppConfig.MAX_STICKERS_PER_COLLECTION)
        val collectionId = existingCollectionId ?: UUID.randomUUID().toString()
        val existing = if (existingCollectionId != null) {
            collectionDao.getCollectionWithDetails(existingCollectionId)
        } else null

        if (existing != null && !editSnapshots.containsKey(collectionId)) {
            editSnapshots[collectionId] = existing
            activeEditIds.add(collectionId)
        }

        val packHasAnyAnimated = cappedEmotes.any { it.animated }
        val existingItemsByEmoteId = existing?.items?.associateBy { it.emoteId } ?: emptyMap()

        val itemsToSave = cappedEmotes.mapIndexed { index, emote ->
            val targetMode = WhatsAppPackBuilder.targetConversionMode(
                isEmoteAnimated = emote.animated,
                packHasAnyAnimated = packHasAnyAnimated
            )
            val expectAnimatedFile = targetMode != ConversionModeType.STATIC
            val effectiveUpdatedAt = emote.updatedAt
            val effectiveVersionKey = emote.versionKey
            val cacheKey = "${emote.id}_$effectiveVersionKey"
            val prev = existingItemsByEmoteId[emote.id]

            // 1. Check global shared cache for exact emote + version + target mode + converter version
            val cacheEntry = emoteCacheDao.getByCacheKey(cacheKey)
                ?: if (effectiveUpdatedAt == 0L) emoteCacheDao.getLatestForEmote(emote.id) else null

            val cachedVariantPath = if (cacheEntry != null && cacheEntry.converterVersion == AppConfig.CONVERTER_VERSION) {
                when (targetMode) {
                    ConversionModeType.STATIC -> cacheEntry.staticWebpPath
                    ConversionModeType.ANIMATED -> cacheEntry.animatedWebpPath
                    ConversionModeType.FAKE_VID -> cacheEntry.fakeVidWebpPath
                    else -> null
                }
            } else null

            val cachedVariantFile = cachedVariantPath?.let(::File)
            val cachedInfo = if (cachedVariantFile != null && cachedVariantFile.exists()) {
                StickerConverter.validateExistingWebp(cachedVariantFile, expectAnimatedFile)
            } else null

            if (cachedInfo != null && cachedVariantFile != null && cacheEntry != null) {
                emoteCacheDao.touch(cacheEntry.cacheKey, now)
                StickerItemEntity(
                    id = prev?.id ?: UUID.randomUUID().toString(),
                    collectionId = collectionId,
                    emoteId = emote.id,
                    emoteName = emote.name,
                    sourceSetId = emote.sourceSetId ?: prev?.sourceSetId,
                    sourceSetName = emote.sourceSetName ?: prev?.sourceSetName,
                    isAnimated = emote.animated,
                    previewUrl = emote.previewUrl,
                    highResUrl = emote.highResUrl,
                    fallbackUrlsCsv = emote.fallbackUrls.joinToString("|"),
                    finalWebpPath = cachedVariantFile.absolutePath,
                    stickerFileName = sanitizeStickerFileName(emote.id),
                    fileSizeBytes = cachedVariantFile.length(),
                    width = cachedInfo.width,
                    height = cachedInfo.height,
                    frameCount = cachedInfo.frameCount,
                    durationMs = cachedInfo.totalDurationMs,
                    hasAlpha = cachedInfo.hasAlpha,
                    status = ItemConversionStatus.COMPLETED,
                    errorMessage = null,
                    subpackId = null,
                    sortOrder = index,
                    versionUpdatedAt = effectiveUpdatedAt,
                    versionKey = effectiveVersionKey,
                    conversionMode = targetMode
                )
            } else {
                // 2. Fallback: reuse existing valid local file on the edited collection if version & mode match
                val prevFile = prev?.finalWebpPath?.let(::File)
                val sameVersion = prev != null &&
                    (effectiveUpdatedAt == 0L || prev.versionUpdatedAt == effectiveUpdatedAt)
                val fakeVidVersionOk = targetMode != ConversionModeType.FAKE_VID ||
                    (cacheEntry != null && cacheEntry.converterVersion == AppConfig.CONVERTER_VERSION)
                val prevValidInfo = if (
                    prev != null &&
                    sameVersion &&
                    fakeVidVersionOk &&
                    prev.status == ItemConversionStatus.COMPLETED &&
                    prev.conversionMode == targetMode &&
                    prevFile != null &&
                    prevFile.exists()
                ) {
                    StickerConverter.validateExistingWebp(prevFile, expectAnimatedFile)
                } else null

                if (prev != null && prevValidInfo != null) {
                    prev.copy(
                        sortOrder = index,
                        emoteName = emote.name,
                        stickerFileName = sanitizeStickerFileName(emote.id),
                        status = ItemConversionStatus.COMPLETED,
                        errorMessage = null
                    )
                } else {
                    // Carry over any old local path in finalWebpPath ONLY if status is PENDING
                    //? Better: Worker can consult prev's path or cacheEntry.originalFilePath cleanly via DB/cache,
                    // keeping finalWebpPath = prevFile if still exists for fallback offline conversion.
                    StickerItemEntity(
                        id = prev?.id ?: UUID.randomUUID().toString(),
                        collectionId = collectionId,
                        emoteId = emote.id,
                        emoteName = emote.name,
                        sourceSetId = emote.sourceSetId ?: prev?.sourceSetId,
                        sourceSetName = emote.sourceSetName ?: prev?.sourceSetName,
                        isAnimated = emote.animated,
                        previewUrl = emote.previewUrl,
                        highResUrl = emote.highResUrl,
                        fallbackUrlsCsv = emote.fallbackUrls.joinToString("|"),
                        finalWebpPath = prevFile?.takeIf { it.exists() }?.absolutePath,
                        stickerFileName = sanitizeStickerFileName(emote.id),
                        fileSizeBytes = 0L,
                        width = 0,
                        height = 0,
                        frameCount = 0,
                        durationMs = 0,
                        hasAlpha = true,
                        status = ItemConversionStatus.PENDING,
                        errorMessage = null,
                        subpackId = null,
                        sortOrder = index,
                        versionUpdatedAt = effectiveUpdatedAt,
                        versionKey = effectiveVersionKey,
                        conversionMode = targetMode
                    )
                }
            }
        }

        val entity = StickerCollectionEntity(
            id = collectionId,
            name = collectionName.trim(),
            createdAt = existing?.collection?.createdAt ?: now,
            updatedAt = now,
            searchType = origin.type.name,
            searchQuery = origin.query,
            streamerId = origin.streamerId,
            streamerName = origin.streamerName,
            streamerUsername = origin.streamerUsername,
            streamerAvatarUrl = origin.streamerAvatarUrl,
            sourceEmoteSetId = origin.emoteSetId,
            trayIconPath = existing?.collection?.trayIconPath,
            totalCount = itemsToSave.size,
            animatedCount = itemsToSave.count { it.isAnimated },
            staticCount = itemsToSave.count { !it.isAnimated },
            status = CollectionStatus.PROCESSING,
            isAnimatedPack = packHasAnyAnimated
        )

        collectionDao.upsert(entity)
        val keepIds = itemsToSave.map { it.id }.toSet()
        val removedIds = existing?.items?.filter { it.id !in keepIds }?.map { it.id }.orEmpty()
        if (removedIds.isNotEmpty()) {
            itemDao.deleteByIds(removedIds)
        }
        itemDao.insertAll(itemsToSave)
        collectionId
    }

    /**
     * Re-queues failed or needs-trim items for retry without touching completed stickers.
     */
    suspend fun resetItemsForRetry(collectionId: String, includeNeedsTrim: Boolean = false) = withContext(Dispatchers.IO) {
        val details = collectionDao.getCollectionWithDetails(collectionId) ?: return@withContext
        val updated = details.items.map { item ->
            val shouldReset = item.status == ItemConversionStatus.FAILED ||
                item.status == ItemConversionStatus.CANCELLED ||
                (includeNeedsTrim && item.status == ItemConversionStatus.NEEDS_TRIM)
            if (shouldReset) {
                item.copy(status = ItemConversionStatus.PENDING, errorMessage = null)
            } else {
                item
            }
        }
        itemDao.insertAll(updated)
        collectionDao.upsert(
            details.collection.copy(
                status = CollectionStatus.PROCESSING,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun finalizeCollectionBuild(collectionId: String, cancelled: Boolean) = withContext(Dispatchers.IO) {
        val details = collectionDao.getCollectionWithDetails(collectionId) ?: return@withContext
        val completed = details.items.filter { it.status == ItemConversionStatus.COMPLETED }

        if (completed.isEmpty() && cancelled) {
            collectionDao.upsert(
                details.collection.copy(
                    status = CollectionStatus.CANCELLED,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return@withContext
        }

        val buildResult = WhatsAppPackBuilder.buildSinglePack(
            storage = storage,
            collectionId = collectionId,
            collectionName = details.collection.name,
            completedItems = completed,
            existingPacks = details.subpacks
        )

        subpackDao.deleteForCollection(collectionId)
        val builtPack = buildResult.pack
        if (builtPack != null) {
            subpackDao.insertAll(listOf(builtPack))
        }
        if (buildResult.updatedItems.isNotEmpty()) {
            itemDao.insertAll(buildResult.updatedItems)
        }

        val activeStickerPaths = completed.mapNotNull { it.finalWebpPath }.toSet()
        val activeTrayPaths = builtPack?.trayIconPath?.let(::setOf) ?: emptySet()
        storage.cleanupUnusedStickers(collectionId, activeStickerPaths, activeTrayPaths)

        val hasWarnings = details.items.any {
            it.status == ItemConversionStatus.FAILED || it.status == ItemConversionStatus.NEEDS_TRIM
        }
        val finalStatus = when {
            cancelled -> CollectionStatus.CANCELLED
            hasWarnings -> CollectionStatus.PARTIAL_ERROR
            builtPack == null || !builtPack.isWhatsAppReady -> CollectionStatus.DRAFT_TOO_FEW
            else -> CollectionStatus.READY
        }

        collectionDao.upsert(
            details.collection.copy(
                updatedAt = System.currentTimeMillis(),
                trayIconPath = builtPack?.trayIconPath,
                totalCount = completed.size,
                animatedCount = completed.count { it.isAnimated },
                staticCount = completed.count { !it.isAnimated },
                status = finalStatus,
                isAnimatedPack = buildResult.isAnimatedPack
            )
        )
        StickerContentProvider.notifyPackChanged(context, collectionId)
    }

    private fun sanitizeStickerFileName(emoteId: String): String =
        LocalStorageManager.sanitizeStickerFileName(emoteId)

    suspend fun updateItem(item: StickerItemEntity) {
        itemDao.update(item)
    }

    fun hasPendingEditSnapshot(collectionId: String): Boolean =
        activeEditIds.contains(collectionId)

    fun commitCreationSummary(collectionId: String) {
        editSnapshots.remove(collectionId)
        activeEditIds.remove(collectionId)
    }

    /**
     * Discards a newly created pack/draft OR reverts an active edit back to the pre-edit state.
     * Shared cache files remain untouched for reuse and for other collections.
     */
    suspend fun discardCreationOrRevertEdit(collectionId: String) = withContext(Dispatchers.IO) {
        val snapshot = editSnapshots.remove(collectionId)
        val wasEdit = activeEditIds.remove(collectionId)
        if (wasEdit && snapshot != null) {
            collectionDao.upsert(snapshot.collection)
            itemDao.deleteForCollection(collectionId)
            itemDao.insertAll(snapshot.items)
            subpackDao.deleteForCollection(collectionId)
            subpackDao.insertAll(snapshot.subpacks)
        } else {
            collectionDao.deleteById(collectionId)
            storage.deleteCollectionFiles(collectionId)
        }
    }

    suspend fun deleteCollection(collectionId: String) = withContext(Dispatchers.IO) {
        editSnapshots.remove(collectionId)
        activeEditIds.remove(collectionId)
        collectionDao.deleteById(collectionId)
        storage.deleteCollectionFiles(collectionId)
        // Never delete shared cache files referenced by other collections; unreferenced cache
        // remains reusable unless the user explicitly triggers Clear Cache in Settings.
    }

    suspend fun clearProtectedCache() = withContext(Dispatchers.IO) {
        val protectedPaths = itemDao.getAllReferencedWebpPaths().toMutableSet()
        storage.clearAppCache(protectedPaths)

        // Prune emote_cache rows whose files are completely gone from disk
        val deadKeys = emoteCacheDao.getAllCacheEntries().filter { entry ->
            val hasOrig = !entry.originalFilePath.isNullOrBlank() && File(entry.originalFilePath).exists()
            val hasStatic = !entry.staticWebpPath.isNullOrBlank() && File(entry.staticWebpPath).exists()
            val hasAnim = !entry.animatedWebpPath.isNullOrBlank() && File(entry.animatedWebpPath).exists()
            val hasFake = !entry.fakeVidWebpPath.isNullOrBlank() && File(entry.fakeVidWebpPath).exists()
            !hasOrig && !hasStatic && !hasAnim && !hasFake
        }.map { it.cacheKey }
        if (deadKeys.isNotEmpty()) {
            emoteCacheDao.deleteByKeys(deadKeys)
        }
    }

    suspend fun exportBackupToUri(uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = collectionDao.getAllCollectionsWithDetails()
        storage.exportBackup(uri, all)
    }

    suspend fun importBackupFromUri(uri: Uri): Int = withContext(Dispatchers.IO) {
        val imported = storage.importBackup(uri)
        for (entry in imported) {
            collectionDao.upsert(entry.collection)
            itemDao.deleteForCollection(entry.collection.id)
            itemDao.insertAll(entry.items)
            subpackDao.deleteForCollection(entry.collection.id)
            subpackDao.insertAll(entry.subpacks)
        }
        imported.size
    }
}
