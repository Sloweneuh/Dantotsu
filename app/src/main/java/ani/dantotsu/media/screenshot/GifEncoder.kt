package ani.dantotsu.media.screenshot

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * A minimal GIF89a writer, enough to turn a decoded clip into an animated GIF.
 *
 * Android can decode GIFs but has never been able to encode them, so the format is written out by
 * hand here. Three decisions keep it viable for a few hundred frames on a phone:
 *
 *  - **Streaming.** Frames are encoded and written as they arrive, never collected. A 30s clip is
 *    hundreds of frames, and holding even one 480p frame each as ARGB would run to hundreds of
 *    megabytes; only the current frame is ever in memory.
 *  - **One global palette.** Colours are quantised once up front, from [begin]'s sample of the clip,
 *    rather than per frame — which streaming requires anyway, and which also saves 768 bytes a frame.
 *  - **A shared lookup cache.** Mapping a pixel to a palette index is the inner loop of the whole
 *    encode, so results are memoised per RGB555 bucket and reused across every frame.
 *
 * The LZW stage uses the GIF spec's fixed-size hash table, which keeps dictionary lookups
 * allocation-free.
 *
 * Usage is [begin], then [addFrame] per frame, then [finish].
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    /** 0 loops forever, which is what a clip should do. */
    private val loopCount: Int = 0,
) {

    private var palette: IntArray? = null
    private val cache = HashMap<Int, Int>(4096)
    private val indices = ByteArray(width * height)
    private val pixels = IntArray(width * height)

    /**
     * Quantises [colorSamples] into the palette every frame will be drawn with, and writes the file
     * header. Samples should be spread across the whole clip — see [sampleColors].
     */
    fun begin(colorSamples: IntArray) {
        palette = MedianCut.quantize(colorSamples, MAX_COLORS).also { writeHeader(it) }
    }

    /** Encodes a frame shown for [delayMs]. The bitmap is read immediately and not retained. */
    fun addFrame(bitmap: Bitmap, delayMs: Int) {
        val palette = checkNotNull(palette) { "begin() first" }
        val scaled = if (bitmap.width == width && bitmap.height == height) bitmap
        else Bitmap.createScaledBitmap(bitmap, width, height, true)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()

        mapToPalette(palette)
        // GIF delays are in hundredths of a second; anything under 2cs is widely clamped up by
        // viewers anyway, so keep it there rather than pretend to be faster.
        writeGraphicControl((delayMs / 10).coerceAtLeast(2))
        writeImageDescriptor()
        LzwEncoder(indices, COLOR_DEPTH).encode(out)
    }

    fun finish() {
        out.write(TRAILER)
        out.flush()
    }

    private fun mapToPalette(palette: IntArray) {
        pixels.indices.forEach { i ->
            val rgb = pixels[i]
            // Bucket by RGB555: visually indistinguishable neighbours share a lookup.
            val key = (rgb shr 3 and 0x1F) or (rgb shr 6 and 0x3E0) or (rgb shr 9 and 0x7C00)
            indices[i] = (cache.getOrPut(key) { nearestColor(rgb, palette) }).toByte()
        }
    }

    private fun nearestColor(rgb: Int, palette: IntArray): Int {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        var best = 0
        var bestDistance = Int.MAX_VALUE
        palette.indices.forEach { i ->
            val entry = palette[i]
            val dr = r - (entry shr 16 and 0xFF)
            val dg = g - (entry shr 8 and 0xFF)
            val db = b - (entry and 0xFF)
            val distance = dr * dr + dg * dg + db * db
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    private fun writeHeader(palette: IntArray) {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical screen descriptor, flagged as carrying a global colour table.
        writeShort(width)
        writeShort(height)
        out.write(0x80 or (COLOR_DEPTH - 1))
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio

        repeat(MAX_COLORS) { i ->
            val color = palette.getOrElse(i) { 0 }
            out.write(color shr 16 and 0xFF)
            out.write(color shr 8 and 0xFF)
            out.write(color and 0xFF)
        }

        // Netscape application extension — the only way to say "loop".
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1)
        writeShort(loopCount)
        out.write(0)
    }

    private fun writeGraphicControl(delayCs: Int) {
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(0) // no transparency, no disposal
        writeShort(delayCs)
        out.write(0) // transparent colour index
        out.write(0)
    }

    private fun writeImageDescriptor() {
        out.write(0x2C)
        writeShort(0); writeShort(0)
        writeShort(width); writeShort(height)
        out.write(0) // no local colour table, not interlaced
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write(value shr 8 and 0xFF)
    }

    companion object {
        private const val MAX_COLORS = 256
        private const val COLOR_DEPTH = 8
        private const val TRAILER = 0x3B
        private const val PIXEL_SAMPLE_STEP = 7

        /** How many frames across the clip to sample when building the palette. */
        const val PALETTE_SAMPLE_FRAMES = 16

        /**
         * Adds every Nth pixel of [bitmap] to [into]. Callers collect these from frames spread
         * across the clip and hand the result to [begin], so the palette covers the whole thing
         * rather than just whatever the opening shot happened to look like.
         */
        fun sampleColors(bitmap: Bitmap, into: MutableList<Int>) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var i = 0
            while (i < pixels.size) {
                into += pixels[i] and 0xFFFFFF
                i += PIXEL_SAMPLE_STEP
            }
        }
    }
}

