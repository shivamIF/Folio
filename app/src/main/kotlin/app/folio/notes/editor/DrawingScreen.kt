package app.folio.notes.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.folio.core.ink.SelectionTransform
import app.folio.core.model.PaperTemplate
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import app.folio.notes.editor.oneui.FIcon
import app.folio.notes.editor.oneui.IconAction
import app.folio.notes.editor.oneui.LocalOneUi
import app.folio.notes.editor.oneui.OneUiType
import app.folio.notes.editor.oneui.PalettePager
import app.folio.notes.editor.oneui.PenArt
import app.folio.notes.editor.oneui.PenStand
import app.folio.notes.editor.oneui.StepSlider
import java.io.File

/**
 * Insert > Drawing: a full-screen sketch canvas with Folio's brushes. Done turns the drawing
 * into a picture (cropped to what was drawn, transparent around it) placed on the page;
 * Cancel drops it. Nothing is saved to the notebook until Done.
 */
@Composable
fun DrawingScreen(onDone: (File?) -> Unit) {
    val c = LocalOneUi.current
    val context = LocalContext.current
    val strokes = remember { mutableStateListOf<Stroke>() }
    val redo = remember { mutableStateListOf<Stroke>() }
    var tool by remember { mutableStateOf(ToolType.BRUSH) }
    var color by remember { mutableStateOf(0xFF2E7DD7L) }
    var step by remember { mutableStateOf(SizeScale.stepOf(ToolType.BRUSH, ToolState.DEFAULT_WIDTHS.getValue(ToolType.BRUSH))) }
    var erasing by remember { mutableStateOf(false) }
    val brushes = listOf(ToolType.BRUSH, ToolType.PENCIL, ToolType.AIRBRUSH, ToolType.MARKER, ToolType.FOUNTAIN, ToolType.FINELINER, ToolType.HIGHLIGHTER)
    Dialog({ onDone(null) }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.appBar).testTag("drawing-screen")) {
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(56.dp).clickable { onDone(null) }.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text("Cancel", style = OneUiType.bodyStrong, color = c.text)
                }
                Text("Drawing", style = OneUiType.appTitle, color = c.text, modifier = Modifier.weight(1f))
                IconAction(FIcon.UNDO, "Undo", { strokes.removeLastOrNull()?.let { redo += it } }, enabled = strokes.isNotEmpty())
                IconAction(FIcon.REDO, "Redo", { redo.removeLastOrNull()?.let { strokes += it } }, enabled = redo.isNotEmpty())
                IconAction(FIcon.ERASER, "Eraser", { erasing = !erasing }, selected = erasing)
                Box(
                    Modifier.height(56.dp).clickable { onDone(if (strokes.isEmpty()) null else renderDrawing(context, strokes)) }
                        .padding(horizontal = 20.dp).semantics { contentDescription = "Done drawing" },
                    contentAlignment = Alignment.Center,
                ) { Text("Done", style = OneUiType.bodyStrong, color = c.accent) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp))) {
                AndroidView(
                    factory = { ctx ->
                        InkCanvasView(ctx).apply {
                            penOnlyPreference = false
                            showPageNumbers = false
                            listener = object : InkCanvasView.Listener {
                                override fun onStrokeFinished(pageId: String, stroke: Stroke) { strokes += stroke; redo.clear() }
                                override fun onStrokesErased(pageId: String, ids: Set<String>) { strokes.removeAll { it.id in ids } }
                                override fun onLassoSelected(pageId: String, ids: Set<String>) = Unit
                                override fun onSelectionTransformed(pageId: String, ids: Set<String>, transform: SelectionTransform) = Unit
                                override fun onStrokesSplit(pageId: String, pieces: Map<String, List<Stroke>>) {
                                    val out = ArrayList<Stroke>()
                                    for (s in strokes) { val p = pieces[s.id]; if (p == null) out += s else out.addAll(p) }
                                    strokes.clear(); strokes.addAll(out)
                                }
                            }
                        }
                    },
                    update = { v ->
                        v.tool = tool; v.color = color; v.penWidth = SizeScale.widthOf(tool, step)
                        v.eraserSelected = erasing; v.eraserPartial = true; v.eraserSizeDp = 18f
                        v.setPage("drawing", strokes.toList(), PaperTemplate.BLANK, DRAWING_SIZE, DRAWING_SIZE)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.fillMaxWidth().background(c.toolbar).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.Bottom) {
                    brushes.forEach { t ->
                        PenStand(PenArt.of(t), Color(color), t == tool && !erasing, toolLabel(t), { tool = t; erasing = false; step = SizeScale.stepOf(t, ToolState.DEFAULT_WIDTHS.getValue(t)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepSlider(step, 1..SizeScale.stepsOf(tool), { step = it }, "Brush size", Modifier.width(320.dp))
                    PalettePager(color, emptyList(), { color = it; erasing = false }, null, {}, Modifier.weight(1f))
                }
            }
        }
    }
}

/** The drawing as a PNG, cropped to its ink with a margin; transparent around the strokes. */
private fun renderDrawing(context: android.content.Context, strokes: List<Stroke>): File? {
    val bounds = strokes.map { it.bounds() }.reduceOrNull { a, b -> a.union(b) } ?: return null
    val pad = 12f
    val l = (bounds.left - pad).coerceAtLeast(0f); val t = (bounds.top - pad).coerceAtLeast(0f)
    val r = (bounds.right + pad).coerceAtMost(DRAWING_SIZE); val b = (bounds.bottom + pad).coerceAtMost(DRAWING_SIZE)
    if (r <= l || b <= t) return null
    val scale = 2f
    val bmp = Bitmap.createBitmap(((r - l) * scale).toInt().coerceAtLeast(1), ((b - t) * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    cv.scale(scale, scale); cv.translate(-l, -t)
    val renderer = PageRenderer()
    strokes.forEach { renderer.drawStroke(cv, it) }
    val dir = File(context.cacheDir, "drawings").apply { mkdirs() }
    val f = File(dir, "drawing-${System.currentTimeMillis()}.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return f
}

private const val DRAWING_SIZE = 1000f
