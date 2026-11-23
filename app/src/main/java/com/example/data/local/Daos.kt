package com.example.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM sticker_collections ORDER BY updatedAt DESC")
    fun observeAllCollections(): Flow<List<StickerCollectionEntity>>

    @Query("SELECT * FROM sticker_collections WHERE status = 'PROCESSING' ORDER BY updatedAt DESC LIMIT 1")
    fun observeActiveProcessingCollection(): Flow<StickerCollectionEntity?>

    @Transaction
    @Query("SELECT * FROM sticker_collections WHERE id = :collectionId")
    fun observeCollectionWithDetails(collectionId: String): Flow<CollectionWithDetails?>

    @Transaction
    @Query("SELECT * FROM sticker_collections WHERE id = :collectionId")
    suspend fun getCollectionWithDetails(collectionId: String): CollectionWithDetails?

    @Transaction
    @Query("SELECT * FROM sticker_collections ORDER BY createdAt ASC")
    suspend fun getAllCollectionsWithDetails(): List<CollectionWithDetails>

    @Upsert
    suspend fun upsert(collection: StickerCollectionEntity)

    @Query("DELETE FROM sticker_collections WHERE id = :collectionId")
    suspend fun deleteById(collectionId: String)
}

@Dao
interface StickerItemDao {
    @Query("SELECT * FROM sticker_items WHERE collectionId = :collectionId ORDER BY sortOrder ASC")
    fun observeItemsForCollection(collectionId: String): Flow<List<StickerItemEntity>>

    @Query("SELECT * FROM sticker_items WHERE collectionId = :collectionId ORDER BY sortOrder ASC")
    suspend fun getItemsForCollection(collectionId: String): List<StickerItemEntity>

    @Query("SELECT * FROM sticker_items WHERE subpackId = :subpackId AND status = 'COMPLETED' ORDER BY sortOrder ASC LIMIT 30")
    suspend fun getCompletedItemsForSubpack(subpackId: String): List<StickerItemEntity>

    @Query("SELECT * FROM sticker_items WHERE collectionId = :collectionId AND stickerFileName = :fileName LIMIT 1")
    suspend fun getItemByCollectionAndFileName(collectionId: String, fileName: String): StickerItemEntity?

    @Query("SELECT finalWebpPath FROM sticker_items WHERE finalWebpPath IS NOT NULL")
    suspend fun getAllReferencedWebpPaths(): List<String>

    @Query("SELECT DISTINCT emoteId FROM sticker_items")
    suspend fun getAllReferencedEmoteIds(): List<String>

    @Upsert
    suspend fun insertAll(items: List<StickerItemEntity>)

    @Update
    suspend fun update(item: StickerItemEntity)

    @Query("DELETE FROM sticker_items WHERE collectionId = :collectionId")
    suspend fun deleteForCollection(collectionId: String)

    @Query("DELETE FROM sticker_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface SubpackDao {
    @Query("SELECT * FROM whatsapp_subpacks WHERE collectionId = :collectionId ORDER BY isAnimated DESC, displayName ASC")
    fun observeSubpacksForCollection(collectionId: String): Flow<List<WhatsAppSubpackEntity>>

    @Query("SELECT * FROM whatsapp_subpacks WHERE isWhatsAppReady = 1")
    suspend fun getAllReadySubpacks(): List<WhatsAppSubpackEntity>

    @Query("SELECT * FROM whatsapp_subpacks WHERE id = :subpackId")
    suspend fun getSubpackById(subpackId: String): WhatsAppSubpackEntity?

    @Upsert
    suspend fun insertAll(subpacks: List<WhatsAppSubpackEntity>)

    @Query("UPDATE whatsapp_subpacks SET addedToWhatsApp = :added WHERE id = :subpackId")
    suspend fun markAddedToWhatsApp(subpackId: String, added: Boolean)

    @Query("DELETE FROM whatsapp_subpacks WHERE collectionId = :collectionId")
    suspend fun deleteForCollection(collectionId: String)
}

@Dao
interface EmoteCacheDao {
    @Query("SELECT * FROM emote_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getByCacheKey(cacheKey: String): EmoteCacheEntity?

    @Query("SELECT * FROM emote_cache WHERE emoteId = :emoteId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestForEmote(emoteId: String): EmoteCacheEntity?

    @Query("SELECT * FROM emote_cache")
    suspend fun getAllCacheEntries(): List<EmoteCacheEntity>

    @Upsert
    suspend fun upsert(entry: EmoteCacheEntity)

    @Query("UPDATE emote_cache SET lastUsedAt = :timestamp WHERE cacheKey = :cacheKey")
    suspend fun touch(cacheKey: String, timestamp: Long)

    @Query("DELETE FROM emote_cache WHERE cacheKey IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)
}
