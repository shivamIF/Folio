package app.folio.notes.library

import app.folio.core.model.Folder
import app.folio.core.model.LibraryIndex
import app.folio.core.model.NotebookMeta

/** Top-level destinations in the library sidebar. */
enum class Section(val label: String) { DOCUMENTS("Documents"), FAVORITES("Favorites"), TRASH("Trash") }

/** [descendingByDefault]: dates start newest first, names start A→Z. */
enum class SortOrder(val label: String, val descendingByDefault: Boolean) {
    MODIFIED("Last modified", true),
    NAME("Name", false),
    CREATED("Date created", true),
    TYPE("Type", false),
}

enum class ItemFilter(val label: String) { ALL("All"), NOTEBOOKS("Notebooks"), FOLDERS("Folders") }

enum class ViewMode { GRID, LIST }

/** One tile or row in the library: a folder or a notebook. */
sealed interface LibraryItem {
    val key: String
    val title: String
    val createdAt: Long
    /** For folders, the newest change to the folder itself or to a notebook directly inside it. */
    val modifiedAt: Long
    val favorite: Boolean

    data class FolderItem(val folder: Folder, val notebookCount: Int, val folderCount: Int, override val modifiedAt: Long) : LibraryItem {
        override val key get() = folderKey(folder.id)
        override val title get() = folder.name
        override val createdAt get() = folder.createdAt
        override val favorite get() = folder.favorite
    }

    data class NotebookItem(val meta: NotebookMeta) : LibraryItem {
        override val key get() = notebookKey(meta.id)
        override val title get() = meta.title
        override val createdAt get() = meta.createdAt
        override val modifiedAt get() = meta.modifiedAt
        override val favorite get() = meta.favorite
    }

    companion object {
        fun folderKey(id: String) = "f:$id"
        fun notebookKey(id: String) = "n:$id"
    }
}

/** What the library is showing. Pure data, so the listing rules can be unit-tested. */
data class LibraryQuery(
    val section: Section = Section.DOCUMENTS,
    val folderId: String? = null,
    val filter: ItemFilter = ItemFilter.ALL,
    val sort: SortOrder = SortOrder.MODIFIED,
    val descending: Boolean = true,
    val search: String = "",
) {
    fun items(index: LibraryIndex): List<LibraryItem> {
        val trashed = index.trashedFolderIds()
        val liveFolders = index.folders.filter { it.id !in trashed }
        val liveNotebooks = index.notebooks.filter { it.deletedAt == null && it.folderId !in trashed }
        val text = search.trim()

        val (folders, notebooks) = when (section) {
            Section.DOCUMENTS ->
                if (text.isNotEmpty()) liveFolders to liveNotebooks
                else liveFolders.filter { it.parentId == folderId } to liveNotebooks.filter { it.folderId == folderId }
            Section.FAVORITES -> liveFolders.filter { it.favorite } to liveNotebooks.filter { it.favorite }
            // Only the top of each trashed tree: a notebook inside a trashed folder goes with its folder.
            Section.TRASH ->
                index.folders.filter { it.deletedAt != null && it.parentId !in trashed } to
                    index.notebooks.filter { it.deletedAt != null && it.folderId !in trashed }
        }

        val counted = if (section == Section.TRASH) index.notebooks else liveNotebooks
        val countedFolders = if (section == Section.TRASH) index.folders else liveFolders
        val items = buildList<LibraryItem> {
            if (filter != ItemFilter.NOTEBOOKS) folders.forEach { f ->
                val inside = counted.filter { it.folderId == f.id }
                add(
                    LibraryItem.FolderItem(
                        f, inside.size, countedFolders.count { it.parentId == f.id },
                        maxOf(f.modifiedAt, inside.maxOfOrNull { it.modifiedAt } ?: 0L),
                    ),
                )
            }
            if (filter != ItemFilter.FOLDERS) notebooks.forEach { add(LibraryItem.NotebookItem(it)) }
        }.filter { text.isEmpty() || it.title.contains(text, ignoreCase = true) }

        return sorted(items)
    }

    private fun sorted(items: List<LibraryItem>): List<LibraryItem> {
        val byName = compareBy<LibraryItem, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        val cmp: Comparator<LibraryItem> = when (sort) {
            SortOrder.MODIFIED -> compareBy<LibraryItem> { it.modifiedAt }.then(byName)
            SortOrder.CREATED -> compareBy<LibraryItem> { it.createdAt }.then(byName)
            SortOrder.NAME, SortOrder.TYPE -> byName
        }
        val ordered = if (descending) cmp.reversed() else cmp
        // Type keeps folders before notebooks whichever way the names run.
        return if (sort == SortOrder.TYPE) items.sortedWith(compareBy<LibraryItem> { if (it is LibraryItem.FolderItem) 0 else 1 }.then(ordered))
        else items.sortedWith(ordered)
    }
}

/** Folders from the top level down to [folderId], for the breadcrumb bar. */
fun LibraryIndex.pathTo(folderId: String?): List<Folder> {
    val byId = folders.associateBy { it.id }
    val path = ArrayList<Folder>()
    var cur = folderId?.let(byId::get)
    while (cur != null && path.size <= folders.size) {
        path.add(0, cur)
        cur = cur.parentId?.let(byId::get)
    }
    return path
}

/** A folder and its depth, for the "Move to" picker. */
data class FolderNode(val folder: Folder, val depth: Int)

/** Live folders as an indented tree, leaving out [excluded] folders and everything under them. */
fun LibraryIndex.folderTree(excluded: Set<String> = emptySet()): List<FolderNode> {
    val trashed = trashedFolderIds()
    val live = folders.filter { it.id !in trashed && it.id !in excluded }
    val children = live.groupBy { it.parentId }
    val out = ArrayList<FolderNode>()
    fun walk(parent: String?, depth: Int) {
        children[parent].orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).forEach {
            out += FolderNode(it, depth)
            walk(it.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}
