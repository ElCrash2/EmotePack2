package com.example.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

object CollectionStatus {
    const val PROCESSING = "PROCESSING"
    const val READY = "READY"
    const val DRAFT_TOO_FEW = "DRAFT_TOO_FEW"
    const val PARTIAL_ERROR = "PARTIAL_ERROR"
    const val CANCELLED = "CANCELLED"
}

object ItemConversionStatus {
    const val PENDING = "PENDING"
    const val DOWNLOADING = "DOWNLOADING"
    const val CONVERTING = "CONVERTING"
    const val VALIDATING = "VALIDATING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val NEEDS_TRIM = "NEEDS_TRIM"
    const val CANCELLED = "CANCELLED"
}

object ConversionModeType {
    const val STATIC = "STATIC"
    const val ANIMATED = "ANIMATED"
    const val FAKE_VID = "FAKE_VID"
}

@Entity(tableName = "sticker_collections")
data class StickerCollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val searchType: String, // "STREAMER" or "EMOTE"
    val searchQuery: String,
    val streamerId: String?,
    val streamerName: String?,
    val streamerUsername: String?,
    val streamerAvatarUrl: String?,
    val sourceEmoteSetId: String?,
    val trayIconPath: String?,
    val totalCount: Int,
    val animatedCount: Int,
    val staticCount: Int,
    val status: String,
    val isAnimatedPack: Boolean = false
)

@Entity(
    tableName = "sticker_items",
    foreignKeys = [
        ForeignKey(
            entity = StickerCollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("collectionId"), Index("subpackId"), Index("emoteId")]
)
data class StickerItemEntity(
    @PrimaryKey val id: String,
    val collectionId: String,
    val emoteId: String,
    val emoteName: String,
    val sourceSetId: String?,
    val sourceSetName: String?,
    val isAnimated: Boolean, // Original 7TV emote animated flag
    val previewUrl: String,
    val highResUrl: String,
    val fallbackUrlsCsv: String,
    val finalWebpPath: String?,
    val stickerFileName: String?, // e.g. "sticker_<emoteId>.webp"
    val fileSizeBytes: Long,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val durationMs: Int,
    val hasAlpha: Boolean,
    val status: String,
    val errorMessage: String?,
    val subpackId: String?,
    val sortOrder: Int,
    val versionUpdatedAt: Long = 0L,
    val versionKey: String = "",
    val conversionMode: String = ConversionModeType.STATIC
)

@Entity(
    tableName = "whatsapp_subpacks",
    foreignKeys = [
        ForeignKey(
            entity = StickerCollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("collectionId")]
)
data class WhatsAppSubpackEntity(
    @PrimaryKey val id: String,
    val collectionId: String,
    val displayName: String,
    val isAnimated: Boolean,
    val trayIconPath: String,
    val imageDataVersion: Int,
    val stickerCount: Int,
    val isWhatsAppReady: Boolean, // true when 3..30 stickers
    val addedToWhatsApp: Boolean
)

/**
 * Global shared emote cache across all collections.
 * Stores original downloads and conversion variants (STATIC, ANIMATED, FAKE_VID) separately.
 */
@Entity(
    tableName = "emote_cache",
    indices = [Index("emoteId")]
)
data class EmoteCacheEntity(
    @PrimaryKey val cacheKey: String, // "${emoteId}_${versionKey}"
    val emoteId: String,
    val versionKey: String,
    val updatedAt: Long,
    val sourceUrl: String,
    val originalFormat: String,
    val originalFilePath: String?,
    val staticWebpPath: String?,
    val animatedWebpPath: String?,
    val fakeVidWebpPath: String?,
    val converterVersion: Int,
    val createdAt: Long,
    val lastUsedAt: Long
)

data class CollectionWithDetails(
    @Embedded val collection: StickerCollectionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "collectionId"
    )
    val items: List<StickerItemEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "collectionId"
    )
    val subpacks: List<WhatsAppSubpackEntity>
)
