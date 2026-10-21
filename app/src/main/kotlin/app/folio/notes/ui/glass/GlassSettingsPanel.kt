package app.folio.notes.ui.glass

import androidx.compose.runtime.Composable

/**
 * Settings › Glass & toolbar: every control over the glass look and the toolbar dock
 * ([GlassSettings]), with a live preview. Opened from the library's Settings and from the
 * editor. Placeholder until the panel lands; [preview] shows a working toolbar dock.
 */
@Composable
fun GlassSettingsPanel(onDismiss: () -> Unit, preview: @Composable () -> Unit = {}) {
    @Suppress("UNUSED_EXPRESSION") onDismiss
    @Suppress("UNUSED_EXPRESSION") preview
}
