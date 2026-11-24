package com.example.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

enum class StickerConversionMode {
    STATIC,
    ANIMATED,
    FAKE_VID
}

enum class Over10sHandling {
    ASK_USER,
    TRIM_10S,
    LOWER_QUALITY
}

private enum class SniffedContainerFormat {
    GIF,
    WEBP,
    PNG,
    APNG,
    AVIF_HEIF,
    JPEG,
    BMP,
    UNKNOWN
}

/**
 * Conversion parameters adapted from laggykiller/sticker-convert's WhatsApp preset:
 * quality/fps/res min-max bounds, power-curve interpolation, byte limits, and binary search steps.
 */
data class ConverterParams(
    val qualityMin: Int = 15,
    val qualityMax: Int = 96,
    val qualityPower: Float = 1.0f,
    val fpsMin: Int = 5,
    val fpsMax: Int = 30,
    val fpsPower: Float = 1.0f,
    val resMin: Int = 256,
    val resMax: Int = 512,
    val resPower: Float = 1.0f,
    val imgSizeMax: Int = 99_000,
    val vidSizeMax: Int = 495_000,
    val traySizeMax: Int = 49_000,
    val durationMaxMs: Int = 10_000,
    val minFrameDurationMs: Int = 8,
    val steps: Int = 5
)

sealed class ConversionOutcome {
    data class Success(
        val webpBytes: ByteArray,
        val inspection: WebPInspectionResult,
        val isAnimated: Boolean,
        val conversionMode: StickerConversionMode,
        val preservedOver10s: Boolean = false
    ) : ConversionOutcome()

    data class NeedsTrimConfirmation(val totalDurationMs: Int) : ConversionOutcome()
    data class Failure(val reason: String) : ConversionOutcome()
}

object StickerConverter {

    const val STICKER_SIZE_PX = 512
    const val TRAY_SIZE_PX = 96
    const val MAX_STATIC_BYTES = 100_000 // Strictly <= 100 KB decimal & binary
    const val MAX_ANIMATED_BYTES = 500_000 // Strictly <= 500 KB decimal & binary
    const val MAX_TRAY_BYTES = 50_000 // Strictly <= 50 KB decimal & binary
    const val MAX_ANIMATION_DURATION_MS = 10_000
    const val MIN_FRAME_DURATION_MS = 8
    private const val SMOOTH_FPS_FLOOR = 12

    private val defaultParams = ConverterParams()

    fun convert(
        inputBytes: ByteArray,
        expectAnimated: Boolean,
        allowTrimOver10s: Boolean,
        shouldAbort: () -> Boolean = { false }
    ): ConversionOutcome {
        val mode = if (expectAnimated) StickerConversionMode.ANIMATED else StickerConversionMode.STATIC
        val over10s = if (allowTrimOver10s) Over10sHandling.TRIM_10S else Over10sHandling.ASK_USER
        return convert(inputBytes, mode, over10s, defaultParams, shouldAbort)
    }

    fun convert(
        inputBytes: ByteArray,
        mode: StickerConversionMode,
        allowTrimOver10s: Boolean,
        params: ConverterParams = defaultParams,
        shouldAbort: () -> Boolean = { false }
    ): ConversionOutcome {
        val over10s = if (allowTrimOver10s) Over10sHandling.TRIM_10S else Over10sHandling.ASK_USER
        return convert(inputBytes, mode, over10s, params, shouldAbort)
    }

    fun convert(
        inputBytes: ByteArray,
        mode: StickerConversionMode,
        over10sHandling: Over10sHandling,
        params: ConverterParams = defaultParams,
        shouldAbort: () -> Boolean = { false }
    ): ConversionOutcome {
        if (inputBytes.isEmpty()) return ConversionOutcome.Failure("Empty input image")
        if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")

        val format = sniffContainerFormat(inputBytes)
        if (mode != StickerConversionMode.STATIC && over10sHandling == Over10sHandling.ASK_USER) {
            val fastDurationMs = inspectAnimatedDurationFast(inputBytes, format, shouldAbort)
            if (fastDurationMs != null && fastDurationMs > params.durationMaxMs) {
                return ConversionOutcome.NeedsTrimConfirmation(fastDurationMs)
            }
        }

        val stopDecodeAfterMs = if (mode != StickerConversionMode.STATIC && over10sHandling == Over10sHandling.TRIM_10S) {
            params.durationMaxMs
        } else {
            null
        }
        val decodedFrames = decodeInputFrames(inputBytes, format, stopDecodeAfterMs, shouldAbort)
        if (decodedFrames.isEmpty()) {
            return ConversionOutcome.Failure("Unsupported or corrupt image format ($format)")
        }

        return try {
            if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")
            when (mode) {
                StickerConversionMode.STATIC -> {
                    convertStatic(decodedFrames.first().bitmap, params, shouldAbort)
                }
                StickerConversionMode.FAKE_VID -> {
                    if (decodedFrames.size > 1) {
                        convertAnimated(decodedFrames, over10sHandling, params, shouldAbort)
                    } else {
                        convertFakeVid(decodedFrames.first().bitmap, params, shouldAbort)
                    }
                }
                StickerConversionMode.ANIMATED -> {
                    if (decodedFrames.size > 1) {
                        convertAnimated(decodedFrames, over10sHandling, params, shouldAbort)
                    } else {
                        // Single-frame source inside an animated/mixed pack -> encode as motionless fake-vid
                        convertFakeVid(decodedFrames.first().bitmap, params, shouldAbort)
                    }
                }
            }
        } catch (ce: InterruptedException) {
            ConversionOutcome.Failure("Cancelled")
        } finally {
            decodedFrames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }
    }

