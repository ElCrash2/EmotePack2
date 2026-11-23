package com.example.whatsapp

import android.graphics.BitmapFactory
import com.example.AppConfig
import com.example.conversion.StickerConverter
import com.example.data.local.ConversionModeType
import com.example.data.local.ItemConversionStatus
import com.example.data.local.LocalStorageManager
import com.example.data.local.StickerItemEntity
import com.example.data.local.WhatsAppSubpackEntity
import com.example.data.model.SevenTvEmote
import java.io.File

enum class StickerPackFormat {
    STATIC,
    ANIMATED,
    MIXED_FAKE_VID
}

data class SinglePackBuildResult(
    val pack: WhatsAppSubpackEntity?,
    val updatedItems: List<StickerItemEntity>,
    val isAnimatedPack: Boolean
)

object WhatsAppPackBuilder {

    const val MIN_STICKERS_FOR_WHATSAPP = AppConfig.MIN_STICKERS_FOR_WHATSAPP
    const val MAX_STICKERS_PER_COLLECTION = AppConfig.MAX_STICKERS_PER_COLLECTION

    fun determinePackFormat(emotes: List<SevenTvEmote>): StickerPackFormat {
        val hasAnimated = emotes.any { it.animated }
        val hasStatic = emotes.any { !it.animated }
        return when {
            hasAnimated && hasStatic -> StickerPackFormat.MIXED_FAKE_VID
            hasAnimated -> StickerPackFormat.ANIMATED
            else -> StickerPackFormat.STATIC
        }
    }

    fun targetConversionMode(isEmoteAnimated: Boolean, packHasAnyAnimated: Boolean): String {
        return when {
            !packHasAnyAnimated -> ConversionModeType.STATIC
            isEmoteAnimated -> ConversionModeType.ANIMATED
            else -> ConversionModeType.FAKE_VID
        }
    }

    /**
     * Builds ONE single WhatsApp sticker pack (1..30 items; ready for WhatsApp when 3..30)
     * and generates a single 96x96 PNG tray icon <= 50 KB.
     * Mixed collections are marked animated (`isAnimated = true`) because static items
     * have already been converted via `fake-vid` into real motionless animated WebPs.
     */
    fun buildSinglePack(
        storage: LocalStorageManager,
        collectionId: String,
        collectionName: String,
        completedItems: List<StickerItemEntity>,
        existingPacks: List<WhatsAppSubpackEntity>
    ): SinglePackBuildResult {
        val candidateItems = completedItems
            .filter {
                it.status == ItemConversionStatus.COMPLETED &&
                    !it.finalWebpPath.isNullOrBlank() &&
                    File(it.finalWebpPath).exists()
            }
            .sortedBy { it.sortOrder }
            .take(MAX_STICKERS_PER_COLLECTION)

        if (candidateItems.isEmpty()) {
            return SinglePackBuildResult(pack = null, updatedItems = emptyList(), isAnimatedPack = false)
        }

        val isAnimatedPack = candidateItems.any {
            it.isAnimated ||
                it.conversionMode == ConversionModeType.ANIMATED ||
                it.conversionMode == ConversionModeType.ANIMATED_LOWER_QUALITY ||
                it.conversionMode == ConversionModeType.FAKE_VID
        }

        // Verify every sticker on disk matches the pack's format (static vs animated)
        val validItems = candidateItems.filter { item ->
            val file = item.finalWebpPath?.let(::File) ?: return@filter false
            val allowOver10s = item.conversionMode == ConversionModeType.ANIMATED_LOWER_QUALITY
            StickerConverter.validateExistingWebp(file, isAnimatedPack, allowOver10s = allowOver10s) != null
        }

        val itemsForPack = if (validItems.isNotEmpty()) validItems else candidateItems

        val packId = collectionId
        val nextVersion = (existingPacks.maxOfOrNull { it.imageDataVersion } ?: 0) + 1
        val trayFile = storage.getTrayFile(collectionId, packId)

        val firstFile = itemsForPack.firstOrNull()?.finalWebpPath?.let(::File)
        if (firstFile != null && firstFile.exists()) {
            StickerConverter.generateTrayIcon(firstFile)?.let { trayBytes ->
                trayFile.writeBytes(trayBytes)
            }
        }

        val trayBounds = if (trayFile.exists()) {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { opts ->
                BitmapFactory.decodeFile(trayFile.absolutePath, opts)
            }
        } else null
        val trayValid = trayFile.exists() &&
            trayFile.length() in 1L..StickerConverter.MAX_TRAY_BYTES.toLong() &&
            trayBounds != null &&
            trayBounds.outWidth == StickerConverter.TRAY_SIZE_PX &&
            trayBounds.outHeight == StickerConverter.TRAY_SIZE_PX

        val allStickersValid = validItems.size == candidateItems.size
        val isReady = allStickersValid &&
            validItems.size in MIN_STICKERS_FOR_WHATSAPP..MAX_STICKERS_PER_COLLECTION &&
            trayValid

        val pack = WhatsAppSubpackEntity(
            id = packId,
            collectionId = collectionId,
            displayName = collectionName.trim().take(128).ifBlank { "EmotePack" },
            isAnimated = isAnimatedPack,
            trayIconPath = trayFile.absolutePath,
            imageDataVersion = nextVersion,
            stickerCount = itemsForPack.size,
            isWhatsAppReady = isReady,
            addedToWhatsApp = false
        )

        val updatedItems = itemsForPack.map { it.copy(subpackId = packId) }
        return SinglePackBuildResult(
            pack = pack,
            updatedItems = updatedItems,
            isAnimatedPack = isAnimatedPack
        )
    }
}
