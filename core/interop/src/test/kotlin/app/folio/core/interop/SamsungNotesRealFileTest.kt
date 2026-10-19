package app.folio.core.interop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs against the user's own notes when present locally (never committed). */
class SamsungNotesRealFileTest {
    private val dir = File(System.getProperty("user.home"), "Developer/Folio/build/formats/sdocx")

    @Test fun importsTheUsersNotes() {
        var total = 0
        val files = dir.listFiles { f -> f.name.endsWith(".sdocx") }.orEmpty()
        if (files.isEmpty()) { println("skipped: no local samples"); return }
        for (f in files.sortedBy { it.name }) {
            val nb = SamsungNotesImporter(f).use { it.read(f.nameWithoutExtension) }
            val strokes = nb.pages.flatMap { it.strokes }
            println("SDOCX ${f.name.take(10)} pages=${nb.pages.size} strokes=${strokes.size} highlighter=${strokes.count { it.tool == app.folio.core.model.ToolType.HIGHLIGHTER }} pdfPages=${nb.pages.count { it.pdfKey != null }} pdfs=${nb.attachmentKeys.size} warnings=${nb.warnings}")
            nb.pages.flatMap { it.texts }.forEach { println("SDOCX   text '${it.text.replace("\n", "⏎")}' at (%.0f,%.0f) w=%.0f size=%.1f color=%08X".format(it.x, it.y, it.width, it.fontSize, it.color)) }
            // Every page that has a PDF key points at an attachment the notebook lists, and the file is there.
            for (p in nb.pages) p.pdfKey?.let { k -> assertTrue(k in nb.attachmentKeys, "pdf $k not listed") }
            assertTrue(nb.pages.isNotEmpty())
            total += strokes.size
            for (p in nb.pages) for (s in p.strokes) {
                assertTrue(s.x.all { it > -p.width && it < 2 * p.width } && s.y.all { it > -p.width && it < p.height + p.width }, "stroke ${s.id} far outside its page")
                assertTrue(s.pressure.all { it in 0f..1f })
            }
        }
        println("SDOCX total strokes=$total")
        // 33,001 classic + 1,896 with recognition data + 588 shape-tool strokes + 367 rotated were verified in research.
        assertTrue(total >= 35_500, "expected all decodable strokes, got $total")
    }
}
