package com.example.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WebPInspectionResult(
    val isValidWebP: Boolean,
    val isAnimated: Boolean,
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    val frameCount: Int,
    val totalDurationMs: Int,
    val minFrameDurationMs: Int,
    val fileSizeBytes: Int,
    val loopCount: Int = 0
)

/**
 * Pure Kotlin RFC 9649 WebP RIFF container parser, animated WebP frame decoder,
 * animated WebP muxer (using Android framework's libwebp static frame encoder per keyframe),
 * fake-vid motionless animated WebP builder, and strict WhatsApp sticker validator.
 */
object WebPContainer {

    private const val VP8X_FLAG_ALPHA = 0x10
    private const val VP8X_FLAG_ANIMATION = 0x02

    private const val ANMF_FLAG_DISPOSE_BG = 0x01
    private const val ANMF_FLAG_NO_BLEND = 0x02

    private const val MAX_CHUNKS = 8192
    private const val MAX_FRAMES = 300
    private const val MAX_DECODED_DIMENSION = 512

    fun isWebP(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
    }

    fun inspect(bytes: ByteArray): WebPInspectionResult {
        if (!isWebP(bytes)) {
            return WebPInspectionResult(false, false, 0, 0, false, 0, 0, 0, bytes.size)
        }

        val riffDeclaredSize = readUInt32LE(bytes, 4)
        if (riffDeclaredSize < 4L || riffDeclaredSize + 8L != bytes.size.toLong()) {
            return WebPInspectionResult(false, false, 0, 0, false, 0, 0, 0, bytes.size)
        }

        var width = 0
        var height = 0
        var hasAlpha = false
        var isAnimated = false
        var frameCount = 0
        var totalDuration = 0
        var minFrameDuration = Int.MAX_VALUE
        var loopCount = 0
        var chunkGuard = 0
        var hasBitStream = false
        var truncatedOrMalformed = false

        var offset = 12
        while (offset + 8 <= bytes.size && chunkGuard++ < MAX_CHUNKS) {
            val fourCC = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = readUInt32LE(bytes, offset + 4).toInt()
            val dataOffset = offset + 8
            val paddedEnd = dataOffset.toLong() + chunkSize.toLong() + (chunkSize and 1).toLong()
            if (chunkSize < 0 || paddedEnd > bytes.size.toLong()) {
                truncatedOrMalformed = true
                break
            }

            when (fourCC) {
                "VP8X" -> {
                    if (chunkSize >= 10) {
                        val flags = bytes[dataOffset].toInt() and 0xFF
                        hasAlpha = (flags and VP8X_FLAG_ALPHA) != 0
                        isAnimated = (flags and VP8X_FLAG_ANIMATION) != 0
                        width = readUInt24LE(bytes, dataOffset + 4) + 1
                        height = readUInt24LE(bytes, dataOffset + 7) + 1
                    } else {
                        truncatedOrMalformed = true
                    }
                }
                "ANIM" -> {
                    if (chunkSize >= 6) {
                        loopCount = readUInt16LE(bytes, dataOffset + 4)
                    } else {
                        truncatedOrMalformed = true
                    }
                }
                "VP8L" -> {
                    if (chunkSize >= 5 && bytes[dataOffset] == 0x2F.toByte()) {
                        hasBitStream = true
                        if (!isAnimated) {
                            val bits = readUInt32LE(bytes, dataOffset + 1)
                            if (width == 0) width = ((bits and 0x3FFF).toInt()) + 1
                            if (height == 0) height = (((bits ushr 14) and 0x3FFF).toInt()) + 1
                            hasAlpha = ((bits ushr 28) and 0x01) != 0L
                        }
                    }
                    if (!isAnimated) frameCount = maxOf(frameCount, 1)
                }
                "VP8 " -> {
                    if (chunkSize >= 10 &&
                        bytes[dataOffset + 3] == 0x9D.toByte() &&
                        bytes[dataOffset + 4] == 0x01.toByte() &&
                        bytes[dataOffset + 5] == 0x2A.toByte()
                    ) {
                        hasBitStream = true
                        if (!isAnimated) {
                            val w = readUInt16LE(bytes, dataOffset + 6) and 0x3FFF
                            val h = readUInt16LE(bytes, dataOffset + 8) and 0x3FFF
                            if (width == 0) width = w
                            if (height == 0) height = h
                        }
                    }
                    if (!isAnimated) frameCount = maxOf(frameCount, 1)
                }
                "ALPH" -> hasAlpha = true
                "ANMF" -> {
                    if (chunkSize > 16 && hasFrameImageBitstream(bytes, dataOffset + 16, chunkSize - 16)) {
                        isAnimated = true
                        hasBitStream = true
                        frameCount++
                        val dur = readUInt24LE(bytes, dataOffset + 12)
                        totalDuration += dur
                        if (dur < minFrameDuration) minFrameDuration = dur
                    } else {
                        truncatedOrMalformed = true
                    }
                }
            }

            offset = paddedEnd.toInt()
        }

        if (offset != bytes.size) truncatedOrMalformed = true
        if (frameCount == 0 && width > 0 && height > 0) frameCount = 1
        if (minFrameDuration == Int.MAX_VALUE) minFrameDuration = 0

        return WebPInspectionResult(
            isValidWebP = !truncatedOrMalformed && hasBitStream && width > 0 && height > 0,
            isAnimated = isAnimated,
            width = width,
            height = height,
            hasAlpha = hasAlpha,
            frameCount = frameCount,
            totalDurationMs = totalDuration,
            minFrameDurationMs = minFrameDuration,
            fileSizeBytes = bytes.size,
            loopCount = loopCount
        )
    }

