package com.example

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.conversion.ConversionOutcome
import com.example.conversion.DecodedFrame
import com.example.conversion.Over10sHandling
import com.example.conversion.StickerConversionMode
import com.example.conversion.StickerConverter
import com.example.conversion.WebPContainer
import com.example.data.local.AppDatabase
import com.example.data.local.CollectionRepository
import com.example.data.local.CollectionStatus
import com.example.data.local.ConversionModeType
import com.example.data.local.EmoteCacheEntity
import com.example.data.local.ItemConversionStatus
import com.example.data.local.LocalStorageManager
import com.example.data.local.StickerCollectionEntity
import com.example.data.local.StickerItemEntity
import com.example.data.local.WhatsAppSubpackEntity
import com.example.data.model.SearchOrigin
import com.example.data.model.SearchOriginType
import com.example.data.model.SevenTvEmote
import com.example.ui.state.SelectionSessionManager
import com.example.ui.state.ToggleSelectionResult
import com.example.ui.viewmodel.HomeViewModel
import com.example.whatsapp.PackValidationResult
import com.example.whatsapp.StickerContentProvider
import com.example.whatsapp.StickerPackFormat
import com.example.whatsapp.WhatsAppPackBuilder
import com.example.whatsapp.WhatsAppPackExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmotePackRobolectricTest {

    private fun createSamplePng(color: Int = Color.CYAN): ByteArray {
        val bmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            val c = Canvas(this)
            val p = Paint().apply { this.color = color }
            c.drawCircle(100f, 50f, 40f, p)
        }
        val out = ByteArrayOutputStream().apply {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        bmp.recycle()
        return out
    }

    private fun sampleEmotes(count: Int, animatedEvery: Int = 0): List<SevenTvEmote> =
        (1..count).map { i ->
            val anim = animatedEvery > 0 && (i % animatedEvery == 0)
            SevenTvEmote(
                id = "emote_$i",
                name = "Emote$i",
                animated = anim,
                flags = if (anim) 256 else 0,
                ownerName = "Tester",
                previewUrl = "https://cdn.7tv.app/emote/emote_$i/2x.webp",
                highResUrl = "https://cdn.7tv.app/emote/emote_$i/4x.webp",
                updatedAt = 1000L + i
            )
        }

    @Test
    fun `localization switches cleanly between Spanish and English`() {
        val base = ApplicationProvider.getApplicationContext<Context>()

        val esConfig = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("es")) }
        val esContext = base.createConfigurationContext(esConfig)
        assertEquals("Buscar streamer", esContext.getString(R.string.search_streamer_label))
        assertEquals("Seleccionar primeros 30", esContext.getString(R.string.select_first_30))
        assertEquals("Cancelar creación", esContext.getString(R.string.cancel_creation))
        assertEquals("Enviar a WhatsApp", esContext.getString(R.string.send_to_whatsapp))

        val enConfig = Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val enContext = base.createConfigurationContext(enConfig)
        assertEquals("Search streamer", enContext.getString(R.string.search_streamer_label))
        assertEquals("Select first 30", enContext.getString(R.string.select_first_30))
        assertEquals("Cancel creation", enContext.getString(R.string.cancel_creation))
        assertEquals("Send to WhatsApp", enContext.getString(R.string.send_to_whatsapp))
        assertEquals("EmotePack", AppConfig.getPackAuthor(enContext))
    }

    @Test
    fun `home view model enforces mutually exclusive dual search fields`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = HomeViewModel(app)

        vm.updateStreamerQuery("Rubius")
        assertEquals(SearchOriginType.STREAMER, vm.uiState.value.activeMode)
        assertEquals("Rubius", vm.uiState.value.streamerQuery)
        assertEquals("", vm.uiState.value.emoteQuery)

        vm.onEmoteFieldActivated()
        assertEquals(SearchOriginType.EMOTE, vm.uiState.value.activeMode)
        assertEquals("", vm.uiState.value.streamerQuery)

        vm.updateEmoteQuery("peepoHappy")
        assertEquals("peepoHappy", vm.uiState.value.emoteQuery)
        assertEquals("", vm.uiState.value.streamerQuery)
    }

    @Test
    fun `selection session strictly enforces max 30 cap and selectFirst30 order`() {
        val session = SelectionSessionManager()
        val emotes35 = sampleEmotes(35)

        val first30Res = session.selectFirst30(emotes35)
        assertEquals(30, first30Res.selectedCount)
        assertEquals(30, session.selectedEmotes.value.size)
        assertEquals("emote_1", session.selectedEmotes.value.keys.first())
        assertEquals("emote_30", session.selectedEmotes.value.keys.last())
        assertFalse(session.selectedEmotes.value.containsKey("emote_31"))

        val toggle31 = session.toggleEmote(emotes35[30])
        assertEquals(ToggleSelectionResult.LIMIT_REACHED, toggle31)
        assertEquals(30, session.selectedEmotes.value.size)

        val toggleRemove = session.toggleEmote(emotes35[0])
        assertEquals(ToggleSelectionResult.REMOVED, toggleRemove)
        assertEquals(29, session.selectedEmotes.value.size)

        val toggleAddAgain = session.toggleEmote(emotes35[30])
        assertEquals(ToggleSelectionResult.ADDED, toggleAddAgain)
        assertEquals(30, session.selectedEmotes.value.size)
    }

    @Test
    fun `static sticker conversion and fake-vid conversion meet WhatsApp limits`() {
        val pngBytes = createSamplePng(Color.CYAN)

        // Pure static -> 512x512 static WebP <= 100 KB
        val staticOutcome = StickerConverter.convert(
            inputBytes = pngBytes,
            mode = StickerConversionMode.STATIC,
            allowTrimOver10s = false
        )
        assertTrue(staticOutcome is ConversionOutcome.Success)
        val staticSuccess = staticOutcome as ConversionOutcome.Success
        assertFalse(staticSuccess.isAnimated)
        assertEquals(StickerConversionMode.STATIC, staticSuccess.conversionMode)
        assertEquals(512, staticSuccess.inspection.width)
        assertEquals(512, staticSuccess.inspection.height)
        assertTrue(staticSuccess.webpBytes.size <= StickerConverter.MAX_STATIC_BYTES)

        // Mixed pack static emote -> FAKE_VID real motionless animated WebP (>=2 frames, <=500 KB)
        val fakeVidOutcome = StickerConverter.convert(
            inputBytes = pngBytes,
            mode = StickerConversionMode.FAKE_VID,
            allowTrimOver10s = false
        )
        assertTrue(fakeVidOutcome is ConversionOutcome.Success)
        val fakeVidSuccess = fakeVidOutcome as ConversionOutcome.Success
        assertTrue(fakeVidSuccess.isAnimated)
        assertEquals(StickerConversionMode.FAKE_VID, fakeVidSuccess.conversionMode)
        assertEquals(512, fakeVidSuccess.inspection.width)
        assertEquals(512, fakeVidSuccess.inspection.height)
        assertTrue(fakeVidSuccess.inspection.isAnimated)
        assertTrue(fakeVidSuccess.inspection.frameCount >= 2)
        assertTrue(fakeVidSuccess.inspection.minFrameDurationMs >= 8)
        assertTrue(fakeVidSuccess.inspection.totalDurationMs <= 10_000)
        assertTrue(fakeVidSuccess.webpBytes.size <= StickerConverter.MAX_ANIMATED_BYTES)

        val tempFile = File.createTempFile("fakevid_test_", ".webp").apply {
            writeBytes(fakeVidSuccess.webpBytes)
            deleteOnExit()
        }
        val trayBytes = StickerConverter.generateTrayIcon(tempFile)
        assertTrue(trayBytes != null && trayBytes.size <= StickerConverter.MAX_TRAY_BYTES)
    }

    @Test
    fun `animated webp muxer and converter enforce 512x512, 8ms min frame, and 10s trim prompt`() {
        val frame1 = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val frame2 = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

        val muxedLong = WebPContainer.encodeAnimatedWebP(
            frames = listOf(
                DecodedFrame(frame1, 4000),
                DecodedFrame(frame2, 4000),
                DecodedFrame(frame1, 4000)
            ),
            width = 128,
            height = 128,
            quality = 80
        )

        val needsTrim = StickerConverter.convert(muxedLong, expectAnimated = true, allowTrimOver10s = false)
        assertTrue(needsTrim is ConversionOutcome.NeedsTrimConfirmation)
        assertEquals(12000, (needsTrim as ConversionOutcome.NeedsTrimConfirmation).totalDurationMs)

        val trimmed = StickerConverter.convert(muxedLong, expectAnimated = true, allowTrimOver10s = true)
        assertTrue(trimmed is ConversionOutcome.Success)
        val trimmedSuccess = trimmed as ConversionOutcome.Success
        assertTrue(trimmedSuccess.isAnimated)
        assertEquals(512, trimmedSuccess.inspection.width)
        assertEquals(512, trimmedSuccess.inspection.height)
        assertTrue(trimmedSuccess.inspection.totalDurationMs <= 10_000)
        assertTrue(trimmedSuccess.inspection.minFrameDurationMs >= 8)
        assertTrue(trimmedSuccess.webpBytes.size <= StickerConverter.MAX_ANIMATED_BYTES)

        frame1.recycle()
        frame2.recycle()
    }

    @Test
    fun `single pack builder handles mixed fake-vid collections and drafts without splitting`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = LocalStorageManager(context)
        val pngBytes = createSamplePng(Color.GREEN)
        val fakeVidRes = StickerConverter.convert(pngBytes, StickerConversionMode.FAKE_VID, false) as ConversionOutcome.Success
        val frame1 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val frame2 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        val animRes = StickerConverter.convert(
            WebPContainer.encodeAnimatedWebP(listOf(DecodedFrame(frame1, 80), DecodedFrame(frame2, 80)), 64, 64, 85),
            StickerConversionMode.ANIMATED,
            false
        ) as ConversionOutcome.Success
        frame1.recycle()
        frame2.recycle()

        val colId = "single_mixed_pack_1"
        val items = listOf(1, 2, 3).map { idx ->
            val isAnim = idx == 1
            val bytes = if (isAnim) animRes.webpBytes else fakeVidRes.webpBytes
            val f = storage.getSharedVariantFile("em_$idx", "1", if (isAnim) "ANIMATED" else "FAKE_VID")
            f.writeBytes(bytes)
            StickerItemEntity(
                id = "it_$idx",
                collectionId = colId,
                emoteId = "em_$idx",
                emoteName = "Emote$idx",
                sourceSetId = null,
                sourceSetName = null,
                isAnimated = isAnim,
                previewUrl = "",
                highResUrl = "",
                fallbackUrlsCsv = "",
                finalWebpPath = f.absolutePath,
                stickerFileName = "sticker_em_$idx.webp",
                fileSizeBytes = bytes.size.toLong(),
                width = 512,
                height = 512,
                frameCount = 2,
                durationMs = 500,
                hasAlpha = true,
                status = ItemConversionStatus.COMPLETED,
                errorMessage = null,
                subpackId = null,
                sortOrder = idx - 1,
                versionUpdatedAt = 1L,
                versionKey = "1",
                conversionMode = if (isAnim) ConversionModeType.ANIMATED else ConversionModeType.FAKE_VID
            )
        }

        val mixedEmotes = sampleEmotes(3, animatedEvery = 3)
        assertEquals(StickerPackFormat.MIXED_FAKE_VID, WhatsAppPackBuilder.determinePackFormat(mixedEmotes))

        val singleBuild = WhatsAppPackBuilder.buildSinglePack(
            storage = storage,
            collectionId = colId,
            collectionName = "Mixed Test Pack",
            completedItems = items,
            existingPacks = emptyList()
        )
        val builtPack = singleBuild.pack!!
        assertTrue(singleBuild.isAnimatedPack)
        assertTrue(builtPack.isWhatsAppReady)
        assertEquals(3, builtPack.stickerCount)
        assertTrue(WhatsAppPackExporter.validatePackReadyForWhatsApp(builtPack, items) is PackValidationResult.Valid)

        // Draft check: 2 items -> saved cleanly locally, not WhatsApp-ready yet
        val draftBuild = WhatsAppPackBuilder.buildSinglePack(
            storage = storage,
            collectionId = "draft_col",
            collectionName = "Draft 2",
            completedItems = items.take(2),
            existingPacks = emptyList()
        )
        assertNotNull(draftBuild.pack)
        assertFalse(draftBuild.pack!!.isWhatsAppReady)
        assertTrue(WhatsAppPackExporter.validatePackReadyForWhatsApp(draftBuild.pack, items.take(2)) is PackValidationResult.TooFewStickers)
    }

    @Test
    fun `repository shared cache reuses files, reverts edit cancel, and protects other collections on delete`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val storage = LocalStorageManager(context)
        val repo = CollectionRepository(context, db, storage)

        val pngBytes = createSamplePng(Color.BLUE)
        val staticRes = StickerConverter.convert(pngBytes, StickerConversionMode.STATIC, false) as ConversionOutcome.Success
        val emote = sampleEmotes(1).first()
        val cacheKey = "${emote.id}_${emote.versionKey}"
        val cachedVariant = storage.getSharedVariantFile(emote.id, emote.versionKey, ConversionModeType.STATIC)
        cachedVariant.writeBytes(staticRes.webpBytes)
        db.emoteCacheDao().upsert(
            EmoteCacheEntity(
                cacheKey = cacheKey,
                emoteId = emote.id,
                versionKey = emote.versionKey,
                updatedAt = emote.updatedAt,
                sourceUrl = emote.highResUrl,
                originalFormat = "WEBP",
                originalFilePath = null,
                staticWebpPath = cachedVariant.absolutePath,
                animatedWebpPath = null,
                fakeVidWebpPath = null,
                converterVersion = AppConfig.CONVERTER_VERSION,
                createdAt = 100L,
                lastUsedAt = 100L
            )
        )

        val origin = SearchOrigin(SearchOriginType.EMOTE, "Emote1")
        // Create Col A -> should immediately hit shared cache!
        val colA = repo.prepareCollectionBuild(null, "Pack A", origin, listOf(emote))
        repo.finalizeCollectionBuild(colA, cancelled = false)
        val detailsA = repo.getCollectionWithDetails(colA)
        assertNotNull(detailsA)
        assertEquals(ItemConversionStatus.COMPLETED, detailsA!!.items.first().status)
        assertEquals(cachedVariant.absolutePath, detailsA.items.first().finalWebpPath)

        // Create Col B sharing the exact same emote
        val colB = repo.prepareCollectionBuild(null, "Pack B", origin, listOf(emote))
        repo.finalizeCollectionBuild(colB, cancelled = false)

        // Delete Col A -> shared cache file referenced by Col B MUST survive!
        repo.deleteCollection(colA)
        assertNull(repo.getCollectionWithDetails(colA))
        assertTrue(cachedVariant.exists())
        repo.clearProtectedCache()
        assertTrue(cachedVariant.exists())

        // Edit Col B with a different name and cancel/revert -> restores "Pack B"
        val emote2 = sampleEmotes(2)[1]
        repo.prepareCollectionBuild(colB, "Pack B Edited", origin, listOf(emote, emote2))
        assertEquals("Pack B Edited", repo.getCollectionWithDetails(colB)?.collection?.name)
        repo.discardCreationOrRevertEdit(colB)
        assertEquals("Pack B", repo.getCollectionWithDetails(colB)?.collection?.name)
        assertEquals(1, repo.getCollectionWithDetails(colB)?.items?.size)
        Unit
    }

    @Test
    fun `sticker content provider exposes ready single pack with EmotePack author and resolves shared paths`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val storage = LocalStorageManager(context)

        val colId = "550e8400-e29b-41d4-a716-446655440000"
        val emoteIdUpper = "01FEEQAQM0000409S3FGDM8BN7"
        val pngBytes = createSamplePng(Color.RED)
        val fakeVidRes = StickerConverter.convert(pngBytes, StickerConversionMode.FAKE_VID, false) as ConversionOutcome.Success
        assertEquals(3, fakeVidRes.inspection.frameCount)
        val decodedFakeVid = WebPContainer.decodeAnimatedFrames(fakeVidRes.webpBytes)
        assertEquals(3, decodedFakeVid.size)
        decodedFakeVid.forEach { it.bitmap.recycle() }

        val variantFile = storage.getSharedVariantFile(emoteIdUpper, "1", "FAKE_VID")
        variantFile.writeBytes(fakeVidRes.webpBytes)
        val trayFile = storage.getTrayFile(colId, colId)
        trayFile.writeBytes(StickerConverter.generateTrayIcon(variantFile)!!)
        assertTrue(trayFile.name.matches(Regex("^[a-z0-9_]+\\.png$")))

        db.collectionDao().upsert(
            StickerCollectionEntity(
                id = colId,
                name = "Mixed Pack",
                createdAt = 1000L,
                updatedAt = 2000L,
                searchType = "EMOTE",
                searchQuery = "Mixed",
                streamerId = null,
                streamerName = null,
                streamerUsername = null,
                streamerAvatarUrl = null,
                sourceEmoteSetId = null,
                trayIconPath = trayFile.absolutePath,
                totalCount = 3,
                animatedCount = 1,
                staticCount = 2,
                status = CollectionStatus.READY,
                isAnimatedPack = true
            )
        )
        db.subpackDao().insertAll(
            listOf(
                WhatsAppSubpackEntity(
                    id = colId,
                    collectionId = colId,
                    displayName = "Mixed Pack",
                    isAnimated = true,
                    trayIconPath = trayFile.absolutePath,
                    imageDataVersion = 1,
                    stickerCount = 3,
                    isWhatsAppReady = true,
                    addedToWhatsApp = false
                )
            )
        )
        db.stickerItemDao().insertAll(
            (1..3).map { i ->
                val rawEmoteId = "${emoteIdUpper}_$i"
                StickerItemEntity(
                    id = "p_item_$i",
                    collectionId = colId,
                    emoteId = rawEmoteId,
                    emoteName = "ProvEmote$i",
                    sourceSetId = null,
                    sourceSetName = null,
                    isAnimated = (i == 1),
                    previewUrl = "",
                    highResUrl = "",
                    fallbackUrlsCsv = "",
                    finalWebpPath = variantFile.absolutePath,
                    stickerFileName = LocalStorageManager.sanitizeStickerFileName(rawEmoteId),
                    fileSizeBytes = fakeVidRes.webpBytes.size.toLong(),
                    width = 512,
                    height = 512,
                    frameCount = 3,
                    durationMs = 900,
                    hasAlpha = true,
                    status = ItemConversionStatus.COMPLETED,
                    errorMessage = null,
                    subpackId = colId,
                    sortOrder = i,
                    versionUpdatedAt = 1L,
                    versionKey = "1",
                    conversionMode = ConversionModeType.FAKE_VID
                )
            }
        )

        val controller = Robolectric.buildContentProvider(StickerContentProvider::class.java).create()
        val provider = controller.get()
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY

        val metaUri = Uri.parse("content://$authority/metadata/$colId")
        provider.query(metaUri, null, null, null, null).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(colId, cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.STICKER_PACK_IDENTIFIER_IN_QUERY)))
            assertEquals("Mixed Pack", cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.STICKER_PACK_NAME_IN_QUERY)))
            assertEquals(AppConfig.getPackAuthor(context), cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.STICKER_PACK_PUBLISHER_IN_QUERY)))
            assertEquals(trayFile.name, cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.STICKER_PACK_ICON_IN_QUERY)))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow(StickerContentProvider.ANIMATED_STICKER_PACK)))
        }

        // Non-null projection support
        provider.query(
            metaUri,
            arrayOf(StickerContentProvider.STICKER_PACK_NAME_IN_QUERY, StickerContentProvider.ANIMATED_STICKER_PACK),
            null,
            null,
            null
        ).use { projected ->
            assertEquals(2, projected.columnCount)
            assertTrue(projected.moveToFirst())
            assertEquals("Mixed Pack", projected.getString(0))
            assertEquals(1, projected.getInt(1))
        }

        val expectedFirstStickerName = LocalStorageManager.sanitizeStickerFileName("${emoteIdUpper}_1")
        assertTrue(expectedFirstStickerName.matches(Regex("^[a-z0-9_]+\\.webp$")))

        val stickersUri = Uri.parse("content://$authority/stickers/$colId")
        provider.query(stickersUri, null, null, null, null).use { cursor ->
            assertEquals(3, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedFirstStickerName, cursor.getString(cursor.getColumnIndexOrThrow(StickerContentProvider.STICKER_FILE_NAME_IN_QUERY)))
        }

        // Open asset directly from shared cache path via ContentProvider (both openAssetFile and openFile)
        val assetUri = Uri.parse("content://$authority/stickers_asset/$colId/$expectedFirstStickerName")
        val afd = provider.openAssetFile(assetUri, "r")
        assertNotNull(afd)
        assertTrue((afd?.length ?: -1L) > 0L)
        afd?.close()

        val pfd = provider.openFile(assetUri, "r")
        assertNotNull(pfd)
        pfd?.close()

        val trayUri = Uri.parse("content://$authority/stickers_asset/$colId/${trayFile.name}")
        val trayAfd = provider.openAssetFile(trayUri, "r")
        assertNotNull(trayAfd)
        assertEquals(trayFile.length(), trayAfd?.length)
        trayAfd?.close()

        val intent = WhatsAppPackExporter.buildAddToWhatsAppIntent(colId, "Mixed Pack")
        assertEquals("com.whatsapp.intent.action.ENABLE_STICKER_PACK", intent.action)
        assertEquals(colId, intent.getStringExtra("sticker_pack_id"))
        assertEquals(authority, intent.getStringExtra("sticker_pack_authority"))
        assertEquals("Mixed Pack", intent.getStringExtra("sticker_pack_name"))
        Unit
    }

    @Test
    fun `real gif89a converts cleanly to transparent animated webp and corrupt bytes fail fast`() {
        val gifBytes = byteArrayOf(
            // GIF89a header + 2x2 logical screen (4 colors)
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
            0x02, 0x00, 0x02, 0x00, 0x91.toByte(), 0x00, 0x00,
            // Global Color Table: idx 0 black (transparent), 1 red, 2 green, 3 blue
            0x00, 0x00, 0x00,
            0xFF.toByte(), 0x00, 0x00,
            0x00, 0xFF.toByte(), 0x00,
            0x00, 0x00, 0xFF.toByte(),
            // NETSCAPE2.0 infinite loop
            0x21, 0xFF.toByte(), 0x0B,
            0x4E, 0x45, 0x54, 0x53, 0x43, 0x41, 0x50, 0x45, 0x32, 0x2E, 0x30,
            0x03, 0x01, 0x00, 0x00, 0x00,
            // Frame 1 (100ms, transparent idx 0)
            0x21, 0xF9.toByte(), 0x04, 0x09, 0x0A, 0x00, 0x00, 0x00,
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00,
            0x02, 0x03, 0x44, 0x22, 0x05, 0x00,
            // Frame 2 (120ms, transparent idx 0)
            0x21, 0xF9.toByte(), 0x04, 0x09, 0x0C, 0x00, 0x00, 0x00,
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00,
            0x02, 0x03, 0xC4.toByte(), 0x26, 0x05, 0x00,
            // GIF Trailer
            0x3B
        )

        val gifOutcome = StickerConverter.convert(
            inputBytes = gifBytes,
            mode = StickerConversionMode.ANIMATED,
            allowTrimOver10s = false
        )
        assertTrue(gifOutcome is ConversionOutcome.Success)
        val success = gifOutcome as ConversionOutcome.Success
        assertTrue(success.isAnimated)
        assertEquals(512, success.inspection.width)
        assertEquals(512, success.inspection.height)
        assertEquals(2, success.inspection.frameCount)
        assertEquals(0, success.inspection.loopCount)
        assertTrue(success.inspection.hasAlpha)
        assertTrue(success.webpBytes.size <= StickerConverter.MAX_ANIMATED_BYTES)

        val corruptOutcome = StickerConverter.convert(
            inputBytes = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00),
            mode = StickerConversionMode.ANIMATED,
            allowTrimOver10s = false
        )
        assertTrue(corruptOutcome is ConversionOutcome.Failure)
    }

    @Test
    fun `over 10s lower quality preserves full duration under 500 KB and validates for pack`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val frames = List(6) { idx ->
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(this)
                val paint = Paint().apply { color = if (idx % 2 == 0) Color.MAGENTA else Color.CYAN }
                canvas.drawCircle(32f, 32f, 24f, paint)
            }
            DecodedFrame(bitmap = bmp, durationMs = 2_400)
        }
        val longWebp = WebPContainer.encodeAnimatedWebP(frames, 64, 64, quality = 80)
        frames.forEach { it.bitmap.recycle() }

        val lowered = StickerConverter.convert(
            inputBytes = longWebp,
            mode = StickerConversionMode.ANIMATED,
            over10sHandling = Over10sHandling.LOWER_QUALITY
        )
        assertTrue(lowered is ConversionOutcome.Success)
        val success = lowered as ConversionOutcome.Success
        assertTrue(success.preservedOver10s)
        assertTrue(success.isAnimated)
        assertEquals(512, success.inspection.width)
        assertEquals(512, success.inspection.height)
        assertTrue(success.inspection.totalDurationMs > 10_000)
        assertTrue(success.webpBytes.size <= StickerConverter.MAX_ANIMATED_BYTES)

        val storage = LocalStorageManager(ctx)
        val colId = "col_over10s"
        val variantFile = storage.getSharedVariantFile("over10", "v1", ConversionModeType.ANIMATED_LOWER_QUALITY)
        variantFile.writeBytes(success.webpBytes)

        val items = (1..3).map { idx ->
            StickerItemEntity(
                id = "it_$idx",
                collectionId = colId,
                emoteId = "over10_$idx",
                emoteName = "LongEmote$idx",
                sourceSetId = null,
                sourceSetName = null,
                isAnimated = true,
                previewUrl = "",
                highResUrl = "",
                fallbackUrlsCsv = "",
                finalWebpPath = variantFile.absolutePath,
                stickerFileName = LocalStorageManager.sanitizeStickerFileName("over10_$idx"),
                fileSizeBytes = variantFile.length(),
                width = success.inspection.width,
                height = success.inspection.height,
                frameCount = success.inspection.frameCount,
                durationMs = success.inspection.totalDurationMs,
                hasAlpha = success.inspection.hasAlpha,
                status = ItemConversionStatus.COMPLETED,
                errorMessage = null,
                subpackId = colId,
                sortOrder = idx - 1,
                conversionMode = ConversionModeType.ANIMATED_LOWER_QUALITY
            )
        }

        val buildResult = WhatsAppPackBuilder.buildSinglePack(
            storage = storage,
            collectionId = colId,
            collectionName = "Long Pack",
            completedItems = items,
            existingPacks = emptyList()
        )
        assertNotNull(buildResult.pack)
        assertTrue(buildResult.isAnimatedPack)
        assertTrue(buildResult.pack!!.isWhatsAppReady)
        val validation = WhatsAppPackExporter.validatePackReadyForWhatsApp(buildResult.pack, items)
        assertTrue(validation is com.example.whatsapp.PackValidationResult.Valid)
    }

    @Test
    fun `high frame count over 10s uses fast sampled search cleanly for both trim and lower quality`() {
        val frames = List(40) { idx ->
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(this)
                val paint = Paint().apply {
                    color = Color.rgb((idx * 6) % 255, 180, (255 - idx * 5) % 255)
                }
                canvas.drawCircle(16f + (idx % 32), 32f, 18f, paint)
            }
            DecodedFrame(bitmap = bmp, durationMs = 300) // 12,000 ms total
        }
        val longWebp = WebPContainer.encodeAnimatedWebP(frames, 64, 64, quality = 80)
        frames.forEach { it.bitmap.recycle() }

        val askResult = StickerConverter.convert(
            inputBytes = longWebp,
            mode = StickerConversionMode.ANIMATED,
            over10sHandling = Over10sHandling.ASK_USER
        )
        assertTrue(askResult is ConversionOutcome.NeedsTrimConfirmation)
        assertEquals(12_000, (askResult as ConversionOutcome.NeedsTrimConfirmation).totalDurationMs)

        val trimResult = StickerConverter.convert(
            inputBytes = longWebp,
            mode = StickerConversionMode.ANIMATED,
            over10sHandling = Over10sHandling.TRIM_10S
        )
        assertTrue(trimResult is ConversionOutcome.Success)
        val trimSuccess = trimResult as ConversionOutcome.Success
        assertTrue(trimSuccess.inspection.totalDurationMs <= 10_000)
        assertTrue(trimSuccess.webpBytes.size <= StickerConverter.MAX_ANIMATED_BYTES)

        val lowQResult = StickerConverter.convert(
            inputBytes = longWebp,
            mode = StickerConversionMode.ANIMATED,
            over10sHandling = Over10sHandling.LOWER_QUALITY
        )
        assertTrue(lowQResult is ConversionOutcome.Success)
        val lowQSuccess = lowQResult as ConversionOutcome.Success
        assertTrue(lowQSuccess.preservedOver10s)
        assertTrue(lowQSuccess.inspection.totalDurationMs > 10_000)
        assertTrue(lowQSuccess.webpBytes.size <= StickerConverter.MAX_ANIMATED_BYTES)
    }
}



