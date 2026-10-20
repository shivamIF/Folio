package app.folio.notes.library

import app.folio.core.model.Folder
import app.folio.core.model.LibraryIndex
import app.folio.core.model.NotebookMeta
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryQueryTest {
    private val upsc = Folder(id = "upsc", name = "UPSC", createdAt = 10, modifiedAt = 10)
    private val polity = Folder(id = "polity", name = "Polity", parentId = "upsc", createdAt = 11, favorite = true)
    private val old = Folder(id = "old", name = "Old", createdAt = 1, deletedAt = 50)
    private val idx = LibraryIndex(
        folders = listOf(upsc, polity, old),
        notebooks = listOf(
            NotebookMeta(id = "a", title = "Untitled", createdAt = 30, modifiedAt = 300),
            NotebookMeta(id = "b", title = "SEC - B", createdAt = 20, modifiedAt = 200, favorite = true),
            NotebookMeta(id = "c", title = "Art 21", folderId = "polity", createdAt = 5, modifiedAt = 400),
            NotebookMeta(id = "d", title = "Scrap", createdAt = 1, modifiedAt = 1, deletedAt = 60),
            NotebookMeta(id = "e", title = "Inside old", folderId = "old", createdAt = 1, modifiedAt = 1),
        ),
    )

    private fun keys(q: LibraryQuery) = q.items(idx).map { it.key }

    @Test fun topLevelMixesFoldersAndNotebooksNewestFirst() {
        // UPSC's own date is old, but its newest direct notebook is not inside it (Art 21 is in Polity).
        assertEquals(listOf("n:a", "n:b", "f:upsc"), keys(LibraryQuery()))
    }

    @Test fun folderShowsItsOwnChildren() {
        assertEquals(listOf("f:polity"), keys(LibraryQuery(folderId = "upsc")))
        assertEquals(listOf("n:c"), keys(LibraryQuery(folderId = "polity")))
    }

    @Test fun folderDateFollowsNotebooksDirectlyInside() {
        val item = LibraryQuery(folderId = "upsc").items(idx).single() as LibraryItem.FolderItem
        assertEquals(400, item.modifiedAt)
        assertEquals(1, item.notebookCount)
    }

    @Test fun filtersAndTypeSort() {
        assertEquals(listOf("f:upsc"), keys(LibraryQuery(filter = ItemFilter.FOLDERS)))
        assertEquals(listOf("n:a", "n:b"), keys(LibraryQuery(filter = ItemFilter.NOTEBOOKS)))
        assertEquals(listOf("f:upsc", "n:b", "n:a"), keys(LibraryQuery(sort = SortOrder.TYPE, descending = false)))
        assertEquals(listOf("n:b", "n:a", "f:upsc"), keys(LibraryQuery(sort = SortOrder.NAME, descending = false)))
    }

    @Test fun searchLooksEverywhereButTheTrash() {
        assertEquals(listOf("n:c"), keys(LibraryQuery(search = "art")))
        assertEquals(listOf("f:polity"), keys(LibraryQuery(search = "POL")))
        assertEquals(emptyList(), keys(LibraryQuery(search = "scrap")))
    }

    @Test fun favoritesAndTrash() {
        // Polity is dated by Art 21 (400), newer than SEC - B (200).
        assertEquals(listOf("f:polity", "n:b"), keys(LibraryQuery(section = Section.FAVORITES)))
        // "Inside old" goes with its trashed folder rather than being listed on its own.
        assertEquals(listOf("n:d", "f:old"), keys(LibraryQuery(section = Section.TRASH)))
    }

    @Test fun breadcrumbAndMoveTree() {
        assertEquals(listOf("UPSC", "Polity"), idx.pathTo("polity").map { it.name })
        assertEquals(listOf("UPSC" to 0, "Polity" to 1), idx.folderTree().map { it.folder.name to it.depth })
        assertEquals(emptyList(), idx.folderTree(excluded = setOf("upsc")))
    }
}