    private fun hasFrameImageBitstream(bytes: ByteArray, startOffset: Int, len: Int): Boolean {
        var offset = startOffset
        val end = startOffset + len
        var foundImageChunk = false
        while (offset + 8 <= end) {
            val fourCC = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = readUInt32LE(bytes, offset + 4).toInt()
            val dataOffset = offset + 8
            val paddedEnd = dataOffset + chunkSize + (chunkSize and 1)
            if (chunkSize < 0 || dataOffset + chunkSize > end) return false
            if (fourCC == "VP8 " || fourCC == "VP8L") {
                foundImageChunk = true
            }
            offset = paddedEnd
        }
        return foundImageChunk
    }

    /**
     * Decodes every frame of an animated WebP into ARGB_8888 Bitmaps while honoring
     * frame X/Y offsets, alpha blending, and disposal-to-background.
     */
    fun decodeAnimatedFrames(
        bytes: ByteArray,
        maxDimension: Int = MAX_DECODED_DIMENSION,
        stopAfterMs: Int? = null,
        shouldAbort: () -> Boolean = { false }
    ): List<DecodedFrame> {
        val inspection = inspect(bytes)
        if (!inspection.isValidWebP) return emptyList()
        if (inspection.width > 4096 || inspection.height > 4096) return emptyList()

        if (!inspection.isAnimated) {
            val bmp = decodeStaticFrameBitmap(bytes) ?: return emptyList()
            val bounded = createBoundedSnapshot(bmp, maxDimension)
            if (bounded !== bmp) bmp.recycle()
            return listOf(DecodedFrame(bounded, 100))
        }

        val canvasW = inspection.width
        val canvasH = inspection.height
        val canvasBitmap = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        canvasBitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(canvasBitmap)
        val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val blendPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        val decoded = mutableListOf<DecodedFrame>()
        var decodedDurationMs = 0
        var offset = 12
        var chunkGuard = 0

        try {
            while (offset + 8 <= bytes.size && chunkGuard++ < MAX_CHUNKS) {
                if (shouldAbort()) break
                val fourCC = String(bytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = readUInt32LE(bytes, offset + 4).toInt()
                val dataOffset = offset + 8
                if (chunkSize < 0 || dataOffset.toLong() + chunkSize.toLong() > bytes.size.toLong()) break

                if (fourCC == "ANMF" && chunkSize > 16) {
                    val frameX = readUInt24LE(bytes, dataOffset) * 2
                    val frameY = readUInt24LE(bytes, dataOffset + 3) * 2
                    val frameW = readUInt24LE(bytes, dataOffset + 6) + 1
                    val frameH = readUInt24LE(bytes, dataOffset + 9) + 1
                    val rawDuration = readUInt24LE(bytes, dataOffset + 12)
                    val durationMs = if (rawDuration <= 0) 40 else rawDuration
                    val flags = bytes[dataOffset + 15].toInt() and 0xFF
                    val disposeBg = (flags and ANMF_FLAG_DISPOSE_BG) != 0
                    val noBlend = (flags and ANMF_FLAG_NO_BLEND) != 0

                    val subPayloadOffset = dataOffset + 16
                    val subPayloadLen = chunkSize - 16
                    val staticFrameWebp = wrapFrameAsStaticWebP(
                        bytes = bytes,
                        payloadOffset = subPayloadOffset,
                        payloadLen = subPayloadLen,
                        width = frameW,
                        height = frameH
                    )

                    val frameBitmap = decodeStaticFrameBitmap(staticFrameWebp)
                    if (frameBitmap != null) {
                        try {
                            val paint = if (noBlend) copyPaint else blendPaint
                            canvas.drawBitmap(frameBitmap, frameX.toFloat(), frameY.toFloat(), paint)
                        } finally {
                            frameBitmap.recycle()
                        }

                        decoded.add(
                            DecodedFrame(
                                bitmap = createBoundedSnapshot(canvasBitmap, maxDimension),
                                durationMs = durationMs
                            )
                        )
                        decodedDurationMs += durationMs

                        if (decoded.size >= MAX_FRAMES ||
                            (stopAfterMs != null && decodedDurationMs >= stopAfterMs && decoded.size >= 2)
                        ) break

                        if (disposeBg) {
                            canvas.drawRect(
                                frameX.toFloat(),
                                frameY.toFloat(),
                                (frameX + frameW).toFloat(),
                                (frameY + frameH).toFloat(),
                                clearPaint
                            )
                        }
                    }
                }

                offset = dataOffset + chunkSize + (chunkSize and 1)
            }
        } finally {
            if (!canvasBitmap.isRecycled) canvasBitmap.recycle()
        }

        return decoded
    }

    private fun decodeStaticFrameBitmap(webpBytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val src = android.graphics.ImageDecoder.createSource(ByteBuffer.wrap(webpBytes))
                return android.graphics.ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } catch (_: Exception) {
                // Fall back to BitmapFactory
            }
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(webpBytes, 0, webpBytes.size, opts) ?: return null
        return if (bmp.config == Bitmap.Config.ARGB_8888) {
            bmp
        } else {
            val converted = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp.recycle()
            converted
        }
    }

