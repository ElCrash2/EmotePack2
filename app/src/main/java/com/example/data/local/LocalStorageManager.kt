package com.example.data.local

import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

    suspend fun exportBackup(uri: Uri, collections: List<CollectionWithDetails>): Int = withContext(Dispatchers.IO) {
        val manifest = JSONObject()
        val collectionsJson = JSONArray()

        context.contentResolver.openOutputStream(uri)?.use { rawOut ->
            ZipOutputStream(BufferedOutputStream(rawOut)).use { zip ->
                for (entry in collections) {
                    val c = entry.collection
                    val cObj = JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("createdAt", c.createdAt)
                        put("updatedAt", c.updatedAt)
                        put("searchType", c.searchType)
                        put("searchQuery", c.searchQuery)
                        put("streamerId", c.streamerId)
                        put("streamerName", c.streamerName)
                        put("streamerUsername", c.streamerUsername)
                        put("streamerAvatarUrl", c.streamerAvatarUrl)
                        put("sourceEmoteSetId", c.sourceEmoteSetId)
                        put("totalCount", c.totalCount)
                        put("animatedCount", c.animatedCount)
                        put("staticCount", c.staticCount)
                        put("status", c.status)
                        put("isAnimatedPack", c.isAnimatedPack)
                    }

                    val itemsJson = JSONArray()
                    for (item in entry.items) {
                        var relStickerPath: String? = null
                        val filePath = item.finalWebpPath
                        if (!filePath.isNullOrBlank()) {
                            val f = File(filePath)
                            if (f.exists()) {
                                relStickerPath = "files/${c.id}/stickers/${item.stickerFileName ?: f.name}"
                                writeFileToZip(zip, relStickerPath, f)
                            }
                        }
                        itemsJson.put(JSONObject().apply {
                            put("id", item.id)
                            put("emoteId", item.emoteId)
                            put("emoteName", item.emoteName)
                            put("sourceSetId", item.sourceSetId)
                            put("sourceSetName", item.sourceSetName)
                            put("isAnimated", item.isAnimated)
                            put("previewUrl", item.previewUrl)
                            put("highResUrl", item.highResUrl)
                            put("fallbackUrlsCsv", item.fallbackUrlsCsv)
                            put("relStickerPath", relStickerPath)
                            put("stickerFileName", item.stickerFileName)
                            put("fileSizeBytes", item.fileSizeBytes)
                            put("width", item.width)
                            put("height", item.height)
                            put("frameCount", item.frameCount)
                            put("durationMs", item.durationMs)
                            put("hasAlpha", item.hasAlpha)
                            put("status", item.status)
                            put("subpackId", item.subpackId)
                            put("sortOrder", item.sortOrder)
                            put("versionUpdatedAt", item.versionUpdatedAt)
                            put("versionKey", item.versionKey)
                            put("conversionMode", item.conversionMode)
                        })
                    }
                    cObj.put("items", itemsJson)

                    val subpacksJson = JSONArray()
                    for (sp in entry.subpacks) {
                        var relTrayPath: String? = null
                        val trayFile = File(sp.trayIconPath)
                        if (trayFile.exists()) {
                            relTrayPath = "files/${c.id}/trays/${trayFile.name}"
                            writeFileToZip(zip, relTrayPath, trayFile)
                        }
                        subpacksJson.put(JSONObject().apply {
                            put("id", sp.id)
                            put("displayName", sp.displayName)
                            put("isAnimated", sp.isAnimated)
                            put("relTrayPath", relTrayPath)
                            put("imageDataVersion", sp.imageDataVersion)
                            put("stickerCount", sp.stickerCount)
                            put("isWhatsAppReady", sp.isWhatsAppReady)
                        })
                    }
                    cObj.put("subpacks", subpacksJson)
                    collectionsJson.put(cObj)
                }

                manifest.put("version", 2)
                manifest.put("exportedAt", System.currentTimeMillis())
                manifest.put("collections", collectionsJson)

                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } ?: error("Cannot open output stream")

        collections.size
    }

    suspend fun importBackup(uri: Uri): List<CollectionWithDetails> = withContext(Dispatchers.IO) {
        val tempRoot = File(context.cacheDir, "backup_import_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            var manifestJson: String? = null
            context.contentResolver.openInputStream(uri)?.use { rawIn ->
                ZipInputStream(BufferedInputStream(rawIn)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && !name.contains("..")) {
                            if (name == "manifest.json") {
                                manifestJson = zip.readBytes().toString(Charsets.UTF_8)
                            } else {
                                val outFile = File(tempRoot, name)
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { out -> zip.copyTo(out) }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("Cannot open backup file")

            val rawManifest = manifestJson ?: error("Missing manifest.json in backup")
            val root = JSONObject(rawManifest)
            val cols = root.optJSONArray("collections") ?: JSONArray()
            val imported = mutableListOf<CollectionWithDetails>()

            for (i in 0 until cols.length()) {
                val cObj = cols.optJSONObject(i) ?: continue
                val colId = cObj.getString("id")

                val itemsArr = cObj.optJSONArray("items") ?: JSONArray()
                val items = mutableListOf<StickerItemEntity>()
                for (j in 0 until itemsArr.length()) {
                    val itObj = itemsArr.getJSONObject(j)
                    val relPath = itObj.optString("relStickerPath").ifBlank { null }
                    var destPath: String? = null
                    if (relPath != null) {
                        val srcFile = File(tempRoot, relPath)
                        if (srcFile.exists()) {
                            val fileName = itObj.optString("stickerFileName").ifBlank { srcFile.name }
                            val destFile = getStickerFile(colId, fileName)
                            srcFile.copyTo(destFile, overwrite = true)
                            destPath = destFile.absolutePath
                        }
                    }
                    items.add(
                        StickerItemEntity(
                            id = itObj.getString("id"),
                            collectionId = colId,
                            emoteId = itObj.getString("emoteId"),
                            emoteName = itObj.getString("emoteName"),
                            sourceSetId = itObj.optString("sourceSetId").ifBlank { null },
                            sourceSetName = itObj.optString("sourceSetName").ifBlank { null },
                            isAnimated = itObj.getBoolean("isAnimated"),
                            previewUrl = itObj.optString("previewUrl"),
                            highResUrl = itObj.optString("highResUrl"),
                            fallbackUrlsCsv = itObj.optString("fallbackUrlsCsv"),
                            finalWebpPath = destPath,
                            stickerFileName = itObj.optString("stickerFileName").ifBlank { null },
                            fileSizeBytes = itObj.optLong("fileSizeBytes"),
                            width = itObj.optInt("width", 512),
                            height = itObj.optInt("height", 512),
                            frameCount = itObj.optInt("frameCount", 1),
                            durationMs = itObj.optInt("durationMs", 0),
                            hasAlpha = itObj.optBoolean("hasAlpha", true),
                            status = itObj.optString("status", ItemConversionStatus.COMPLETED),
                            errorMessage = null,
                            subpackId = itObj.optString("subpackId").ifBlank { null },
                            sortOrder = itObj.optInt("sortOrder", j),
                            versionUpdatedAt = itObj.optLong("versionUpdatedAt", 0L),
                            versionKey = itObj.optString("versionKey", ""),
                            conversionMode = itObj.optString("conversionMode", ConversionModeType.STATIC)
                        )
                    )
                }

                val subpacksArr = cObj.optJSONArray("subpacks") ?: JSONArray()
                val subpacks = mutableListOf<WhatsAppSubpackEntity>()
                var mainTrayPath: String? = null
                for (k in 0 until subpacksArr.length()) {
                    val spObj = subpacksArr.getJSONObject(k)
                    val spId = spObj.getString("id")
                    val relTray = spObj.optString("relTrayPath").ifBlank { null }
                    val destTray = getTrayFile(colId, spId)
                    if (relTray != null) {
                        val srcTray = File(tempRoot, relTray)
                        if (srcTray.exists()) {
                            srcTray.copyTo(destTray, overwrite = true)
                        }
                    }
                    if (mainTrayPath == null && destTray.exists()) {
                        mainTrayPath = destTray.absolutePath
                    }
                    subpacks.add(
                        WhatsAppSubpackEntity(
                            id = spId,
                            collectionId = colId,
                            displayName = spObj.getString("displayName"),
                            isAnimated = spObj.getBoolean("isAnimated"),
                            trayIconPath = destTray.absolutePath,
                            imageDataVersion = spObj.optInt("imageDataVersion", 1),
                            stickerCount = spObj.optInt("stickerCount"),
                            isWhatsAppReady = spObj.optBoolean("isWhatsAppReady"),
                            addedToWhatsApp = false
                        )
                    )
                }

                val entity = StickerCollectionEntity(
                    id = colId,
                    name = cObj.getString("name"),
                    createdAt = cObj.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = System.currentTimeMillis(),
                    searchType = cObj.optString("searchType", "EMOTE"),
                    searchQuery = cObj.optString("searchQuery"),
                    streamerId = cObj.optString("streamerId").ifBlank { null },
                    streamerName = cObj.optString("streamerName").ifBlank { null },
                    streamerUsername = cObj.optString("streamerUsername").ifBlank { null },
                    streamerAvatarUrl = cObj.optString("streamerAvatarUrl").ifBlank { null },
                    sourceEmoteSetId = cObj.optString("sourceEmoteSetId").ifBlank { null },
                    trayIconPath = mainTrayPath,
                    totalCount = cObj.optInt("totalCount", items.size),
                    animatedCount = cObj.optInt("animatedCount", items.count { it.isAnimated }),
                    staticCount = cObj.optInt("staticCount", items.count { !it.isAnimated }),
                    status = cObj.optString("status", CollectionStatus.READY),
                    isAnimatedPack = cObj.optBoolean("isAnimatedPack", subpacks.firstOrNull()?.isAnimated == true)
                )

                imported.add(CollectionWithDetails(entity, items, subpacks))
            }
            imported
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun writeFileToZip(zip: ZipOutputStream, entryName: String, file: File) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
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
