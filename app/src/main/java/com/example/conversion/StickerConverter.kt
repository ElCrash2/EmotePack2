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
        val conversionMode: StickerConversionMode
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

    private val defaultParams = ConverterParams()

    fun convert(
        inputBytes: ByteArray,
        expectAnimated: Boolean,
        allowTrimOver10s: Boolean,
        shouldAbort: () -> Boolean = { false }
    ): ConversionOutcome {
        val mode = if (expectAnimated) StickerConversionMode.ANIMATED else StickerConversionMode.STATIC
        return convert(inputBytes, mode, allowTrimOver10s, defaultParams, shouldAbort)
    }

    fun convert(
        inputBytes: ByteArray,
        mode: StickerConversionMode,
        allowTrimOver10s: Boolean,
        params: ConverterParams = defaultParams,
        shouldAbort: () -> Boolean = { false }
    ): ConversionOutcome {
        if (inputBytes.isEmpty()) return ConversionOutcome.Failure("Empty input image")
        if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")

        val format = sniffContainerFormat(inputBytes)
        val decodedFrames = decodeInputFrames(inputBytes, format, shouldAbort)
        if (decodedFrames.isEmpty()) {
            return ConversionOutcome.Failure("Unsupported or corrupt image format ($format)")
        }

        return try {
            when (mode) {
                StickerConversionMode.STATIC -> {
                    convertStatic(decodedFrames.first().bitmap, params, shouldAbort)
                }
                StickerConversionMode.FAKE_VID -> {
                    if (decodedFrames.size > 1) {
                        convertAnimated(decodedFrames, allowTrimOver10s, params, shouldAbort)
                    } else {
                        convertFakeVid(decodedFrames.first().bitmap, params, shouldAbort)
                    }
                }
                StickerConversionMode.ANIMATED -> {
                    if (decodedFrames.size > 1) {
                        convertAnimated(decodedFrames, allowTrimOver10s, params, shouldAbort)
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

    private fun decodeInputFrames(
        bytes: ByteArray,
        format: SniffedContainerFormat,
        shouldAbort: () -> Boolean
    ): List<DecodedFrame> {
        when (format) {
            SniffedContainerFormat.GIF -> {
                val frames = GifFrameDecoder.decode(bytes, STICKER_SIZE_PX, shouldAbort)
                if (frames.isNotEmpty()) return frames
            }
            SniffedContainerFormat.WEBP -> {
                val frames = WebPContainer.decodeAnimatedFrames(bytes, STICKER_SIZE_PX, shouldAbort)
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
     * Animated WebP optimization modeled on sticker-convert:
     * 1) Normalize frame delays >= 8ms and trim >10s (prompting user when allowTrimOver10s = false)
     * 2) Lossless consecutive duplicate frame merging & pre-cap FPS <= fpsMax (30fps)
     * 3) Pass A: Binary search quality at full 512x512 resolution
     * 4) Pass B: Power-curve FPS resampling at 512x512
     * 5) Pass C: Alpha-preserving color quantization optimization
     * 6) Pass D: Power-curve inner resolution adjustment inside 512x512 canvas
     */
    private fun convertAnimated(
        rawFrames: List<DecodedFrame>,
        allowTrimOver10s: Boolean,
        params: ConverterParams,
        shouldAbort: () -> Boolean
    ): ConversionOutcome {
        val maxBytes = minOf(params.vidSizeMax, MAX_ANIMATED_BYTES)

        // Normalize frame timings (>= 8 ms per frame)
        var frames = rawFrames.map { frame ->
            val safeDur = if (frame.durationMs < params.minFrameDurationMs) params.minFrameDurationMs else frame.durationMs
            frame.copy(durationMs = safeDur)
        }

        val totalDuration = frames.sumOf { it.durationMs }
        if (totalDuration > params.durationMaxMs) {
            if (!allowTrimOver10s) {
                return ConversionOutcome.NeedsTrimConfirmation(totalDuration)
            }
            frames = trimFramesToMaxDuration(frames, params.durationMaxMs)
        }

        // Pre-cap excessive FPS (> fpsMax) before allocating 512x512 canvas frames
        val cappedFrames = resampleFramesToMaxFps(frames, params.fpsMax)

        // Fit every frame to 512x512 transparent canvas
        val scaledFrames = cappedFrames.map {
            if (shouldAbort()) throw InterruptedException("Aborted")
            DecodedFrame(fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, params.resMax), it.durationMs)
        }

        try {
            val dedupedFrames = deduplicateIdenticalFrames(scaledFrames)

            // Pass A: Full capped FPS at 512x512, binary search quality [qualityMin..qualityMax]
            val passA = binarySearchQuality(
                minQ = params.qualityMin,
                maxQ = params.qualityMax,
                steps = params.steps,
                maxBytes = maxBytes,
                shouldAbort = shouldAbort
            ) { q ->
                WebPContainer.encodeAnimatedWebP(dedupedFrames, STICKER_SIZE_PX, STICKER_SIZE_PX, q, shouldAbort = shouldAbort)
            }
            if (passA != null) {
                val check = validateAnimated(passA)
                if (check != null) {
                    return ConversionOutcome.Success(passA, check, isAnimated = true, StickerConversionMode.ANIMATED)
                }
            }

            // Pass B: Iterative FPS reduction via time-based resampling (keeping total duration faithful)
            val sourceDuration = dedupedFrames.sumOf { it.durationMs }.coerceAtLeast(16)
            val sourceFps = ((dedupedFrames.size * 1000f) / sourceDuration).roundToInt()
                .coerceIn(params.fpsMin, params.fpsMax)

            var lastTriedFrameCount = dedupedFrames.size
            if (dedupedFrames.size > 3 && sourceFps > params.fpsMin) {
                val fpsSteps = generatePowerCurveInts(sourceFps, params.fpsMin, params.fpsPower, steps = 4).drop(1)
                for (targetFps in fpsSteps) {
                    if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")
                    val resampled = resampleFramesByFps(dedupedFrames, targetFps)
                    if (resampled.size >= lastTriedFrameCount) continue
                    lastTriedFrameCount = resampled.size

                    val encoded = binarySearchQuality(
                        minQ = params.qualityMin,
                        maxQ = params.qualityMax,
                        steps = params.steps,
                        maxBytes = maxBytes,
                        shouldAbort = shouldAbort
                    ) { q ->
                        WebPContainer.encodeAnimatedWebP(resampled, STICKER_SIZE_PX, STICKER_SIZE_PX, q, shouldAbort = shouldAbort)
                    }
                    if (encoded != null) {
                        val check = validateAnimated(encoded)
                        if (check != null) {
                            return ConversionOutcome.Success(encoded, check, isAnimated = true, StickerConversionMode.ANIMATED)
                        }
                    }
                }
            }

            // Pass C: Alpha-safe color quantization at 512x512 with moderate FPS before dropping resolution
            val midFrames = if (dedupedFrames.size > 8) {
                resampleFramesByFps(dedupedFrames, maxOf(params.fpsMin, minOf(12, sourceFps)))
            } else {
                dedupedFrames
            }
            val quantizedFrames = midFrames.map {
                if (shouldAbort()) throw InterruptedException("Aborted")
                DecodedFrame(quantizeColorsAlphaSafe(it.bitmap, levelsPerChannel = 24), it.durationMs)
            }
            try {
                val passC = binarySearchQuality(
                    minQ = params.qualityMin,
                    maxQ = params.qualityMax,
                    steps = params.steps,
                    maxBytes = maxBytes,
                    shouldAbort = shouldAbort
                ) { q ->
                    WebPContainer.encodeAnimatedWebP(quantizedFrames, STICKER_SIZE_PX, STICKER_SIZE_PX, q, shouldAbort = shouldAbort)
                }
                if (passC != null) {
                    val check = validateAnimated(passC)
                    if (check != null) {
                        return ConversionOutcome.Success(passC, check, isAnimated = true, StickerConversionMode.ANIMATED)
                    }
                }
            } finally {
                quantizedFrames.forEach { it.bitmap.recycle() }
            }

            // Pass D: Last resort inner resolution reduction inside 512x512 transparent canvas
            val resSteps = generatePowerCurveInts(params.resMax, params.resMin, params.resPower, steps = 4).drop(1)
            for (innerRes in resSteps) {
                if (shouldAbort()) return ConversionOutcome.Failure("Cancelled")
                val innerScaled = cappedFrames.map {
                    DecodedFrame(fitToSquareCanvas(it.bitmap, STICKER_SIZE_PX, innerRes), it.durationMs)
                }
                try {
                    val dedupInner = deduplicateIdenticalFrames(innerScaled)
                    val compact = if (dedupInner.size > 6) {
                        resampleFramesByFps(dedupInner, params.fpsMin.coerceAtLeast(8))
                    } else {
                        dedupInner
                    }
                    val encoded = binarySearchQuality(
                        minQ = params.qualityMin,
                        maxQ = params.qualityMax,
                        steps = params.steps,
                        maxBytes = maxBytes,
                        shouldAbort = shouldAbort
                    ) { q ->
                        WebPContainer.encodeAnimatedWebP(compact, STICKER_SIZE_PX, STICKER_SIZE_PX, q, shouldAbort = shouldAbort)
                    }
                    if (encoded != null) {
                        val check = validateAnimated(encoded)
                        if (check != null) {
                            return ConversionOutcome.Success(encoded, check, isAnimated = true, StickerConversionMode.ANIMATED)
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

        val step = 255f / (levelsPerChannel - 1).coerceAtLeast(1)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            if (a == 0) {
                pixels[i] = 0
                continue
            }
            val r = ((((c ushr 16) and 0xFF) / step).roundToInt() * step).roundToInt().coerceIn(0, 255)
            val g = ((((c ushr 8) and 0xFF) / step).roundToInt() * step).roundToInt().coerceIn(0, 255)
            val b = (((c and 0xFF) / step).roundToInt() * step).roundToInt().coerceIn(0, 255)
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

    private fun resampleFramesToMaxFps(frames: List<DecodedFrame>, maxFps: Int): List<DecodedFrame> {
        if (frames.size <= 2) return frames
        val totalDuration = frames.sumOf { it.durationMs }.coerceAtLeast(16)
        val avgFps = (frames.size * 1000f) / totalDuration
        if (avgFps <= maxFps + 1f && frames.size <= 150) return frames
        val targetFps = minOf(maxFps, (frames.size * 1000f / totalDuration).roundToInt().coerceAtLeast(8))
        return resampleFramesByFps(frames, targetFps)
    }

    private fun resampleFramesByFps(frames: List<DecodedFrame>, targetFps: Int): List<DecodedFrame> {
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
        return if (clampedTotal > MAX_ANIMATION_DURATION_MS) {
            trimFramesToMaxDuration(out, MAX_ANIMATION_DURATION_MS)
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

    fun validateExistingWebp(file: File, expectAnimated: Boolean): WebPInspectionResult? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        return if (expectAnimated) validateAnimated(bytes) else validateStatic(bytes)
    }

    private fun validateStatic(bytes: ByteArray): WebPInspectionResult? {
        val info = WebPContainer.inspect(bytes)
        if (!info.isValidWebP) return null
        if (info.isAnimated) return null
        if (info.width != STICKER_SIZE_PX || info.height != STICKER_SIZE_PX) return null
        if (info.fileSizeBytes > MAX_STATIC_BYTES) return null
        return info
    }

    private fun validateAnimated(bytes: ByteArray): WebPInspectionResult? {
        val info = WebPContainer.inspect(bytes)
        if (!info.isValidWebP) return null
        if (!info.isAnimated) return null
        if (info.width != STICKER_SIZE_PX || info.height != STICKER_SIZE_PX) return null
        if (info.fileSizeBytes > MAX_ANIMATED_BYTES) return null
        if (info.frameCount < 2) return null
        if (info.minFrameDurationMs < MIN_FRAME_DURATION_MS) return null
        if (info.totalDurationMs > MAX_ANIMATION_DURATION_MS) return null
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
