package com.example.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.AppConfig
import com.example.EmotePackApplication
import com.example.R
import com.example.conversion.ConversionOutcome
import com.example.conversion.GifFrameDecoder
import com.example.conversion.Over10sHandling
import com.example.conversion.StickerConversionMode
import com.example.conversion.StickerConverter
import com.example.conversion.WebPContainer
import com.example.data.local.CollectionRepository
import com.example.data.local.ConversionModeType
import com.example.data.local.EmoteCacheEntity
import com.example.data.local.ItemConversionStatus
import com.example.data.local.LocalStorageManager
import com.example.data.local.StickerItemEntity
import com.example.whatsapp.WhatsAppPackBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class PackBuildWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "PackBuildWorker"
        const val KEY_COLLECTION_ID = "collection_id"
        const val KEY_TRIM_ALL_OVER_10S = "trim_all_over_10s"
        const val KEY_TRIM_ITEM_IDS = "trim_item_ids"
        const val KEY_LOWER_QUALITY_ALL_OVER_10S = "lower_quality_all_over_10s"
        const val KEY_LOWER_QUALITY_ITEM_IDS = "lower_quality_item_ids"
        const val CHANNEL_ID = "emotepack_downloads"
        const val NOTIFICATION_ID = 4102
        private const val DOWNLOAD_TIMEOUT_MS = 20_000L
        private const val CONVERSION_TIMEOUT_MS = 90_000L
        private const val MAX_CONCURRENT_ITEMS = 3
        private const val MAX_CONCURRENT_ANIMATIONS = 1
    }

    override suspend fun doWork(): Result {
        val collectionId = inputData.getString(KEY_COLLECTION_ID) ?: return Result.failure()
        val trimAll = inputData.getBoolean(KEY_TRIM_ALL_OVER_10S, false)
        val trimItemIds = inputData.getStringArray(KEY_TRIM_ITEM_IDS)?.toSet() ?: emptySet()
        val lowerQualityAll = inputData.getBoolean(KEY_LOWER_QUALITY_ALL_OVER_10S, false)
        val lowerQualityItemIds = inputData.getStringArray(KEY_LOWER_QUALITY_ITEM_IDS)?.toSet() ?: emptySet()

        val app = applicationContext as EmotePackApplication
        val repo = app.collectionRepository
        val httpClient = app.okHttpClient

        val details = repo.getCollectionWithDetails(collectionId) ?: return Result.failure()
        val collectionName = details.collection.name
        val total = details.items.size
        val packHasAnyAnimated = details.items.any { it.isAnimated }

        try {
            setForeground(createForegroundInfo(collectionName, 0, total))
        } catch (_: Exception) {
            // Foreground notification may be skipped if POST_NOTIFICATIONS is denied on API 33+
        }

        val processedCount = AtomicInteger(
            details.items.count { it.status == ItemConversionStatus.COMPLETED }
        )
        val itemSemaphore = Semaphore(MAX_CONCURRENT_ITEMS)
        val animSemaphore = Semaphore(MAX_CONCURRENT_ANIMATIONS)
        var wasCancelled = false

        try {
            coroutineScope {
                details.items.map { item ->
                    async(Dispatchers.IO) {
                        itemSemaphore.withPermit {
                            if (isStopped || !isActive) {
                                wasCancelled = true
                                return@withPermit
                            }
                            processSingleItemSafely(
                                repo = repo,
                                httpClient = httpClient,
                                item = item,
                                packHasAnyAnimated = packHasAnyAnimated,
                                trimAll = trimAll,
                                trimItemIds = trimItemIds,
                                lowerQualityAll = lowerQualityAll,
                                lowerQualityItemIds = lowerQualityItemIds,
                                animSemaphore = animSemaphore,
                                collectionName = collectionName,
                                processedCount = processedCount,
                                total = total
                            )
                        }
                    }
                }.awaitAll()
            }
        } catch (_: CancellationException) {
            wasCancelled = true
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected worker error for collection $collectionId", t)
        }

        if (wasCancelled || isStopped) {
            val latest = repo.getCollectionWithDetails(collectionId)
            latest?.items?.filter {
                it.status == ItemConversionStatus.PENDING ||
                    it.status == ItemConversionStatus.DOWNLOADING ||
                    it.status == ItemConversionStatus.CONVERTING ||
                    it.status == ItemConversionStatus.VALIDATING
            }?.forEach { pending ->
                repo.updateItem(pending.copy(status = ItemConversionStatus.CANCELLED))
            }
            repo.finalizeCollectionBuild(collectionId, cancelled = true)
            return Result.success()
        }

        repo.finalizeCollectionBuild(collectionId, cancelled = false)
        return Result.success()
    }

    private suspend fun processSingleItemSafely(
        repo: CollectionRepository,
        httpClient: okhttp3.OkHttpClient,
        item: StickerItemEntity,
        packHasAnyAnimated: Boolean,
        trimAll: Boolean,
        trimItemIds: Set<String>,
        lowerQualityAll: Boolean,
        lowerQualityItemIds: Set<String>,
        animSemaphore: Semaphore,
        collectionName: String,
        processedCount: AtomicInteger,
        total: Int
    ) {
        val targetModeStr = WhatsAppPackBuilder.targetConversionMode(
            isEmoteAnimated = item.isAnimated,
            packHasAnyAnimated = packHasAnyAnimated
        )
        val targetMode = when (targetModeStr) {
            ConversionModeType.ANIMATED -> StickerConversionMode.ANIMATED
            ConversionModeType.FAKE_VID -> StickerConversionMode.FAKE_VID
            else -> StickerConversionMode.STATIC
        }
        val expectAnimatedOutput = targetMode != StickerConversionMode.STATIC
        val over10sHandling = when {
            lowerQualityAll || item.id in lowerQualityItemIds -> Over10sHandling.LOWER_QUALITY
            trimAll || item.id in trimItemIds -> Over10sHandling.TRIM_10S
            else -> Over10sHandling.ASK_USER
        }

        // Fast reuse only if already COMPLETED in matching target mode and valid on disk
        val existingPath = item.finalWebpPath
        val existingMatchesMode = item.conversionMode == targetModeStr ||
            (expectAnimatedOutput && item.conversionMode == ConversionModeType.ANIMATED_LOWER_QUALITY)
        if (item.status == ItemConversionStatus.COMPLETED &&
            existingMatchesMode &&
            !existingPath.isNullOrBlank()
        ) {
            val file = File(existingPath)
            if (StickerConverter.validateExistingWebp(file, expectAnimatedOutput, allowOver10s = false) != null) {
                return
            }
        }

        if (item.status != ItemConversionStatus.PENDING &&
            item.status != ItemConversionStatus.DOWNLOADING &&
            item.status != ItemConversionStatus.CONVERTING &&
            item.status != ItemConversionStatus.VALIDATING &&
            !(item.status == ItemConversionStatus.NEEDS_TRIM && over10sHandling != Over10sHandling.ASK_USER)
        ) {
            return
        }

        try {
            val effectiveVersionKey = item.versionKey.ifBlank {
                "${item.versionUpdatedAt}_${item.highResUrl.hashCode()}"
            }
            val cacheKey = "${item.emoteId}_$effectiveVersionKey"
            val cacheEntry = repo.getEmoteCache(cacheKey)
                ?: if (item.versionUpdatedAt == 0L) repo.getLatestEmoteCache(item.emoteId) else null

            // Step A: Reuse validated shared-cache variant ONLY if original was not >10s
            // (so >10s emotes always let the user choose Trim vs Lower Quality/FPS per pack).
            val cachedOriginalOver10s = expectAnimatedOutput &&
                StickerConverter.isOriginalFileOver10s(cacheEntry?.originalFilePath?.let(::File))
            if (over10sHandling == Over10sHandling.ASK_USER &&
                !cachedOriginalOver10s &&
                cacheEntry != null &&
                cacheEntry.converterVersion == AppConfig.CONVERTER_VERSION
            ) {
                val cachedVariantPath = when (targetMode) {
                    StickerConversionMode.STATIC -> cacheEntry.staticWebpPath
                    StickerConversionMode.ANIMATED -> cacheEntry.animatedWebpPath
                    StickerConversionMode.FAKE_VID -> cacheEntry.fakeVidWebpPath
                }
                val variantFile = cachedVariantPath?.let(::File)
                val info = if (variantFile != null && variantFile.exists()) {
                    StickerConverter.validateExistingWebp(variantFile, expectAnimatedOutput, allowOver10s = false)
                } else null

                if (info != null && variantFile != null) {
                    repo.touchEmoteCache(cacheEntry.cacheKey)
                    repo.updateItem(
                        item.copy(
                            finalWebpPath = variantFile.absolutePath,
                            stickerFileName = sanitizeStickerFileName(item.emoteId),
                            fileSizeBytes = variantFile.length(),
                            width = info.width,
                            height = info.height,
                            frameCount = info.frameCount,
                            durationMs = info.totalDurationMs,
                            hasAlpha = info.hasAlpha,
                            status = ItemConversionStatus.COMPLETED,
                            errorMessage = null,
                            versionKey = effectiveVersionKey,
                            conversionMode = targetModeStr
                        )
                    )
                    updateNotificationSafe(collectionName, processedCount.incrementAndGet(), total)
                    return
                }
            }

            // Step B: Obtain source bytes from local original cache (or offline static fallback),
            // or download from 7TV if not cached locally
            var sourceBytes: ByteArray? = readLocalOriginalOrFallback(cacheEntry, item)
            var originalFile: File? = cacheEntry?.originalFilePath?.let(::File)?.takeIf { it.exists() }

            if (sourceBytes == null) {
                repo.updateItem(item.copy(status = ItemConversionStatus.DOWNLOADING, errorMessage = null))
                val urlsToTry = listOf(item.highResUrl) +
                    item.fallbackUrlsCsv.split("|").filter { it.isNotBlank() }

                val downloaded = withTimeout(DOWNLOAD_TIMEOUT_MS) {
                    downloadFirstAvailable(httpClient, urlsToTry)
                }
                if (downloaded == null) {
                    repo.updateItem(
                        item.copy(
                            status = ItemConversionStatus.FAILED,
                            errorMessage = applicationContext.getString(R.string.error_network)
                        )
                    )
                    updateNotificationSafe(collectionName, processedCount.incrementAndGet(), total)
                    return
                }
                sourceBytes = downloaded.bytes
                val detectedExt = detectExtensionFromMagic(downloaded.bytes, downloaded.extension)
                val savedOrig = repo.storage.getSharedOriginalFile(
                    emoteId = item.emoteId,
                    versionKey = effectiveVersionKey,
                    ext = detectedExt
                )
                savedOrig.writeBytes(downloaded.bytes)
                originalFile = savedOrig
            }

            if (isStopped) {
                throw CancellationException("Worker stopped")
            }

            // Step C: Convert off the main thread on Dispatchers.Default, limiting simultaneous animations
            // Timeout starts AFTER acquiring animSemaphore so queued animations never starve each other.
            val outcome = if (expectAnimatedOutput) {
                animSemaphore.withPermit {
                    repo.updateItem(item.copy(status = ItemConversionStatus.CONVERTING))
                    withTimeout(CONVERSION_TIMEOUT_MS) {
                        withContext(Dispatchers.Default) {
                            StickerConverter.convert(
                                inputBytes = sourceBytes,
                                mode = targetMode,
                                over10sHandling = over10sHandling,
                                shouldAbort = { isStopped || !isActive }
                            )
                        }
                    }
                }
            } else {
                repo.updateItem(item.copy(status = ItemConversionStatus.CONVERTING))
                withTimeout(CONVERSION_TIMEOUT_MS) {
                    withContext(Dispatchers.Default) {
                        StickerConverter.convert(
                            inputBytes = sourceBytes,
                            mode = targetMode,
                            over10sHandling = over10sHandling,
                            shouldAbort = { isStopped || !isActive }
                        )
                    }
                }
            }

                when (outcome) {
                    is ConversionOutcome.Success -> {
                        repo.updateItem(item.copy(status = ItemConversionStatus.VALIDATING))
                        val finalModeName = if (over10sHandling == Over10sHandling.LOWER_QUALITY && outcome.sourceWasOver10s) {
                            ConversionModeType.ANIMATED_LOWER_QUALITY
                        } else {
                            outcome.conversionMode.name
                        }
                        val variantFile = if (outcome.sourceWasOver10s) {
                            repo.storage.getStickerFile(
                                collectionId = item.collectionId,
                                fileName = sanitizeStickerFileName(item.emoteId)
                            )
                        } else {
                            repo.storage.getSharedVariantFile(
                                emoteId = item.emoteId,
                                versionKey = effectiveVersionKey,
                                mode = finalModeName
                            )
                        }
                        variantFile.writeBytes(outcome.webpBytes)

                        updateSharedCacheEntry(
                            repo = repo,
                            existing = cacheEntry,
                            cacheKey = cacheKey,
                            item = item,
                            versionKey = effectiveVersionKey,
                            originalFile = originalFile,
                            mode = outcome.conversionMode,
                            variantFile = variantFile,
                            skipAnimatedVariantCache = outcome.sourceWasOver10s
                        )

                        repo.updateItem(
                            item.copy(
                                finalWebpPath = variantFile.absolutePath,
                                stickerFileName = sanitizeStickerFileName(item.emoteId),
                                fileSizeBytes = outcome.webpBytes.size.toLong(),
                                width = outcome.inspection.width,
                                height = outcome.inspection.height,
                                frameCount = outcome.inspection.frameCount,
                                durationMs = outcome.inspection.totalDurationMs,
                                hasAlpha = outcome.inspection.hasAlpha,
                                status = ItemConversionStatus.COMPLETED,
                                errorMessage = null,
                                versionKey = effectiveVersionKey,
                                conversionMode = finalModeName
                            )
                        )
                    }
                    is ConversionOutcome.NeedsTrimConfirmation -> {
                        if (originalFile != null) {
                            updateSharedCacheEntry(
                                repo = repo,
                                existing = cacheEntry,
                                cacheKey = cacheKey,
                                item = item,
                                versionKey = effectiveVersionKey,
                                originalFile = originalFile,
                                mode = targetMode,
                                variantFile = originalFile,
                                skipAnimatedVariantCache = true
                            )
                        }
                        repo.updateItem(
                            item.copy(
                                durationMs = outcome.totalDurationMs,
                                status = ItemConversionStatus.NEEDS_TRIM,
                                errorMessage = null
                            )
                        )
                    }
                    is ConversionOutcome.Failure -> {
                        Log.w(TAG, "Conversion failed for ${item.emoteName} (${item.emoteId}): ${outcome.reason}")
                        repo.updateItem(
                            item.copy(
                                status = ItemConversionStatus.FAILED,
                                errorMessage = outcome.reason
                            )
                        )
                    }
                }

                updateNotificationSafe(collectionName, processedCount.incrementAndGet(), total)
        } catch (ce: CancellationException) {
            if (ce is TimeoutCancellationException) {
                Log.e(TAG, "Conversion timed out for ${item.emoteName} (${item.emoteId})")
                repo.updateItem(
                    item.copy(
                        status = ItemConversionStatus.FAILED,
                        errorMessage = applicationContext.getString(R.string.error_conversion_timeout)
                    )
                )
                updateNotificationSafe(collectionName, processedCount.incrementAndGet(), total)
            } else {
                throw ce
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Conversion crashed for ${item.emoteName} (${item.emoteId})", t)
            repo.updateItem(
                item.copy(
                    status = ItemConversionStatus.FAILED,
                    errorMessage = t.message?.take(120)
                        ?: applicationContext.getString(R.string.error_conversion_failed)
                )
            )
            updateNotificationSafe(collectionName, processedCount.incrementAndGet(), total)
        }
    }

    private fun sanitizeStickerFileName(emoteId: String): String =
        LocalStorageManager.sanitizeStickerFileName(emoteId)

    private fun detectExtensionFromMagic(bytes: ByteArray, fallbackExt: String): String = when {
        GifFrameDecoder.isGif(bytes) -> "gif"
        WebPContainer.isWebP(bytes) -> "webp"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() -> "avif"
        else -> fallbackExt
    }

    private fun readLocalOriginalOrFallback(
        cacheEntry: EmoteCacheEntity?,
        item: StickerItemEntity
    ): ByteArray? {
        val origFile = cacheEntry?.originalFilePath?.let(::File)
        if (origFile != null && origFile.exists() && origFile.length() > 0L) {
            return origFile.readBytes()
        }
        if (!item.isAnimated && !item.finalWebpPath.isNullOrBlank()) {
            val prevFile = File(item.finalWebpPath)
            if (prevFile.exists() && prevFile.length() > 0L) {
                return prevFile.readBytes()
            }
        }
        return null
    }

    private suspend fun updateSharedCacheEntry(
        repo: CollectionRepository,
        existing: EmoteCacheEntity?,
        cacheKey: String,
        item: StickerItemEntity,
        versionKey: String,
        originalFile: File?,
        mode: StickerConversionMode,
        variantFile: File,
        skipAnimatedVariantCache: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val base = existing ?: EmoteCacheEntity(
            cacheKey = cacheKey,
            emoteId = item.emoteId,
            versionKey = versionKey,
            updatedAt = item.versionUpdatedAt,
            sourceUrl = item.highResUrl,
            originalFormat = originalFile?.extension.orEmpty(),
            originalFilePath = originalFile?.absolutePath,
            staticWebpPath = null,
            animatedWebpPath = null,
            fakeVidWebpPath = null,
            converterVersion = AppConfig.CONVERTER_VERSION,
            createdAt = now,
            lastUsedAt = now
        )
        val updated = if (skipAnimatedVariantCache) {
            base.copy(
                originalFilePath = originalFile?.absolutePath ?: base.originalFilePath,
                animatedWebpPath = null,
                converterVersion = AppConfig.CONVERTER_VERSION,
                lastUsedAt = now
            )
        } else {
            when (mode) {
                StickerConversionMode.STATIC -> base.copy(
                    originalFilePath = originalFile?.absolutePath ?: base.originalFilePath,
                    staticWebpPath = variantFile.absolutePath,
                    converterVersion = AppConfig.CONVERTER_VERSION,
                    lastUsedAt = now
                )
                StickerConversionMode.ANIMATED -> base.copy(
                    originalFilePath = originalFile?.absolutePath ?: base.originalFilePath,
                    animatedWebpPath = variantFile.absolutePath,
                    converterVersion = AppConfig.CONVERTER_VERSION,
                    lastUsedAt = now
                )
                StickerConversionMode.FAKE_VID -> base.copy(
                    originalFilePath = originalFile?.absolutePath ?: base.originalFilePath,
                    fakeVidWebpPath = variantFile.absolutePath,
                    converterVersion = AppConfig.CONVERTER_VERSION,
                    lastUsedAt = now
                )
            }
        }
        repo.upsertEmoteCache(updated)
    }

    private data class DownloadedPayload(val bytes: ByteArray, val extension: String)

    private fun downloadFirstAvailable(client: okhttp3.OkHttpClient, urls: List<String>): DownloadedPayload? {
        for (url in urls.distinct()) {
            if (isStopped) return null
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val ext = url.substringAfterLast('.', "webp")
                                .substringBefore('?')
                                .lowercase()
                                .ifBlank { "webp" }
                            return DownloadedPayload(bytes, ext)
                        }
                    }
                }
            } catch (_: Exception) {
                // Try next fallback variant
            }
        }
        return null
    }

    private suspend fun updateNotificationSafe(name: String, processed: Int, total: Int) {
        try {
            setForeground(createForegroundInfo(name, processed, total))
        } catch (_: Exception) {
        }
    }

    private fun createForegroundInfo(collectionName: String, processed: Int, total: Int): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sticker_add)
            .setContentTitle(applicationContext.getString(R.string.notification_progress_title, collectionName))
            .setContentText(applicationContext.getString(R.string.notification_progress_body, processed, total))
            .setProgress(total.coerceAtLeast(1), processed.coerceAtMost(total.coerceAtLeast(1)), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