    private fun createBoundedSnapshot(source: Bitmap, maxDimension: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxDimension && h <= maxDimension && source.config == Bitmap.Config.ARGB_8888) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val scale = if (w > maxDimension || h > maxDimension) {
            minOf(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
        } else 1.0f
        val outW = maxOf(1, (w * scale).toInt())
        val outH = maxOf(1, (h * scale).toInt())
        val scaled = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        scaled.eraseColor(Color.TRANSPARENT)
        val c = Canvas(scaled)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(source, null, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), paint)
        return scaled
    }

    /**
     * Encodes a list of 512x512 ARGB_8888 frames into a standards-compliant Animated WebP
     * where every frame is an independent keyframe (disposal=1, no-blend=1) to guarantee
     * crisp transparency and prevent WhatsApp black-line/preview artifacts.
     */
    fun encodeAnimatedWebP(
        frames: List<DecodedFrame>,
        width: Int,
        height: Int,
        quality: Int,
        lossless: Boolean = false,
        shouldAbort: () -> Boolean = { false }
    ): ByteArray {
        val anmfChunks = ArrayList<ByteArray>(frames.size)
        val cachedByBitmap = java.util.IdentityHashMap<Bitmap, EncodedFramePayload>(frames.size)
        var anyAlpha = false
        for (frame in frames) {
            if (shouldAbort()) throw InterruptedException("Aborted")
            val cached = cachedByBitmap[frame.bitmap]
            val encoded = if (cached != null) {
                cached
            } else {
                encodeSingleAnmfPayload(frame.bitmap, width, height, quality, lossless)
                    .also { cachedByBitmap[frame.bitmap] = it }
            }
            if (encoded.hasCanvasAlpha) {
                anyAlpha = true
            }
            val duration = maxOf(8, frame.durationMs)
            val anmf = buildAnmfChunk(
                x = encoded.x,
                y = encoded.y,
                width = encoded.w,
                height = encoded.h,
                durationMs = duration,
                anmfFlags = ANMF_FLAG_DISPOSE_BG or ANMF_FLAG_NO_BLEND,
                payloadChunks = encoded.payloadChunks
            )
            anmfChunks.add(anmf)
        }

        return assembleAnimatedRiff(width, height, anyAlpha, anmfChunks)
    }

    private data class EncodedFramePayload(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val hasCanvasAlpha: Boolean,
        val payloadChunks: ByteArray
    )

    private fun encodeSingleAnmfPayload(
        bitmap: Bitmap,
        canvasW: Int,
        canvasH: Int,
        quality: Int,
        lossless: Boolean
    ): EncodedFramePayload {
        val bw = bitmap.width
        val bh = bitmap.height
        if (bw != canvasW || bh != canvasH || !bitmap.hasAlpha()) {
            val staticBytes = encodeStaticWebP(bitmap, quality, lossless)
            val chunks = extractImageBitstreamChunks(staticBytes)
            if (chunks.isEmpty()) throw IllegalStateException("Empty WebP frame bitstream")
            val alpha = containsAlphaPayload(chunks) || bw < canvasW || bh < canvasH
            return EncodedFramePayload(0, 0, bw, bh, alpha, chunks)
        }

        val pixels = IntArray(bw * bh)
        bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        var minX = bw
        var minY = bh
        var maxX = -1
        var maxY = -1
        var allOpaque = true
        for (py in 0 until bh) {
            val rowOff = py * bw
            for (px in 0 until bw) {
                val a = pixels[rowOff + px] ushr 24
                if (a < 255) allOpaque = false
                if (a > 0) {
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                }
            }
        }

        if (allOpaque) {
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true).apply { setHasAlpha(false) }
            try {
                val staticBytes = encodeStaticWebP(copy, quality, lossless)
                val chunks = extractImageBitstreamChunks(staticBytes)
                if (chunks.isEmpty()) throw IllegalStateException("Empty WebP frame bitstream")
                return EncodedFramePayload(0, 0, bw, bh, false, chunks)
            } finally {
                copy.recycle()
            }
        }

        if (maxX < 0 || maxY < 0) {
            val tiny = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
            try {
                val staticBytes = encodeStaticWebP(tiny, quality, lossless)
                val chunks = extractImageBitstreamChunks(staticBytes)
                if (chunks.isEmpty()) throw IllegalStateException("Empty WebP frame bitstream")
                return EncodedFramePayload(0, 0, 2, 2, true, chunks)
            } finally {
                tiny.recycle()
            }
        }

        val cropX = minX and 1.inv()
        val cropY = minY and 1.inv()
        val cropW = (maxX - cropX + 1).coerceIn(2, bw - cropX)
        val cropH = (maxY - cropY + 1).coerceIn(2, bh - cropY)

        if (cropX == 0 && cropY == 0 && cropW == bw && cropH == bh) {
            val staticBytes = encodeStaticWebP(bitmap, quality, lossless)
            val chunks = extractImageBitstreamChunks(staticBytes)
            if (chunks.isEmpty()) throw IllegalStateException("Empty WebP frame bitstream")
            return EncodedFramePayload(0, 0, bw, bh, containsAlphaPayload(chunks), chunks)
        }

        val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
        return try {
            val staticBytes = encodeStaticWebP(cropped, quality, lossless)
            val chunks = extractImageBitstreamChunks(staticBytes)
            if (chunks.isEmpty()) throw IllegalStateException("Empty WebP frame bitstream")
            EncodedFramePayload(cropX, cropY, cropW, cropH, true, chunks)
        } finally {
            cropped.recycle()
        }
    }

    /**
     * Encodes a single static bitmap into a genuine Animated WebP ("fake-vid") containing
     * [frameCount] identical keyframes using DISPOSE_BG + NO_BLEND so the sticker remains
     * visually motionless, clean with transparency, and fully compatible with WhatsApp's
     * animated sticker pack decoder.
     */
    fun encodeFakeVidWebP(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        quality: Int,
        lossless: Boolean = false,
        frameCount: Int = 3,
        frameDurationMs: Int = 300
    ): ByteArray {
        val staticBytes = encodeStaticWebP(bitmap, quality, lossless)
        val payloadChunks = extractImageBitstreamChunks(staticBytes)
        if (payloadChunks.isEmpty()) {
            throw IllegalStateException("Empty fake-vid WebP bitstream")
        }
        val hasAlpha = containsAlphaPayload(payloadChunks)
        val count = maxOf(2, frameCount)
        val duration = maxOf(8, frameDurationMs)

        val anmfChunks = ArrayList<ByteArray>(count)
        val sharedAnmf = buildAnmfChunk(
            x = 0,
            y = 0,
            width = width,
            height = height,
            durationMs = duration,
            anmfFlags = ANMF_FLAG_DISPOSE_BG or ANMF_FLAG_NO_BLEND,
            payloadChunks = payloadChunks
        )
        repeat(count) {
            anmfChunks.add(sharedAnmf)
        }
        return assembleAnimatedRiff(width, height, hasAlpha, anmfChunks)
    }

    /**
     * Accurately checks whether an extracted frame payload contains alpha data
     * (either an ALPH chunk for VP8 or VP8L with alpha_is_used bit set).
     */
    private fun containsAlphaPayload(payloadChunks: ByteArray): Boolean {
        var offset = 0
        while (offset + 8 <= payloadChunks.size) {
            val fourCC = String(payloadChunks, offset, 4, Charsets.US_ASCII)
            val chunkSize = readUInt32LE(payloadChunks, offset + 4).toInt()
            val dataOffset = offset + 8
            if (chunkSize < 0 || dataOffset + chunkSize > payloadChunks.size) break

            if (fourCC == "ALPH") return true
            if (fourCC == "VP8L" && chunkSize >= 5 && payloadChunks[dataOffset] == 0x2F.toByte()) {
                val bits = readUInt32LE(payloadChunks, dataOffset + 1)
                if (((bits ushr 28) and 0x01L) != 0L) return true
            }

            offset = dataOffset + chunkSize + (chunkSize and 1)
        }
        return false
    }

    private fun assembleAnimatedRiff(
        width: Int,
        height: Int,
        hasAlpha: Boolean,
        anmfChunks: List<ByteArray>
    ): ByteArray {
        val bodyOut = ByteArrayOutputStream()
        val vp8xFlags = VP8X_FLAG_ANIMATION or if (hasAlpha) VP8X_FLAG_ALPHA else 0

        // VP8X (10 bytes payload -> 18 bytes total chunk)
        bodyOut.write("VP8X".toByteArray(Charsets.US_ASCII))
        writeUInt32LE(bodyOut, 10)
        bodyOut.write(vp8xFlags)
        bodyOut.write(0)
        bodyOut.write(0)
        bodyOut.write(0)
        writeUInt24LE(bodyOut, width - 1)
        writeUInt24LE(bodyOut, height - 1)

        // ANIM (6 bytes payload: transparent BGRA 0x00000000, loopCount = 0 infinite)
        bodyOut.write("ANIM".toByteArray(Charsets.US_ASCII))
        writeUInt32LE(bodyOut, 6)
        bodyOut.write(0)
        bodyOut.write(0)
        bodyOut.write(0)
        bodyOut.write(0)
        bodyOut.write(0)
        bodyOut.write(0)

        for (anmf in anmfChunks) {
            bodyOut.write(anmf)
        }

        val bodyBytes = bodyOut.toByteArray()
        val finalOut = ByteArrayOutputStream(12 + bodyBytes.size)
        finalOut.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeUInt32LE(finalOut, 4 + bodyBytes.size)
        finalOut.write("WEBP".toByteArray(Charsets.US_ASCII))
        finalOut.write(bodyBytes)
        return finalOut.toByteArray()
    }

    @Suppress("DEPRECATION")
    fun encodeStaticWebP(bitmap: Bitmap, quality: Int, lossless: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, quality.coerceIn(0, 100), out)
        return out.toByteArray()
    }

    private fun wrapFrameAsStaticWebP(
        bytes: ByteArray,
        payloadOffset: Int,
        payloadLen: Int,
        width: Int,
        height: Int
    ): ByteArray {
        val body = ByteArrayOutputStream(payloadLen + 24)
        var hasAlph = false
        val filteredChunks = ByteArrayOutputStream(payloadLen)

        var offset = payloadOffset
        val end = payloadOffset + payloadLen
        while (offset + 8 <= end) {
            val fourCC = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = readUInt32LE(bytes, offset + 4).toInt()
            val dataOffset = offset + 8
            if (chunkSize < 0 || dataOffset + chunkSize > end) break

            if (fourCC == "ALPH") {
                hasAlph = true
                filteredChunks.write(bytes, offset, 8 + chunkSize)
                if ((chunkSize and 1) == 1) filteredChunks.write(0)
            } else if (fourCC == "VP8 " || fourCC == "VP8L") {
                filteredChunks.write(bytes, offset, 8 + chunkSize)
                if ((chunkSize and 1) == 1) filteredChunks.write(0)
            }
            offset = dataOffset + chunkSize + (chunkSize and 1)
        }

        if (hasAlph) {
            body.write("VP8X".toByteArray(Charsets.US_ASCII))
            writeUInt32LE(body, 10)
            body.write(VP8X_FLAG_ALPHA)
            body.write(0); body.write(0); body.write(0)
            writeUInt24LE(body, width - 1)
            writeUInt24LE(body, height - 1)
        }

        body.write(filteredChunks.toByteArray())
        val bodyBytes = body.toByteArray()

        val out = ByteArrayOutputStream(12 + bodyBytes.size)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeUInt32LE(out, 4 + bodyBytes.size)
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        return out.toByteArray()
    }

    /**
     * Extracts ALPH, VP8, and VP8L chunks from a static WebP (stripping RIFF/VP8X/ICCP/EXIF/XMP).
     */
    private fun extractImageBitstreamChunks(staticWebP: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(staticWebP.size)
        var offset = 12
        while (offset + 8 <= staticWebP.size) {
            val fourCC = String(staticWebP, offset, 4, Charsets.US_ASCII)
            val chunkSize = readUInt32LE(staticWebP, offset + 4).toInt()
            val dataOffset = offset + 8
            if (chunkSize < 0 || dataOffset + chunkSize > staticWebP.size) break

            if (fourCC == "ALPH" || fourCC == "VP8 " || fourCC == "VP8L") {
                val paddedSize = chunkSize + (chunkSize and 1)
                out.write(staticWebP, offset, 8 + chunkSize)
                if ((chunkSize and 1) == 1) out.write(0)
                offset = dataOffset + paddedSize
                continue
            }
            offset = dataOffset + chunkSize + (chunkSize and 1)
        }
        return out.toByteArray()
    }

    private fun buildAnmfChunk(
        x: Int = 0,
        y: Int = 0,
        width: Int,
        height: Int,
        durationMs: Int,
        anmfFlags: Int,
        payloadChunks: ByteArray
    ): ByteArray {
        val chunkPayloadSize = 16 + payloadChunks.size
        val out = ByteArrayOutputStream(8 + chunkPayloadSize + 1)
        out.write("ANMF".toByteArray(Charsets.US_ASCII))
        writeUInt32LE(out, chunkPayloadSize)
        writeUInt24LE(out, x / 2)
        writeUInt24LE(out, y / 2)
        writeUInt24LE(out, width - 1)
        writeUInt24LE(out, height - 1)
        writeUInt24LE(out, durationMs)
        out.write(anmfFlags)
        out.write(payloadChunks)
        if ((chunkPayloadSize and 1) == 1) out.write(0)
        return out.toByteArray()
    }

    private fun readUInt16LE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt24LE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16)

    private fun readUInt32LE(b: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun writeUInt24LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
    }

    private fun writeUInt32LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
