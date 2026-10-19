package app.folio.core.interop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs against the user's own notes when present locally (never committed). */
class SamsungNotesRealFileTest {
    private val dir = File(System.getProperty("user.home"), "Developer/Folio/build/formats/sdocx")

    @Test fun importsTheUsersNotes() {
        val files = dir.listFiles { f -> f.name.endsWith(".sdocx") }.orEmpty()
        if (files.isEmpty()) { println("skipped: no local samples"); return }
        for (f in files.sortedBy { it.name }) {
            val nb = SamsungNotesImporter(f).use { it.read(f.nameWithoutExtension) }
            val strokes = nb.pages.flatMap { it.strokes }
            println("SDOCX ${f.name.take(40)} title='${nb.title}' pages=${nb.pages.size} strokes=${strokes.size} highlighter=${strokes.count { it.tool == app.folio.core.model.ToolType.HIGHLIGHTER }} warnings=${nb.warnings}")
            assertTrue(nb.pages.isNotEmpty())
            for (p in nb.pages) for (s in p.strokes) {
                assertTrue(s.x.all { it > -400 && it < p.width + 400 } && s.y.all { it > -400 && it < p.height + 400 }, "stroke ${s.id} far outside its page")
                assertTrue(s.pressure.all { it in 0f..1f })
            }
        }
    }
}
