package app.folio.notes.editor

import android.content.Context
import app.folio.core.ink.PressureCurve
import app.folio.core.model.Argb
import app.folio.core.model.ToolType
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

enum class LassoShape { FREEFORM, RECTANGLE }

/** The tool the pen works with. Text mode, Pen, Highlighter, Eraser and Selection are the toolbar's fixed tools. */
enum class EditorTool { TEXT, PEN, HIGHLIGHTER, ERASER, SELECTION, CHANGE_STYLE, TAPE, LASER }

/** The pen popup's line pills (teardown §5). */
enum class PenLine { FIXED, VARIABLE, DASHED, DOTTED }

enum class LaserStyle { SOLID, OUTLINED, DOT }

/** A saved pen (Favourite pens). */
data class PenPreset(val tool: ToolType, val color: Argb, val width: Float, val line: PenLine) {
    fun encode() = "${tool.name}:${color.toString(16)}:$width:${line.name}"

    companion object {
        fun decode(s: String): PenPreset? {
            val p = s.split(':')
            if (p.size != 4) return null
            return runCatching {
                PenPreset(ToolType.valueOf(p[0]), p[1].toLong(16), p[2].toFloat(), PenLine.valueOf(p[3]))
            }.getOrNull()?.takeIf { it.tool in ToolState.PENS && it.width.isFinite() && it.width > 0f }
        }
    }
}

/**
 * Everything the toolbar and its popups set. Each tool keeps its own colour; widths are kept
 * per [ToolType] (so each pen keeps its size); quick thicknesses are kept per tool too.
 */
