package app.folio.core.interop

/** Thrown for any malformed or unsupported input; the message is safe to show to the user. */
class FormatException(message: String) : Exception(message)

/**
 * Decoder for Apple's Compression-framework LZ4 container: a sequence of
 * "bv41" (LZ4 block), "bv4-" (stored) blocks ended by "bv4$".
 */
object AppleLz4 {
    fun decode(src: ByteArray, maxOutput: Int = 64 * 1024 * 1024): ByteArray {
        val out = Growable()
        var i = 0
        while (i + 4 <= src.size) {
            val magic = String(src, i, 4, Charsets.ISO_8859_1)
            when (magic) {
                "bv4$" -> return out.toByteArray()
                "bv41" -> {
                    val raw = le32(src, i + 4); val comp = le32(src, i + 8)
                    if (raw < 0 || comp < 0 || i + 12 + comp > src.size) throw FormatException("bad LZ4 block")
                    if (out.size + raw > maxOutput) throw FormatException("decompressed data too large")
                    lz4Block(src, i + 12, i + 12 + comp, raw, out)
                    i += 12 + comp
                }
                "bv4-" -> {
                    val n = le32(src, i + 4)
                    if (n < 0 || i + 8 + n > src.size) throw FormatException("bad stored block")
                    if (out.size + n > maxOutput) throw FormatException("decompressed data too large")
                    out.write(src, i + 8, n); i += 8 + n
                }
                else -> throw FormatException("unknown compression block")
            }
        }
        return out.toByteArray()
    }

    private fun lz4Block(src: ByteArray, start: Int, end: Int, rawLen: Int, out: Growable) {
        val target = out.size + rawLen
        var i = start
        while (i < end) {
            val token = src[i++].toInt() and 0xFF
            var lit = token ushr 4
            if (lit == 15) { while (true) { val b = src[i++].toInt() and 0xFF; lit += b; if (b != 255) break } }
            if (i + lit > end) throw FormatException("LZ4 literal overrun")
            out.write(src, i, lit); i += lit
            if (i >= end || out.size >= target) break
            val off = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8); i += 2
            var ml = token and 15
            if (ml == 15) { while (true) { val b = src[i++].toInt() and 0xFF; ml += b; if (b != 255) break } }
            ml += 4
            if (off <= 0 || off > out.size) throw FormatException("LZ4 bad offset")
            if (out.size + ml > target) throw FormatException("LZ4 output overrun")
            out.copyBack(off, ml)
        }
        if (out.size != target) throw FormatException("LZ4 size mismatch")
    }

    internal fun le32(b: ByteArray, i: Int): Int {
        if (i + 4 > b.size) throw FormatException("truncated")
        return (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
    }

    private class Growable {
        var buf = ByteArray(1024); var size = 0
        fun ensure(n: Int) { if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n)) }
        fun write(src: ByteArray, off: Int, len: Int) { ensure(len); System.arraycopy(src, off, buf, size, len); size += len }
        fun copyBack(offset: Int, len: Int) { ensure(len); var s = size - offset; repeat(len) { buf[size++] = buf[s++] } }
        fun toByteArray() = buf.copyOf(size)
    }
}

/** Minimal schema-less protobuf reader: enough to walk messages by field number. */
class Proto private constructor(val fields: List<Field>) {
    class Field(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray?) {
        val string: String get() = bytes?.toString(Charsets.UTF_8) ?: ""
        val float32: Float get() = java.lang.Float.intBitsToFloat(varint.toInt())
        fun message(): Proto = parse(bytes ?: throw FormatException("field $number is not a message"))
    }

    fun first(n: Int): Field? = fields.firstOrNull { it.number == n }
    fun all(n: Int): List<Field> = fields.filter { it.number == n }
    fun has(n: Int) = fields.any { it.number == n }
    fun string(n: Int): String? = first(n)?.string
    fun msg(n: Int): Proto? = first(n)?.takeIf { it.wireType == 2 }?.let { runCatching { it.message() }.getOrNull() }
    fun int(n: Int): Long? = first(n)?.takeIf { it.wireType == 0 }?.varint
    fun float(n: Int): Float? = first(n)?.takeIf { it.wireType == 5 }?.float32

