package app.folio.notes.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.folio.notes.ui.LocalGlass
import app.folio.notes.ui.glass
import kotlin.math.roundToInt

/** Width of the page sidebar (Samsung Notes' page sorter is 212dp; this leaves more room to write). */
internal val SIDEBAR_WIDTH = 176.dp
private val THUMB_WIDTH = 120.dp
private val ITEM_GAP = 10.dp
private const val ADD_KEY = "__add_page__"

/** What the page menu hands back to the screen, which owns the dialogs. */
internal class PageMenuActions(
    val onMove: (pageId: String) -> Unit,
    val onDelete: (pageId: String) -> Unit,
    val onExport: (pageId: String) -> Unit,
)

/**
 * The page navigator: a scrolling column of live page thumbnails beside the canvas.
 *
 * - Tap a thumbnail to open the page; the open page is outlined and kept in view.
 * - ⋮ at the thumbnail's bottom-end corner opens the page menu (docs/research/page-navigator-spec.md).
 * - Long-press (finger or S Pen) and drag to reorder; the list scrolls at its edges.
 * - The last tile adds a page at the end.
 *
 * Thumbnails come from [EditorViewModel.thumbnail]: drawn off the main thread, cached,
 * and re-drawn only when that page's content version changes.
 */
@Composable
internal fun PageSidebar(
    vm: EditorViewModel,
    pageIds: List<String>,
    current: Int,
    /** Height / width of the notebook's pages, used until a page's own thumbnail is known. */
    defaultAspect: Float,
    actions: PageMenuActions,
    modifier: Modifier = Modifier,
) {
    val g = LocalGlass.current
    val versions by vm.thumbVersions.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceAtLeast(0))
    val drag = remember { DragState() }
    val density = LocalDensity.current
    val gapPx = with(density) { ITEM_GAP.toPx() }
    val haptics = LocalHapticFeedback.current

    // Keep the open page in view (Previous/Next, a new or deleted page, a page opened from here).
    LaunchedEffect(current, pageIds.size) {
        if (current !in pageIds.indices || drag.pageId != null) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == current }
        val fullyVisible = item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(current, scrollOffset = -info.viewportSize.height / 4)
    }

    Column(modifier.glass(RoundedCornerShape(24.dp), elevation = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pages", color = g.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${current + 1} / ${pageIds.size}", style = MaterialTheme.typography.labelMedium, color = g.inkSoft)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val heightPx = constraints.maxHeight.toFloat()
            // Drag to reorder. The gesture belongs to the list, so it survives the dragged item
            // scrolling out of view; scrolling by hand is off while dragging.
            LazyColumn(
                state = listState,
                userScrollEnabled = drag.pageId == null,
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(ITEM_GAP),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().pointerInput(pageIds) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                it.key != ADD_KEY && at.y >= it.offset && at.y < it.offset + it.size
                            } ?: return@detectDragGesturesAfterLongPress
                            drag.pageId = item.key as String
                            drag.from = item.index
                            drag.grabDy = at.y - item.offset
                            drag.y = at.y
                            drag.target = targetSlot(listState, at.y)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, amount ->
                            if (drag.pageId == null) return@detectDragGesturesAfterLongPress
                            change.consume()
                            drag.y += amount.y
                            drag.target = targetSlot(listState, drag.y)
                        },
                        onDragEnd = {
                            val id = drag.pageId
                            if (id != null) {
                                // A slot below the page's own place shifts up by one once it's removed.
                                val to = if (drag.target > drag.from) drag.target - 1 else drag.target
                                if (to != drag.from) vm.movePage(id, to)
                            }
                            drag.reset()
                        },
                        onDragCancel = { drag.reset() },
                    )
                },
            ) {
                itemsIndexed(pageIds, key = { _, id -> id }) { index, id ->
                    PageThumbItem(
                        vm = vm, pageId = id, number = index + 1, pageCount = pageIds.size,
                        selected = index == current, version = versions[id] ?: 0L, aspect = defaultAspect,
                        lifted = drag.pageId == id, actions = actions,
                        onOpen = { if (index != current) vm.openPage(index) },
                        modifier = Modifier.animateItem(),
                    )
                }
                item(key = ADD_KEY) {
                    AddPageTile(onClick = { pageIds.lastOrNull()?.let { vm.addPage(it, before = false) } }, modifier = Modifier.animateItem())
                }
            }

            val draggedId = drag.pageId
            if (draggedId != null) {
                // Auto-scroll near the edges while dragging, then re-aim at the slot under the pen.
                LaunchedEffect(draggedId) {
                    val edge = with(density) { 56.dp.toPx() }
                    val maxStep = with(density) { 14.dp.toPx() }
                    while (true) {
                        withFrameNanos { }
                        val step = when {
                            drag.y < edge -> -maxStep * (1f - drag.y / edge).coerceIn(0.2f, 1f)
                            drag.y > heightPx - edge -> maxStep * (1f - (heightPx - drag.y) / edge).coerceIn(0.2f, 1f)
                            else -> 0f
                        }
                        if (step != 0f) {
                            listState.scrollBy(step)
                            drag.target = targetSlot(listState, drag.y)
                        }
                    }
                }
                // Where the page will land.
                val lineY = slotLineY(listState, drag.target, pageIds.size, gapPx)
                if (lineY != null) Box(
                    Modifier.offset { IntOffset(0, lineY.roundToInt() - with(density) { 2.dp.roundToPx() }) }
                        .padding(horizontal = 20.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(g.accent),
                )
                // The page being moved follows the pen.
                vm.thumbnails.peekAny(draggedId)?.let { t ->
                    val image = remember(t) { t.bitmap.asImageBitmap() }
                    Image(
                        image, contentDescription = null, contentScale = ContentScale.FillBounds,
                        modifier = Modifier.align(Alignment.TopCenter)
                            .offset { IntOffset(0, (drag.y - drag.grabDy).roundToInt()) }
                            .width(THUMB_WIDTH).aspectRatio(t.pageWidth / t.pageHeight)
                            .graphicsLayer { scaleX = 1.06f; scaleY = 1.06f; alpha = 0.92f }
                            .shadow(12.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)),
                    )
                }
            }
        }
    }
}