data class ToolState(
    val active: EditorTool = EditorTool.PEN,
    /** The pen type the Pen tool writes with (every Folio pen, pencil included). */
    val pen: ToolType = ToolType.FOUNTAIN,
    /** Highlighter tip: round ("marker pen") instead of chisel. */
    val highlighterRound: Boolean = false,
    val colors: Map<EditorTool, Argb> = DEFAULT_COLORS,
    /** Width per tool type (page units), so switching keeps each one's own size. */
    val widths: Map<ToolType, Float> = DEFAULT_WIDTHS,
    /** Highlighter opacity, 1–100 % of its full strength. */
    val highlighterOpacity: Int = 100,
    val penLine: PenLine = PenLine.VARIABLE,
    val straightenPen: Boolean = false,
    val straightenHighlighter: Boolean = false,
    val straightenTape: Boolean = false,
    val adjustHighlighterToText: Boolean = false,
    val adjustTapeToText: Boolean = false,
    /** Area eraser (cuts strokes where you rub) instead of the stroke eraser. */
    val eraserArea: Boolean = false,
    /** Area eraser radius on screen (dp). */
    val eraserSizeDp: Float = 14f,
    val eraseHighlighterOnly: Boolean = false,
    val eraseTapeOnly: Boolean = false,
    val lassoShape: LassoShape = LassoShape.FREEFORM,
    /** Selection takes strokes the loop only crosses (off: only strokes fully inside). */
    val selectPartial: Boolean = true,
    /** Font size for new text boxes, in page units (A4 width = 1000). */
    val textSize: Float = 28f,
    /** Change style: the width it gives strokes (page units). */
    val restyleWidth: Float = 3.5f,
    /** Tape pattern: 0 = plain colour, 1–9 = Folio's patterns. */
    val tapePattern: Int = 0,
    /** All tape is hidden on screen (it isn't deleted). */
    val tapeHidden: Boolean = false,
    val laserStyle: LaserStyle = LaserStyle.SOLID,
    val laserRainbow: Boolean = true,
    val stabilization: Float = 0.3f,
    val penOnly: Boolean = true,
    /** 0 = light hand … 1 = firm hand; see [PressureCurve.fromSensitivity]. */
    val pressureSensitivity: Float = PressureCurve.sensitivityOf(PressureCurve.DEFAULT_CEILING),
    /** Most recent first, at most [RECENT_MAX]. */
    val recentColors: List<Argb> = emptyList(),
    /** The toolbar's quick colours (shared by the colour tools). */
    val quickColors: List<Argb> = DEFAULT_QUICK_COLORS,
    /** The toolbar's quick thicknesses, per tool type. */
    val quickSizes: Map<ToolType, List<Float>> = SIZE_PRESETS,
    val favourites: List<PenPreset> = emptyList(),
    /** Every pen stroke that looks like a shape snaps to it on lift, without holding. */
    val autoFixShapes: Boolean = false,
    /** Fingers can't pan or zoom the page. */
    val lockCanvas: Boolean = false,
) {
    /** The tool type the canvas draws with (pen, highlighter or tape). */
    val tool: ToolType get() = when (active) {
        EditorTool.HIGHLIGHTER -> ToolType.HIGHLIGHTER
        EditorTool.TAPE -> ToolType.TAPE
        else -> pen
    }

    /** The tool whose colour the toolbar's colours show and set. */
    val colorTool: EditorTool get() = when (active) {
        EditorTool.HIGHLIGHTER, EditorTool.TAPE, EditorTool.LASER, EditorTool.CHANGE_STYLE -> active
        else -> EditorTool.PEN
    }

    val color: Argb get() = colorOf(colorTool)
    fun colorOf(t: EditorTool): Argb = colors[t] ?: DEFAULT_COLORS.getValue(EditorTool.PEN)

    /** The ink colour a new stroke gets (the highlighter's carries its opacity). */
    val strokeColor: Argb get() = when (active) {
        EditorTool.HIGHLIGHTER -> (color and 0xFFFFFF) or (((highlighterOpacity.coerceIn(1, 100) * 255 + 50) / 100).toLong() shl 24)
        else -> color
    }

    /** The tool type whose size the toolbar's thicknesses show and set. */
    val sizeTool: ToolType get() = tool
    val width: Float get() = widthOf(tool)
    fun widthOf(t: ToolType): Float = widths[t] ?: DEFAULT_WIDTHS[t] ?: 3f

    val eraser: Boolean get() = active == EditorTool.ERASER
    val lasso: Boolean get() = active == EditorTool.SELECTION
    val text: Boolean get() = active == EditorTool.TEXT
    /** The pen lays down ink (pen, highlighter or tape). */
    val inks: Boolean get() = active == EditorTool.PEN || active == EditorTool.HIGHLIGHTER || active == EditorTool.TAPE

    val straighten: Boolean get() = when (active) {
        EditorTool.HIGHLIGHTER -> straightenHighlighter
        EditorTool.TAPE -> straightenTape
        EditorTool.PEN -> straightenPen
        else -> false
    }
    val adjustToText: Boolean get() = when (active) {
        EditorTool.HIGHLIGHTER -> adjustHighlighterToText
        EditorTool.TAPE -> adjustTapeToText
        else -> false
    }

    fun activate(t: EditorTool): ToolState = copy(active = t)

    /** Picks pen type [t] for the Pen tool (and switches to it). */
    fun selectPen(t: ToolType): ToolState = if (t == ToolType.HIGHLIGHTER) copy(active = EditorTool.HIGHLIGHTER)
    else if (t == ToolType.TAPE) copy(active = EditorTool.TAPE)
    else copy(active = EditorTool.PEN, pen = t)

    /** Sets [c] as the colour of the colour tool in use, and moves it to the front of [recentColors]. */
    fun withColor(c: Argb): ToolState = withColorFor(colorTool, c).let { if (it.active in COLORLESS) it.copy(active = EditorTool.PEN) else it }

    fun withColorFor(t: EditorTool, c: Argb): ToolState = copy(
        colors = colors + (t to (c or 0xFF000000)),
        recentColors = (listOf(c or 0xFF000000) + recentColors.filter { it != (c or 0xFF000000) }).take(RECENT_MAX),
    )

    fun withWidth(t: ToolType, w: Float): ToolState = copy(widths = widths + (t to w.coerceIn(sizeRange(t))))

    /** Quick colour slot [i] now holds [c]. */
    fun withQuickColor(i: Int, c: Argb): ToolState =
        copy(quickColors = quickColors.toMutableList().also { if (i in it.indices) it[i] = c or 0xFF000000 })

    /** Quick thickness slot [i] of [t] now holds [w]. */
    fun withQuickSize(t: ToolType, i: Int, w: Float): ToolState {
        val list = (quickSizes[t] ?: SIZE_PRESETS.getValue(t)).toMutableList()
        if (i in list.indices) list[i] = w.coerceIn(sizeRange(t))
        return copy(quickSizes = quickSizes + (t to list))
    }

    fun quickSizesOf(t: ToolType): List<Float> = quickSizes[t] ?: SIZE_PRESETS.getValue(t)

    fun apply(p: PenPreset): ToolState = copy(active = EditorTool.PEN, pen = p.tool, penLine = p.line)
        .withColorFor(EditorTool.PEN, p.color).withWidth(p.tool, p.width)

    fun currentPreset(): PenPreset = PenPreset(pen, colorOf(EditorTool.PEN), widthOf(pen), penLine)

    companion object {
        private val COLORLESS = setOf(EditorTool.ERASER, EditorTool.SELECTION, EditorTool.TEXT)

        val DEFAULT_COLORS: Map<EditorTool, Argb> = mapOf(
            EditorTool.PEN to 0xFF1B1B1F, EditorTool.HIGHLIGHTER to 0xFFF5D547, EditorTool.TAPE to 0xFF9CC3E6,
            EditorTool.LASER to 0xFFE8473F, EditorTool.CHANGE_STYLE to 0xFF2E7DD7,
        )

        /** Folio's own default quick colours. */
        val DEFAULT_QUICK_COLORS: List<Argb> = listOf(0xFF1B1B1F, 0xFFD92D20, 0xFF2F6FE0, 0xFF23864F, 0xFFE8701A)

        val DEFAULT_WIDTHS = mapOf(
            ToolType.FOUNTAIN to 3.5f, ToolType.BALLPOINT to 2.5f, ToolType.PENCIL to 3f,
            ToolType.BRUSH to 8f, ToolType.HIGHLIGHTER to 18f, ToolType.FINELINER to 1.8f, ToolType.MARKER to 6f,
            ToolType.AIRBRUSH to 14f, ToolType.TAPE to 26f,
        )

        /** Width range per tool: broad tools (highlighter, spray, tape) go much wider. */
        fun sizeRange(tool: ToolType): ClosedFloatingPointRange<Float> = when (tool) {
            ToolType.HIGHLIGHTER -> 6f..40f
            ToolType.AIRBRUSH -> 3f..48f
            ToolType.TAPE -> 12f..60f
            else -> 0.8f..16f
        }

        const val RECENT_MAX = 6

        /** The Pen tool's types, in the pen row's order. */
        val PENS = listOf(
            ToolType.FOUNTAIN, ToolType.BALLPOINT, ToolType.FINELINER, ToolType.BRUSH, ToolType.MARKER, ToolType.AIRBRUSH, ToolType.PENCIL,
        )

        /** Three one-tap sizes per tool: fine, medium, bold. The middle one is the default width. */
        val SIZE_PRESETS: Map<ToolType, List<Float>> = mapOf(
            ToolType.FOUNTAIN to listOf(2f, 3.5f, 6f), ToolType.BALLPOINT to listOf(1.5f, 2.5f, 4.5f),
            ToolType.PENCIL to listOf(2f, 3f, 5.5f), ToolType.BRUSH to listOf(5f, 8f, 13f),
            ToolType.HIGHLIGHTER to listOf(10f, 18f, 30f), ToolType.FINELINER to listOf(1f, 1.8f, 3f),
            ToolType.MARKER to listOf(4f, 6f, 10f), ToolType.AIRBRUSH to listOf(8f, 14f, 28f),
            ToolType.TAPE to listOf(18f, 26f, 40f),
        )
    }
}

