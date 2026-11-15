package app.folio.notes.ui.glass

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the editor toolbar sits. */
enum class DockPosition(val label: String) { TOP("Top"), BOTTOM("Bottom") }

/**
 * How the editor toolbar is drawn: a floating glass dock over the page that magnifies under
 * the pen (macOS Dock), or the classic full-width strip under the app bar, in glass.
 */
enum class DockStyle(val label: String) { FLOATING("Floating dock"), BAR("Full-width bar") }

/** A colour cast on every glass surface. CLEAR is the neutral material. */
enum class GlassTint(val label: String, val argb: Long) {
    CLEAR("Clear", 0x00000000),
    BLUE("Blue", 0xFF3E7BFA),
    TEAL("Teal", 0xFF2AA198),
    ROSE("Rose", 0xFFE0607E),
    AMBER("Amber", 0xFFE6A23C),
    GRAPHITE("Graphite", 0xFF6B6F76),
}

/**
 * Everything the user can tune about Folio's glass look (Settings › Glass & toolbar). It only
 * changes how the app looks and moves; no feature depends on it. Stored in SharedPreferences,
 * not in notebooks, so it needs no schema migration.
 *
 * Defaults come from the macOS materials studied in docs/research/macos-glass-dock.md
 * (the Dock's own material: blur 30, saturation 1.6–1.8, quarter-resolution backdrop).
 */
@Immutable
data class GlassSettings(
    /** Master switch. Off = the plain One UI / Material surfaces. */
    val enabled: Boolean = true,
    /** Amount of glass, 0 (barely frosted) … 1 (thick). Picks blur and the material weight. */
    val frost: Float = 0.55f,
    /** How much of what's behind shows through, 0 (milky) … 1 (clear). */
    val transparency: Float = 0.5f,
    /** Colour boost of the backdrop seen through the glass (macOS uses 1.1–2.0). */
    val saturation: Float = 1.7f,
    /** Edge refraction: content bends near the rim like a lens. 0 = off. */
    val lensing: Float = 0.5f,
    /** Bright rim and top sheen. 0 = off. */
    val highlight: Float = 0.65f,
    val tint: GlassTint = GlassTint.CLEAR,
    /** Text and icons take their colour from the material behind them (macOS vibrancy). */
    val vibrantText: Boolean = true,
    /** Darker/brighter text and visible borders on glass (macOS "Increase contrast"). */
    val increaseContrast: Boolean = false,
    /** Solid surfaces, no see-through (macOS "Reduce transparency"). */
    val reduceTransparency: Boolean = false,
    /** Blur what's really behind the glass (GPU, Android 12+). Off = frosted tint only, the lightest. */
    val liveBlur: Boolean = true,

    // ---- toolbar (dock)
    val dockStyle: DockStyle = DockStyle.FLOATING,
    val dockPosition: DockPosition = DockPosition.TOP,
    /** Toolbar icon cell size in dp at rest. */
    val dockIconSize: Float = 44f,
    /** Magnify icons under the S Pen / mouse / finger, like the macOS Dock. */
    val magnify: Boolean = true,
    /** Size of the icon right under the pointer, as a multiple of [dockIconSize]. */
    val magnification: Float = 1.7f,
    /** How many neighbouring icons on each side grow with it. */
    val magnifySpread: Float = 2.5f,
    /** Collapse the toolbar into a small handle; it opens on hover or tap. */
    val autoHide: Boolean = false,
    /** Seconds without use before an auto-hiding toolbar collapses again. */
    val autoHideDelay: Float = 2.5f,
    /** Collapse an auto-hiding toolbar as soon as the pen touches the page. */
    val hideWhileWriting: Boolean = true,
    /** Show the tool's name above the icon under the pen (the Dock's label). */
    val dockLabels: Boolean = true,

    // ---- where glass is used
    val glassPopups: Boolean = true,
    val glassMenus: Boolean = true,
    val glassSidebar: Boolean = true,
    val glassDialogs: Boolean = true,

    // ---- motion
    /** 0 = calm, no overshoot … 1 = lively, bouncy springs. */
    val springiness: Float = 0.45f,
) {
    /** Whether surfaces should be see-through at all. */
    val seeThrough: Boolean get() = enabled && !reduceTransparency

    fun clamped() = copy(
        frost = frost.coerceIn(0f, 1f), transparency = transparency.coerceIn(0f, 1f),
        saturation = saturation.coerceIn(1f, 2.2f), lensing = lensing.coerceIn(0f, 1f),
        highlight = highlight.coerceIn(0f, 1f), dockIconSize = dockIconSize.coerceIn(MIN_ICON, MAX_ICON),
        magnification = magnification.coerceIn(1f, MAX_MAGNIFICATION), magnifySpread = magnifySpread.coerceIn(1f, 5f),
        autoHideDelay = autoHideDelay.coerceIn(0.5f, 10f), springiness = springiness.coerceIn(0f, 1f),
    )

    companion object {
        const val MIN_ICON = 32f
        const val MAX_ICON = 64f
        const val MAX_MAGNIFICATION = 3f
    }
}