private class DragState {
    var pageId by mutableStateOf<String?>(null)
    var from = -1
    /** Pointer position in the list, and where on the item it was grabbed (pixels). */
    var y by mutableFloatStateOf(0f)
    var grabDy = 0f
    /** Insertion slot 0..pageCount: the page goes before the page now at this index. */
    var target by mutableIntStateOf(-1)

    fun reset() { pageId = null; from = -1; target = -1 }
}

/** The insertion slot under list position [y]: before the first page whose middle is below it. */
private fun targetSlot(list: LazyListState, y: Float): Int {
    val pages = list.layoutInfo.visibleItemsInfo.filter { it.key != ADD_KEY }
    for (p in pages) if (y < p.offset + p.size / 2f) return p.index
    return (pages.lastOrNull()?.index ?: -1) + 1
}

/** Y of the gap above slot [slot] (or below the last page), or null when it's off screen. */
private fun slotLineY(list: LazyListState, slot: Int, count: Int, gapPx: Float): Float? {
    val items = list.layoutInfo.visibleItemsInfo
    items.firstOrNull { it.index == slot && it.key != ADD_KEY }?.let { return it.offset - gapPx / 2f }
    if (slot >= count) items.firstOrNull { it.index == count - 1 }?.let { return it.offset + it.size + gapPx / 2f }
    return null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageThumbItem(
    vm: EditorViewModel, pageId: String, number: Int, pageCount: Int, selected: Boolean, version: Long,
    aspect: Float, lifted: Boolean, actions: PageMenuActions, onOpen: () -> Unit, modifier: Modifier = Modifier,
) {
    val g = LocalGlass.current
    val density = LocalDensity.current
    val widthPx = with(density) { THUMB_WIDTH.roundToPx() }
    // The last thumbnail stays up while a fresh one renders, so edits never flash a blank page.
    var thumb by remember(pageId) { mutableStateOf(vm.thumbnails.peekAny(pageId)) }
    LaunchedEffect(pageId, version, widthPx) { vm.thumbnail(pageId, version, widthPx)?.let { thumb = it } }
    val image = remember(thumb) { thumb?.bitmap?.asImageBitmap() }
    val ratio = thumb?.let { it.pageWidth / it.pageHeight } ?: (1f / aspect)

    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }

    Column(modifier.alpha(if (lifted) 0.35f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(THUMB_WIDTH).aspectRatio(ratio)
                .shadow(if (selected || hovered) 8.dp else 3.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .border(
                    if (selected) 3.dp else 1.dp,
                    when { selected -> g.accent; hovered -> g.accent.copy(alpha = 0.5f); else -> g.inkSoft.copy(alpha = 0.25f) },
                    RoundedCornerShape(6.dp),
                )
                .hoverable(hover)
                .pointerHoverIcon(PointerIcon.Hand)
                // No long-click here: the list's long-press drag would be cancelled by it.
                .clickable(onClickLabel = "Open page", onClick = onOpen)
                .semantics {
                    contentDescription = "Page $number"
                    this.selected = selected
                    if (selected) stateDescription = "Open page"
                },
        ) {
            if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            // ⋮ at the bottom-end corner of the thumbnail (as in Samsung Notes' page sorter).
            Box(Modifier.align(Alignment.BottomEnd)) {
                Box(
                    Modifier.padding(2.dp).size(36.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.85f))
                        .combinedClickable(onClick = { menu = true })
                        .semantics { contentDescription = "More options for page $number" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = Color(0xFF3A4150), modifier = Modifier.size(20.dp)) }
                PageMenu(
                    expanded = menu, onDismiss = { menu = false }, vm = vm, pageId = pageId, pageCount = pageCount,
                    actions = actions,
                )
            }
        }
        Box(
            Modifier.padding(top = 4.dp).clip(CircleShape)
                .background(if (selected) g.accent else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 1.dp),
        ) {
            Text(
                "$number", style = MaterialTheme.typography.labelMedium,
                color = if (selected) Color.White else g.inkSoft, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/**
 * The per-page menu. Order follows Samsung Notes' page menu (add, copy/cut, delete, erase,
 * share/save), with Goodnotes' before/after split; every item works (spec §3).
 */
@Composable
private fun PageMenu(
    expanded: Boolean, onDismiss: () -> Unit, vm: EditorViewModel, pageId: String, pageCount: Int, actions: PageMenuActions,
) {
    // Read when the menu opens: only offer Erase for a page with something on it.
    val hasContent = remember(expanded) { if (expanded) vm.pageHasContent(pageId) else null }
    DropdownMenu(expanded, onDismissRequest = onDismiss) {
        MenuRow("Add page before", Icons.Outlined.VerticalAlignTop) { onDismiss(); vm.addPage(pageId, before = true) }
        MenuRow("Add page after", Icons.Outlined.VerticalAlignBottom) { onDismiss(); vm.addPage(pageId, before = false) }
        MenuRow("Duplicate page", Icons.Outlined.ContentCopy) { onDismiss(); vm.duplicatePage(pageId) }
        MenuRow("Move page…", Icons.Outlined.SwapVert, enabled = pageCount > 1) { onDismiss(); actions.onMove(pageId) }
        HorizontalDivider()
        MenuRow(
            "Delete page", Icons.Outlined.Delete, enabled = pageCount > 1,
            supporting = if (pageCount > 1) null else "A notebook needs at least one page",
        ) { onDismiss(); actions.onDelete(pageId) }
        if (hasContent == true) MenuRow("Erase page", Icons.Outlined.CleaningServices) { onDismiss(); vm.erasePage(pageId) }
        HorizontalDivider()
        MenuRow("Export page…", Icons.Outlined.IosShare) { onDismiss(); actions.onExport(pageId) }
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector, enabled: Boolean = true, supporting: String? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(label)
                if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = { Icon(icon, contentDescription = null) },
        enabled = enabled,
        onClick = onClick,
    )
}

/** The last tile: adds a page at the end (Samsung's page sorter ends with the same "+" tile). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddPageTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    Box(
        modifier.width(THUMB_WIDTH).height(56.dp).clip(RoundedCornerShape(6.dp))
            .border(1.5.dp, g.inkSoft.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .background(g.fill)
            .pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(onClick = onClick)
            .semantics { contentDescription = "Add page at the end" },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Add, contentDescription = null, tint = g.inkSoft, modifier = Modifier.size(32.dp)) }
}
