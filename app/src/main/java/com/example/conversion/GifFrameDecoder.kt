package com.example.conversion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

data class DecodedFrame(
    val bitmap: Bitmap,
    val durationMs: Int
)

/**
 * Pure Kotlin GIF87a/GIF89a frame-by-frame decoder preserving alpha transparency,
 * disposal methods, interlacing, and accurate frame delays.
 * Includes cycle/corrupt-block guards, cancellation checks, and memory-safe scaling
 * so malformed or large GIFs never hang or trigger OutOfMemoryError.
 */
object GifFrameDecoder {

    private const val MAX_STACK_SIZE = 4096
    private const val MAX_GIF_FRAMES = 300
    private const val MAX_DECODED_DIMENSION = 512

    fun isGif(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        val header = String(bytes, 0, 6, Charsets.US_ASCII)
        return header == "GIF87a" || header == "GIF89a"
    }

    fun decode(
        bytes: ByteArray,
        maxDimension: Int = MAX_DECODED_DIMENSION,
        shouldAbort: () -> Boolean = { false }
    ): List<DecodedFrame> {
        val reader = ByteReader(bytes)
        if (bytes.size < 13) return emptyList()
        val sig = reader.readAscii(6)
        if (sig != "GIF87a" && sig != "GIF89a") return emptyList()

        val screenWidth = reader.readUInt16LE()
        val screenHeight = reader.readUInt16LE()
        if (screenWidth <= 0 || screenHeight <= 0 || screenWidth > 4096 || screenHeight > 4096) {
            return emptyList()
        }

        val packed = reader.readByte()
        val gctFlag = (packed and 0x80) != 0
        val gctSize = 1 shl ((packed and 0x07) + 1)
        reader.readByte() // bgColorIndex
        reader.readByte() // pixel aspect ratio

        val gct = if (gctFlag) readColorTable(reader, gctSize) else IntArray(0)

        var disposalMethod = 0
        var transparencyFlag = false
        var transparentIndex = 0
        var delayMs = 100

        var canvasBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        canvasBitmap.eraseColor(Color.TRANSPARENT)
        var previousBitmap: Bitmap? = null
        val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val frames = mutableListOf<DecodedFrame>()
        val destPixels = IntArray(screenWidth * screenHeight)

        var blockSteps = 0
        val maxBlockSteps = maxOf(2048, bytes.size)

        try {
            while (reader.hasRemaining() && blockSteps++ < maxBlockSteps) {
                if (shouldAbort()) break
                when (reader.readByte()) {
                    0x3B -> break // Trailer
                    0x21 -> { // Extension
                        if (!reader.hasRemaining()) break
                        val label = reader.readByte()
                        if (label == 0xF9) { // Graphic Control Extension
                            val blockSize = reader.readByte()
                            if (blockSize >= 4 && reader.hasRemaining(4)) {
                                val gcePacked = reader.readByte()
                                disposalMethod = (gcePacked ushr 2) and 0x07
                                transparencyFlag = (gcePacked and 0x01) != 0
                                val delayCs = reader.readUInt16LE()
                                // Standard browser/GIF behavior: 0 or 1cs defaults to 100ms
                                delayMs = if (delayCs <= 1) 100 else delayCs * 10
                                transparentIndex = reader.readByte()
                                if (blockSize > 4) {
                                    reader.skip(blockSize - 4)
                                }
                            } else if (blockSize > 0) {
                                reader.skip(blockSize)
                            }
                            skipSubBlocks(reader, shouldAbort)
                        } else {
                            skipSubBlocks(reader, shouldAbort)
                        }
                    }
                    0x2C -> { // Image Descriptor
                        if (!reader.hasRemaining(9)) break
                        val imgLeft = reader.readUInt16LE()
                        val imgTop = reader.readUInt16LE()
                        val imgWidth = reader.readUInt16LE()
                        val imgHeight = reader.readUInt16LE()
                        val imgPacked = reader.readByte()
                        val lctFlag = (imgPacked and 0x80) != 0
                        val interlaceFlag = (imgPacked and 0x40) != 0
                        val lctSize = 1 shl ((imgPacked and 0x07) + 1)

                        val activeTable = if (lctFlag) readColorTable(reader, lctSize) else gct
                        if (!reader.hasRemaining()) break
                        val lzwMinCodeSize = reader.readByte()
                        val compressedData = readSubBlocksData(reader, shouldAbort)

                        if (imgWidth > 0 && imgHeight > 0 &&
                            imgWidth <= 4096 && imgHeight <= 4096 &&
                            activeTable.isNotEmpty() && !shouldAbort()
                        ) {
                            if (disposalMethod == 3) {
                                previousBitmap?.recycle()
                                previousBitmap = canvasBitmap.copy(Bitmap.Config.ARGB_8888, true)
                            }

                            val pixels = decodeLzwPixels(
                                width = imgWidth,
                                height = imgHeight,
                                minCodeSize = lzwMinCodeSize,
                                data = compressedData,
                                shouldAbort = shouldAbort
                            )

                            canvasBitmap.getPixels(destPixels, 0, screenWidth, 0, 0, screenWidth, screenHeight)

                            writeFramePixels(
                                pixels = pixels,
                                colorTable = activeTable,
                                transparencyFlag = transparencyFlag,
                                transparentIndex = transparentIndex,
                                interlaced = interlaceFlag,
                                imgLeft = imgLeft,
                                imgTop = imgTop,
                                imgWidth = imgWidth,
                                imgHeight = imgHeight,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight,
                                dest = destPixels
                            )

                            canvasBitmap.setPixels(destPixels, 0, screenWidth, 0, 0, screenWidth, screenHeight)
                            val snapshot = createBoundedSnapshot(canvasBitmap, maxDimension)
                            frames.add(DecodedFrame(snapshot, delayMs))

                            if (frames.size >= MAX_GIF_FRAMES) {
                                break
                            }

                            // Handle disposal for NEXT frame
                            when (disposalMethod) {
                                2 -> { // Restore to transparent background
                                    val canvas = Canvas(canvasBitmap)
                                    canvas.drawRect(
                                        imgLeft.toFloat(),
                                        imgTop.toFloat(),
                                        (imgLeft + imgWidth).toFloat(),
                                        (imgTop + imgHeight).toFloat(),
                                        clearPaint
                                    )
                                }
                                3 -> { // Restore to previous
                                    val prev = previousBitmap
                                    if (prev != null) {
                                        canvasBitmap.recycle()
                                        canvasBitmap = prev.copy(Bitmap.Config.ARGB_8888, true)
                                    }
                                }
                            }
                            previousBitmap?.recycle()
                            previousBitmap = null
                        }

                        // Reset GCE state for next image
                        disposalMethod = 0
                        transparencyFlag = false
                        transparentIndex = 0
                        delayMs = 100
                    }
                    0x00 -> {
                        // Padding / stray block terminator byte: continue safely within blockSteps bound
                    }
                    else -> {
                        // Unrecognized byte outside block framing: abort cleanly if stream exhausted
                        if (!reader.hasRemaining()) break
                    }
                }
            }
        } finally {
            previousBitmap?.recycle()
            if (!canvasBitmap.isRecycled) canvasBitmap.recycle()
        }

        return frames
    }

