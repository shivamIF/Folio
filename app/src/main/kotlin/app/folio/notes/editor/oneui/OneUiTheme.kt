package app.folio.notes.editor.oneui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Colours of the note editor's chrome (app bar, toolbar, popups). The dark set follows the
 * measurements in docs/research/samsung-notes-editor-teardown.md §2 (plain colour values);
 * the light set is Folio's own. The editor follows the system theme.
 */
@Immutable
data class OneUiColors(
    val dark: Boolean,
    val appBar: Color,
    val divider: Color,
    val toolbar: Color,
    val toolSelected: Color,
    val desk: Color,
    val page: Color,
    val popup: Color,
    val popupRim: Color,
    val shadow: Color,
    val sliderTrack: Color,
    val sliderDot: Color,
    val thumb: Color,
    val thumbText: Color,
    val pill: Color,
    val pillSelected: Color,
    val pillBorder: Color,
    val pageDot: Color,
    val pageDotCurrent: Color,
    val text: Color,
    val textSoft: Color,
    val icon: Color,
    val iconDisabled: Color,
    val accent: Color,
    val onAccent: Color,
    val switchOff: Color,
    val switchThumbOff: Color,
    val closeFill: Color,
    val scrim: Color,
    val field: Color,
    val danger: Color,
)

val OneUiDark = OneUiColors(
    dark = true,
    appBar = Color(0xFF000000),
    divider = Color(0xFF3C3D43),
    toolbar = Color(0xFF252525),
    toolSelected = Color(0xFF5A5A5D),
    desk = Color(0xFF2F2F32),
    page = Color(0xFF030303),
    popup = Color(0xFF2D2D30),
    popupRim = Color(0x14FFFFFF),
    shadow = Color(0xCC000000),
    sliderTrack = Color(0xFF47474A),
    sliderDot = Color(0xFF77777A),
    thumb = Color(0xFF77777A),
    thumbText = Color(0xFFFFFFFF),
    pill = Color(0xFF38383B),
    pillSelected = Color(0xFF4F4F51),
    pillBorder = Color(0xFFB9B9BD),
    pageDot = Color(0xFF4D4D52),
    pageDotCurrent = Color(0xFF848487),
    text = Color(0xFFFFFFFF),
    textSoft = Color(0xFFA9A9AE),
    icon = Color(0xFFF2F2F4),
    iconDisabled = Color(0xFF6A6A6E),
    accent = Color(0xFF3E91FF),
    onAccent = Color(0xFFFFFFFF),
    switchOff = Color(0xFF5B5B5F),
    switchThumbOff = Color(0xFFFCFCFF),
    closeFill = Color(0xFF3E3E42),
    scrim = Color(0x99000000),
    field = Color(0xFF38383B),
    danger = Color(0xFFFF5A4F),
)

val OneUiLight = OneUiColors(
    dark = false,
    appBar = Color(0xFFFFFFFF),
    divider = Color(0xFFDCDCE0),
    toolbar = Color(0xFFF2F2F2),
    toolSelected = Color(0xFFDDDDE1),
    desk = Color(0xFFE6E6E9),
    page = Color(0xFFFFFFFF),
    popup = Color(0xFFFFFFFF),
    popupRim = Color(0x14000000),
    shadow = Color(0x40000000),
    sliderTrack = Color(0xFFE3E3E6),
    sliderDot = Color(0xFFB4B4B9),
    thumb = Color(0xFF8E8E93),
    thumbText = Color(0xFFFFFFFF),
    pill = Color(0xFFEEEEF0),
    pillSelected = Color(0xFFDADADE),
    pillBorder = Color(0xFF8E8E93),
    pageDot = Color(0xFFD2D2D6),
    pageDotCurrent = Color(0xFF8E8E93),
    text = Color(0xFF111114),
    textSoft = Color(0xFF6E6E74),
    icon = Color(0xFF26262A),
    iconDisabled = Color(0xFFBDBDC2),
    accent = Color(0xFF2F7DF6),
    onAccent = Color(0xFFFFFFFF),
    switchOff = Color(0xFFC9C9CE),
    switchThumbOff = Color(0xFFFFFFFF),
    closeFill = Color(0xFFEDEDF0),
    scrim = Color(0x66000000),
    field = Color(0xFFF1F1F3),
    danger = Color(0xFFE5392F),
)

val LocalOneUi = staticCompositionLocalOf { OneUiDark }

/** Provides the editor colours for [dark] (the system theme unless told otherwise). */
@Composable
fun ProvideOneUi(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOneUi provides if (dark) OneUiDark else OneUiLight, content = content)
}

/** Text sizes of the editor chrome. */
object OneUiType {
    val appTitle = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    val popupTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp)
    val bodyStrong = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontSize = 14.sp)
    val small = TextStyle(fontSize = 12.sp)
    val thumb = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
}
