package app.folio.notes.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.folio.core.ink.PressureCurve
import app.folio.core.ink.StrokeBuilder
import app.folio.core.model.Rect
import app.folio.core.model.Stroke
import app.folio.core.model.StrokeLook
import app.folio.core.model.ToolType
import app.folio.notes.editor.oneui.FIcon
import app.folio.notes.editor.oneui.FolioIcon
import app.folio.notes.editor.oneui.IconAction
import app.folio.notes.editor.oneui.LocalOneUi

/**
 * The easy writing pad (teardown §14): a pad docked at the bottom shows the window marked on
 * the page, magnified; what you write in the pad lands, scaled down, inside the window. The bar
 * has a drag handle (pad height), Previous, Next, Enter and Close; writing past the end
 * marker moves the window on.
 */
@Composable
fun EasyWritingPad(vm: EditorViewModel, state: EditorUiState, canvas: InkCanvasView?, modifier: Modifier = Modifier) {
    if (!state.easyPad || state.reading) return
    val c = LocalOneUi.current
    val density = LocalDensity.current
    var padHeight by remember { mutableFloatStateOf(with(density) { 260.dp.toPx() }) }
    var aspect by remember { mutableStateOf(0f) }
    val win = state.padWindow
    LaunchedEffect(aspect, win == null) {
        if (aspect > 0f && win == null) canvas?.visiblePageArea()?.let { (pid, area) -> vm.placePad(pid, area, aspect) }
    }
    if (win != null && aspect > 0f) LaunchedEffect(aspect) { vm.reshapePad(aspect) }
    Column(modifier.fillMaxWidth().background(c.appBar).testTag("easy-pad")) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).semantics { contentDescription = "Drag to resize the pad" }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { ch, dy ->
                            ch.consume()
                            padHeight = (padHeight - dy).coerceIn(140.dp.toPx(), 520.dp.toPx())
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { FolioIcon(FIcon.DRAG_HANDLE, null, size = 22.dp) }
            Spacer(Modifier.weight(1f))
            IconAction(FIcon.CHEVRON_LEFT, "Previous", { vm.movePad(PadStep.PREVIOUS) })
            IconAction(FIcon.CHEVRON_RIGHT, "Next", { vm.movePad(PadStep.NEXT) })
            IconAction(FIcon.ENTER, "Enter", { vm.movePad(PadStep.ENTER) })
            IconAction(FIcon.CLOSE, "Close easy writing pad", vm::toggleEasyPad)
        }
        Box(Modifier.fillMaxWidth().height(with(density) { padHeight.toDp() }).onSizeChanged { aspect = it.width / it.height.toFloat() }) {
            val page = win?.let { state.pages[it.pageId] }
            val tools = state.tools
            val dark = c.dark && (state.meta?.darkAdjust ?: true)
            AndroidView(
                factory = { ctx -> EasyPadView(ctx) },
                update = { v ->
                    v.window = win; v.page = page
                    v.look = PageLook(dark = dark, pageColor = state.meta?.pageColor, density = state.meta?.density ?: app.folio.core.model.PaperDensity.NORMAL, desk = c.desk.toArgb())
                    v.tool = tools.tool; v.color = tools.strokeColor; v.penWidth = tools.width; v.stabilization = tools.stabilization
                    v.strokeLook = if (tools.active == EditorTool.HIGHLIGHTER && tools.highlighterRound) StrokeLook(roundTip = true)
                    else if (tools.active == EditorTool.TAPE) StrokeLook(pattern = tools.tapePattern) else StrokeLook.DEFAULT
                    v.pressureCurve = PressureCurve.fromSensitivity(tools.pressureSensitivity)
                    v.fingers = !tools.penOnly
                    v.onStroke = { pid, s -> vm.onPadStroke(pid, s) }
                    v.invalidate()
                },
                modifier = Modifier.fillMaxWidth().height(with(density) { padHeight.toDp() }).clipToBounds(),
            )
        }
    }
}

enum class PadStep { PREVIOUS, NEXT, ENTER }

