package app.folio.notes.editor.oneui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Folio's own colour sets for the palette pages (8 each). */
object FolioPalettes {
    val INK = listOf(0xFF1B1B1F, 0xFF5F6670, 0xFF1F4E9C, 0xFF2E7DD7, 0xFF1E7A4C, 0xFFB3261E, 0xFFE8701A, 0xFF6B3FA0)
    val VIVID = listOf(0xFFE8473F, 0xFFF59A23, 0xFFF5D547, 0xFF4CC26B, 0xFF24B3C8, 0xFF3A6FF0, 0xFF8A55E8, 0xFFE85AA8)
    val PASTEL = listOf(0xFFF4A7A1, 0xFFF8C99B, 0xFFF6E59A, 0xFFB8E3B0, 0xFFA8DCE6, 0xFFAFC4F5, 0xFFCDB6F0, 0xFFF3B5D5)
    val EARTH = listOf(0xFF6B4A33, 0xFF9C6B3E, 0xFFC9A46A, 0xFF7C8B4A, 0xFF4E6E5D, 0xFF3E5C76, 0xFF8E5A6B, 0xFFFFFFFF)
    val SETS = listOf(INK, VIVID, PASTEL, EARTH)
    const val RECENT_SHOWN = 7
}

/**
 * The palette under a tool's settings (teardown §4): five pages with a dot indicator whose
 * first item is a clock. Page 1 holds the recent colours, an eyedropper and the full picker;
 * pages 2–5 hold a Folio colour set plus the current colour in a rainbow ring (opens the
 * picker). [display] maps a stored colour to how it's shown (dark-mode adaptation).
 */
@Composable
fun PalettePager(
    selected: Long,
    recent: List<Long>,
    onPick: (Long) -> Unit,
    onEyedropper: (() -> Unit)?,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
    display: (Long) -> Long = { it },
) {
    val c = LocalOneUi.current
    val startPage = FolioPalettes.SETS.indexOfFirst { selected in it }.let { if (it >= 0) it + 1 else 0 }
    val pager = rememberPagerState(initialPage = startPage) { 1 + FolioPalettes.SETS.size }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth()) {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(50.dp)) { page ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page == 0) {
                    val shown = recent.take(FolioPalettes.RECENT_SHOWN)
                    shown.forEach { col -> ColorDot(col, display(col), col == selected, 24.dp) { onPick(col) } }
                    repeat(FolioPalettes.RECENT_SHOWN - shown.size) { Spacer(Modifier.size(30.dp)) }
                    if (onEyedropper != null) RoundGlyph("Pick a colour from the page", onEyedropper) { FolioIcon(FIcon.EYEDROPPER, null, size = 18.dp) }
                    else Spacer(Modifier.size(30.dp))
                    RoundGlyph("All colours", onOpenPicker) { Canvas(Modifier.size(20.dp)) { drawPaletteGlyph() } }
                } else {
                    FolioPalettes.SETS[page - 1].forEach { col -> ColorDot(col, display(col), col == selected, 24.dp) { onPick(col) } }
                    CurrentColorSlot(display(selected), onOpenPicker)
                }
            }
        }
        // Page indicator: a clock for the recent page, then dots.
        Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            repeat(pager.pageCount) { i ->
                val on = pager.currentPage == i
                Box(
                    Modifier.size(22.dp).clip(CircleShape).clickable(role = Role.Tab) { scope.launch { pager.animateScrollToPage(i) } }
                        .semantics { contentDescription = if (i == 0) "Recent colours" else "Colour page $i"; this.selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    if (i == 0) FolioIcon(FIcon.CLOCK, null, tint = if (on) c.pageDotCurrent else c.pageDot, size = 13.dp)
                    else Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) c.pageDotCurrent else c.pageDot))
                }
            }
        }
    }
}

/** A colour dot (30 dp cell); the selected one shows a check in a contrasting colour. */
@Composable
fun ColorDot(stored: Long, shown: Long, selected: Boolean, size: Dp, label: String? = null, onClick: () -> Unit) {
    val c = LocalOneUi.current
    Box(
        Modifier.size(size + 6.dp).clip(CircleShape).clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label ?: "Colour ${ColorMath.toHex(stored)}"; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            drawCircle(Color(shown))
            drawCircle(if (c.dark) Color(0x33FFFFFF) else Color(0x22000000), style = Stroke(1.dp.toPx()))
            if (selected) {
                val ck = if (ColorMath.prefersDarkContent(shown)) Color(0xFF1B1B1F) else Color.White
                val s = this.size.minDimension
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.27f, s * 0.52f); lineTo(s * 0.43f, s * 0.68f); lineTo(s * 0.74f, s * 0.36f)
                }
                drawPath(p, ck, style = Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }
}

/** The fixed slot at the end of a palette page: the current colour inside a rainbow ring. */
@Composable
fun CurrentColorSlot(shown: Long, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Current colour, open all colours" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            drawCircle(Brush.sweepGradient(RAINBOW, center), style = Stroke(2.4.dp.toPx()))
            drawCircle(Color(shown), radius = size.minDimension / 2 - 4.dp.toPx())
        }
    }
}

@Composable
private fun RoundGlyph(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalOneUi.current
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(c.pill).border(1.dp, c.popupRim, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

val RAINBOW = listOf(
    Color(0xFFFF4D4D), Color(0xFFFFA63D), Color(0xFFFFE24D), Color(0xFF5BD66B), Color(0xFF3DD6D0),
    Color(0xFF4D8BFF), Color(0xFFA35BFF), Color(0xFFFF5BC8), Color(0xFFFF4D4D),
)

/** Offsets helper for canvases. */
internal fun off(x: Float, y: Float) = Offset(x, y)

/** Unused width spacer used by rows of fixed cells. */
@Composable
internal fun Gap(w: Dp) = Spacer(Modifier.width(w))
