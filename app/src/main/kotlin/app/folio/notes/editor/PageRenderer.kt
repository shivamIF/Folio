package app.folio.notes.editor

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.folio.core.ink.BrushSpec
import app.folio.core.ink.PenProfile
import app.folio.core.ink.StrokeGeometry
import app.folio.core.model.Argb
import app.folio.core.model.LineStyle
import app.folio.core.model.PageAudio
import app.folio.core.model.PageTable
import app.folio.core.model.PaperDensity
import app.folio.core.model.PaperTemplate
import app.folio.core.model.StickyNote
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import app.folio.notes.editor.oneui.ColorMath

/**
 * Draws a page in page units onto any Canvas: the screen, a bitmap, or a PDF
 * export, so they always match. Holds Paint objects: one instance per thread.
 *
 * [forPdf]: the PDF canvas can't draw vertex meshes or blurs, so the pencil is drawn as a flat
 * fill at its mean darkness and the airbrush without its blur.
 *
 * Display settings ([dark], [pageColor], [density]) are set per page before drawing it. [dark]
 * is the screen-only "Adjust colour in Dark mode": a dark page with ink lightened by
 * [ColorMath.adaptForDark]; stored colours never change and exports always draw light.
 */
class PageRenderer(private val forPdf: Boolean = false) {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaintInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; blendMode = android.graphics.BlendMode.MULTIPLY }
    private val tapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.BUTT }
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9D6E3.toInt(); strokeWidth = 1.2f }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0B8C4.toInt() }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2B8B5.toInt(); strokeWidth = 1.5f }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    /** Screen only: dark page, lightened ink. */
    var dark: Boolean = false
    /** Background colour of the page (Page settings), or null for white. */
    var pageColor: Argb? = null
    /** Line spacing of the ruling. */
    var density: PaperDensity = PaperDensity.NORMAL
    /** Tape is hidden on screen (the "Hide tape" switch). */
    var hideTape: Boolean = false
    /** Ruling drawn this many times heavier, for small previews (Note style) where hairlines would vanish. */
    var rulingWeight: Float = 1f

    /** A stored ink colour as it is shown on this page. */
    fun shown(c: Argb): Int = (if (dark) ColorMath.adaptForDark(c) else c).toInt()

    /** The sheet plus the ruling. With a PDF background the ruling is skipped. */
    fun drawPaper(canvas: Canvas, paper: PaperTemplate, w: Float, h: Float, hasPdf: Boolean = false) {
        val bg = pageColor
        pagePaint.color = when {
            bg != null -> if (dark) ColorMath.adaptForDark(bg).toInt().let { darkPaper(it) } else bg.toInt()
            dark -> DARK_PAGE
            else -> Color.WHITE
        }
        canvas.drawRect(0f, 0f, w, h, pagePaint)
        if (hasPdf) return
        linePaint.color = if (dark) 0xFF2E3742.toInt() else 0xFFC9D6E3.toInt()
        linePaint.strokeWidth = 1.2f * rulingWeight
        marginPaint.strokeWidth = 1.5f * rulingWeight
        dotPaint.color = if (dark) 0xFF4A5260.toInt() else 0xFFB0B8C4.toInt()
        marginPaint.color = if (dark) 0xFF5A3434.toInt() else 0xFFF2B8B5.toInt()
        val step = 40f * density.factor
        when (paper) {
            PaperTemplate.BLANK -> Unit
            PaperTemplate.RULED -> {
                var y = 120f
                while (y < h - 40f) { canvas.drawLine(40f, y, w - 40f, y, linePaint); y += step }
                canvas.drawLine(110f, 0f, 110f, h, marginPaint)
            }
            PaperTemplate.GRID -> {
                var x = step; while (x < w) { canvas.drawLine(x, 0f, x, h, linePaint); x += step }
                var y = step; while (y < h) { canvas.drawLine(0f, y, w, y, linePaint); y += step }
            }
            PaperTemplate.DOTTED -> {
                var y = step
                while (y < h) {
                    var x = step
                    while (x < w) { canvas.drawCircle(x, y, 1.6f * rulingWeight, dotPaint); x += step }
                    y += step
                }
            }
        }
    }

    /** A chosen page colour shown in dark mode: a deep version of it, so light ink reads on it. */
    private fun darkPaper(adapted: Int): Int {
        val hsl = ColorMath.toHsl(adapted.toLong() and 0xFFFFFFFFL)
        return ColorMath.fromHsl(hsl[0], hsl[1] * 0.35f, 0.07f).toInt()
    }

    /** Stretches a rendered PDF page over the whole page. */
    fun drawPdfBackground(canvas: Canvas, bitmap: Bitmap, w: Float, h: Float) {
        dst.set(0f, 0f, w, h)
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
    }

    /** Engine v1 look: a flat fill at the tool's opacity (kept for the v1 switch and the Lab). */
    fun drawStroke(canvas: Canvas, color: Argb, tool: ToolType, path: Path) {
        val argb = color.toInt()
        strokePaint.color = argb
        strokePaint.alpha = (Color.alpha(argb) * PenProfile.of(tool).opacity).toInt()
        strokePaint.shader = null
        canvas.drawPath(path, strokePaint)
    }

    private val sprayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var sprayRadius = -1f

    /**
     * Each tool's own look (engine v2):
     * - pencil: graphite mesh, darker where pressed, lighter on its side, with paper grain;
     * - airbrush: a soft-edged mist whose density follows pressure;
     * - highlighter: multiplied over the page (screened on a dark page), so the ink and print
     *   under it stay crisp and overlapping passes deepen the colour like a real highlighter;
     * - tape: an opaque band (plain or patterned); peeled, a faint band with an outline;
     * - dashed and dotted lines: the centreline stroked with dashes sized by the width;
     * - pens, brush and marker: solid ink (their character is in the geometry).
     */
    fun drawStroke(canvas: Canvas, art: StrokeArt) {
        if (art.tool == ToolType.TAPE) return drawTape(canvas, art)
        if (!StrokeGeometry.useV2 && art.centerline == null) return drawStroke(canvas, art.color, art.tool, art.path)
        val argb = shown(art.color)
        art.centerline?.let { line -> return drawLine(canvas, art, line, argb) }
        val grain = StrokeArt.grainOf(art.tool)
        when {
            grain != null && art.colors != null && !forPdf -> {
                meshPaint.shader = grain
                val colors = art.colorsWith(argb)!!
                canvas.drawVertices(
                    Canvas.VertexMode.TRIANGLES, art.verts!!.size, art.verts, 0, art.texs, 0, colors, 0, null, 0, 0, meshPaint,
                )
            }
            grain != null -> {
                // PDF (no meshes): a flat fill at the stroke's mean darkness.
                strokePaint.color = argb
                strokePaint.alpha = (Color.alpha(argb) * art.meanShade).toInt()
                strokePaint.shader = null
                canvas.drawPath(art.path, strokePaint)
            }
            art.tool == ToolType.AIRBRUSH -> {
                sprayPaint.color = argb
                sprayPaint.alpha = (Color.alpha(argb) * SPRAY_ALPHA * art.meanShade).toInt()
                // The blur is in page units (it follows the canvas matrix), so the mist scales
                // with zoom. PDF gets the plain soft-coloured fill.
                val radius = BrushSpec.of(ToolType.AIRBRUSH).soften * SPRAY_BLUR_OF_SOFTEN * art.width
                if (forPdf) sprayPaint.maskFilter = null
                else if (radius != sprayRadius || sprayPaint.maskFilter == null) {
                    sprayPaint.maskFilter = BlurMaskFilter(maxOf(0.5f, radius), BlurMaskFilter.Blur.NORMAL)
                    sprayRadius = radius
                }
                canvas.drawPath(art.path, sprayPaint)
            }
            art.tool == ToolType.HIGHLIGHTER -> {
                highlightPaint.color = argb
                highlightPaint.alpha = (Color.alpha(argb) * HIGHLIGHTER_ALPHA).toInt()
                highlightPaint.blendMode = if (dark) android.graphics.BlendMode.SCREEN else android.graphics.BlendMode.MULTIPLY
                canvas.drawPath(art.path, highlightPaint)
            }
            else -> {
                strokePaint.color = argb
                strokePaint.shader = null
                canvas.drawPath(art.path, strokePaint)
            }
        }
    }

    /** Dashed or dotted pen lines and the round highlighter: the centreline, stroked. */
    private fun drawLine(canvas: Canvas, art: StrokeArt, line: Path, argb: Int) {
        val p = if (art.tool == ToolType.HIGHLIGHTER) highlightPaint else linePaintInk
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.color = argb
        p.strokeWidth = art.lineWidth
        when (art.look.line) {
            LineStyle.DASHED -> { p.strokeCap = Paint.Cap.BUTT; p.pathEffect = DashPathEffect(floatArrayOf(art.lineWidth * 3.2f, art.lineWidth * 2.4f), 0f) }
            LineStyle.DOTTED -> { p.strokeCap = Paint.Cap.ROUND; p.pathEffect = DashPathEffect(floatArrayOf(0.001f, art.lineWidth * 2.1f), 0f) }
            LineStyle.SOLID -> { p.strokeCap = Paint.Cap.ROUND; p.pathEffect = null }
        }
        if (art.tool == ToolType.HIGHLIGHTER) {
            p.alpha = (Color.alpha(argb) * HIGHLIGHTER_ALPHA).toInt()
            p.blendMode = if (dark) android.graphics.BlendMode.SCREEN else android.graphics.BlendMode.MULTIPLY
        }
        canvas.drawPath(line, p)
        p.style = Paint.Style.FILL; p.pathEffect = null
    }

    /** Tape: opaque over what's under it; peeled, a faint band with a thin outline. */
    fun drawTape(canvas: Canvas, art: StrokeArt) {
        if (hideTape) return
        val line = art.centerline ?: return
        val color = art.color
        tapePaint.strokeWidth = art.lineWidth
        tapePaint.pathEffect = null
        if (art.look.peeled) {
            tapePaint.shader = null
            tapePaint.color = ((color.toInt() and 0xFFFFFF) or 0x24000000)
            canvas.drawPath(line, tapePaint)
            // The outline: the band's edges, drawn as a thin ring around the stroke.
            tapePaint.color = (color.toInt() and 0xFFFFFF) or 0xCC000000.toInt()
            val edge = Paint(tapePaint).apply { strokeWidth = art.lineWidth }
            val outline = Path()
            edge.getFillPath(line, outline)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = maxOf(1f, art.lineWidth * 0.06f); this.color = tapePaint.color
            }
            canvas.drawPath(outline, ring)
            return
        }
        val pattern = art.look.pattern
        if (pattern in 1..9) {
            tapePaint.shader = TapePatterns.shader(pattern, color)
            tapePaint.color = Color.BLACK
        } else {
            tapePaint.shader = null
            tapePaint.color = (color or 0xFF000000).toInt()
        }
        canvas.drawPath(line, tapePaint)
        tapePaint.shader = null
        // A soft sheen along the top edge so the band reads as tape, not ink.
        tapePaint.color = 0x1FFFFFFF
        tapePaint.strokeWidth = art.lineWidth * 0.18f
        canvas.drawPath(line, tapePaint)
    }

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val missingPaint = Paint().apply { color = 0xFFE3E1DC.toInt() }
    private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** A placed picture, stretched to its frame. */
    fun drawImage(canvas: Canvas, bitmap: Bitmap, img: app.folio.core.model.PageImage) {
        dst.set(img.x, img.y, img.x + img.width, img.y + img.height)
        canvas.drawBitmap(bitmap, null, dst, imagePaint)
    }

    /** Placeholder while a picture can't be shown (unreadable file). */
    fun drawMissingImage(canvas: Canvas, img: app.folio.core.model.PageImage) {
        canvas.drawRect(img.x, img.y, img.x + img.width, img.y + img.height, missingPaint)
    }

    /** A text box, wrapped at its width (page units; the canvas carries the zoom). */
    fun drawText(canvas: Canvas, t: app.folio.core.model.TextBox) {
        if (t.text.isEmpty()) return
        val layout = layout(t.text, t.width, t.fontSize, shown(t.color).toLong() and 0xFFFFFFFFL)
        canvas.save()
        canvas.translate(t.x, t.y)
        layout.draw(canvas)
        canvas.restore()
    }

    fun layout(text: String, width: Float, fontSize: Float, color: Argb): android.text.StaticLayout {
        textPaint.textSize = fontSize
        textPaint.color = color.toInt()
        return android.text.StaticLayout.Builder.obtain(text, 0, text.length, textPaint, maxOf(1, width.toInt()))
            .setIncludePad(false).build()
    }

    // ---- sticky notes, tables, recordings (schema 6) ----

    private val stickyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Draw on-screen controls on page objects (a sticky note's Minimise); never in exports or thumbnails. */
    var drawControls: Boolean = false

    /**
     * A sticky note: a softly shadowed square in its colour, a strip of torn-edged tape across
     * its top, and its ink. In dark mode the note is dimmed and its ink lightened, like the page.
     */
    fun drawSticky(canvas: Canvas, n: StickyNote) {
        if (n.minimized) return drawStickyTab(canvas, n)
        val r = n.width * 0.015f
        dst.set(n.x + r, n.y + r * 1.8f, n.x + n.width + r, n.y + n.height + r * 1.8f)
        shadowPaint.color = if (dark) 0x55000000 else 0x26000000
        canvas.drawRoundRect(dst, r, r, shadowPaint)
        dst.set(n.x, n.y, n.x + n.width, n.y + n.height)
        stickyPaint.color = stickyFill(n.color)
        canvas.drawRoundRect(dst, r, r, stickyPaint)
        // Its ink, clipped to the note, in the note's own coordinates.
        canvas.save()
        canvas.clipRect(dst)
        canvas.translate(n.x, n.y)
        for (s in n.strokes) drawStroke(canvas, StrokeArt.of(s))
        canvas.restore()
        drawStickyTape(canvas, n)
        if (drawControls) drawStickyMinimise(canvas, n)
    }

    /** The note's fill: its colour, or half as bright on a dark page. */
    fun stickyFill(c: Argb): Int = if (dark) ColorMath.rgb((ColorMath.red(c) * 0.5f).toInt(), (ColorMath.green(c) * 0.5f).toInt(), (ColorMath.blue(c) * 0.5f).toInt()).toInt() else c.toInt()

    private val tapePath = Path()
    private val tapeRect = RectF()

    /** The tape: a pastel strip in a hue opposite the note's, tilted, with torn ends (tap it to collapse the note). */
    private fun drawStickyTape(canvas: Canvas, n: StickyNote) {
        val g = StickyGeometry.tape(n)
        val hsl = ColorMath.toHsl(n.color)
        var tape = ColorMath.fromHsl(hsl[0] + 150f, 0.5f, 0.8f)
        if (dark) tape = ColorMath.rgb((ColorMath.red(tape) * 0.62f).toInt(), (ColorMath.green(tape) * 0.62f).toInt(), (ColorMath.blue(tape) * 0.62f).toInt())
        canvas.save()
        canvas.rotate(StickyGeometry.TAPE_ANGLE, g.cx, g.cy)
        tapeRect.set(g.cx - g.w / 2, g.cy - g.h / 2, g.cx + g.w / 2, g.cy + g.h / 2)
        TapeArt.torn(tapePath, tapeRect)
        stickyPaint.color = ColorMath.withAlpha(tape, 0xE8).toInt()
        canvas.drawPath(tapePath, stickyPaint)
        stickyPaint.color = 0x22FFFFFF
        canvas.drawRect(tapeRect.left, tapeRect.top, tapeRect.right, tapeRect.top + g.h * 0.28f, stickyPaint)
        canvas.restore()
    }

    /** The on-note Minimise control: two arrows pointing in, at the top-right corner. */
    private fun drawStickyMinimise(canvas: Canvas, n: StickyNote) {
        val b = StickyGeometry.minimise(n)
        val k = b.width / 24f
        gridPaint.color = if (dark || ColorMath.luminance(n.color) < 0.45f) 0xD9FFFFFF.toInt() else 0xB3303034.toInt()
        gridPaint.strokeWidth = 1.7f * k
        gridPaint.strokeCap = Paint.Cap.ROUND
        fun l(x0: Float, y0: Float, x1: Float, y1: Float) = canvas.drawLine(b.left + x0 * k, b.top + y0 * k, b.left + x1 * k, b.top + y1 * k, gridPaint)
        l(5f, 19f, 10.5f, 13.5f); l(10.5f, 13.5f, 10.5f, 17.6f); l(10.5f, 13.5f, 6.4f, 13.5f)
        l(19f, 5f, 13.5f, 10.5f); l(13.5f, 10.5f, 13.5f, 6.4f); l(13.5f, 10.5f, 17.6f, 10.5f)
        gridPaint.strokeCap = Paint.Cap.BUTT
    }

    /** A minimised sticky note: a small rounded tab with a folded corner, where its tape was. */
    fun drawStickyTab(canvas: Canvas, n: StickyNote) {
        val t = StickyGeometry.tab(n)
        val rr = t.width * 0.22f
        dst.set(t.left, t.top, t.right, t.bottom)
        shadowPaint.color = if (dark) 0x66000000 else 0x33000000
        canvas.drawRoundRect(dst.left + 1.5f, dst.top + 2.5f, dst.right + 1.5f, dst.bottom + 2.5f, rr, rr, shadowPaint)
        val fill = if (dark) ColorMath.fromHsl(ColorMath.toHsl(n.color)[0], ColorMath.toHsl(n.color)[1] * 0.45f, 0.62f).toInt() else n.color.toInt()
        // The body without its bottom-right corner, which is folded over.
        val f = t.width * 0.34f
        val body = Path().apply {
            moveTo(t.left + rr, t.top); lineTo(t.right - rr, t.top); quadTo(t.right, t.top, t.right, t.top + rr)
            lineTo(t.right, t.bottom - f); lineTo(t.right - f, t.bottom); lineTo(t.left + rr, t.bottom); quadTo(t.left, t.bottom, t.left, t.bottom - rr)
            lineTo(t.left, t.top + rr); quadTo(t.left, t.top, t.left + rr, t.top); close()
        }
        stickyPaint.color = fill
        canvas.drawPath(body, stickyPaint)
        val fold = Path().apply { moveTo(t.right, t.bottom - f); quadTo(t.right - f * 0.9f, t.bottom - f * 0.9f, t.right - f, t.bottom); quadTo(t.right - f * 0.25f, t.bottom - f * 0.25f, t.right, t.bottom - f); close() }
        stickyPaint.color = ColorMath.withAlpha(ColorMath.fromHsl(ColorMath.toHsl(n.color)[0], 0.3f, if (dark) 0.8f else 0.55f), 0xFF).toInt()
        canvas.drawPath(fold, stickyPaint)
        gridPaint.color = if (dark) 0x99FFFFFF.toInt() else 0x40000000
        gridPaint.strokeWidth = maxOf(1f, t.width * 0.03f)
        canvas.drawPath(body, gridPaint)
    }

    /** A table: a ruled grid and the text of its cells. */
    fun drawTable(canvas: Canvas, t: PageTable) {
        gridPaint.color = if (dark) 0xFF8A93A3.toInt() else 0xFF7D8796.toInt()
        gridPaint.strokeWidth = 1.6f
        val cw = t.width / t.cols; val rh = t.height / t.rows
        dst.set(t.x, t.y, t.x + t.width, t.y + t.height)
        stickyPaint.color = if (dark) 0x14FFFFFF else 0x0A3D5A80
        canvas.drawRect(dst, stickyPaint)
        canvas.drawRect(dst, gridPaint)
        for (c in 1 until t.cols) canvas.drawLine(t.x + c * cw, t.y, t.x + c * cw, t.y + t.height, gridPaint)
        for (r in 1 until t.rows) canvas.drawLine(t.x, t.y + r * rh, t.x + t.width, t.y + r * rh, gridPaint)
        val pad = t.fontSize * 0.35f
        for (r in 0 until t.rows) for (c in 0 until t.cols) {
            val text = t.cell(r, c)
            if (text.isEmpty()) continue
            val layout = layout(text, cw - 2 * pad, t.fontSize, shown(t.color).toLong() and 0xFFFFFFFFL)
            canvas.save()
            canvas.clipRect(t.x + c * cw, t.y + r * rh, t.x + (c + 1) * cw, t.y + (r + 1) * rh)
            canvas.translate(t.x + c * cw + pad, t.y + r * rh + pad)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    /** A recording: a rounded chip with a play glyph, a waveform and its title. */
    fun drawAudio(canvas: Canvas, a: PageAudio, playing: Boolean = false) {
        val b = a.bounds
        dst.set(b.left, b.top, b.right, b.bottom)
        stickyPaint.color = if (dark) 0xFF2C2F36.toInt() else 0xFFEEF2F8.toInt()
        canvas.drawRoundRect(dst, b.height / 2, b.height / 2, stickyPaint)
        val cx = b.left + b.height / 2; val cy = b.top + b.height / 2
        stickyPaint.color = 0xFF2F7DF6.toInt()
        canvas.drawCircle(cx, cy, b.height * 0.36f, stickyPaint)
        stickyPaint.color = Color.WHITE
        val g = b.height * 0.14f
        if (playing) {
            canvas.drawRect(cx - g * 0.9f, cy - g, cx - g * 0.3f, cy + g, stickyPaint)
            canvas.drawRect(cx + g * 0.3f, cy - g, cx + g * 0.9f, cy + g, stickyPaint)
        } else canvas.drawPath(Path().apply { moveTo(cx - g * 0.6f, cy - g); lineTo(cx + g, cy); lineTo(cx - g * 0.6f, cy + g); close() }, stickyPaint)
        textPaint.textSize = b.height * 0.3f
        textPaint.color = if (dark) 0xFFE8EAF0.toInt() else 0xFF1B1B1F.toInt()
        val label = android.text.TextUtils.ellipsize(a.title, textPaint, b.width - b.height * 1.3f, android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(label, b.left + b.height * 1.0f, cy - b.height * 0.02f, textPaint)
        textPaint.textSize = b.height * 0.22f
        textPaint.color = if (dark) 0xFF9AA2B0.toInt() else 0xFF6B7280.toInt()
        val secs = a.durationMs / 1000
        canvas.drawText("%d:%02d".format(secs / 60, secs % 60), b.left + b.height * 1.0f, cy + b.height * 0.28f, textPaint)
    }

    /**
     * A page's own content above its paper/PDF: pictures, sticky notes, tables, ink, text, then
     * tape on top of everything. Used for PDF export and thumbnails ([image] decodes an
     * attachment, or null if it can't).
     */
    fun drawContent(canvas: Canvas, page: app.folio.core.model.Page, image: (String) -> Bitmap?) {
        for (img in page.images) {
            val bmp = image(img.attachmentId)
            if (bmp != null) drawImage(canvas, bmp, img) else drawMissingImage(canvas, img)
        }
        for (t in page.tables) drawTable(canvas, t)
        var tape: ArrayList<Stroke>? = null
        for (s in page.strokes) if (s.tool == ToolType.TAPE) (tape ?: ArrayList<Stroke>().also { tape = it }) += s else drawStroke(canvas, s)
        for (t in page.texts) drawText(canvas, t)
        for (a in page.audio) drawAudio(canvas, a)
        tape?.forEach { drawStroke(canvas, it) }
        for (n in page.stickies) drawSticky(canvas, n)
    }

    fun drawStroke(canvas: Canvas, s: Stroke) = drawStroke(canvas, StrokeArt.of(s))

    companion object {
        /** Highlighter strength under multiply: light enough to read through. */
        const val HIGHLIGHTER_ALPHA = 0.6f
        /** Airbrush mist strength at full pressure. */
        const val SPRAY_ALPHA = 0.8f
        /** Blur radius as a share of the spec's softening (Android blur reaches ~1.5 × radius). */
        const val SPRAY_BLUR_OF_SOFTEN = 0.7f
        /** Page colour in dark mode (teardown §2). */
        const val DARK_PAGE = 0xFF030303.toInt()
        /** Size of a minimised sticky note's tab (page units). */
        const val STICKY_TAB = 44f

        fun pathOf(outline: FloatArray): Path = Path().also { fillPath(it, outline) }

        fun fillPath(path: Path, outline: FloatArray) {
            path.rewind()
            if (outline.size < 6) return
            path.moveTo(outline[0], outline[1])
            var i = 2
            while (i < outline.size) { path.lineTo(outline[i], outline[i + 1]); i += 2 }
            path.close()
            path.fillType = Path.FillType.WINDING
        }
    }
}