/** The app-wide glass settings, live: screens recompose when they change. */
object GlassPrefs {
    private const val FILE = "glass_ui"
    private var sp: SharedPreferences? = null
    private val state = MutableStateFlow(GlassSettings())
    val settings: StateFlow<GlassSettings> = state.asStateFlow()

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        state.value = read(p)
    }

    fun update(change: (GlassSettings) -> GlassSettings) {
        val next = change(state.value).clamped()
        if (next == state.value) return
        state.value = next
        sp?.let { write(it, next) }
    }

    fun reset() = update { GlassSettings() }

    private fun read(p: SharedPreferences): GlassSettings {
        val d = GlassSettings()
        fun f(k: String, v: Float) = p.getFloat(k, v)
        fun b(k: String, v: Boolean) = p.getBoolean(k, v)
        return GlassSettings(
            enabled = b("enabled", d.enabled), frost = f("frost", d.frost), transparency = f("transparency", d.transparency),
            saturation = f("saturation", d.saturation), lensing = f("lensing", d.lensing), highlight = f("highlight", d.highlight),
            tint = enumOr(p.getString("tint", null), d.tint), vibrantText = b("vibrantText", d.vibrantText),
            increaseContrast = b("increaseContrast", d.increaseContrast), reduceTransparency = b("reduceTransparency", d.reduceTransparency),
            liveBlur = b("liveBlur", d.liveBlur),
            dockStyle = enumOr(p.getString("dockStyle", null), d.dockStyle),
            dockPosition = enumOr(p.getString("dockPosition", null), d.dockPosition),
            dockIconSize = f("dockIconSize", d.dockIconSize), magnify = b("magnify", d.magnify),
            magnification = f("magnification", d.magnification), magnifySpread = f("magnifySpread", d.magnifySpread),
            autoHide = b("autoHide", d.autoHide), autoHideDelay = f("autoHideDelay", d.autoHideDelay),
            hideWhileWriting = b("hideWhileWriting", d.hideWhileWriting), dockLabels = b("dockLabels", d.dockLabels),
            glassPopups = b("glassPopups", d.glassPopups), glassMenus = b("glassMenus", d.glassMenus),
            glassSidebar = b("glassSidebar", d.glassSidebar), glassDialogs = b("glassDialogs", d.glassDialogs),
            springiness = f("springiness", d.springiness),
        ).clamped()
    }

    private fun write(p: SharedPreferences, s: GlassSettings) {
        p.edit()
            .putBoolean("enabled", s.enabled).putFloat("frost", s.frost).putFloat("transparency", s.transparency)
            .putFloat("saturation", s.saturation).putFloat("lensing", s.lensing).putFloat("highlight", s.highlight)
            .putString("tint", s.tint.name).putBoolean("vibrantText", s.vibrantText)
            .putBoolean("increaseContrast", s.increaseContrast).putBoolean("reduceTransparency", s.reduceTransparency)
            .putBoolean("liveBlur", s.liveBlur)
            .putString("dockStyle", s.dockStyle.name).putString("dockPosition", s.dockPosition.name)
            .putFloat("dockIconSize", s.dockIconSize).putBoolean("magnify", s.magnify)
            .putFloat("magnification", s.magnification).putFloat("magnifySpread", s.magnifySpread)
            .putBoolean("autoHide", s.autoHide).putFloat("autoHideDelay", s.autoHideDelay)
            .putBoolean("hideWhileWriting", s.hideWhileWriting).putBoolean("dockLabels", s.dockLabels)
            .putBoolean("glassPopups", s.glassPopups).putBoolean("glassMenus", s.glassMenus)
            .putBoolean("glassSidebar", s.glassSidebar).putBoolean("glassDialogs", s.glassDialogs)
            .putFloat("springiness", s.springiness)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
}

val LocalGlassSettings = staticCompositionLocalOf { GlassSettings() }

/** The live settings, for providing at the root ([app.folio.notes.ui.FolioTheme]). */
@Composable
fun rememberGlassSettings(): GlassSettings {
    val s by GlassPrefs.settings.collectAsState()
    return s
}
