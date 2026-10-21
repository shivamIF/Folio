package app.folio.notes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1A73E8), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FC), onPrimaryContainer = Color(0xFF0B3A82),
    secondary = Color(0xFF98C1D9), tertiary = Color(0xFFEE6C4D),
    background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEFEFF2),
    surfaceContainer = Color(0xFFF4F4F6), surfaceContainerHigh = Color(0xFFECECEF),
    onSurfaceVariant = Color(0xFF6B6B72), outlineVariant = Color(0xFFDADADF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF2E8BFF), onPrimary = Color.White,
    primaryContainer = Color(0xFF173A66), onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFF98C1D9), tertiary = Color(0xFFEE6C4D),
    background = Color(0xFF000000), surface = Color(0xFF121214), surfaceVariant = Color(0xFF2A2A2E),
    surfaceContainer = Color(0xFF1C1C1F), surfaceContainerHigh = Color(0xFF262629),
    onSurfaceVariant = Color(0xFFA1A1A8), outlineVariant = Color(0xFF3A3A3F),
)

/** Library chrome colours that Material's scheme doesn't name. */
@Immutable
data class LibraryColors(val sidebar: Color, val content: Color, val selectedItem: Color, val star: Color)

val LocalLibraryColors = staticCompositionLocalOf {
    LibraryColors(Color(0xFFF4F4F6), Color.White, Color(0xFFDCE8FC), Color(0xFFFFB800))
}

@Composable
fun FolioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val library = if (dark) LibraryColors(Color(0xFF1C1C1F), Color(0xFF000000), Color(0xFF1B3557), Color(0xFFFFC83D))
    else LibraryColors(Color(0xFFF4F4F6), Color(0xFFFFFFFF), Color(0xFFDCE8FC), Color(0xFFFFB800))
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLibraryColors provides library,
        app.folio.notes.ui.glass.LocalGlassSettings provides app.folio.notes.ui.glass.rememberGlassSettings(),
    ) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}

/** Cover colours offered for notebooks. Original palette. */
val CoverColors: List<Long> = listOf(
    0xFF3D5A80, 0xFF293241, 0xFFEE6C4D, 0xFF98C1D9, 0xFF2A9D8F, 0xFFE9C46A, 0xFFF4A261, 0xFF8E7DBE, 0xFFB5838D, 0xFF6D6875,
)

/** Folder colours offered in the folder customiser, with names for accessibility. Original palette. */
val FolderColors: List<Pair<String, Long>> = listOf(
    "Sky" to 0xFF4FB6E3, "Blue" to 0xFF3B7FF2, "Green" to 0xFF34B37E, "Yellow" to 0xFFF2C230, "Orange" to 0xFFF28C38,
    "Red" to 0xFFE5534B, "Pink" to 0xFFE96BA8, "Purple" to 0xFF9970E6, "Gray" to 0xFF9AA0A6, "Graphite" to 0xFF4A4D55,
)

val DefaultFolderColor: Long = FolderColors.first().second