/**
 * Median cut: repeatedly split the colour box with the most room left in it, then take each box's
 * average. Cheap, deterministic, and good enough for video stills — unlike a uniform palette it
 * spends its colours where the clip actually has detail.
 */
private object MedianCut {

    private class Box(val from: Int, val to: Int) {
        var channel = 0
        var extent = 0
    }

    fun quantize(samples: IntArray, maxColors: Int): IntArray {
        if (samples.isEmpty()) return IntArray(1)
        val colors = samples.copyOf()
        val boxes = mutableListOf(Box(0, colors.size).also { measure(colors, it) })

        while (boxes.size < maxColors) {
            // Splitting the widest box first is what stops one busy region eating the palette.
            val target = boxes.filter { it.to - it.from > 1 && it.extent > 0 }
                .maxByOrNull { it.extent } ?: break
            val mid = (target.from + target.to) / 2
            sortRange(colors, target.from, target.to, target.channel)
            boxes.remove(target)
            boxes += Box(target.from, mid).also { measure(colors, it) }
            boxes += Box(mid, target.to).also { measure(colors, it) }
        }

        return boxes.map { box -> average(colors, box.from, box.to) }.toIntArray()
    }

    /** Records the box's widest channel and how wide it is, which drives the split order. */
    private fun measure(colors: IntArray, box: Box) {
        var minR = 255; var maxR = 0
        var minG = 255; var maxG = 0
        var minB = 255; var maxB = 0
        for (i in box.from until box.to) {
            val c = colors[i]
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            if (r < minR) minR = r; if (r > maxR) maxR = r
            if (g < minG) minG = g; if (g > maxG) maxG = g
            if (b < minB) minB = b; if (b > maxB) maxB = b
        }
        val dr = maxR - minR
        val dg = maxG - minG
        val db = maxB - minB
        box.extent = maxOf(dr, dg, db)
        box.channel = when (box.extent) {
            dr -> 16
            dg -> 8
            else -> 0
        }
    }

    private fun average(colors: IntArray, from: Int, to: Int): Int {
        if (to <= from) return 0
        var r = 0L; var g = 0L; var b = 0L
        for (i in from until to) {
            r += (colors[i] shr 16 and 0xFF).toLong()
            g += (colors[i] shr 8 and 0xFF).toLong()
            b += (colors[i] and 0xFF).toLong()
        }
        val n = (to - from).toLong()
        return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** In-place quicksort of a sub-range by one channel, avoiding boxing a few million Ints. */
    private fun sortRange(colors: IntArray, from: Int, to: Int, shift: Int) {
        if (to - from < 2) return
        val pivot = colors[(from + to) / 2] shr shift and 0xFF
        var low = from
        var high = to - 1
        while (low <= high) {
            while ((colors[low] shr shift and 0xFF) < pivot) low++
            while ((colors[high] shr shift and 0xFF) > pivot) high--
            if (low <= high) {
                val tmp = colors[low]; colors[low] = colors[high]; colors[high] = tmp
                low++; high--
            }
        }
        if (from < high) sortRange(colors, from, high + 1, shift)
        if (low < to - 1) sortRange(colors, low, to, shift)
    }
}

/**
 * GIF's variable-width LZW, written straight onto the output as 255-byte sub-blocks.
 *
 * The dictionary is a fixed open-addressed hash table rather than a map: this runs once per pixel
 * of every frame, so it has to stay allocation-free.
 */
private class LzwEncoder(private val pixels: ByteArray, private val colorDepth: Int) {