/**
 * The size sliders' numbered steps (teardown §4): [STEPS] steps spread evenly in ratio over a
 * tool's [ToolState.sizeRange], so each step looks the same amount bigger. Widths are stored as
 * page units; the slider only shows and sets steps.
 */
object SizeScale {
    const val STEPS = 50
    /** Tape and the area eraser use 1–10 like the reference. */
    const val SHORT_STEPS = 10

    fun stepsOf(t: ToolType) = if (t == ToolType.TAPE) SHORT_STEPS else STEPS

    fun stepOf(t: ToolType, width: Float): Int = stepIn(ToolState.sizeRange(t), stepsOf(t), width)
    fun widthOf(t: ToolType, step: Int): Float = widthIn(ToolState.sizeRange(t), stepsOf(t), step)

    fun stepIn(r: ClosedFloatingPointRange<Float>, steps: Int, v: Float): Int {
        val f = ln(v.coerceIn(r) / r.start) / ln(r.endInclusive / r.start)
        return (1 + f * (steps - 1)).roundToInt().coerceIn(1, steps)
    }

    fun widthIn(r: ClosedFloatingPointRange<Float>, steps: Int, step: Int): Float {
        val f = (step.coerceIn(1, steps) - 1f) / (steps - 1f)
        return r.start * (r.endInclusive / r.start).pow(f)
    }

