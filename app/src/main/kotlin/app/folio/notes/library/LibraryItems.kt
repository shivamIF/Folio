package app.folio.notes.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.folio.core.model.NotebookMeta
import app.folio.notes.ui.glass.GlassDropdownMenu
import app.folio.notes.ui.glass.glassMenuItemColors

/** One entry in an item's context menu. */
@Immutable
data class MenuAction(val label: String, val icon: ImageVector, val destructive: Boolean = false, val dividerBefore: Boolean = false, val run: () -> Unit)

/** What the screen does with an item; built once per state so tiles stay cheap to recompose. */
@Immutable
class ItemActions(
    val open: (LibraryItem) -> Unit,
    val menu: (LibraryItem) -> List<MenuAction>,
    val toggleFavorite: (LibraryItem) -> Unit,
    val toggleSelected: (LibraryItem) -> Unit,
    /** Whether a notebook shows its first page instead of its cover (Settings › Library shows). */
    val showsPreview: (NotebookMeta) -> Boolean,
)

/** Height of the art area, so folders and notebooks line up in a row. */
private val ArtHeight = 196.dp

private val LibraryItem.deletedAt: Long? get() = when (this) {
    is LibraryItem.FolderItem -> folder.deletedAt
    is LibraryItem.NotebookItem -> meta.deletedAt
}

private fun LibraryItem.description(): String {
    val kind = if (this is LibraryItem.FolderItem) "folder" else "notebook"
    return if (deletedAt != null) "Trashed $kind $title" else kind.replaceFirstChar(Char::uppercase) + " $title"
}

/** The date line: last change, or for Trash how long until it's deleted for good. */
private fun LibraryItem.dateLine(): String = deletedAt?.let { d ->
    val left = (LibraryViewModel.TRASH_DAYS - ((System.currentTimeMillis() - d) / 86_400_000L).toInt()).coerceAtLeast(0)
    "Deleted in $left day" + if (left == 1) "" else "s"
} ?: formatDateTime(modifiedAt)

private fun LibraryItem.subtitle(): String = when (this) {
    is LibraryItem.FolderItem -> folderContents(notebookCount, folderCount)
    is LibraryItem.NotebookItem -> meta.pageIds.size.let { if (it == 1) "1 page" else "$it pages" }
}

@Composable
fun ItemMenu(expanded: Boolean, actions: List<MenuAction>, onDismiss: () -> Unit) {
    GlassDropdownMenu(expanded, onDismissRequest = onDismiss) {
        actions.forEach { a ->
            if (a.dividerBefore) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val tint = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            DropdownMenuItem(
                text = { Text(a.label, color = tint) },
                leadingIcon = { Icon(a.icon, contentDescription = null, tint = tint) },
                onClick = { onDismiss(); a.run() },
                colors = glassMenuItemColors(),
            )
        }
    }
}

/**
 * Grid tile: art, then title with a menu chevron, then the date.
 * Tap opens (or toggles selection); long-press or the chevron opens the menu.
 */
@Composable
fun ItemTile(item: LibraryItem, selected: Boolean?, showStar: Boolean, actions: ItemActions, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val selecting = selected != null
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = { if (selecting) actions.toggleSelected(item) else actions.open(item) },
                onLongClick = { if (selecting) actions.toggleSelected(item) else menu = true },
            )
            .semantics { contentDescription = item.description(); if (selected != null) this.selected = selected }
            .testTag(if (item is LibraryItem.NotebookItem) "notebook-${item.title}" else "folder-${item.title}")
            .padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(ArtHeight), contentAlignment = Alignment.BottomCenter) {
            val outline = if (selected == true) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)).padding(5.dp) else Modifier
            val artBox = when (item) {
                is LibraryItem.FolderItem -> Modifier.fillMaxWidth(0.94f).aspectRatio(1.24f)
                is LibraryItem.NotebookItem -> Modifier.fillMaxHeight().aspectRatio(0.75f)
            }
            Box(artBox.then(outline)) {
                when (item) {
                    is LibraryItem.FolderItem -> FolderArt(item.folder.color, item.folder.icon, Modifier.fillMaxSize())
                    is LibraryItem.NotebookItem -> NotebookFace(item.meta, actions.showsPreview(item.meta), Modifier.fillMaxSize())
                }
                if (showStar && !selecting) StarToggle(
                    item.favorite, { actions.toggleFavorite(item) },
                    // On a folder, sit on the front panel rather than the paper sheet.
                    if (item is LibraryItem.FolderItem) Modifier.align(BiasAlignment(1f, -0.2f)) else Modifier.align(Alignment.TopEnd),
                )
                if (selected != null) SelectionCheck(selected, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
        }
        // The menu hangs from the title, like a popover from its label.
        Box(Modifier.padding(top = 10.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !selecting) { menu = true }.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false),
                )
                if (!selecting) Icon(
                    Icons.Filled.ExpandMore, contentDescription = "Options for ${item.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                )
            }
            ItemMenu(menu, if (menu) actions.menu(item) else emptyList()) { menu = false }
        }
        Text(
            item.dateLine(), maxLines = 1, style = MaterialTheme.typography.bodySmall,
            color = if (item.deletedAt != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** List row: small art, title, date and size, star and a menu button. */
@Composable
fun ItemRow(item: LibraryItem, selected: Boolean?, showStar: Boolean, actions: ItemActions, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val selecting = selected != null
    Row(
        modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected == true) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = { if (selecting) actions.toggleSelected(item) else actions.open(item) },
                onLongClick = { if (selecting) actions.toggleSelected(item) else menu = true },
            )
            .semantics { contentDescription = item.description(); if (selected != null) this.selected = selected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (selected != null) SelectionCheck(selected)
        MiniArt(item, item is LibraryItem.NotebookItem && actions.showsPreview(item.meta))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                item.dateLine() + "  ·  " + item.subtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!selecting) {
            if (showStar) StarToggle(item.favorite, { actions.toggleFavorite(item) })
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreHoriz, contentDescription = "Options for ${item.title}") }
                ItemMenu(menu, if (menu) actions.menu(item) else emptyList()) { menu = false }
            }
        }
    }
}

/** Centred message for an empty folder, section or search. */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
        if (action != null) { Spacer(Modifier.height(6.dp)); action() }
    }
}