    private fun createBoundedSnapshot(source: Bitmap, maxDimension: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxDimension && h <= maxDimension) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val scale = minOf(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
        val outW = maxOf(1, (w * scale).toInt())
        val outH = maxOf(1, (h * scale).toInt())
        val scaled = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        scaled.eraseColor(Color.TRANSPARENT)
        val c = Canvas(scaled)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(source, null, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), paint)
        return scaled
    }

    private fun readColorTable(reader: ByteReader, count: Int): IntArray {
        val table = IntArray(count)
        for (i in 0 until count) {
            if (!reader.hasRemaining(3)) break
            val r = reader.readByte()
            val g = reader.readByte()
            val b = reader.readByte()
            table[i] = Color.argb(255, r, g, b)
        }
        return table
    }

    private fun skipSubBlocks(reader: ByteReader, shouldAbort: () -> Boolean) {
        var safety = 0
        while (reader.hasRemaining() && safety++ < 65536 && !shouldAbort()) {
            val size = reader.readByte()
            if (size == 0) break
            reader.skip(size)
        }
    }

    private fun readSubBlocksData(reader: ByteReader, shouldAbort: () -> Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream(4096)
        var safety = 0
        while (reader.hasRemaining() && safety++ < 65536 && !shouldAbort()) {
            val size = reader.readByte()
            if (size == 0) break
            val chunk = reader.readBytes(size)
            out.write(chunk)
        }
        return out.toByteArray()
    }

    private fun decodeLzwPixels(
        width: Int,
        height: Int,
        minCodeSize: Int,
        data: ByteArray,
        shouldAbort: () -> Boolean
    ): ByteArray {
        val npixLong = width.toLong() * height.toLong()
        if (npixLong <= 0L || npixLong > 16_777_216L) return ByteArray(0)
        val npix = npixLong.toInt()
        val pixels = ByteArray(npix)
        if (minCodeSize < 2 || minCodeSize > 11 || data.isEmpty()) return pixels

        val clearCode = 1 shl minCodeSize
        val endOfInfo = clearCode + 1
        var available = clearCode + 2
        var oldCode = -1
        var codeSize = minCodeSize + 1
        var codeMask = (1 shl codeSize) - 1

        val prefix = IntArray(MAX_STACK_SIZE)
        val suffix = ByteArray(MAX_STACK_SIZE)
        val pixelStack = ByteArray(MAX_STACK_SIZE + 1)

        for (code in 0 until clearCode) {
            prefix[code] = 0
            suffix[code] = code.toByte()
        }

        var datum = 0
        var bits = 0
        var dataOffset = 0
        var first = 0
        var top = 0
        var pi = 0
        var iterationGuard = 0
        val maxIterations = npix * 4 + data.size * 2 + 4096

        while (pi < npix && iterationGuard++ < maxIterations) {
            if ((iterationGuard and 4095) == 0 && shouldAbort()) break

            if (top == 0) {
                if (bits < codeSize) {
                    while (bits < codeSize && dataOffset < data.size) {
                        datum += (data[dataOffset++].toInt() and 0xFF) shl bits
                        bits += 8
                    }
                    if (bits < codeSize) break
                }

                var code = datum and codeMask
                datum = datum ushr codeSize
                bits -= codeSize

                if (code > available || code == endOfInfo) break
                if (code == clearCode) {
                    codeSize = minCodeSize + 1
                    codeMask = (1 shl codeSize) - 1
                    available = clearCode + 2
                    oldCode = -1
                    continue
                }
                if (oldCode == -1) {
                    if (code >= MAX_STACK_SIZE) break
                    pixelStack[top++] = suffix[code]
                    oldCode = code
                    first = code
                    continue
                }

                val inCode = code
                if (code == available) {
                    if (top >= MAX_STACK_SIZE) break
                    pixelStack[top++] = first.toByte()
                    code = oldCode
                }
                var chainSteps = 0
                while (code > clearCode && top < MAX_STACK_SIZE && chainSteps++ < MAX_STACK_SIZE) {
                    pixelStack[top++] = suffix[code]
                    code = prefix[code]
                }
                if (code >= MAX_STACK_SIZE || top >= MAX_STACK_SIZE) break
                first = suffix[code].toInt() and 0xFF
                pixelStack[top++] = first.toByte()

                if (available < MAX_STACK_SIZE) {
                    prefix[available] = oldCode
                    suffix[available] = first.toByte()
                    available++
                    if ((available and codeMask) == 0 && available < MAX_STACK_SIZE) {
                        codeSize++
                        codeMask = (1 shl codeSize) - 1
                    }
                }
                oldCode = inCode
            }

            top--
            pixels[pi++] = pixelStack[top]
        }

        return pixels
    }

    private fun writeFramePixels(
        pixels: ByteArray,
        colorTable: IntArray,
        transparencyFlag: Boolean,
        transparentIndex: Int,
        interlaced: Boolean,
        imgLeft: Int,
        imgTop: Int,
        imgWidth: Int,
        imgHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        dest: IntArray
    ) {
        if (pixels.isEmpty()) return
        var pass = 1
        var inc = 8
        var iline = 0
        for (i in 0 until imgHeight) {
            var line = i
            if (interlaced) {
                if (iline >= imgHeight) {
                    pass++
                    when (pass) {
                        2 -> iline = 4
                        3 -> {
                            iline = 2
                            inc = 4
                        }
                        4 -> {
                            iline = 1
                            inc = 2
                        }
                    }
                }
                line = iline
                iline += inc
            }
            val destY = imgTop + line
            if (destY !in 0 until screenHeight) continue
            val rowOffset = i * imgWidth
            val destRowOffset = destY * screenWidth
            for (x in 0 until imgWidth) {
                val destX = imgLeft + x
                if (destX !in 0 until screenWidth) continue
                val pIdx = rowOffset + x
                if (pIdx >= pixels.size) break
                val colorIdx = pixels[pIdx].toInt() and 0xFF
                if (transparencyFlag && colorIdx == transparentIndex) continue
                if (colorIdx < colorTable.size) {
                    dest[destRowOffset + destX] = colorTable[colorIdx]
                }
            }
        }
    }

    private class ByteReader(private val bytes: ByteArray) {
        private var pos = 0

        fun hasRemaining(n: Int = 1): Boolean = pos + n <= bytes.size

        fun readByte(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else 0

        fun readBytes(n: Int): ByteArray {
            val count = minOf(n, bytes.size - pos).coerceAtLeast(0)
            val slice = bytes.copyOfRange(pos, pos + count)
            pos += count
            return slice
        }

        fun readUInt16LE(): Int {
            val lo = readByte()
            val hi = readByte()
            return lo or (hi shl 8)
        }

        fun readAscii(len: Int): String {
            if (pos + len > bytes.size) return ""
            val s = String(bytes, pos, len, Charsets.US_ASCII)
            pos += len
            return s
        }

        fun skip(n: Int) {
            pos = minOf(bytes.size, pos + n)
        }
    }
}