    /** The area eraser's 1–10 sizes, in dp on screen. */
    val ERASER_DP = 4f..60f
}

/** Persists tool settings and the toolbar layout in SharedPreferences (keys versioned with "v2."). */
class ToolPrefs(context: Context) {
    private val sp = context.getSharedPreferences("tools", Context.MODE_PRIVATE)

    /** Whether the page sidebar is shown in the editor (closed until the user opens it). */
    var sidebarOpen: Boolean
        get() = sp.getBoolean("sidebarOpen", false)
        set(value) { sp.edit().putBoolean("sidebarOpen", value).apply() }

    /** S Pen to text's handwriting language (an ML Kit tag such as "en-US"). */
    var spenLanguage: String
        get() = sp.getString("v2.spenLanguage", null) ?: HandwritingRecognizer.defaultTag()
        set(value) { sp.edit().putString("v2.spenLanguage", value).apply() }

    var toolbar: ToolbarConfig
        get() = ToolbarConfig.decode(sp.getString("v2.toolbar", null))
        set(value) { sp.edit().putString("v2.toolbar", value.normalized().encode()).apply() }

    fun load(): ToolState {
        val d = ToolState()
        val v2 = sp.contains("v2.active")
        fun <E : Enum<E>> enumOf(key: String, def: E, values: Array<E>): E =
            sp.getString(key, null)?.let { n -> values.firstOrNull { it.name == n } } ?: def
        // Settings saved by builds before the redesign (one colour, a "tool" name) carry over.
        val legacyTool = if (v2) null else sp.getString("tool", null)?.let { n -> ToolType.entries.firstOrNull { it.name == n } }
        val pen = if (v2) enumOf("v2.pen", d.pen, ToolType.entries.toTypedArray()).takeIf { it in ToolState.PENS } ?: d.pen
        else legacyTool?.takeIf { it in ToolState.PENS } ?: sp.getString("lastPen", null)?.let { n -> ToolState.PENS.firstOrNull { it.name == n } } ?: d.pen
        val active = if (v2) enumOf("v2.active", d.active, EditorTool.entries.toTypedArray())
        else if (legacyTool == ToolType.HIGHLIGHTER) EditorTool.HIGHLIGHTER else EditorTool.PEN
        val colors = EditorTool.entries.mapNotNull { t ->
            val key = "v2.color.${t.name}"
            when {
                sp.contains(key) -> t to sp.getLong(key, 0)
                !v2 && t == EditorTool.PEN && sp.contains("color") -> t to sp.getLong("color", 0)
                else -> ToolState.DEFAULT_COLORS[t]?.let { t to it }
            }
        }.toMap()
        val widths = ToolType.entries.associateWith { t ->
            sp.getFloat("width_${t.name}", d.widthOf(t)).takeIf { it.isFinite() && it > 0f }?.coerceIn(ToolState.sizeRange(t)) ?: d.widthOf(t)
        }
        return ToolState(
            // The app always reopens on a writing tool, never mid-selection or typing.
            active = if (active == EditorTool.SELECTION || active == EditorTool.TEXT || active == EditorTool.LASER) EditorTool.PEN else active,
            pen = pen,
            highlighterRound = sp.getBoolean("v2.highlighterRound", d.highlighterRound),
            colors = colors,
            widths = widths,
            highlighterOpacity = sp.getInt("v2.highlighterOpacity", d.highlighterOpacity).coerceIn(1, 100),
            penLine = enumOf("v2.penLine", d.penLine, PenLine.entries.toTypedArray()),
            straightenPen = sp.getBoolean("v2.straightenPen", false),
            straightenHighlighter = sp.getBoolean("v2.straightenHighlighter", false),
            straightenTape = sp.getBoolean("v2.straightenTape", false),
            adjustHighlighterToText = sp.getBoolean("v2.adjustHighlighterToText", false),
            adjustTapeToText = sp.getBoolean("v2.adjustTapeToText", false),
            eraserArea = sp.getBoolean("eraserPartial", d.eraserArea),
            eraserSizeDp = sp.getFloat("eraserSizeDp", d.eraserSizeDp).coerceIn(SizeScale.ERASER_DP),
            eraseHighlighterOnly = sp.getBoolean("eraseHighlighterOnly", d.eraseHighlighterOnly),
            eraseTapeOnly = sp.getBoolean("v2.eraseTapeOnly", false),
            lassoShape = enumOf("lassoShape", d.lassoShape, LassoShape.entries.toTypedArray()),
            selectPartial = sp.getBoolean("v2.selectPartial", true),
            textSize = sp.getFloat("textSize", d.textSize),
            restyleWidth = sp.getFloat("v2.restyleWidth", d.restyleWidth),
            tapePattern = sp.getInt("v2.tapePattern", 0).coerceIn(0, 9),
            tapeHidden = sp.getBoolean("v2.tapeHidden", false),
            laserStyle = enumOf("v2.laserStyle", d.laserStyle, LaserStyle.entries.toTypedArray()),
            laserRainbow = sp.getBoolean("v2.laserRainbow", true),
            stabilization = sp.getFloat("stabilization", d.stabilization),
            penOnly = sp.getBoolean("penOnly", d.penOnly),
            pressureSensitivity = sp.getFloat("pressureSensitivity", d.pressureSensitivity),
            recentColors = sp.getString("recentColors", "")!!.split(',').mapNotNull { it.toLongOrNull(16) }.take(ToolState.RECENT_MAX),
            quickColors = sp.getString("v2.quickColors", null)?.split(',')?.mapNotNull { it.toLongOrNull(16) }
                ?.takeIf { it.size == ToolbarConfig.MAX_COLOURS } ?: d.quickColors,
            quickSizes = ToolType.entries.associateWith { t ->
                sp.getString("v2.quickSizes.${t.name}", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
                    ?.takeIf { l -> l.size == 3 && l.all { it.isFinite() && it > 0f } } ?: ToolState.SIZE_PRESETS.getValue(t)
            },
            favourites = sp.getString("v2.favourites", "")!!.split('|').mapNotNull(PenPreset::decode),
            autoFixShapes = sp.getBoolean("v2.autoFixShapes", false),
            lockCanvas = sp.getBoolean("v2.lockCanvas", false),
        )
    }

    fun save(s: ToolState) {
        sp.edit().apply {
            putString("v2.active", s.active.name)
            putString("v2.pen", s.pen.name)
            putBoolean("v2.highlighterRound", s.highlighterRound)
            s.colors.forEach { (t, c) -> putLong("v2.color.${t.name}", c) }
            s.widths.forEach { (t, w) -> putFloat("width_${t.name}", w) }
            putInt("v2.highlighterOpacity", s.highlighterOpacity)
            putString("v2.penLine", s.penLine.name)
            putBoolean("v2.straightenPen", s.straightenPen)
            putBoolean("v2.straightenHighlighter", s.straightenHighlighter)
            putBoolean("v2.straightenTape", s.straightenTape)
            putBoolean("v2.adjustHighlighterToText", s.adjustHighlighterToText)
            putBoolean("v2.adjustTapeToText", s.adjustTapeToText)
            putBoolean("eraserPartial", s.eraserArea)
            putFloat("eraserSizeDp", s.eraserSizeDp)
            putBoolean("eraseHighlighterOnly", s.eraseHighlighterOnly)
            putBoolean("v2.eraseTapeOnly", s.eraseTapeOnly)
            putString("lassoShape", s.lassoShape.name)
            putBoolean("v2.selectPartial", s.selectPartial)
            putFloat("textSize", s.textSize)
            putFloat("v2.restyleWidth", s.restyleWidth)
            putInt("v2.tapePattern", s.tapePattern)
            putBoolean("v2.tapeHidden", s.tapeHidden)
            putString("v2.laserStyle", s.laserStyle.name)
            putBoolean("v2.laserRainbow", s.laserRainbow)
            putFloat("stabilization", s.stabilization)
            putBoolean("penOnly", s.penOnly)
            putFloat("pressureSensitivity", s.pressureSensitivity)
            putString("recentColors", s.recentColors.joinToString(",") { it.toString(16) })
            putString("v2.quickColors", s.quickColors.joinToString(",") { it.toString(16) })
            s.quickSizes.forEach { (t, l) -> putString("v2.quickSizes.${t.name}", l.joinToString(",")) }
            putString("v2.favourites", s.favourites.joinToString("|") { it.encode() })
            putBoolean("v2.autoFixShapes", s.autoFixShapes)
            putBoolean("v2.lockCanvas", s.lockCanvas)
            // Older builds read these two; keep them meaningful.
            putString("tool", s.tool.name)
            putLong("color", s.colorOf(EditorTool.PEN))
        }.apply()
    }
}
