package app.folio.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * On-disk form of a [Stroke].
 *
 * Schema 2 stores the per-point channels as one base64 string, [pts]: little-endian,
 * channel after channel (x, y, pressure, tilt, orientation as float32, then t as int32),
 * so 24 bytes per point. Lossless, and it reads with one decode instead of parsing
 * every number as text: a 7,000-stroke imported page took 17 s to open on a
 * Galaxy Tab S6 Lite as schema 1.
 *
 * Schema 1 wrote the channels as JSON number arrays; those files still load.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class StrokeRecord(
    val id: String,
    val tool: ToolType,
    val color: Argb,
    val width: Float,
    val pts: String? = null,
    // Schema 1 channels; never written any more.
    @EncodeDefault(NEVER) val x: FloatArray? = null,
    @EncodeDefault(NEVER) val y: FloatArray? = null,
    @EncodeDefault(NEVER) val pressure: FloatArray? = null,
    @EncodeDefault(NEVER) val tilt: FloatArray? = null,
    @EncodeDefault(NEVER) val orientation: FloatArray? = null,
    @EncodeDefault(NEVER) val t: IntArray? = null,
    val startedAt: Long = 0,
)

internal object StrokeSerializer : KSerializer<Stroke> {
    private const val BYTES_PER_POINT = 24
    private val record = StrokeRecord.serializer()

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor("app.folio.core.model.Stroke", record.descriptor)

    override fun serialize(encoder: Encoder, value: Stroke) {
        encoder.encodeSerializableValue(
            record,
            StrokeRecord(value.id, value.tool, value.color, value.width, pts = pack(value), startedAt = value.startedAt),
        )
    }

    override fun deserialize(decoder: Decoder): Stroke {
        val r = decoder.decodeSerializableValue(record)
        val pts = r.pts
        if (pts != null) return unpack(r, pts)
        // Schema 1. Missing channels are a damaged file; Stroke's init rejects unequal lengths.
        requireNotNull(r.x) { "stroke ${r.id} has no points" }
        return Stroke(
            r.id, r.tool, r.color, r.width, r.x,
            requireNotNull(r.y) { "stroke ${r.id} has no y" },
            requireNotNull(r.pressure) { "stroke ${r.id} has no pressure" },
            r.tilt ?: FloatArray(r.x.size), r.orientation ?: FloatArray(r.x.size), r.t ?: IntArray(r.x.size),
            r.startedAt,
        )
    }

    fun pack(s: Stroke): String {
        val n = s.size
        val buf = ByteBuffer.allocate(n * BYTES_PER_POINT).order(ByteOrder.LITTLE_ENDIAN)
        // Each view starts at the buffer's current position; advance it past what the view wrote.
        for (ch in arrayOf(s.x, s.y, s.pressure, s.tilt, s.orientation)) {
            buf.asFloatBuffer().put(ch)
            buf.position(buf.position() + n * 4)
        }
        buf.asIntBuffer().put(s.t)
        return Base64.getEncoder().encodeToString(buf.array())
    }

    private fun unpack(r: StrokeRecord, pts: String): Stroke {
        val bytes = Base64.getDecoder().decode(pts) // IllegalArgumentException on bad base64 → reported as corrupt
        require(bytes.size % BYTES_PER_POINT == 0) { "stroke ${r.id} has a truncated point list" }
        val n = bytes.size / BYTES_PER_POINT
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun floats() = FloatArray(n).also { buf.asFloatBuffer().get(it); buf.position(buf.position() + n * 4) }
        val x = floats(); val y = floats(); val p = floats(); val tilt = floats(); val o = floats()
        val t = IntArray(n).also { buf.asIntBuffer().get(it) }
        return Stroke(r.id, r.tool, r.color, r.width, x, y, p, tilt, o, t, r.startedAt)
    }
}