    /**
     * Identifies actual container format strictly from magic bytes, never from file extension.
     */
    private fun sniffContainerFormat(bytes: ByteArray): SniffedContainerFormat {
        if (GifFrameDecoder.isGif(bytes)) return SniffedContainerFormat.GIF
        if (WebPContainer.isWebP(bytes)) return SniffedContainerFormat.WEBP
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        ) {
            return if (hasPngChunk(bytes, "acTL")) SniffedContainerFormat.APNG else SniffedContainerFormat.PNG
        }
        if (bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        ) {
            return SniffedContainerFormat.AVIF_HEIF
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return SniffedContainerFormat.JPEG
        }
        if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
            return SniffedContainerFormat.BMP
        }
        return SniffedContainerFormat.UNKNOWN
    }

    private fun hasPngChunk(bytes: ByteArray, targetChunk: String): Boolean {
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val len = ByteBuffer.wrap(bytes, offset, 4).int
            val name = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (name == targetChunk) return true
            if (name == "IDAT" || name == "IEND" || len < 0) break
            offset += 12 + len
        }
        return false
    }

    private fun inspectAnimatedDurationFast(
        bytes: ByteArray,
        format: SniffedContainerFormat,
        shouldAbort: () -> Boolean
    ): Int? {
        return when (format) {
            SniffedContainerFormat.WEBP -> {
                val info = WebPContainer.inspect(bytes)
                if (info.isValidWebP && info.isAnimated && info.frameCount >= 2) info.totalDurationMs else null
            }
            SniffedContainerFormat.GIF -> GifFrameDecoder.inspectDurationMs(bytes, shouldAbort)
            else -> null
        }
    }

    private fun decodeInputFrames(
        bytes: ByteArray,
        format: SniffedContainerFormat,
        stopAfterMs: Int?,
        shouldAbort: () -> Boolean
    ): List<DecodedFrame> {
        when (format) {
            SniffedContainerFormat.GIF -> {
                val frames = GifFrameDecoder.decode(bytes, STICKER_SIZE_PX, stopAfterMs, shouldAbort)
                if (frames.isNotEmpty()) return frames
            }
            SniffedContainerFormat.WEBP -> {
                val frames = WebPContainer.decodeAnimatedFrames(bytes, STICKER_SIZE_PX, stopAfterMs, shouldAbort)
                if (frames.isNotEmpty()) return frames
            }
            SniffedContainerFormat.APNG, SniffedContainerFormat.AVIF_HEIF -> {
                val animatedFrames = tryDecodeAnimatedViaImageDecoder(bytes, shouldAbort)
                if (animatedFrames.isNotEmpty()) return animatedFrames
            }
            else -> Unit
        }

        // Fallback single-frame decoding (PNG / JPEG / BMP / static AVIF / WebP fallback)
        if (shouldAbort()) return emptyList()
        val bitmap = decodeSingleBitmap(bytes) ?: return emptyList()
        return listOf(DecodedFrame(bitmap, 100))
    }

    private fun tryDecodeAnimatedViaImageDecoder(
        bytes: ByteArray,
        shouldAbort: () -> Boolean
    ): List<DecodedFrame> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        return try {
            val source = android.graphics.ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val drawable = android.graphics.ImageDecoder.decodeDrawable(source)
            if (drawable is AnimatedImageDrawable && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                val w = minOf(STICKER_SIZE_PX, drawable.intrinsicWidth)
                val h = minOf(STICKER_SIZE_PX, drawable.intrinsicHeight)
                drawable.setBounds(0, 0, w, h)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(bmp)
                drawable.draw(canvas)
                if (!shouldAbort()) listOf(DecodedFrame(bmp, 100)) else {
                    bmp.recycle()
                    emptyList()
                }
            } else {
                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun decodeSingleBitmap(bytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = android.graphics.ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } catch (_: Throwable) {
                // Fallback to BitmapFactory
            }
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return if (bmp.config == Bitmap.Config.ARGB_8888) {
            bmp
        } else {
            val argb = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp.recycle()
            argb
        }
    }

    /**
     * Static WebP optimization: lossless check first, then iterative binary search over
     * quality [qualityMin..qualityMax], then adaptive color quantization, then inner res fallback.
     */
    private fun convertStatic(
        source: Bitmap,
        params: ConverterParams,
        shouldAbort: () -> Boolean
    ): ConversionOutcome {
        val maxBytes = minOf(params.imgSizeMax, MAX_STATIC_BYTES)
        val canvasBitmap = fitToSquareCanvas(source, STICKER_SIZE_PX, params.resMax)
        try {
            // 1. Try lossless first on API 30+ for pixel-perfect fidelity
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")
                val lossless = WebPContainer.encodeStaticWebP(canvasBitmap, 100, lossless = true)
                if (lossless.size <= maxBytes) {
                    val check = validateStatic(lossless)
                    if (check != null) {
                        return ConversionOutcome.Success(lossless, check, isAnimated = false, StickerConversionMode.STATIC)
                    }
                }
            }

            // 2. Iterative binary search over quality at full 512x512 resolution
            val bestFullRes = binarySearchQuality(
                minQ = params.qualityMin,
                maxQ = params.qualityMax,
                steps = params.steps,
                maxBytes = maxBytes,
                shouldAbort = shouldAbort
            ) { q ->
                WebPContainer.encodeStaticWebP(canvasBitmap, q, lossless = false)
            }
            if (bestFullRes != null) {
                val check = validateStatic(bestFullRes)
                if (check != null) {
                    return ConversionOutcome.Success(bestFullRes, check, isAnimated = false, StickerConversionMode.STATIC)
                }
            }

            // 3. Color optimization at full 512x512 before shrinking resolution
            val quantized = quantizeColorsAlphaSafe(canvasBitmap, levelsPerChannel = 24)
            try {
                val bestQuantized = binarySearchQuality(
                    minQ = params.qualityMin,
                    maxQ = params.qualityMax,
                    steps = params.steps,
                    maxBytes = maxBytes,
                    shouldAbort = shouldAbort
                ) { q ->
                    WebPContainer.encodeStaticWebP(quantized, q, lossless = false)
                }
                if (bestQuantized != null) {
                    val check = validateStatic(bestQuantized)
                    if (check != null) {
                        return ConversionOutcome.Success(bestQuantized, check, isAnimated = false, StickerConversionMode.STATIC)
                    }
                }
            } finally {
                quantized.recycle()
            }

            // 4. Fallback: power-curve inner resolution steps inside 512x512 transparent canvas
            val resCandidates = generatePowerCurveInts(params.resMax, params.resMin, params.resPower, steps = 4).drop(1)
            for (innerRes in resCandidates) {
                if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")
                val compactBitmap = fitToSquareCanvas(source, STICKER_SIZE_PX, innerRes)
                try {
                    val encoded = binarySearchQuality(
                        minQ = params.qualityMin,
                        maxQ = params.qualityMax,
                        steps = params.steps,
                        maxBytes = maxBytes,
                        shouldAbort = shouldAbort
                    ) { q ->
                        WebPContainer.encodeStaticWebP(compactBitmap, q, lossless = false)
                    }
                    if (encoded != null) {
                        val check = validateStatic(encoded)
                        if (check != null) {
                            return ConversionOutcome.Success(encoded, check, isAnimated = false, StickerConversionMode.STATIC)
                        }
                    }
                } finally {
                    compactBitmap.recycle()
                }
            }

            return ConversionOutcome.Failure("Could not compress static sticker under 100 KB")
        } finally {
            canvasBitmap.recycle()
        }
    }

    /**
     * Converts a static image into a genuine motionless Animated WebP ("fake-vid")
     * with >=2 identical keyframes so static stickers can live alongside animated
     * stickers inside a single WhatsApp animated sticker pack.
     */
    private fun convertFakeVid(
        source: Bitmap,
        params: ConverterParams,
        shouldAbort: () -> Boolean
    ): ConversionOutcome {
        val maxBytes = minOf(params.vidSizeMax, MAX_ANIMATED_BYTES)
        val canvasBitmap = fitToSquareCanvas(source, STICKER_SIZE_PX, params.resMax)
        try {
            // 1. Binary search highest lossy VP8/ALPH quality at full 512x512 resolution
            // (3 identical keyframes x 300ms = 900ms, matching img2webp animated sticker keyframes)
            val bestFullRes = binarySearchQuality(
                minQ = params.qualityMin,
                maxQ = params.qualityMax,
                steps = params.steps,
                maxBytes = maxBytes,
                shouldAbort = shouldAbort
            ) { q ->
                WebPContainer.encodeFakeVidWebP(
                    bitmap = canvasBitmap,
                    width = STICKER_SIZE_PX,
                    height = STICKER_SIZE_PX,
                    quality = q,
                    lossless = false,
                    frameCount = 3,
                    frameDurationMs = 300
                )
            }
            if (bestFullRes != null) {
                val check = validateAnimated(bestFullRes)
                if (check != null) {
                    return ConversionOutcome.Success(bestFullRes, check, isAnimated = true, StickerConversionMode.FAKE_VID)
                }
            }

            // 3. Last resort inner resolution adjustment inside 512x512 canvas
            val resCandidates = generatePowerCurveInts(params.resMax, params.resMin, params.resPower, steps = 4).drop(1)
            for (innerRes in resCandidates) {
                if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")
                val compactBitmap = fitToSquareCanvas(source, STICKER_SIZE_PX, innerRes)
                try {
                    val encoded = binarySearchQuality(
                        minQ = params.qualityMin,
                        maxQ = params.qualityMax,
                        steps = params.steps,
                        maxBytes = maxBytes,
                        shouldAbort = shouldAbort
                    ) { q ->
                        WebPContainer.encodeFakeVidWebP(
                            bitmap = compactBitmap,
                            width = STICKER_SIZE_PX,
                            height = STICKER_SIZE_PX,
                            quality = q,
                            lossless = false,
                            frameCount = 3,
                            frameDurationMs = 300
                        )
                    }
                    if (encoded != null) {
                        val check = validateAnimated(encoded)
                        if (check != null) {
                            return ConversionOutcome.Success(encoded, check, isAnimated = true, StickerConversionMode.FAKE_VID)
                        }
                    }
                } finally {
                    compactBitmap.recycle()
                }
            }

            return ConversionOutcome.Failure("Could not encode fake-vid animated WebP under 500 KB")
        } finally {
            canvasBitmap.recycle()
        }
    }

    /**
     * Animated WebP optimization:
     * 1) Normalize frame delays >= 8ms and handle >10s per [over10sHandling]
     * 2) Preserve smooth FPS (>= 12fps) by lowering quality/quantization/inner-sharpness BEFORE dropping FPS
     * 3) For LOWER_QUALITY (>10s kept intact), reduce inner resolution & quality first, then FPS if needed
     */
    private fun convertAnimated(
        rawFrames: List<DecodedFrame>,
        over10sHandling: Over10sHandling,
        params: ConverterParams,
        shouldAbort: () -> Boolean
    ): ConversionOutcome {
        val maxBytes = minOf(params.vidSizeMax, MAX_ANIMATED_BYTES)

        // Normalize frame timings (>= 8 ms per frame)
        var frames = rawFrames.map { frame ->
            val safeDur = if (frame.durationMs < params.minFrameDurationMs) params.minFrameDurationMs else frame.durationMs
            frame.copy(durationMs = safeDur)
        }

        val initialDuration = frames.sumOf { it.durationMs }
        var keepOver10sDuration = false
        if (initialDuration > params.durationMaxMs) {
            when (over10sHandling) {
                Over10sHandling.ASK_USER -> {
                    return ConversionOutcome.NeedsTrimConfirmation(initialDuration)
                }
                Over10sHandling.TRIM_10S -> {
                    frames = trimFramesToMaxDuration(frames, params.durationMaxMs)
                }
                Over10sHandling.LOWER_QUALITY -> {
                    keepOver10sDuration = true
                }
            }
        }

        val maxDurationCapMs = if (keepOver10sDuration) null else params.durationMaxMs
        val enforceMaxDuration = !keepOver10sDuration

        // Pre-cap extreme frame bursts before allocating 512x512 canvas frames
        val cappedFrames = resampleFramesToSmartFpsCap(
            frames = frames,
            fpsMax = params.fpsMax,
            allowOver10s = keepOver10sDuration,
            maxDurationMs = maxDurationCapMs
        )

        if (keepOver10sDuration) {
            return convertAnimatedOver10sLowerQuality(
                cappedFrames = cappedFrames,
                maxBytes = maxBytes,
                params = params,
                shouldAbort = shouldAbort
            )
        }

        // Fit every frame to 512x512 transparent canvas
        val scaledFrames = cappedFrames.map {
            if (shouldAbort()) throw InterruptedException("Aborted")
            DecodedFrame(fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, params.resMax), it.durationMs)
        }

        try {
            val dedupedFrames = deduplicateIdenticalFrames(scaledFrames)
            val sourceDuration = dedupedFrames.sumOf { it.durationMs }.coerceAtLeast(16)
            val sourceFps = ((dedupedFrames.size * 1000f) / sourceDuration).roundToInt()
                .coerceIn(params.fpsMin, params.fpsMax)

            // Pass A: Full capped FPS at 512x512
            val passAResult = searchAnimatedQuality(
                frames = dedupedFrames,
                minQ = params.qualityMin,
                maxQ = params.qualityMax,
                steps = minOf(params.steps, 4),
                maxBytes = maxBytes,
                probeMinFirst = false,
                shouldAbort = shouldAbort
            )
            passAResult.successBytes?.let { bytes ->
                validateAnimated(bytes, enforceMaxDuration)?.let { check ->
                    return ConversionOutcome.Success(bytes, check, isAnimated = true, StickerConversionMode.ANIMATED)
                }
            }

            // Pass B1: Keep smooth FPS (>= 12fps) at 512x512 first, then lower quality/quantization
            val minBytesPerFrame = if (passAResult.minQBytes > 0 && dedupedFrames.isNotEmpty()) {
                passAResult.minQBytes.toFloat() / dedupedFrames.size.toFloat()
            } else 0f
            val maxQBytesPerFrame = if (passAResult.maxQBytes > 0 && dedupedFrames.isNotEmpty()) {
                passAResult.maxQBytes.toFloat() / dedupedFrames.size.toFloat()
            } else Float.MAX_VALUE

            var smoothFrames = dedupedFrames
            val smoothFloor = minOf(sourceFps, SMOOTH_FPS_FLOOR)
            var lastTriedFrameCount = dedupedFrames.size
            if (dedupedFrames.size > 3 && sourceFps > smoothFloor) {
                val smoothFpsSteps = generatePowerCurveInts(sourceFps, smoothFloor, params.fpsPower, steps = 3).drop(1)
                for (targetFps in smoothFpsSteps) {
                    if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")
                    val resampled = resampleFramesByFps(dedupedFrames, targetFps, maxDurationCapMs)
                    if (resampled.size >= lastTriedFrameCount) continue
                    lastTriedFrameCount = resampled.size
                    smoothFrames = resampled

                    if (targetFps > smoothFloor &&
                        minBytesPerFrame > 0f &&
                        resampled.size * minBytesPerFrame > maxBytes * 1.12f
                    ) {
                        continue
                    }

                    val likelyNeedsLowerQ = resampled.size * maxQBytesPerFrame > maxBytes * 1.25f
                    val passBResult = searchAnimatedQuality(
                        frames = resampled,
                        minQ = params.qualityMin,
                        maxQ = params.qualityMax,
                        steps = minOf(params.steps, 4),
                        maxBytes = maxBytes,
                        probeMinFirst = likelyNeedsLowerQ,
                        shouldAbort = shouldAbort
                    )
                    passBResult.successBytes?.let { bytes ->
                        validateAnimated(bytes, enforceMaxDuration)?.let { check ->
                            return ConversionOutcome.Success(bytes, check, isAnimated = true, StickerConversionMode.ANIMATED)
                        }
                    }
                }
            }

            // Try lower WebP quality (6..14) + color quantization at smooth FPS before losing resolution
            if (smoothFrames.size * minBytesPerFrame <= maxBytes * 1.6f) {
                val lowQResult = searchAnimatedQuality(
                    frames = smoothFrames,
                    minQ = 6,
                    maxQ = (params.qualityMin - 1).coerceAtLeast(6),
                    steps = 3,
                    maxBytes = maxBytes,
                    probeMinFirst = true,
                    shouldAbort = shouldAbort
                )
                lowQResult.successBytes?.let { bytes ->
                    validateAnimated(bytes, enforceMaxDuration)?.let { check ->
                        return ConversionOutcome.Success(bytes, check, isAnimated = true, StickerConversionMode.ANIMATED)
                    }
                }
            }

            val quantizedBase = if (dedupedFrames.size > 60 && sourceFps > smoothFloor) {
                resampleFramesByFps(dedupedFrames, smoothFloor, maxDurationCapMs)
            } else {
                smoothFrames
            }
            val quantizedFrames = quantizedBase.map {
                if (shouldAbort()) throw InterruptedException("Aborted")
                DecodedFrame(quantizeColorsAlphaSafe(it.bitmap, levelsPerChannel = 24), it.durationMs)
            }
            try {
                val passQuantResult = searchAnimatedQuality(
                    frames = quantizedFrames,
                    minQ = 8,
                    maxQ = params.qualityMax,
                    steps = 3,
                    maxBytes = maxBytes,
                    probeMinFirst = true,
                    shouldAbort = shouldAbort
                )
                passQuantResult.successBytes?.let { bytes ->
                    validateAnimated(bytes, enforceMaxDuration)?.let { check ->
                        return ConversionOutcome.Success(bytes, check, isAnimated = true, StickerConversionMode.ANIMATED)
                    }
                }
            } finally {
                quantizedFrames.forEach { it.bitmap.recycle() }
            }

            // Pass C: Lower inner sharpness/resolution inside 512x512 while preserving smooth FPS (>= 12fps)
            val smoothCap = if (cappedFrames.size > 64) {
                resampleFramesByFps(cappedFrames, maxOf(smoothFloor, minOf(sourceFps, 14)), maxDurationCapMs)
            } else {
                cappedFrames
            }
            val smoothInnerResSteps = listOf(416, 352, 288).filter { it < params.resMax }
            for (innerRes in smoothInnerResSteps) {
                if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")
                val innerScaled = smoothCap.map {
                    DecodedFrame(fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, innerRes), it.durationMs)
                }
                try {
                    val dedupInner = deduplicateIdenticalFrames(innerScaled)
                    val passCResult = searchAnimatedQuality(
                        frames = dedupInner,
                        minQ = 8,
                        maxQ = 90,
                        steps = 4,
                        maxBytes = maxBytes,
                        probeMinFirst = innerRes >= 384,
                        shouldAbort = shouldAbort
                    )
                    passCResult.successBytes?.let { bytes ->
                        validateAnimated(bytes, enforceMaxDuration)?.let { check ->
                            return ConversionOutcome.Success(bytes, check, isAnimated = true, StickerConversionMode.ANIMATED)
                        }
                    }
                } finally {
                    innerScaled.forEach { it.bitmap.recycle() }
                }
            }

            // Pass D: Last resort gradual FPS reduction paired with compact inner resolution
            val fallbackFpsList = listOf(10, 8, params.fpsMin.coerceAtLeast(6)).distinct().filter { it < sourceFps || it <= 10 }
            for (targetFps in fallbackFpsList) {
                if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")
                val innerRes = if (targetFps <= 6) params.resMin.coerceAtMost(256) else 320
                val fpsBase = resampleFramesByFps(cappedFrames, targetFps, maxDurationCapMs)
                val innerScaled = fpsBase.map {
                    DecodedFrame(fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, innerRes), it.durationMs)
                }
                try {
                    val dedupInner = deduplicateIdenticalFrames(innerScaled)
                    val passDResult = searchAnimatedQuality(
                        frames = dedupInner,
                        minQ = 5,
                        maxQ = 85,
                        steps = 3,
                        maxBytes = maxBytes,
                        probeMinFirst = false,
                        shouldAbort = shouldAbort
                    )
                    passDResult.successBytes?.let { bytes ->
                        validateAnimated(bytes, enforceMaxDuration)?.let { check ->
                            return ConversionOutcome.Success(bytes, check, isAnimated = true, StickerConversionMode.ANIMATED)
                        }
                    }
                } finally {
                    innerScaled.forEach { it.bitmap.recycle() }
                }
            }

            return ConversionOutcome.Failure("Could not compress animated sticker under 500 KB")
        } finally {
            scaledFrames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }
    }

    /**
     * Converts >10s animations keeping their full duration under WhatsApp's 500 KB limit.
     * Prioritizes reducing image sharpness (inner resolution + WebP quality + color quantization)
     * before stepping down FPS.
     */
    private fun convertAnimatedOver10sLowerQuality(
        cappedFrames: List<DecodedFrame>,
        maxBytes: Int,
        params: ConverterParams,
        shouldAbort: () -> Boolean
    ): ConversionOutcome {
        val totalDuration = cappedFrames.sumOf { it.durationMs }.coerceAtLeast(16)
        val baseFps = ((cappedFrames.size * 1000f) / totalDuration).roundToInt().coerceIn(4, params.fpsMax)

        // 1. Try full 512px with reduced WebP quality first
        val fullResFrames = cappedFrames.map {
            if (shouldAbort()) throw InterruptedException("Aborted")
            DecodedFrame(fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, params.resMax), it.durationMs)
        }
        try {
            val dedupFull = deduplicateIdenticalFrames(fullResFrames)
            val fullResPass = searchAnimatedQuality(
                frames = dedupFull,
                minQ = 5,
                maxQ = 82,
                steps = 4,
                maxBytes = maxBytes,
                probeMinFirst = true,
                shouldAbort = shouldAbort
            )
            fullResPass.successBytes?.let { bytes ->
                validateAnimated(bytes, enforceMaxDuration = false)?.let { check ->
                    return ConversionOutcome.Success(
                        webpBytes = bytes,
                        inspection = check,
                        isAnimated = true,
                        conversionMode = StickerConversionMode.ANIMATED,
                        preservedOver10s = true
                    )
                }
            }
        } finally {
            fullResFrames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }

        // 2. Lower sharpness (inner resolution inside 512x512 canvas) while preserving FPS
        val sharpnessSteps = listOf(384, 304, 240, 192)
        for (innerRes in sharpnessSteps) {
            if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")
            val innerScaled = cappedFrames.map {
                DecodedFrame(fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, innerRes), it.durationMs)
            }
            try {
                val dedupInner = deduplicateIdenticalFrames(innerScaled)
                val resResult = searchAnimatedQuality(
                    frames = dedupInner,
                    minQ = 5,
                    maxQ = 82,
                    steps = 4,
                    maxBytes = maxBytes,
                    probeMinFirst = true,
                    shouldAbort = shouldAbort
                )
                resResult.successBytes?.let { bytes ->
                    validateAnimated(bytes, enforceMaxDuration = false)?.let { check ->
                        return ConversionOutcome.Success(
                            webpBytes = bytes,
                            inspection = check,
                            isAnimated = true,
                            conversionMode = StickerConversionMode.ANIMATED,
                            preservedOver10s = true
                        )
                    }
                }
            } finally {
                innerScaled.forEach { it.bitmap.recycle() }
            }
        }

        // 3. Quantize colors + reduced sharpness at preserved FPS
        val quantized256 = cappedFrames.map {
            if (shouldAbort()) throw InterruptedException("Aborted")
            val scaled = fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, 240)
            val quant = quantizeColorsAlphaSafe(scaled, levelsPerChannel = 16)
            scaled.recycle()
            DecodedFrame(quant, it.durationMs)
        }
        try {
            val dedupQuant = deduplicateIdenticalFrames(quantized256)
            val quantResult = searchAnimatedQuality(
                frames = dedupQuant,
                minQ = 5,
                maxQ = 72,
                steps = 3,
                maxBytes = maxBytes,
                probeMinFirst = true,
                shouldAbort = shouldAbort
            )
            quantResult.successBytes?.let { bytes ->
                validateAnimated(bytes, enforceMaxDuration = false)?.let { check ->
                    return ConversionOutcome.Success(
                        webpBytes = bytes,
                        inspection = check,
                        isAnimated = true,
                        conversionMode = StickerConversionMode.ANIMATED,
                        preservedOver10s = true
                    )
                }
            }
        } finally {
            quantized256.forEach { it.bitmap.recycle() }
        }

        // 4. Last resort: gently step down FPS + sharpness so very long animations still fit <= 500 KB
        val fallbackPairs = listOf(10 to 240, 8 to 208, 6 to 192, 4 to 160)
        for ((targetFps, innerRes) in fallbackPairs) {
            if (targetFps >= baseFps && cappedFrames.size <= 90) continue
            if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")
            val resampled = resampleFramesByFps(cappedFrames, targetFps, maxDurationMs = null)
            val innerScaled = resampled.map {
                DecodedFrame(fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, innerRes), it.durationMs)
            }
            try {
                val dedupInner = deduplicateIdenticalFrames(innerScaled)
                val fallbackResult = searchAnimatedQuality(
                    frames = dedupInner,
                    minQ = 4,
                    maxQ = 68,
                    steps = 3,
                    maxBytes = maxBytes,
                    probeMinFirst = true,
                    shouldAbort = shouldAbort
                )
                fallbackResult.successBytes?.let { bytes ->
                    validateAnimated(bytes, enforceMaxDuration = false)?.let { check ->
                        return ConversionOutcome.Success(
                            webpBytes = bytes,
                            inspection = check,
                            isAnimated = true,
                            conversionMode = StickerConversionMode.ANIMATED,
                            preservedOver10s = true
                        )
                    }
                }
            } finally {
                innerScaled.forEach { it.bitmap.recycle() }
            }
        }

        return ConversionOutcome.Failure("Animation too large to fit under 500 KB even at reduced quality")
    }

    private class QualitySearchResult(
        val successBytes: ByteArray?,
        val minQBytes: Int,
        val maxQBytes: Int
    )

    private fun estimateAnimatedWebPSize(
        frames: List<DecodedFrame>,
        quality: Int,
        shouldAbort: () -> Boolean
    ): Int {
        if (frames.size <= 12) {
            return WebPContainer.encodeAnimatedWebP(
                frames = frames,
                width = STICKER_SIZE_PX,
                height = STICKER_SIZE_PX,
                quality = quality,
                shouldAbort = shouldAbort
            ).size
        }
        val sampleCount = 8
        val sampleFrames = (0 until sampleCount).map { i ->
            val idx = (((i + 0.5f) * frames.size) / sampleCount).toInt().coerceIn(0, frames.lastIndex)
            frames[idx]
        }
        val sampleBytes = WebPContainer.encodeAnimatedWebP(
            frames = sampleFrames,
            width = STICKER_SIZE_PX,
            height = STICKER_SIZE_PX,
            quality = quality,
            shouldAbort = shouldAbort
        )
        val payloadOnly = (sampleBytes.size - 38).coerceAtLeast(sampleCount * 64)
        val bytesPerFrame = payloadOnly.toDouble() / sampleCount.toDouble()
        return (38.0 + bytesPerFrame * frames.size).roundToInt()
    }

    private fun searchAnimatedQuality(
        frames: List<DecodedFrame>,
        minQ: Int,
        maxQ: Int,
        steps: Int,
        maxBytes: Int,
        probeMinFirst: Boolean,
        shouldAbort: () -> Boolean
    ): QualitySearchResult {
        if (shouldAbort()) return QualitySearchResult(null, 0, 0)

        // Small frame counts encode quickly directly without sampling overhead
        if (frames.size <= 16) {
            return searchAnimatedQualityExact(frames, minQ, maxQ, steps, maxBytes, probeMinFirst, shouldAbort)
        }

        var estLowBytes = 0
        var estHighBytes = 0
        val sampleTargetMax = (maxBytes * 0.93f).toInt()

        if (probeMinFirst) {
            estLowBytes = estimateAnimatedWebPSize(frames, minQ, shouldAbort)
            if (estLowBytes > maxBytes * 1.18f) {
                return QualitySearchResult(null, estLowBytes, 0)
            }
            if (shouldAbort()) return QualitySearchResult(null, estLowBytes, 0)
            estHighBytes = estimateAnimatedWebPSize(frames, maxQ, shouldAbort)
        } else {
            estHighBytes = estimateAnimatedWebPSize(frames, maxQ, shouldAbort)
            if (estHighBytes <= maxBytes * 0.92f) {
                val fullMax = WebPContainer.encodeAnimatedWebP(frames, STICKER_SIZE_PX, STICKER_SIZE_PX, maxQ, shouldAbort = shouldAbort)
                if (fullMax.size <= maxBytes) {
                    return QualitySearchResult(fullMax, 0, fullMax.size)
                }
            }
            if (shouldAbort()) return QualitySearchResult(null, 0, estHighBytes)
            estLowBytes = estimateAnimatedWebPSize(frames, minQ, shouldAbort)
            if (estLowBytes > maxBytes * 1.18f) {
                return QualitySearchResult(null, estLowBytes, estHighBytes)
            }
        }

        // Fast binary search on the 8-frame sample (10x faster than full-frame search)
        var bestSampleQ = minQ
        if (estHighBytes <= sampleTargetMax) {
            bestSampleQ = maxQ
        } else {
            var low = minQ + 1
            var high = maxQ - 1
            var remainingSteps = maxOf(2, steps)
            var firstProbe = true
            while (low <= high && remainingSteps > 0 && !shouldAbort()) {
                val mid = if (firstProbe && estHighBytes > estLowBytes) {
                    firstProbe = false
                    val ratio = (sampleTargetMax - estLowBytes).toFloat() / (estHighBytes - estLowBytes).toFloat()
                    (minQ + ((maxQ - minQ) * ratio * 0.92f).roundToInt()).coerceIn(low, high)
                } else {
                    (low + high) ushr 1
                }
                val estMid = estimateAnimatedWebPSize(frames, mid, shouldAbort)
                if (estMid <= sampleTargetMax) {
                    bestSampleQ = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
                remainingSteps--
            }
        }

        if (shouldAbort()) return QualitySearchResult(null, estLowBytes, estHighBytes)

        // Single final full-frame encode at best estimated quality (+ at most 1-2 exact adjustments)
        val firstFull = WebPContainer.encodeAnimatedWebP(frames, STICKER_SIZE_PX, STICKER_SIZE_PX, bestSampleQ, shouldAbort = shouldAbort)
        if (firstFull.size <= maxBytes) {
            var bestFull = firstFull
            if (bestSampleQ + 6 <= maxQ && firstFull.size < maxBytes * 0.80f && !shouldAbort()) {
                val boostQ = minOf(maxQ, (bestSampleQ + maxQ + 1) ushr 1)
                val boosted = WebPContainer.encodeAnimatedWebP(frames, STICKER_SIZE_PX, STICKER_SIZE_PX, boostQ, shouldAbort = shouldAbort)
                if (boosted.size <= maxBytes) {
                    bestFull = boosted
                }
            }
            return QualitySearchResult(bestFull, estLowBytes, maxOf(estHighBytes, bestFull.size))
        }

        if (bestSampleQ > minQ && !shouldAbort()) {
            val ratio = maxBytes.toFloat() / firstFull.size.toFloat()
            val adjQ = (bestSampleQ * ratio * 0.88f).roundToInt().coerceIn(minQ, bestSampleQ - 1)
            val adjFull = WebPContainer.encodeAnimatedWebP(frames, STICKER_SIZE_PX, STICKER_SIZE_PX, adjQ, shouldAbort = shouldAbort)
            if (adjFull.size <= maxBytes) {
                return QualitySearchResult(adjFull, estLowBytes, firstFull.size)
            }
            if (adjQ > minQ && !shouldAbort()) {
                val minFull = WebPContainer.encodeAnimatedWebP(frames, STICKER_SIZE_PX, STICKER_SIZE_PX, minQ, shouldAbort = shouldAbort)
                if (minFull.size <= maxBytes) {
                    return QualitySearchResult(minFull, minFull.size, firstFull.size)
                }
                return QualitySearchResult(null, minFull.size, firstFull.size)
            }
            return QualitySearchResult(null, adjFull.size, firstFull.size)
        }

        return QualitySearchResult(null, firstFull.size, maxOf(estHighBytes, firstFull.size))
    }

    private fun searchAnimatedQualityExact(
        frames: List<DecodedFrame>,
        minQ: Int,
        maxQ: Int,
        steps: Int,
        maxBytes: Int,
        probeMinFirst: Boolean,
        shouldAbort: () -> Boolean
    ): QualitySearchResult {
        if (shouldAbort()) return QualitySearchResult(null, 0, 0)

        var highBytesOut = 0
        var lowBytesOut = 0
        var bestBytes: ByteArray?

        if (probeMinFirst) {
            val lowBytes = WebPContainer.encodeAnimatedWebP(frames, STICKER_SIZE_PX, STICKER_SIZE_PX, minQ, shouldAbort = shouldAbort)
            lowBytesOut = lowBytes.size
            if (lowBytesOut > maxBytes) {
                return QualitySearchResult(null, lowBytesOut, 0)
            }
            bestBytes = lowBytes

            if (shouldAbort()) return QualitySearchResult(bestBytes, lowBytesOut, 0)
            val highBytes = WebPContainer.encodeAnimatedWebP(frames, STICKER_SIZE_PX, STICKER_SIZE_PX, maxQ, shouldAbort = shouldAbort)
            highBytesOut = highBytes.size
            if (highBytesOut <= maxBytes) {
                return QualitySearchResult(highBytes, lowBytesOut, highBytesOut)
            }
        } else {
            val highBytes = WebPContainer.encodeAnimatedWebP(frames, STICKER_SIZE_PX, STICKER_SIZE_PX, maxQ, shouldAbort = shouldAbort)
            highBytesOut = highBytes.size
            if (highBytesOut <= maxBytes) {
                return QualitySearchResult(highBytes, 0, highBytesOut)
            }

            if (shouldAbort()) return QualitySearchResult(null, 0, highBytesOut)
            val lowBytes = WebPContainer.encodeAnimatedWebP(frames, STICKER_SIZE_PX, STICKER_SIZE_PX, minQ, shouldAbort = shouldAbort)
            lowBytesOut = lowBytes.size
            if (lowBytesOut > maxBytes) {
                return QualitySearchResult(null, lowBytesOut, highBytesOut)
            }
            bestBytes = lowBytes
        }

        var low = minQ + 1
        var high = maxQ - 1
        var remainingSteps = steps
        var firstProbe = true

        while (low <= high && remainingSteps > 0 && !shouldAbort()) {
            val mid = if (firstProbe && highBytesOut > lowBytesOut) {
                firstProbe = false
                val ratio = (maxBytes - lowBytesOut).toFloat() / (highBytesOut - lowBytesOut).toFloat()
                (minQ + ((maxQ - minQ) * ratio * 0.9f).roundToInt()).coerceIn(low, high)
            } else {
                (low + high) ushr 1
            }
            val candidate = WebPContainer.encodeAnimatedWebP(frames, STICKER_SIZE_PX, STICKER_SIZE_PX, mid, shouldAbort = shouldAbort)
            if (candidate.size <= maxBytes) {
                bestBytes = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
            remainingSteps--
        }
        return QualitySearchResult(bestBytes, lowBytesOut, highBytesOut)
    }

    private fun resampleFramesToSmartFpsCap(
        frames: List<DecodedFrame>,
        fpsMax: Int,
        allowOver10s: Boolean,
        maxDurationMs: Int?
    ): List<DecodedFrame> {
        if (frames.size <= 2) return frames
        val totalDuration = frames.sumOf { it.durationMs }.coerceAtLeast(16)
        val rawFps = (frames.size * 1000f) / totalDuration

        val frameBudget = if (allowOver10s) 100 else 90
        val fpsFloor = if (allowOver10s) 6 else SMOOTH_FPS_FLOOR
        val smartMaxFps = when {
            allowOver10s -> minOf(fpsMax, 15)
            frames.size > 140 -> minOf(fpsMax, 16)
            frames.size > 72 -> minOf(fpsMax, 20)
            else -> fpsMax
        }
        if (rawFps <= smartMaxFps + 1f && frames.size <= frameBudget) return frames
        val budgetFps = ((frameBudget * 1000f) / totalDuration).roundToInt().coerceAtLeast(fpsFloor)
        val targetFps = minOf(smartMaxFps, budgetFps)
        return resampleFramesByFps(frames, targetFps, maxDurationMs)
    }

    /**
     * Binary searches quality in [minQ..maxQ] to find the highest quality whose output
     * byte array is <= [maxBytes]. Tests maxQ first (fast win) and minQ (early fail).
     */
    private inline fun binarySearchQuality(
        minQ: Int,
        maxQ: Int,
        steps: Int,
        maxBytes: Int,
        crossinline shouldAbort: () -> Boolean,
        encode: (Int) -> ByteArray
    ): ByteArray? {
        if (shouldAbort()) return null
        val highBytes = encode(maxQ)
        if (highBytes.size <= maxBytes) return highBytes

        if (shouldAbort()) return null
        val lowBytes = encode(minQ)
        if (lowBytes.size > maxBytes) return null

        var bestBytes = lowBytes
        var low = minQ + 1
        var high = maxQ - 1
        var remainingSteps = steps

        while (low <= high && remainingSteps > 0 && !shouldAbort()) {
            val mid = (low + high) ushr 1
            val candidate = encode(mid)
            if (candidate.size <= maxBytes) {
                bestBytes = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
            remainingSteps--
        }
        return bestBytes
    }

    /**
     * Alpha-preserving RGB color quantization: keeps alpha channel exact for crisp
     * transparent borders while reducing RGB noise to boost WebP compression efficiency.
     */
    private fun quantizeColorsAlphaSafe(source: Bitmap, levelsPerChannel: Int): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val buckets = (levelsPerChannel - 1).coerceAtLeast(1)
        val lut = IntArray(256) { v ->
            (((v * buckets + 127) / 255) * 255 / buckets).coerceIn(0, 255)
        }
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            if (a == 0) {
                pixels[i] = 0
                continue
            }
            val r = lut[(c ushr 16) and 0xFF]
            val g = lut[(c ushr 8) and 0xFF]
            val b = lut[c and 0xFF]
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun generatePowerCurveInts(start: Int, end: Int, power: Float, steps: Int): List<Int> {
        if (steps <= 1 || start == end) return listOf(start)
        val out = LinkedHashSet<Int>()
        for (i in 0 until steps) {
            val t = i.toFloat() / (steps - 1).toFloat()
            val curved = t.pow(power)
            val value = (start + (end - start) * curved).roundToInt()
            out.add(value)
        }
        out.add(end)
        return out.toList()
    }

    private fun deduplicateIdenticalFrames(frames: List<DecodedFrame>): List<DecodedFrame> {
        if (frames.size <= 2) return frames
        val result = mutableListOf<DecodedFrame>()
        var current = frames.first()
        for (i in 1 until frames.size) {
            val next = frames[i]
            if (current.bitmap.sameAs(next.bitmap) && frames.size - result.size > 2) {
                current = current.copy(durationMs = current.durationMs + next.durationMs)
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return if (result.size >= 2) result else frames.take(2)
    }

    private fun resampleFramesByFps(
        frames: List<DecodedFrame>,
        targetFps: Int,
        maxDurationMs: Int? = MAX_ANIMATION_DURATION_MS
    ): List<DecodedFrame> {
        if (frames.size <= 2) return frames
        val totalDuration = frames.sumOf { it.durationMs }.coerceAtLeast(16)
        val targetFrameDur = maxOf(MIN_FRAME_DURATION_MS, (1000f / targetFps).roundToInt())
        val targetCount = maxOf(2, (totalDuration.toFloat() / targetFrameDur).roundToInt())
        if (targetCount >= frames.size) return frames

        val endTimes = IntArray(frames.size)
        var acc = 0
        for (i in frames.indices) {
            acc += frames[i].durationMs
            endTimes[i] = acc
        }

        val out = mutableListOf<DecodedFrame>()
        val stepMs = totalDuration.toFloat() / targetCount.toFloat()
        var lastIdx = -1
        for (k in 0 until targetCount) {
            val sampleTime = (k * stepMs + stepMs * 0.5f).roundToInt().coerceIn(0, totalDuration - 1)
            var idx = 0
            while (idx < endTimes.size - 1 && sampleTime >= endTimes[idx]) {
                idx++
            }
            val dur = maxOf(MIN_FRAME_DURATION_MS, stepMs.roundToInt())
            if (idx == lastIdx && out.isNotEmpty() && (targetCount - k) > 1) {
                val prev = out.removeAt(out.lastIndex)
                out.add(prev.copy(durationMs = prev.durationMs + dur))
            } else {
                out.add(DecodedFrame(frames[idx].bitmap, dur))
                lastIdx = idx
            }
        }
        val clampedTotal = out.sumOf { it.durationMs }
        return if (maxDurationMs != null && clampedTotal > maxDurationMs) {
            trimFramesToMaxDuration(out, maxDurationMs)
        } else if (out.size >= 2) {
            out
        } else {
            frames.take(2)
        }
    }

    /**
     * Generates a 96x96 PNG tray icon strictly under 50 KB from a sticker WebP file.
     */
    fun generateTrayIcon(stickerWebpFile: File): ByteArray? {
        if (!stickerWebpFile.exists()) return null
        val bytes = stickerWebpFile.readBytes()
        val frames = WebPContainer.decodeAnimatedFrames(bytes)
        val firstBitmap = frames.firstOrNull { hasVisiblePixels(it.bitmap) }?.bitmap
            ?: frames.firstOrNull()?.bitmap
            ?: decodeSingleBitmap(bytes)
            ?: return null

        val trayBitmap = fitToSquareCanvas(firstBitmap, TRAY_SIZE_PX, TRAY_SIZE_PX)
        try {
            val maxTray = defaultParams.traySizeMax
            val out = ByteArrayOutputStream()
            trayBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            val pngBytes = out.toByteArray()
            if (pngBytes.size <= maxTray) return pngBytes

            val quantized = quantizeColorsAlphaSafe(trayBitmap, levelsPerChannel = 16)
            try {
                val qOut = ByteArrayOutputStream()
                quantized.compress(Bitmap.CompressFormat.PNG, 100, qOut)
                val qBytes = qOut.toByteArray()
                if (qBytes.size <= maxTray) return qBytes
            } finally {
                quantized.recycle()
            }

            val smallerTray = fitToSquareCanvas(firstBitmap, TRAY_SIZE_PX, 72)
            try {
                val out2 = ByteArrayOutputStream()
                smallerTray.compress(Bitmap.CompressFormat.PNG, 100, out2)
                return out2.toByteArray().takeIf { it.size <= MAX_TRAY_BYTES }
            } finally {
                smallerTray.recycle()
            }
        } finally {
            trayBitmap.recycle()
            frames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            if (frames.isEmpty() && !firstBitmap.isRecycled) firstBitmap.recycle()
        }
    }

    private fun hasVisiblePixels(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return false
        val stepX = maxOf(1, w / 16)
        val stepY = maxOf(1, h / 16)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if ((bmp.getPixel(x, y) ushr 24) > 8) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    fun validateExistingWebp(
        file: File,
        expectAnimated: Boolean,
        allowOver10s: Boolean = false
    ): WebPInspectionResult? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        return if (expectAnimated) validateAnimated(bytes, enforceMaxDuration = !allowOver10s) else validateStatic(bytes)
    }

    private fun validateStatic(bytes: ByteArray): WebPInspectionResult? {
        val info = WebPContainer.inspect(bytes)
        if (!info.isValidWebP) return null
        if (info.isAnimated) return null
        if (info.width != STICKER_SIZE_PX || info.height != STICKER_SIZE_PX) return null
        if (info.fileSizeBytes > MAX_STATIC_BYTES) return null
        return info
    }

    private fun validateAnimated(
        bytes: ByteArray,
        enforceMaxDuration: Boolean = true
    ): WebPInspectionResult? {
        val info = WebPContainer.inspect(bytes)
        if (!info.isValidWebP) return null
        if (!info.isAnimated) return null
        if (info.width != STICKER_SIZE_PX || info.height != STICKER_SIZE_PX) return null
        if (info.fileSizeBytes > MAX_ANIMATED_BYTES) return null
        if (info.frameCount < 2) return null
        if (info.minFrameDurationMs < MIN_FRAME_DURATION_MS) return null
        if (enforceMaxDuration && info.totalDurationMs > MAX_ANIMATION_DURATION_MS) return null
        if (info.loopCount != 0) return null
        return info
    }

    private fun trimFramesToMaxDuration(frames: List<DecodedFrame>, maxMs: Int): List<DecodedFrame> {
        val kept = mutableListOf<DecodedFrame>()
        var elapsed = 0
        for (f in frames) {
            if (elapsed >= maxMs) break
            val remaining = maxMs - elapsed
            if (f.durationMs <= remaining) {
                kept.add(f)
                elapsed += f.durationMs
            } else if (remaining >= MIN_FRAME_DURATION_MS) {
                kept.add(f.copy(durationMs = remaining))
                elapsed += remaining
            } else {
                break
            }
        }
        return if (kept.size >= 2) kept else frames.take(2)
    }

    /**
     * Scales source bitmap proportionally (no distortion, no cropping) to fit inside
     * [innerMaxPx] centered on a transparent [canvasSizePx] x [canvasSizePx] canvas.
     */
    private fun fitToSquareCanvas(source: Bitmap, canvasSizePx: Int, innerMaxPx: Int): Bitmap {
        val result = Bitmap.createBitmap(canvasSizePx, canvasSizePx, Bitmap.Config.ARGB_8888)
        result.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(result)

        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)
        val scale = minOf(innerMaxPx.toFloat() / srcW, innerMaxPx.toFloat() / srcH)
        val destW = srcW * scale
        val destH = srcH * scale
        val left = (canvasSizePx - destW) / 2f
        val top = (canvasSizePx - destH) / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(source, null, RectF(left, top, left + destW, top + destH), paint)
        return result
    }
}
