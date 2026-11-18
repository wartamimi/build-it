package dev.buildit.engine.build.res

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** A decoded image as ARGB pixels (row-major). */
class Argb(val width: Int, val height: Int, val pixels: IntArray) {
    operator fun get(x: Int, y: Int) = pixels[y * width + x]
}

/** Minimal PNG reader and writer in Kotlin (no ImageIO or android.graphics, so it runs everywhere). */
object Png {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    class PngException(message: String) : Exception(message)

    /** Decodes non-interlaced PNGs of every colour type and bit depth into ARGB. */
    fun decode(bytes: ByteArray): Argb {
        if (bytes.size < 8 || !bytes.copyOf(8).contentEquals(SIGNATURE)) throw PngException("not a PNG file")
        var pos = 8
        var width = 0; var height = 0; var depth = 0; var colorType = 0; var interlace = 0
        var palette = IntArray(0)
        var trns: ByteArray? = null
        val idat = ByteArrayOutputStream()
        fun u32(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
        while (pos + 8 <= bytes.size) {
            val len = u32(pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val data = pos + 8
            if (len < 0 || data + len > bytes.size) throw PngException("truncated chunk $type")
            when (type) {
                "IHDR" -> {
                    width = u32(data); height = u32(data + 4)
                    depth = bytes[data + 8].toInt() and 0xFF; colorType = bytes[data + 9].toInt() and 0xFF
                    interlace = bytes[data + 12].toInt() and 0xFF
                }
                "PLTE" -> palette = IntArray(len / 3) { i -> (0xFF shl 24) or ((bytes[data + i * 3].toInt() and 0xFF) shl 16) or ((bytes[data + i * 3 + 1].toInt() and 0xFF) shl 8) or (bytes[data + i * 3 + 2].toInt() and 0xFF) }
                "tRNS" -> trns = bytes.copyOfRange(data, data + len)
                "IDAT" -> idat.write(bytes, data, len)
                "IEND" -> break
            }
            pos = data + len + 4
        }
        if (width <= 0 || height <= 0) throw PngException("missing image header")
        if (interlace != 0) throw PngException("interlaced PNGs are not supported")
        val channels = when (colorType) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> throw PngException("unknown colour type $colorType") }
        val bitsPerPixel = channels * depth
        val stride = (width * bitsPerPixel + 7) / 8
        val bpp = maxOf(1, bitsPerPixel / 8)
        val raw = ByteArray((stride + 1) * height)
        Inflater().apply {
            setInput(idat.toByteArray())
            var off = 0
            while (off < raw.size && !finished()) {
                val n = inflate(raw, off, raw.size - off)
                if (n == 0 && (needsInput() || needsDictionary())) break
                off += n
            }
            end()
            if (off < raw.size) throw PngException("image data is truncated")
        }
        // Undo the per-row filters.
        val cur = ByteArray(stride)
        val prev = ByteArray(stride)
        val out = IntArray(width * height)
        val trnsGray = trns?.takeIf { colorType == 0 && it.size >= 2 }?.let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
        val trnsRgb = trns?.takeIf { colorType == 2 && it.size >= 6 }?.let { t -> IntArray(3) { ((t[it * 2].toInt() and 0xFF) shl 8) or (t[it * 2 + 1].toInt() and 0xFF) } }
        if (colorType == 3 && trns != null) trns.forEachIndexed { i, a -> if (i < palette.size) palette[i] = (palette[i] and 0xFFFFFF) or ((a.toInt() and 0xFF) shl 24) }
        for (y in 0 until height) {
            val base = y * (stride + 1)
            val filter = raw[base].toInt()
            for (x in 0 until stride) {
                val v = raw[base + 1 + x].toInt() and 0xFF
                val a = if (x >= bpp) cur[x - bpp].toInt() and 0xFF else 0
                val b = prev[x].toInt() and 0xFF
                val c = if (x >= bpp) prev[x - bpp].toInt() and 0xFF else 0
                cur[x] = (when (filter) {
                    0 -> v
                    1 -> v + a
                    2 -> v + b
                    3 -> v + ((a + b) ushr 1)
                    4 -> {
                        val p = a + b - c
                        val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
                        v + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> throw PngException("bad filter $filter")
                }).toByte()
            }
            fun sample(index: Int): Int = when (depth) {
                8 -> cur[index].toInt() and 0xFF
                16 -> ((cur[index * 2].toInt() and 0xFF) shl 8) or (cur[index * 2 + 1].toInt() and 0xFF)
                else -> {
                    val bit = index * depth
                    ((cur[bit / 8].toInt() and 0xFF) shr (8 - depth - bit % 8)) and ((1 shl depth) - 1)
                }
            }
            fun to8(v: Int) = when (depth) { 16 -> v ushr 8; 8 -> v; else -> v * 255 / ((1 shl depth) - 1) }
            for (x in 0 until width) {
                out[y * width + x] = when (colorType) {
                    0 -> { val s = sample(x); val g = to8(s); (if (s == trnsGray) 0 else 0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                    2 -> {
                        val r = sample(x * 3); val g = sample(x * 3 + 1); val bl = sample(x * 3 + 2)
                        val alpha = if (trnsRgb != null && r == trnsRgb[0] && g == trnsRgb[1] && bl == trnsRgb[2]) 0 else 0xFF
                        (alpha shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(bl)
                    }
                    3 -> palette.getOrElse(sample(x)) { 0xFF000000.toInt() }
                    4 -> { val g = to8(sample(x * 2)); (to8(sample(x * 2 + 1)) shl 24) or (g shl 16) or (g shl 8) or g }
                    else -> (to8(sample(x * 4 + 3)) shl 24) or (to8(sample(x * 4)) shl 16) or (to8(sample(x * 4 + 1)) shl 8) or to8(sample(x * 4 + 2))
                }
            }
            cur.copyInto(prev)
        }
        return Argb(width, height, out)
    }

    /** Encodes RGBA 8-bit; [extraChunks] are written before the image data (e.g. `npTc`). */
    fun encode(image: Argb, extraChunks: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        fun chunk(type: String, data: ByteArray) {
            val d = DataOutputStream(out)
            d.writeInt(data.size)
            val t = type.toByteArray(Charsets.ISO_8859_1)
            d.write(t); d.write(data)
            d.writeInt(CRC32().apply { update(t); update(data) }.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(image.width); writeInt(image.height); write(byteArrayOf(8, 6, 0, 0, 0)) } }.toByteArray()
        chunk("IHDR", ihdr)
        extraChunks.forEach { (t, d) -> chunk(t, d) }
        val raw = ByteArray((image.width * 4 + 1) * image.height)
        var p = 0
        for (y in 0 until image.height) {
            raw[p++] = 0
            for (x in 0 until image.width) {
                val c = image[x, y]
                raw[p++] = (c shr 16).toByte(); raw[p++] = (c shr 8).toByte(); raw[p++] = c.toByte(); raw[p++] = (c ushr 24).toByte()
            }
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw); deflater.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}

/**
 * Compiles a nine-patch source image (`*.9.png` with a 1-pixel marker border) the way aapt2
 * does: the border is removed and the stretch regions and padding are stored in an `npTc` chunk
 * (Res_png_9patch) that the platform's image decoder reads.
 */
object NinePatch {
    private const val BLACK = 0xFF000000.toInt()
    private const val NO_COLOR = 0x00000001

    class NinePatchException(message: String) : Exception(message)

    fun compile(png: ByteArray): ByteArray {
        val src = try { Png.decode(png) } catch (e: Png.PngException) { throw NinePatchException(e.message ?: "invalid PNG") }
        if (src.width < 3 || src.height < 3) throw NinePatchException("a nine-patch must be at least 3x3 pixels")
        val w = src.width - 2
        val h = src.height - 2
        fun marks(count: Int, pixel: (Int) -> Int, what: String): List<Int> {
            val divs = mutableListOf<Int>()
            var inside = false
            for (i in 0 until count) {
                val c = pixel(i)
                val black = c == BLACK
                // Red marks optical (layout) bounds; anything else that is not transparent or black is an error.
                if (!black && (c ushr 24) != 0 && c != 0xFFFF0000.toInt()) throw NinePatchException("$what marker at ${i + 1} must be black or transparent")
                if (black != inside) { divs += i; inside = black }
            }
            if (inside) divs += count
            return divs
        }
        val xDivs = marks(w, { src[it + 1, 0] }, "top")
        val yDivs = marks(h, { src[0, it + 1] }, "left")
        val xPad = marks(w, { src[it + 1, src.height - 1] }, "bottom")
        val yPad = marks(h, { src[src.width - 1, it + 1] }, "right")
        if (xPad.size > 2 || yPad.size > 2) throw NinePatchException("padding markers must be a single line")
        val padLeft: Int; val padRight: Int; val padTop: Int; val padBottom: Int
        if (xPad.size == 2) { padLeft = xPad[0]; padRight = w - xPad[1] } else if (xDivs.size >= 2) { padLeft = xDivs[0]; padRight = w - xDivs[1] } else { padLeft = 0; padRight = 0 }
        if (yPad.size == 2) { padTop = yPad[0]; padBottom = h - yPad[1] } else if (yDivs.size >= 2) { padTop = yDivs[0]; padBottom = h - yDivs[1] } else { padTop = 0; padBottom = 0 }

        // One colour per distinct rectangle, as the platform counts them; NO_COLOR draws normally.
        fun rects(divs: List<Int>, size: Int): Int {
            if (divs.isEmpty()) return 1
            var n = divs.size + 1
            if (divs.first() == 0) n--
            if (divs.last() == size) n--
            return n
        }
        val numColors = rects(xDivs, w) * rects(yDivs, h)
        if (xDivs.size > 255 || yDivs.size > 255 || numColors > 255) throw NinePatchException("too many stretch regions")

        val chunk = ByteArrayOutputStream()
        val le = java.nio.ByteBuffer.allocate(32 + 4 * (xDivs.size + yDivs.size + numColors))
        le.order(java.nio.ByteOrder.BIG_ENDIAN)
        le.put(0); le.put(xDivs.size.toByte()); le.put(yDivs.size.toByte()); le.put(numColors.toByte())
        // Offsets are recomputed by the reader; written like aapt2 (host order) for fidelity.
        val xOff = 32; val yOff = xOff + xDivs.size * 4; val cOff = yOff + yDivs.size * 4
        le.putInt(Integer.reverseBytes(xOff)); le.putInt(Integer.reverseBytes(yOff))
        le.putInt(padLeft); le.putInt(padRight); le.putInt(padTop); le.putInt(padBottom)
        le.putInt(Integer.reverseBytes(cOff))
        xDivs.forEach { le.putInt(it) }
        yDivs.forEach { le.putInt(it) }
        repeat(numColors) { le.putInt(NO_COLOR) }
        chunk.write(le.array())

        val inner = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) inner[y * w + x] = src[x + 1, y + 1]
        return Png.encode(Argb(w, h, inner), listOf("npTc" to chunk.toByteArray()))
    }
}
