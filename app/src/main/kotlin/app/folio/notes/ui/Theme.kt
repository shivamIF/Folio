package app.folio.notes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF3D5A80), onPrimary = Color.White,
    secondary = Color(0xFF98C1D9), tertiary = Color(0xFFEE6C4D),
    background = Color(0xFFF7F6F3), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEDEBE6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF98C1D9), onPrimary = Color(0xFF0B1F33),
    secondary = Color(0xFF3D5A80), tertiary = Color(0xFFEE6C4D),
    background = Color(0xFF16181C), surface = Color(0xFF1E2126), surfaceVariant = Color(0xFF2A2E35),
)

@Composable
fun FolioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Cover and folder colours offered in pickers. Original palette. */
val CoverColors: List<Long> = listOf(
    0xFF3D5A80, 0xFF293241, 0xFFEE6C4D, 0xFF98C1D9, 0xFF2A9D8F, 0xFFE9C46A, 0xFFF4A261, 0xFF8E7DBE, 0xFFB5838D, 0xFF6D6875,
)