/** The pad's writing surface: the window's part of the page, magnified, and the live stroke. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class EasyPadView(context: Context) : View(context) {
    var window: PadWindow? = null
    var page: LoadedPage? = null
    var look: PageLook = PageLook.LIGHT
    var tool: ToolType = ToolType.FOUNTAIN
    var color: Long = 0xFF1B1B1F
    var penWidth: Float = 3f
    var stabilization: Float = 0.3f
    var strokeLook: StrokeLook = StrokeLook.DEFAULT
    var pressureCurve = PressureCurve()
    var fingers = false
    var onStroke: (String, Stroke) -> Unit = { _, _ -> }

    private val renderer = PageRenderer()
    private val m = Matrix()
    private val inv = Matrix()
    private var builder: StrokeBuilder? = null
    private val pt = FloatArray(2)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3E91FF.toInt(); strokeWidth = 2f * resources.displayMetrics.density }
    private val shadePaint = Paint().apply { color = 0x33000000 }

    init { contentDescription = "Easy writing pad" }

    /** Page units → pad pixels: the window fills the pad's width. */
    private fun syncMatrix(w: PadWindow): Float {
        val s = width.toFloat() / w.rect.width
        m.setScale(s, s); m.postTranslate(-w.rect.left * s, -w.rect.top * s)
        m.invert(inv)
        return s
    }

    override fun onDraw(canvas: Canvas) {
        // Compose doesn't clip an embedded view: keep every pixel inside the pad.
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(look.desk)
        val w = window ?: return
        val p = page ?: return
        val s = syncMatrix(w)
        canvas.save()
        canvas.concat(m)
        canvas.clipRect(w.rect.left, w.rect.top, w.rect.right, w.rect.top + height / s)
        look.applyTo(renderer, p.pdf != null)
        renderer.drawPaper(canvas, p.paper, p.width, p.height, hasPdf = p.pdf != null)
        p.background?.let { renderer.drawPdfBackground(canvas, it, p.width, p.height) }
        val area = Rect(w.rect.left, w.rect.top, w.rect.right, w.rect.top + height / s)
        val idx = p.index
        val strokes = if (idx != null && idx.strokes === p.strokes) idx.query(area).map { p.strokes[it] } else p.strokes.filter { it.bounds().intersects(area) }
        for (t in p.objects.tables) if (t.bounds.intersects(area)) renderer.drawTable(canvas, t)
        for (st in strokes) if (st.tool != ToolType.TAPE) renderer.drawStroke(canvas, StrokeArt.of(st))
        for (t in p.texts) if (t.bounds.intersects(area)) renderer.drawText(canvas, t)
        for (a in p.objects.audio) if (a.bounds.intersects(area)) renderer.drawAudio(canvas, a)
        for (st in strokes) if (st.tool == ToolType.TAPE) renderer.drawStroke(canvas, StrokeArt.of(st))
        for (n in p.objects.stickies) if (n.bounds.inflate(n.width * 0.05f).intersects(area)) renderer.drawSticky(canvas, n)
        builder?.let { b ->
            val live = b.build()
            renderer.drawStroke(canvas, StrokeArt.live(tool, color, penWidth, live.x, live.y, live.pressure, live.tilt, live.orientation, live.size, strokeLook))
        }
        canvas.restore()
        // End marker: a blue line with a drop at the top; past it the pad is shaded.
        val x = width * PadMoves.END_MARKER
        canvas.drawRect(x, 0f, width.toFloat(), height.toFloat(), shadePaint)
        canvas.drawLine(x, 0f, x, height.toFloat(), markerPaint)
        val d = resources.displayMetrics.density
        val drop = Path().apply { moveTo(x - 7 * d, 0f); lineTo(x + 7 * d, 0f); lineTo(x + 7 * d, 10 * d); quadTo(x, 18 * d, x - 7 * d, 10 * d); close() }
        canvas.drawPath(drop, markerPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = window ?: return false
        val stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        if (!stylus && !fingers) return false
        val s = syncMatrix(w)
        fun page(x: Float, y: Float): FloatArray { pt[0] = x; pt[1] = y; inv.mapPoints(pt); return pt }
        fun effort(raw: Float) = if (stylus) pressureCurve.map(raw) else 0.6f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e)
                parent?.requestDisallowInterceptTouchEvent(true)
                builder = StrokeBuilder(tool, color, penWidth, stabilization, minDistance = StrokeBuilder.minDistanceAt(s), startedAt = System.currentTimeMillis(), pxPerUnit = s)
                val p = page(e.x, e.y)
                builder?.add(p[0], p[1], effort(e.pressure), e.getAxisValue(MotionEvent.AXIS_TILT), e.getAxisValue(MotionEvent.AXIS_ORIENTATION), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> builder?.let { b ->
                for (h in 0 until e.historySize) {
                    val p = page(e.getHistoricalX(h), e.getHistoricalY(h))
                    b.add(p[0], p[1], effort(e.getHistoricalPressure(h)), e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h), e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, h), e.getHistoricalEventTime(h))
                }
                val p = page(e.x, e.y)
                b.add(p[0], p[1], effort(e.pressure), e.getAxisValue(MotionEvent.AXIS_TILT), e.getAxisValue(MotionEvent.AXIS_ORIENTATION), e.eventTime)
            }
            MotionEvent.ACTION_UP -> builder?.let { b ->
                val p = page(e.x, e.y)
                b.finishAt(p[0], p[1], effort(e.pressure), e.getAxisValue(MotionEvent.AXIS_TILT), e.getAxisValue(MotionEvent.AXIS_ORIENTATION), e.eventTime)
                val st = b.build().let { if (strokeLook.isDefault) it else it.withLook(strokeLook) }
                builder = null
                if (st.size > 0) onStroke(w.pageId, st)
            }
            MotionEvent.ACTION_CANCEL -> builder = null
        }
        invalidate()
        return true
    }
}
