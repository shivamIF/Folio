package app.folio.notes.library

import app.folio.core.model.PageFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class NotebookDefaultsTest {
    @Test fun standardFormatIgnoresScreen() {
        val d = NotebookDefaults(size = PageSizeChoice.LETTER, landscape = true)
        assertEquals(PageFormat.LETTER.size(true), d.pageSize(1200, 2000))
    }

    @Test fun autoMatchesScreenAspectInEitherOrientation() {
        // Galaxy Tab S6 Lite: 1200 x 2000 px, whichever way it's held when the notebook is created.
        val portrait = NotebookDefaults(size = PageSizeChoice.AUTO, landscape = false)
        assertEquals(1000f to 2000f / 1200f * 1000f, portrait.pageSize(2000, 1200))
        val landscape = NotebookDefaults(size = PageSizeChoice.AUTO, landscape = true)
        val (w, h) = landscape.pageSize(1200, 2000)
        assertEquals(2000f / 1200f, w / h, 1e-4f)
    }
}
