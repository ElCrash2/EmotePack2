package com.example.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import com.example.AppConfig
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.local.LocalStorageManager
import com.example.data.local.WhatsAppSubpackEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * Official WhatsApp Third-Party Sticker ContentProvider implementation
 * following https://github.com/WhatsApp/stickers specifications.
 */
class StickerContentProvider : ContentProvider() {

    companion object {
        const val STICKER_PACK_IDENTIFIER_IN_QUERY = "sticker_pack_identifier"
        const val STICKER_PACK_NAME_IN_QUERY = "sticker_pack_name"
        const val STICKER_PACK_PUBLISHER_IN_QUERY = "sticker_pack_publisher"
        const val STICKER_PACK_ICON_IN_QUERY = "sticker_pack_icon"
        const val ANDROID_APP_DOWNLOAD_LINK_IN_QUERY = "android_play_store_link"
        const val IOS_APP_DOWNLOAD_LINK_IN_QUERY = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val ANIMATED_STICKER_PACK = "animated_sticker_pack"

        const val STICKER_FILE_NAME_IN_QUERY = "sticker_file_name"
        const val STICKER_FILE_EMOJI_IN_QUERY = "sticker_file_emoji"
        const val STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY = "sticker_accessibility_text"

        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"

        private const val METADATA_CODE = 1
        private const val METADATA_CODE_FOR_SINGLE_PACK = 2
        private const val STICKERS_CODE = 3
        private const val STICKERS_ASSET_CODE = 4
        private const val STICKER_PACK_TRAY_ICON_CODE = 5

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH)

        @Volatile
        private var matcherConfigured = false

