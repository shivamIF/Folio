package app.folio.notes.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.folio.core.model.Cover
import app.folio.core.model.CoverStyle
import app.folio.core.model.FolderIcon
import app.folio.core.model.NotebookMeta
import app.folio.notes.FolioApp
import app.folio.notes.ui.LocalLibraryColors
import app.folio.notes.ui.vector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Original folder artwork: a darker back panel with a tab, a sheet of paper
 * peeking out, and a front panel in the folder colour carrying its icon.
 */
@Composable
fun FolderArt(color: Long, icon: FolderIcon, modifier: Modifier = Modifier, iconSize: Dp = 40.dp) {
    val base = Color(color)
    val backColor = lerp(base, Color.Black, 0.22f)
    val frontTop = lerp(base, Color.White, 0.14f)
    val glyph = lerp(base, Color.Black, 0.5f).copy(alpha = 0.85f)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = w * 0.07f
            val tabW = w * 0.42f
            val tabH = h * 0.13f
            val back = Path().apply {
                addRoundRect(RoundRect(0f, tabH * 0.7f, w, h, CornerRadius(r)))
                addRoundRect(RoundRect(0f, 0f, tabW, tabH * 1.8f, CornerRadius(r * 0.8f)))
            }
            drawPath(back, backColor)
            drawRoundRect(
                Color.White.copy(alpha = 0.94f), topLeft = Offset(w * 0.08f, tabH * 1.35f),
                size = Size(w * 0.84f, h * 0.5f), cornerRadius = CornerRadius(r * 0.5f),
            )
            val frontY = h * 0.3f
            drawRoundRect(
                Brush.verticalGradient(listOf(frontTop, base), startY = frontY, endY = h),
                topLeft = Offset(0f, frontY), size = Size(w, h - frontY), cornerRadius = CornerRadius(r),
            )
            drawLine(Color.White.copy(alpha = 0.3f), Offset(r, frontY + 1.5f), Offset(w - r, frontY + 1.5f), strokeWidth = 2f)
        }
        icon.vector?.let {
            // Centred on the front panel, which spans the lower 70 % of the art.
            Icon(it, contentDescription = null, tint = glyph, modifier = Modifier.align(BiasAlignment(0f, 0.34f)).size(iconSize))
        }
    }
}

/** Original cover artwork: a coloured board with a spine and an optional decoration. */
@Composable
fun CoverArt(cover: Cover, title: String, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val base = Color(cover.color)
    Box(modifier.clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp)).background(base)) {
        Box(Modifier.fillMaxHeight().width(if (showTitle) 12.dp else 5.dp).background(Color.Black.copy(alpha = 0.18f)))
        when (cover.style) {
            CoverStyle.PLAIN -> Unit
            CoverStyle.STRIPE -> Box(
                Modifier.fillMaxWidth().fillMaxHeight(0.12f).align(BiasAlignment(0f, 0.7f)).background(Color.White.copy(alpha = 0.35f)),
            )
            CoverStyle.CORNER -> Box(
                Modifier.fillMaxWidth(0.4f).fillMaxHeight(0.3f).align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(bottomStart = 56.dp)).background(Color.White.copy(alpha = 0.3f)),
            )
        }
        if (showTitle) Box(
            Modifier.align(Alignment.Center).padding(horizontal = 20.dp).clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 6.dp),
        ) { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, color = Color(0xFF293241)) }
    }
}

/** First page of a notebook, rendered in the background; the cover shows until it's ready. */
@Composable
fun PagePreview(nb: NotebookMeta, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val thumbs = (LocalContext.current.applicationContext as FolioApp).thumbnails
    val bmp by produceState(thumbs.cached(nb), nb.id, nb.modifiedAt) {
        value = withContext(Dispatchers.IO) { thumbs.load(nb) }
    }
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .semantics { contentDescription = "First page of ${nb.title}" },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else CoverArt(nb.cover, nb.title, Modifier.fillMaxSize(), showTitle)
    }
}

/** A notebook's face in the library: its first page or its cover, as Settings asks. */
@Composable
fun NotebookFace(nb: NotebookMeta, preview: Boolean, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    if (preview) PagePreview(nb, modifier, showTitle) else CoverArt(nb.cover, nb.title, modifier, showTitle)
}

/** Star in the corner of a tile: tap to add to or remove from Favorites. */
@Composable
fun StarToggle(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val star = LocalLibraryColors.current.star
    Box(
        modifier.size(size + 18.dp).clip(CircleShape).clickable(onClick = onToggle)
            .semantics { contentDescription = if (on) "Remove from favorites" else "Add to favorites"; role = Role.Checkbox },
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Icons.Filled.Star, contentDescription = null, tint = star, modifier = Modifier.size(size))
        else {
            Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(size))
            Icon(Icons.Outlined.StarOutline, contentDescription = null, tint = Color(0xFF8E8E93), modifier = Modifier.size(size))
        }
    }
}

/** Round check shown on every item while multi-select is on. */
@Composable
fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier.size(26.dp).shadow(2.dp, CircleShape).clip(CircleShape)
            .background(if (selected) primary else Color.White.copy(alpha = 0.85f))
            .border(1.5.dp, if (selected) primary else Color(0xFF8E8E93), CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
}

/** "16 Aug 2026 at 6:50 PM", in the device's locale. */
fun formatDateTime(ms: Long): String {
    val d = Date(ms)
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(d) + " at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(d)
}

fun folderContents(notebooks: Int, folders: Int): String {
    val parts = buildList {
        if (notebooks > 0 || folders == 0) add(if (notebooks == 1) "1 notebook" else "$notebooks notebooks")
        if (folders > 0) add(if (folders == 1) "1 folder" else "$folders folders")
    }
    return parts.joinToString(" · ")
}

/** Small art for list rows and pickers. */
@Composable
fun MiniArt(item: LibraryItem, preview: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(52.dp), contentAlignment = Alignment.Center) {
        when (item) {
            is LibraryItem.FolderItem -> FolderArt(item.folder.color, item.folder.icon, Modifier.width(52.dp).height(42.dp), iconSize = 18.dp)
            is LibraryItem.NotebookItem -> NotebookFace(item.meta, preview, Modifier.height(52.dp).width(39.dp), showTitle = false)
        }
    }
}