    companion object {
        fun parse(b: ByteArray, from: Int = 0, to: Int = b.size): Proto {
            val out = ArrayList<Field>()
            var i = from
            while (i < to) {
                val (key, i1) = varint(b, i, to); i = i1
                val num = (key ushr 3).toInt(); val wt = (key and 7).toInt()
                if (num <= 0) throw FormatException("bad field number")
                when (wt) {
                    0 -> { val (v, i2) = varint(b, i, to); i = i2; out += Field(num, 0, v, null) }
                    1 -> { if (i + 8 > to) throw FormatException("truncated"); out += Field(num, 1, 0, b.copyOfRange(i, i + 8)); i += 8 }
                    2 -> {
                        val (n, i2) = varint(b, i, to); i = i2
                        if (n < 0 || i + n > to) throw FormatException("length overrun")
                        out += Field(num, 2, 0, b.copyOfRange(i, i + n.toInt())); i += n.toInt()
                    }
                    5 -> { if (i + 4 > to) throw FormatException("truncated"); out += Field(num, 5, AppleLz4.le32(b, i).toLong() and 0xFFFFFFFFL, null); i += 4 }
                    else -> throw FormatException("unsupported wire type $wt")
                }
            }
            return Proto(out)
        }

        /** Varint-length-prefixed sequence of messages. */
        fun stream(b: ByteArray): List<Proto> {
            val out = ArrayList<Proto>()
            var i = 0
            while (i < b.size) {
                val (n, i1) = varint(b, i, b.size); i = i1
                if (n < 0 || i + n > b.size) throw FormatException("record overrun")
                out += parse(b, i, i + n.toInt()); i += n.toInt()
            }
            return out
        }

        private fun varint(b: ByteArray, start: Int, to: Int): Pair<Long, Int> {
            var shift = 0; var v = 0L; var i = start
            while (true) {
                if (i >= to || shift > 63) throw FormatException("bad varint")
                val c = b[i++].toInt() and 0xFF
                v = v or ((c and 0x7F).toLong() shl shift)
                if (c < 0x80) return v to i
                shift += 7
            }
        }
    }
}

/**
 * Reader for "tpl" images (the open-source troydhanson/tpl serialisation):
 * a header, a type-format string such as "vuA(v)A(S(uu))", then the values
 * in format order, each A(...) as a uint32 count followed by its elements.
 */
object Tpl {
    sealed interface Value
    data class Scalar(val type: Char, val bits: Long) : Value {
        val float32 get() = java.lang.Float.intBitsToFloat(bits.toInt())
        val float64 get() = java.lang.Double.longBitsToDouble(bits)
    }
    data class Struct(val members: List<Value>) : Value
    data class Arr(val items: List<Value>) : Value

    class Image(val format: String, val values: List<Value>)

    private val sizes = mapOf('c' to 1, 'v' to 2, 'j' to 2, 'u' to 4, 'i' to 4, 'f' to 8, 'U' to 8, 'I' to 8)

    fun read(b: ByteArray): Image {
        if (b.size < 9 || b[0] != 't'.code.toByte() || b[1] != 'p'.code.toByte() || b[2] != 'l'.code.toByte()) throw FormatException("not a tpl image")
        val declared = AppleLz4.le32(b, 4)
        if (declared != b.size) throw FormatException("tpl length mismatch")
        var end = 8
        while (end < b.size && b[end] != 0.toByte()) end++
        if (end >= b.size) throw FormatException("tpl format unterminated")
        val fmt = String(b, 8, end - 8, Charsets.US_ASCII)
        val r = Reader(b, end + 1)
        val values = split(fmt).map { r.read(it) }
        if (r.pos != b.size) throw FormatException("tpl trailing data")
        return Image(fmt, values)
    }

    private class Reader(val b: ByteArray, var pos: Int) {
        fun read(tok: String): Value = when {
            tok.startsWith("A(") -> {
                val n = AppleLz4.le32(b, pos); pos += 4
                if (n < 0 || n > 10_000_000) throw FormatException("tpl array too large")
                val inner = tok.substring(2, tok.length - 1)
                val parts = split(inner)
                Arr(List(n) { if (parts.size == 1) read(parts[0]) else Struct(parts.map { read(it) }) })
            }
            tok.startsWith("S(") -> Struct(split(tok.substring(2, tok.length - 1)).map { read(it) })
            else -> {
                val c = tok[0]; val n = sizes[c] ?: throw FormatException("tpl type $c unsupported")
                if (pos + n > b.size) throw FormatException("tpl truncated")
                var v = 0L
                for (k in 0 until n) v = v or ((b[pos + k].toLong() and 0xFF) shl (8 * k))
                pos += n
                Scalar(c, v)
            }
        }
    }

    fun split(fmt: String): List<String> {
        val out = ArrayList<String>(); var i = 0
        while (i < fmt.length) {
            val c = fmt[i]
            if ((c == 'S' || c == 'A') && i + 1 < fmt.length && fmt[i + 1] == '(') {
                var depth = 0; var j = i + 1
                while (true) {
                    if (j >= fmt.length) throw FormatException("tpl format unbalanced")
                    if (fmt[j] == '(') depth++ else if (fmt[j] == ')') { depth--; if (depth == 0) break }
                    j++
                }
                out += fmt.substring(i, j + 1); i = j + 1
            } else { out += c.toString(); i++ }
        }
        return out
    }
}
