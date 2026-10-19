package app.folio.core.interop

import app.folio.core.model.ToolType
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tiny protobuf writer for building synthetic fixtures. */
private class Pb {
    private val out = ByteArrayOutputStream()
    private fun varint(v: Long) { var x = v; while (true) { if (x and 0x7F.inv().toLong() == 0L) { out.write(x.toInt()); return }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
    fun int(f: Int, v: Long) = apply { varint((f shl 3).toLong()); varint(v) }
    fun str(f: Int, s: String) = bytes(f, s.toByteArray())
    fun bytes(f: Int, b: ByteArray) = apply { varint(((f shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
    fun msg(f: Int, m: Pb) = bytes(f, m.build())
    fun f32(f: Int, v: Float) = apply {
        varint(((f shl 3) or 5).toLong()); val bits = java.lang.Float.floatToIntBits(v)
        for (k in 0 until 4) out.write((bits ushr (8 * k)) and 0xFF)
    }
    fun build(): ByteArray = out.toByteArray()
    companion object { fun stream(vararg msgs: Pb): ByteArray = ByteArrayOutputStream().apply { for (m in msgs) { val b = m.build(); Pb().apply { varint(b.size.toLong()) }.build().let { write(it) }; write(b) } }.toByteArray() }
}

private fun le(v: Int, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 0xFF).toByte() }

/** A tpl image for vuA(v)A(S(uu))A(S(uuuu))vA(f): one move + quads. */
private fun strokeTpl(width: Float, start: Pair<Float, Float>, quads: List<FloatArray>): ByteArray {
    val fmt = "vuA(v)A(S(uu))A(S(uuuu))vA(f)"
    val body = ByteArrayOutputStream()
    body.write(le(2, 2)); body.write(le(java.lang.Float.floatToIntBits(width), 4))
    body.write(le(1 + quads.size, 4)); body.write(le(0, 2)); repeat(quads.size) { body.write(le(1, 2)) }
    body.write(le(1, 4)); body.write(le(java.lang.Float.floatToIntBits(start.first), 4)); body.write(le(java.lang.Float.floatToIntBits(start.second), 4))
    body.write(le(quads.size, 4)); quads.forEach { q -> q.forEach { body.write(le(java.lang.Float.floatToIntBits(it), 4)) } }
    body.write(le(1, 2)); body.write(le(0, 4))
    val header = "tpl".toByteArray() + byteArrayOf(0) + ByteArray(4) + fmt.toByteArray() + byteArrayOf(0)
    val all = header + body.toByteArray()
    le(all.size, 4).copyInto(all, 4)
    return all
}

/** Wraps bytes as an Apple LZ4 container using one literal-only LZ4 block. */
private fun appleLz4(raw: ByteArray): ByteArray {
    val block = ByteArrayOutputStream()
    var lit = raw.size
    block.write((minOf(lit, 15) shl 4))
    if (lit >= 15) { lit -= 15; while (lit >= 255) { block.write(255); lit -= 255 }; block.write(lit) }
    block.write(raw)
    val b = block.toByteArray()
    return "bv41".toByteArray() + le(raw.size, 4) + le(b.size, 4) + b + "bv4$".toByteArray()
}

class BinaryTest {
    @Test fun lz4LiteralsAndBackReferences() {
        // Hand-made block: literals "abc", then a match of 6 bytes at offset 3 → "abcabcabc".
        val block = byteArrayOf(0x32, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 3, 0)
        val src = "bv41".toByteArray() + le(9, 4) + le(block.size, 4) + block + "bv4$".toByteArray()
        assertEquals("abcabcabc", String(AppleLz4.decode(src)))
    }

    @Test fun lz4RejectsBadOffsetsAndOversizedOutput() {
        val bad = byteArrayOf(0x10, 'a'.code.toByte(), 9, 0)
        assertFailsWith<FormatException> { AppleLz4.decode("bv41".toByteArray() + le(10, 4) + le(bad.size, 4) + bad) }
        assertFailsWith<FormatException> { AppleLz4.decode(appleLz4(ByteArray(100)), maxOutput = 10) }
    }

    @Test fun tplRoundTripAndTruncation() {
        val t = Tpl.read(strokeTpl(2.5f, 1f to 2f, listOf(floatArrayOf(3f, 4f, 5f, 6f))))
        assertEquals("vuA(v)A(S(uu))A(S(uuuu))vA(f)", t.format)
        assertEquals(2.5f, (t.values[1] as Tpl.Scalar).float32)
        assertFailsWith<FormatException> { Tpl.read(strokeTpl(1f, 0f to 0f, emptyList()).copyOf(30)) }
    }

    @Test fun protoRejectsGarbage() {
        assertFailsWith<FormatException> { Proto.stream(byteArrayOf(0x7F, 1, 2)) }
    }
}

class GoodNotesImporterTest {
    private val dir: File = Files.createTempDirectory("gn").toFile()
    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private fun zip(entries: Map<String, ByteArray>): File {
        val f = File(dir, "t.goodnotes")
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }

    private val slotA = "00000000-0000-0000-0000-00000000000A"
    private val slotB = "00000000-0000-0000-0000-00000000000B"

    private fun header(id: String, deleted: Boolean = false) = Pb().str(1, id).apply { if (deleted) int(3, 1) }.str(6, id)
    private fun stroke(id: String, x: Float, alpha: Float = 1f) = Pb().msg(
        7, Pb().str(1, id)
            .bytes(2, appleLz4(strokeTpl(2.6f, x to 100f, listOf(floatArrayOf(x + 10, 110f, x + 20, 100f)))))
            .msg(4, Pb().f32(1, 0f).f32(2, 0.478f).f32(3, 1f).f32(4, alpha)),
    )

    private fun sample(): File {
        val events = Pb.stream(
            Pb().str(1, "doc").msg(30, Pb().str(1, "doc").msg(2, Pb().str(1, "My Notes"))),
            Pb().str(1, "p1").msg(2, Pb().str(1, "doc").str(2, "p1").str(4, "att").int(5, 2).msg(8, Pb().f32(1, 1093f).f32(2, 1545f))),
            Pb().str(1, "p2").msg(2, Pb().str(1, "doc").str(2, "p2").msg(8, Pb().f32(1, 1093f).f32(2, 1545f))),
            // Slots: page 2 is ordered first by its fractional key.
            Pb().str(1, slotA).msg(54, Pb().str(2, slotA).msg(3, Pb().str(1, "p1")).msg(4, Pb().str(1, "a5"))),
            Pb().str(1, slotB).msg(54, Pb().str(2, slotB).msg(3, Pb().str(1, "p2")).msg(4, Pb().str(1, "a1"))),
            Pb().str(1, "t1").msg(130, Pb().str(1, "t1")),
        )
        val notesA = Pb.stream(header("s1"), stroke("s1", 50f), header("s2"), stroke("s2", 300f, alpha = 0.4f), header("gone", deleted = true), header("s3"), stroke("s3", 600f), header("s3", deleted = true))
        // A header that also carries a field 7 must still count as a header.
        val notesB = Pb.stream(header("s9"), stroke("s9", 10f), header("h7").bytes(7, byteArrayOf(10, 0)))
        return zip(
            mapOf(
                "schema.pb" to byteArrayOf(8, 31),
                "index.events.pb" to events,
                "index.attachments.pb" to Pb.stream(Pb().str(1, "att").str(2, "attachments/F1")),
                "attachments/F1" to "%PDF-1.4\n".toByteArray(),
                "notes/" + GoodNotesImporter.plusOne(slotA) to notesA,
                "notes/" + GoodNotesImporter.plusOne(slotB) to notesB,
            ),
        )
    }

    @Test fun importsPagesInOrderWithStrokesAndPdf() {
        val nb = GoodNotesImporter(sample()).use { it.read() }
        assertEquals("My Notes", nb.title)
        assertEquals(2, nb.pages.size)
        val (first, second) = nb.pages
        assertEquals(1, first.strokes.size, "page with key a1 comes first")
        assertEquals(null, first.pdfKey)
        assertEquals("attachments/F1", second.pdfKey)
        assertEquals(1, second.pdfPageIndex, "1-based PDF page 2 → index 1")
        assertEquals(setOf("attachments/F1"), nb.attachmentKeys)
        // s3 was deleted after being drawn; "gone" never had content.
        assertEquals(listOf("s1", "s2"), second.strokes.map { it.id })
        assertTrue(nb.warnings.any { "text box" in it })
    }

    @Test fun convertsUnitsColoursAndHighlighters() {
        val page = GoodNotesImporter(sample()).use { it.read() }.pages[1]
        val s = GoodNotesImporter.UNITS_PER_GN
        assertEquals(1093f * s, page.width, 0.01f)
        val pen = page.strokes[0]
        assertEquals(ToolType.BALLPOINT, pen.tool)
        assertEquals(0xFF007AFFL, pen.color)
        assertEquals(2.6f * s, pen.width, 0.001f)
        assertEquals(50f * s, pen.x.first(), 0.01f)
        assertEquals(70f * s, pen.x.last(), 0.01f)
        assertTrue(pen.size > 2, "quadratic curve is sampled into points")
        assertEquals(ToolType.HIGHLIGHTER, page.strokes[1].tool)
    }

    @Test fun importsVectorLinesAndCurvesFromField9() {
        fun pt(x: Float, y: Float) = Pb().f32(1, x).f32(2, y)
        val emptyBlob = appleLz4(strokeTpl(14f, 0f to 0f, emptyList()).let { t ->
            // Rewrite as a geometry-less tpl: zero moves and zero quads.
            val fmt = "vuA(v)A(S(uu))A(S(uuuu))vA(f)"
            val body = le(2, 2) + le(0, 4) + le(0, 4) + le(0, 4) + le(0, 4) + le(1, 2) + le(0, 4)
            val h = "tpl".toByteArray() + byteArrayOf(0) + ByteArray(4) + fmt.toByteArray() + byteArrayOf(0)
            (h + body).also { le(it.size, 4).copyInto(it, 4) }
        })
        fun vec(id: String, geometry: Pb) = Pb().msg(7, Pb().str(1, id).bytes(2, emptyBlob)
            .msg(4, Pb().f32(1, 1f).f32(2, 0.635f).f32(3, 0.22f).f32(4, 0.5f)).int(5, 1).msg(9, geometry.f32(15, 14f)))
        val notes = Pb.stream(
            header("line"), vec("line", Pb().msg(1, Pb().msg(1, pt(100f, 500f)).msg(1, pt(300f, 500f)))),
            header("curve"), vec("curve", Pb().msg(2, Pb().msg(1, pt(0f, 0f)).msg(2, pt(50f, 50f)).msg(3, pt(100f, 0f)))),
            header("rect"), vec("rect", Pb().msg(3, Pb().msg(1, pt(0f, 0f)).msg(2, pt(10f, 10f)))),
        )
        val f = zip(mapOf(
            "index.events.pb" to Pb.stream(Pb().str(1, "p").msg(2, Pb().str(2, "p")), Pb().str(1, slotA).msg(54, Pb().str(2, slotA).msg(3, Pb().str(1, "p")).msg(4, Pb().str(1, "a")))),
            "notes/" + GoodNotesImporter.plusOne(slotA) to notes,
        ))
        val nb = GoodNotesImporter(f).use { it.read() }
        val (line, curve) = nb.pages.single().strokes
        val s = GoodNotesImporter.UNITS_PER_GN
        assertEquals(ToolType.HIGHLIGHTER, line.tool)
        assertEquals(14f * s, line.width, 0.01f)
        assertEquals(100f * s, line.x.first(), 0.01f); assertEquals(300f * s, line.x.last(), 0.01f)
        assertTrue(line.y.all { kotlin.math.abs(it - 500f * s) < 0.01f })
        // The curve passes through its middle point.
        assertTrue(curve.x.indices.any { kotlin.math.abs(curve.x[it] - 50f * s) < 1f && kotlin.math.abs(curve.y[it] - 50f * s) < 1f })
        assertTrue(nb.warnings.any { "shape" in it }, "the unknown 9.3 shape is reported, not guessed")
    }

    @Test fun rejectsNonGoodNotesFiles() {
        assertFailsWith<FormatException> { GoodNotesImporter(zip(mapOf("hello.txt" to "hi".toByteArray()))).use { it.read() } }
        val notZip = File(dir, "x.goodnotes").apply { writeText("not a zip") }
        assertFailsWith<FormatException> { GoodNotesImporter(notZip) }
    }

    @Test fun corruptStrokeIsSkippedNotFatal() {
        val bad = Pb.stream(header("x"), Pb().msg(7, Pb().str(1, "x").bytes(2, "bv41garbage".toByteArray())))
        val f = zip(
            mapOf(
                "index.events.pb" to Pb.stream(
                    Pb().str(1, "p").msg(2, Pb().str(2, "p")),
                    Pb().str(1, slotA).msg(54, Pb().str(2, slotA).msg(3, Pb().str(1, "p")).msg(4, Pb().str(1, "a"))),
                ),
                "notes/" + GoodNotesImporter.plusOne(slotA) to bad,
            ),
        )
        val nb = GoodNotesImporter(f).use { it.read() }
        assertEquals(1, nb.pages.size)
        assertTrue(nb.warnings.any { "skipped" in it })
    }

    @Test fun plusOneCarriesAcrossTheLowWord() {
        assertEquals("CFDA17F3-7C35-53ED-99D6-E4B0CFB96280", GoodNotesImporter.plusOne("CFDA17F3-7C35-53ED-99D6-E4B0CFB9627F"))
    }
}

/**
 * Runs against the user's own notebook when it exists locally (never committed):
 * build/formats/sample.goodnotes, a 79-page notebook with two PDFs.
 */
class GoodNotesRealFileTest {
    private val sample = File(System.getProperty("user.home"), "Developer/Folio/build/formats/sample.goodnotes")

    @Test fun importsTheUsersNotebook() {
        if (!sample.exists()) { println("skipped: no local sample"); return }
        val nb = GoodNotesImporter(sample).use { it.read() }
        println("title=${nb.title} pages=${nb.pages.size} strokes=${nb.pages.sumOf { it.strokes.size }} pdfs=${nb.attachmentKeys.size} warnings=${nb.warnings}")
        assertTrue(nb.title.isNotBlank())
        assertEquals(79, nb.pages.size)
        // 2,022 live curve strokes (368 more are deletions in the document's history).
        // 685 freehand strokes + 1,487 vector strokes (lines/curves, mostly highlighter); deleted ones excluded.
        val total = nb.pages.sumOf { it.strokes.size }
        assertTrue(total in 1900..2400, "strokes: $total")
        assertTrue(nb.pages.flatMap { it.strokes }.count { it.tool == ToolType.HIGHLIGHTER } > 1000)
        assertTrue(nb.pages.first().pdfKey != null)
        // Every page fits on an A4-ish sheet; strokes stay inside their page.
        for (p in nb.pages) for (s in p.strokes) {
            assertTrue(s.x.all { it > -50 && it < p.width + 50 } && s.y.all { it > -50 && it < p.height + 50 }, "stroke ${s.id} outside its page")
        }
    }
}