    private val hashTable = IntArray(HASH_SIZE)
    private val codeTable = IntArray(HASH_SIZE)

    private var initialCodeSize = 0
    private var codeSize = 0
    private var maxCode = 0
    private var nextCode = 0
    private var clearCode = 0
    private var endCode = 0

    /** Set when a clear code has been emitted but the width hasn't dropped back yet. */
    private var pendingClear = false

    private var accumulator = 0
    private var accumulatedBits = 0
    private val block = ByteArray(256)
    private var blockSize = 0

    fun encode(out: OutputStream) {
        initialCodeSize = maxOf(2, colorDepth)
        out.write(initialCodeSize)

        clearCode = 1 shl initialCodeSize
        endCode = clearCode + 1
        codeSize = initialCodeSize + 1
        maxCode = (1 shl codeSize) - 1
        nextCode = endCode + 1
        hashTable.fill(-1)
        writeCode(out, clearCode)

        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val next = pixels[i].toInt() and 0xFF
            val key = (next shl BITS) + prefix
            var slot = (next shl (BITS - 8)) xor prefix

            var existing = -1
            while (true) {
                if (hashTable[slot] == key) {
                    existing = codeTable[slot]
                    break
                }
                if (hashTable[slot] < 0) break // empty slot: not in the dictionary
                slot = if (slot == 0) HASH_SIZE - 1 else slot - 1
            }

            if (existing >= 0) {
                prefix = existing
                continue
            }

            writeCode(out, prefix)
            if (nextCode < MAX_CODES) {
                hashTable[slot] = key
                codeTable[slot] = nextCode
                nextCode++
            } else {
                // Dictionary full: hand the decoder a clear and start the table over, so codes
                // never need more than 12 bits. The flag is set first so the width drops back
                // immediately after the clear code goes out, not one code later.
                hashTable.fill(-1)
                nextCode = endCode + 1
                pendingClear = true
                writeCode(out, clearCode)
            }
            prefix = next
        }

        writeCode(out, prefix)
        writeCode(out, endCode)
        flushBits(out)
        out.write(0) // block terminator
    }

    /**
     * Emits [code] at the current width, least-significant-bit first, into 255-byte sub-blocks.
     *
     * The width check has to happen *after* the code is emitted and against the pre-increment
     * dictionary size: an LZW decoder only learns each new entry once it reads the following code,
     * so it is always one entry behind. Widening any earlier would desynchronise the two.
     */
    private fun writeCode(out: OutputStream, code: Int) {
        accumulator = accumulator or (code shl accumulatedBits)
        accumulatedBits += codeSize
        while (accumulatedBits >= 8) {
            appendByte(out, accumulator and 0xFF)
            accumulator = accumulator ushr 8
            accumulatedBits -= 8
        }

        if (pendingClear) {
            codeSize = initialCodeSize + 1
            maxCode = (1 shl codeSize) - 1
            pendingClear = false
        } else if (nextCode > maxCode) {
            codeSize++
            // At the ceiling the table stops growing, so park maxCode out of reach.
            maxCode = if (codeSize == BITS) MAX_CODES else (1 shl codeSize) - 1
        }
    }

    private fun flushBits(out: OutputStream) {
        while (accumulatedBits > 0) {
            appendByte(out, accumulator and 0xFF)
            accumulator = accumulator ushr 8
            accumulatedBits -= 8
        }
        if (blockSize > 0) flushBlock(out)
        accumulatedBits = 0
        accumulator = 0
    }

    private fun appendByte(out: OutputStream, value: Int) {
        block[blockSize++] = value.toByte()
        if (blockSize == 255) flushBlock(out)
    }

    private fun flushBlock(out: OutputStream) {
        out.write(blockSize)
        out.write(block, 0, blockSize)
        blockSize = 0
    }

    companion object {
        private const val BITS = 12
        private const val MAX_CODES = 1 shl BITS
        private const val HASH_SIZE = 5003
    }
}