        fun notifyPackChanged(context: android.content.Context, packId: String) {
            val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
            val resolver = context.contentResolver
            resolver.notifyChange(Uri.parse("content://$authority/$METADATA"), null)
            resolver.notifyChange(Uri.parse("content://$authority/$METADATA/$packId"), null)
            resolver.notifyChange(Uri.parse("content://$authority/$STICKERS/$packId"), null)
        }
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        check(authority.startsWith(ctx.packageName)) {
            "ContentProvider authority ($authority) must start with package name (${ctx.packageName})"
        }
        if (!matcherConfigured) {
            synchronized(MATCHER) {
                if (!matcherConfigured) {
                    MATCHER.addURI(authority, METADATA, METADATA_CODE)
                    MATCHER.addURI(authority, "$METADATA/*", METADATA_CODE_FOR_SINGLE_PACK)
                    MATCHER.addURI(authority, "$STICKERS/*", STICKERS_CODE)
                    MATCHER.addURI(authority, "$STICKERS_ASSET/*/*", STICKERS_ASSET_CODE)
                    matcherConfigured = true
                }
            }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val rawCursor = when (MATCHER.match(uri)) {
            METADATA_CODE -> getAllPacksCursor()
            METADATA_CODE_FOR_SINGLE_PACK -> {
                val id = uri.lastPathSegment ?: return applyProjection(emptyMetadataCursor(), projection)
                getSinglePackCursor(id)
            }
            STICKERS_CODE -> {
                val id = uri.lastPathSegment ?: return applyProjection(emptyStickersCursor(), projection)
                getStickersCursor(id)
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        val cursor = applyProjection(rawCursor, projection)
        context?.contentResolver?.let { resolver ->
            cursor.setNotificationUri(resolver, uri)
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = resolveAssetFile(uri) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val file = resolveAssetFile(uri) ?: return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0L, file.length())
    }

    private fun resolveAssetFile(uri: Uri): File? {
        val matchCode = MATCHER.match(uri)
        if (matchCode != STICKERS_ASSET_CODE && matchCode != STICKER_PACK_TRAY_ICON_CODE) {
            return null
        }
        val segments = uri.pathSegments
        if (segments.size != 3) throw FileNotFoundException("Invalid sticker asset URI: $uri")
        val packId = segments[1]
        val fileName = segments[2]

        val ctx = context ?: throw FileNotFoundException("Missing context")
        val db = AppDatabase.getInstance(ctx)
        val storage = LocalStorageManager(ctx)

        val pack = runBlocking { db.subpackDao().getSubpackById(packId) }
            ?: throw FileNotFoundException("Sticker pack not found: $packId")

        val trayFile = File(pack.trayIconPath)
        val normalizedTrayName = LocalStorageManager.normalizeAssetFileName(trayFile.name, "png")
        val file = if (fileName == "tray.png" || fileName == trayFile.name || fileName == normalizedTrayName) {
            trayFile
        } else {
            val matchedItem = runBlocking {
                db.stickerItemDao().getItemByCollectionAndFileName(pack.collectionId, fileName)
                    ?: db.stickerItemDao().getItemsForCollection(pack.collectionId).firstOrNull { item ->
                        val rawItemName = item.stickerFileName ?: item.finalWebpPath?.let(::File)?.name.orEmpty()
                        val normItemName = if (rawItemName.isNotBlank()) {
                            LocalStorageManager.normalizeAssetFileName(rawItemName, "webp")
                        } else ""
                        rawItemName == fileName ||
                            normItemName == fileName ||
                            LocalStorageManager.sanitizeStickerFileName(item.emoteId) == fileName ||
                            item.finalWebpPath?.let(::File)?.name == fileName
                    }
            }
            val itemFile = matchedItem?.finalWebpPath?.let(::File)
            if (itemFile != null && itemFile.exists()) {
                itemFile
            } else {
                storage.getStickerFile(pack.collectionId, fileName)
            }
        }

        if (!file.exists() || file.length() <= 0L) {
            throw FileNotFoundException("Sticker asset file not found or empty: ${file.absolutePath}")
        }
        return file
    }

    override fun getType(uri: Uri): String {
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        return when (MATCHER.match(uri)) {
            METADATA_CODE -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
            METADATA_CODE_FOR_SINGLE_PACK -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
            STICKERS_CODE -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
            STICKERS_ASSET_CODE, STICKER_PACK_TRAY_ICON_CODE -> {
                val name = uri.lastPathSegment.orEmpty()
                if (name.endsWith(".png", ignoreCase = true)) "image/png" else "image/webp"
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    private fun getAllPacksCursor(): MatrixCursor {
        val ctx = context ?: return emptyMetadataCursor()
        val db = AppDatabase.getInstance(ctx)
        val packs = runBlocking { db.subpackDao().getAllReadySubpacks() }
            .filter { File(it.trayIconPath).exists() }
        return buildMetadataCursor(packs)
    }

    private fun getSinglePackCursor(packId: String): MatrixCursor {
        val ctx = context ?: return emptyMetadataCursor()
        val db = AppDatabase.getInstance(ctx)
        val pack = runBlocking { db.subpackDao().getSubpackById(packId) }
        val list = if (pack != null && pack.isWhatsAppReady && File(pack.trayIconPath).exists()) {
            listOf(pack)
        } else {
            emptyList()
        }
        return buildMetadataCursor(list)
    }

    private fun buildMetadataCursor(packs: List<WhatsAppSubpackEntity>): MatrixCursor {
        val ctx = context
        val publisherName = (if (ctx != null) AppConfig.getPackAuthor(ctx) else "EmotePack")
            .trim()
            .take(128)
            .ifBlank { "EmotePack" }
        val cursor = emptyMetadataCursor()
        for (sp in packs) {
            val packName = sp.displayName.trim().take(128).ifBlank { publisherName }
            val rawTrayName = File(sp.trayIconPath).name.ifBlank { "tray.png" }
            val trayFileName = LocalStorageManager.normalizeAssetFileName(rawTrayName, "png")
            cursor.addRow(
                arrayOf<Any>(
                    sp.id,
                    packName,
                    publisherName,
                    trayFileName,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    sp.imageDataVersion.toString(),
                    1,
                    if (sp.isAnimated) 1 else 0
                )
            )
        }
        return cursor
    }

    private fun getStickersCursor(packId: String): MatrixCursor {
        val ctx = context ?: return emptyStickersCursor()
        val db = AppDatabase.getInstance(ctx)
        val items = runBlocking { db.stickerItemDao().getCompletedItemsForSubpack(packId) }
            .filter { !it.finalWebpPath.isNullOrBlank() && File(it.finalWebpPath).exists() }
            .take(AppConfig.MAX_STICKERS_PER_COLLECTION)
        val cursor = emptyStickersCursor()
        for (item in items) {
            val rawName = item.stickerFileName
                ?: item.finalWebpPath?.let { File(it).name }
                ?: continue
            val fileName = LocalStorageManager.normalizeAssetFileName(rawName, "webp")
            cursor.addRow(
                arrayOf<Any>(
                    fileName,
                    TextUtils.join(",", listOf("😀", "🎉")),
                    item.emoteName.trim().take(128).ifBlank { "Sticker" }
                )
            )
        }
        return cursor
    }

    private fun applyProjection(source: MatrixCursor, projection: Array<out String>?): Cursor {
        if (projection.isNullOrEmpty()) return source
        val indices = projection.map { col -> source.getColumnIndex(col) }
        val projected = MatrixCursor(projection)
        val row = arrayOfNulls<Any?>(projection.size)
        if (source.moveToFirst()) {
            do {
                for (i in projection.indices) {
                    val srcIdx = indices[i]
                    row[i] = if (srcIdx >= 0) {
                        when (source.getType(srcIdx)) {
                            Cursor.FIELD_TYPE_INTEGER -> source.getLong(srcIdx)
                            Cursor.FIELD_TYPE_FLOAT -> source.getDouble(srcIdx)
                            Cursor.FIELD_TYPE_BLOB -> source.getBlob(srcIdx)
                            Cursor.FIELD_TYPE_NULL -> null
                            else -> source.getString(srcIdx)
                        }
                    } else null
                }
                projected.addRow(row)
            } while (source.moveToNext())
        }
        source.close()
        return projected
    }

    private fun emptyMetadataCursor(): MatrixCursor = MatrixCursor(
        arrayOf(
            STICKER_PACK_IDENTIFIER_IN_QUERY,
            STICKER_PACK_NAME_IN_QUERY,
            STICKER_PACK_PUBLISHER_IN_QUERY,
            STICKER_PACK_ICON_IN_QUERY,
            ANDROID_APP_DOWNLOAD_LINK_IN_QUERY,
            IOS_APP_DOWNLOAD_LINK_IN_QUERY,
            PUBLISHER_EMAIL,
            PUBLISHER_WEBSITE,
            PRIVACY_POLICY_WEBSITE,
            LICENSE_AGREEMENT_WEBSITE,
            IMAGE_DATA_VERSION,
            AVOID_CACHE,
            ANIMATED_STICKER_PACK
        )
    )

    private fun emptyStickersCursor(): MatrixCursor = MatrixCursor(
        arrayOf(
            STICKER_FILE_NAME_IN_QUERY,
            STICKER_FILE_EMOJI_IN_QUERY,
            STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY
        )
    )

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
