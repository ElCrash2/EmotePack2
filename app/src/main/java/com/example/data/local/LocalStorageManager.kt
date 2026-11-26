package com.example.data.local

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class StorageStats(
    val collectionsBytes: Long,
    val cacheBytes: Long,
    val freeDeviceBytes: Long
)

class LocalStorageManager(private val context: Context) {

    private val collectionsRoot: File
        get() = File(context.filesDir, "collections").apply { mkdirs() }

    private val sharedCacheRoot: File
        get() = File(context.filesDir, "emote_cache").apply { mkdirs() }

    private val sharedOriginalsDir: File
        get() = File(sharedCacheRoot, "originals").apply { mkdirs() }

    private val sharedVariantsDir: File
        get() = File(sharedCacheRoot, "variants").apply { mkdirs() }

    fun collectionDir(collectionId: String): File =
        File(collectionsRoot, collectionId).apply { mkdirs() }

    fun stickersDir(collectionId: String): File =
        File(collectionDir(collectionId), "stickers").apply { mkdirs() }

    fun traysDir(collectionId: String): File =
        File(collectionDir(collectionId), "trays").apply { mkdirs() }

    fun getStickerFile(collectionId: String, fileName: String): File =
        File(stickersDir(collectionId), fileName)

    fun getTrayFile(collectionId: String, packId: String): File =
        File(traysDir(collectionId), sanitizeTrayFileName(packId))

    companion object {
        fun sanitizeStickerFileName(emoteId: String): String {
            val safeId = emoteId.lowercase().replace(Regex("[^a-z0-9_]"), "_").ifBlank { "emote" }
            return "sticker_${safeId}.webp"
        }

        fun sanitizeTrayFileName(packId: String): String {
            val safeId = packId.lowercase().replace(Regex("[^a-z0-9_]"), "_").ifBlank { "pack" }
            return "tray_${safeId}.png"
        }

        fun normalizeAssetFileName(rawName: String, defaultExt: String): String {
            val trimmed = rawName.trim()
            val dotIdx = trimmed.lastIndexOf('.')
            val base = (if (dotIdx > 0) trimmed.substring(0, dotIdx) else trimmed)
                .lowercase()
                .replace(Regex("[^a-z0-9_]"), "_")
                .ifBlank { "asset" }
            val ext = (if (dotIdx > 0 && dotIdx < trimmed.length - 1) trimmed.substring(dotIdx + 1) else defaultExt)
                .lowercase()
                .filter { it.isLetterOrDigit() }
                .ifBlank { defaultExt }
            val prefixed = if (base.first().isLetter()) base else "s_$base"
            return "$prefixed.$ext"
        }
    }

    fun getSharedOriginalFile(emoteId: String, versionKey: String, ext: String): File {
        val safeKey = sanitizeKey(versionKey)
        val safeExt = ext.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "bin" }
        return File(sharedOriginalsDir, "${emoteId}_${safeKey}.$safeExt")
    }

    fun getSharedVariantFile(emoteId: String, versionKey: String, mode: String): File {
        val safeKey = sanitizeKey(versionKey)
        val safeMode = mode.lowercase()
        return File(sharedVariantsDir, "${emoteId}_${safeKey}_${safeMode}.webp")
    }

    private fun sanitizeKey(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

    fun deleteCollectionFiles(collectionId: String) {
        File(collectionsRoot, collectionId).deleteRecursively()
    }

    fun cleanupUnusedStickers(collectionId: String, activeStickerPaths: Set<String>, activeTrayPaths: Set<String>) {
        stickersDir(collectionId).listFiles()?.forEach { file ->
            if (file.absolutePath !in activeStickerPaths) {
                file.delete()
            }
        }
        traysDir(collectionId).listFiles()?.forEach { file ->
            if (file.absolutePath !in activeTrayPaths) {
                file.delete()
            }
        }
    }

    /**
     * Deletes unreferenced files inside the shared emote_cache while strictly preserving
     * any files referenced by existing saved collections.
     */
    fun cleanupUnreferencedSharedFiles(referencedPaths: Set<String>) {
        sharedOriginalsDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in referencedPaths) {
                file.delete()
            }
        }
        sharedVariantsDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in referencedPaths) {
                file.delete()
            }
        }
    }

    fun getFreeSpaceBytes(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    fun hasEnoughSpaceForBatch(itemCount: Int): Boolean {
        val estimatedMb = maxOf(10L, (itemCount * 650L) / 1024L + 10L)
        return getFreeSpaceBytes() >= estimatedMb * 1024L * 1024L
    }

    fun requiredSpaceMbForBatch(itemCount: Int): Long =
        maxOf(10L, (itemCount * 650L) / 1024L + 10L)

    suspend fun calculateStorageStats(): StorageStats = withContext(Dispatchers.IO) {
        val collectionsSize = folderSize(collectionsRoot) + folderSize(sharedVariantsDir)
        val cacheSize = folderSize(context.cacheDir) + folderSize(sharedOriginalsDir)
        val freeBytes = getFreeSpaceBytes()
        StorageStats(collectionsSize, cacheSize, freeBytes)
    }

    suspend fun clearAppCache(protectedPaths: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        // Only remove shared cache files that are NOT referenced by any saved collection
        sharedVariantsDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in protectedPaths) {
                file.delete()
            }
        }
        sharedOriginalsDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in protectedPaths) {
                file.delete()
            }
        }
    }

    private fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }
}
