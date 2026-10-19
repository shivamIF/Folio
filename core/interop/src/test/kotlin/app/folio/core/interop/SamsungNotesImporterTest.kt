package app.folio.core.interop

import app.folio.core.model.ToolType
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds .sdocx files byte by byte following docs/formats/SDOCX.md, so the format rules are tested without user files. */
class SamsungNotesImporterTest {
    private val tmp = File.createTempFile("sdocx", ".sdocx").apply { deleteOnExit() }
    @AfterTest fun cleanup() { tmp.delete() }

    private class Le { val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v) }
        fun u16(v: Int) = apply { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun u32(v: Int) = apply { for (i in 0 until 4) out.write((v ushr (8 * i)) and 0xFF) }
        fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))
        fun f64(v: Double) = apply { val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array(); out.write(b) }
        fun bytes(b: ByteArray) = apply { out.write(b) }
        fun zeros(n: Int) = apply { repeat(n) { out.write(0) } }
        val size get() = out.size()
        fun build(): ByteArray = out.toByteArray()
    }

    private fun sm(v: Int) = if (v < 0) 0x8000 or (-v) else v

    /** An object: id, time, bbox, 5 zero bytes + rotation (f32 degrees), marker, sizes, payload, then [after]. */
    private fun obj(id: String, bbox: DoubleArray, angle: Float, type: Int, payload: ByteArray, after: ByteArray, diff: Int = 24) = Le()
        .u16(36).bytes(id.toByteArray()).zeros(8)
        .f64(bbox[0]).f64(bbox[1]).f64(bbox[2]).f64(bbox[3])
        .zeros(5).f32(angle).zeros(3)
        .u32(1200).u32(1697)
        .u32(payload.size + diff).u16(type).u32(payload.size)
        .bytes(payload).bytes(after).zeros(16).build()

    /** A stroke payload through the pressure deltas; [trailer] ends the payload (colour/size layout). */
    private fun strokePayload(colored: Boolean, pts: List<Pair<Double, Double>>, trailer: ByteArray): ByteArray {
        val p = Le().u8(0x02).u8(0x25).u8(0x04).u8(0x04).u8(if (colored) 0x8e else 0x8a).u8(0x25).u16(0).u16(pts.size)
            .f64(pts[0].first).f64(pts[0].second)
        for (i in 1 until pts.size) {
            p.u16(sm(((pts[i].first - pts[i - 1].first) * 32).toInt())).u16(sm(((pts[i].second - pts[i - 1].second) * 32).toInt()))
        }
        p.f32(0.4f)
        for (i in 1 until pts.size) p.u16(sm(41)) // +0.01 per point
        return p.zeros(8).bytes(trailer).build()
    }

    private fun page(): ByteArray {
        // Header: flags 0x0100 (PDF), width/height, PDF block at 0x88: media 0, page 2.
        val h = Le().zeros(0x0e).u16(0x0100).zeros(0x16 - 0x10).u32(1200).u32(1697)
        h.zeros(0x88 - h.size).u16(1).u32(0).u32(2).zeros(8).u32(1200).u32(1697)
        h.zeros(0x100 - h.size)
        // 1. Coloured pen stroke: RGBA then size, the size's last bytes run past the payload.
        val red = strokePayload(true, listOf(100.0 to 100.0, 110.0 to 105.0, 120.0 to 110.0), byteArrayOf(0x20, 0x40, 0xC0.toByte(), 0xFF.toByte()) + floatBytes(1.5f).copyOf(2))
        val o1 = obj("00000000-0000-0000-0000-000000000001", doubleArrayOf(99.0, 99.0, 121.0, 111.0), 0f, 1, red, floatBytes(1.5f).copyOfRange(2, 4))
        // 2. Stroke without its own colour: size, a flag word, then the size again.
        val plain = strokePayload(false, listOf(200.0 to 300.0, 260.0 to 300.0), floatBytes(1.2f) + byteArrayOf(4, 0))
        val o2 = obj("00000000-0000-0000-0000-000000000002", doubleArrayOf(199.0, 299.0, 261.0, 301.0), 0f, 1, plain, byteArrayOf(0, 0) + floatBytes(1.2f))
        // 3. Rotated 90°: points stored vertical, box horizontal. Shape-tool header size (diff 16).
        val rot = strokePayload(false, listOf(100.0 to 400.0, 100.0 to 450.0, 100.0 to 500.0), floatBytes(1.0f) + byteArrayOf(0, 0))
        val o3 = obj("00000000-0000-0000-0000-000000000003", doubleArrayOf(50.0, 450.0, 150.0, 450.0), 90f, 1, rot, byteArrayOf(0, 0) + floatBytes(1.0f), diff = 16)
        // 4. Text box: frame = bbox; text block; spans (size 23 pt, colour red).
        val text = "Hello Folio\n"
        val spans = Le().u32(2)
        spans.u16(24).u16(3).zeros(14).f32(23f).zeros(4) // 26 bytes: the length excludes itself
        spans.u16(24).u16(1).zeros(14).u32(0xFFFF3636.toInt()).zeros(4)
        val tp = Le().u16(1).u8(1).u8(0x0c).u32(4).zeros(64)
            .u16(6).u32(4 + 2 * text.length + spans.size).u32(text.length).bytes(text.toByteArray(Charsets.UTF_16LE)).bytes(spans.build()).build()
        val o4 = obj("00000000-0000-0000-0000-000000000004", doubleArrayOf(300.0, 600.0, 900.0, 700.0), 0f, 6, tp, ByteArray(0), diff = 39)
        return h.build() + o1 + o2 + o3 + o4
    }

    private fun floatBytes(v: Float) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()

    private fun write() {
        val pageId = "11111111-2222-3333-4444-555555555555"
        val info = Le().zeros(0x20).u16(1).u16(36).bytes(pageId.toByteArray(Charsets.UTF_16LE)).zeros(32).build()
        ZipOutputStream(tmp.outputStream()).use { z ->
            fun put(name: String, b: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(b); z.closeEntry() }
            put("pageIdInfo.dat", info)
            put("$pageId.page", page())
            put("media/0@pdf_1.pdf", "%PDF-1.4\n".toByteArray())
            put("end_tag.bin", ByteArray(16))
        }
    }

    @Test fun decodesStrokesColoursRotationTextAndPdf() {
        write()
        val nb = SamsungNotesImporter(tmp).use { it.read("My_class_notes_260811_134331") }
        assertEquals("My class notes", nb.title)
        val p = nb.pages.single()
        val k = 1000f / 1200f
        assertEquals(1000f, p.width, 0.01f)
        assertEquals("media/0@pdf_1.pdf", p.pdfKey); assertEquals(2, p.pdfPageIndex)
        assertEquals(setOf("media/0@pdf_1.pdf"), nb.attachmentKeys)

        assertEquals(3, p.strokes.size, "two pen strokes and a rotated shape stroke")
        val (colored, plain, rotated) = p.strokes
        assertEquals(0xFF2040C0, colored.color); assertEquals(ToolType.FOUNTAIN, colored.tool)
        assertEquals(1.5f * SamsungNotesImporter.WIDTH_FACTOR * k, colored.width, 0.01f)
        assertEquals(120f * k, colored.x.last(), 0.05f)
        assertEquals(0.42f, colored.pressure[2], 0.001f)
        assertEquals(0xFF000000, plain.color, "no stored colour → black")
        assertEquals(1.2f * SamsungNotesImporter.WIDTH_FACTOR * k, plain.width, 0.01f)
        // Rotated back by -90° about the box centre: horizontal from x 50 to 150 at y 450.
        assertEquals(50f * k, rotated.x.first(), 0.05f); assertEquals(150f * k, rotated.x.last(), 0.05f)
        assertTrue(rotated.y.all { kotlin.math.abs(it - 450f * k) < 0.05f })

        val t = p.texts.single()
        assertEquals("Hello Folio", t.text)
        assertEquals(0xFFFF3636, t.color, "colour span (font size parsed: ${t.fontSize})")
        assertEquals(23f * (1200f / 595.28f) * k, t.fontSize, 0.05f)
        assertEquals(300f * k + 6f, t.x, 0.05f)
    }
}
