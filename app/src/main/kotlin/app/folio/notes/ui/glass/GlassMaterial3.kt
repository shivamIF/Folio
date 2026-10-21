package app.folio.notes.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties

/*
 * Material 3 menus and dialogs dressed in glass. Each wrapper takes the parameters of the M3
 * component it wraps. When glass is off for that kind of surface (Settings › Glass: the master
 * switch, Menus, Dialogs) it is that component with its defaults and the plain theme, so the
 * app looks exactly as it did without glass.
 */

/** Corner of a glass menu (macOS menus are rounder than M3's 4 dp). */
val GlassMenuShape = RoundedCornerShape(14.dp)

/** Corner of a glass dialog: M3's extra-large, so the layout inside is unchanged. */
val GlassDialogShape = RoundedCornerShape(28.dp)

/**
 * Room inside a menu's popup window around the glass, for its shadow and the spring's
 * overshoot (a popup window clips what's drawn outside it).
 */
private val MenuMargin = 8.dp
private val MenuElevation = 5.dp

/** The theme's colours outside any glass, so a plain surface inside glass looks as it always did. */
private val LocalPlainColorScheme = staticCompositionLocalOf<ColorScheme?> { null }

@Composable
fun glassMenusOn(): Boolean = LocalGlassSettings.current.let { it.enabled && it.glassMenus }

@Composable
fun glassDialogsOn(): Boolean = LocalGlassSettings.current.let { it.enabled && it.glassDialogs }

/**
 * The theme's colours with text, icons and hairlines swapped for [spec]'s vibrant ones (and
 * [ColorScheme.surfaceVariant] for its selection platter), so M3 components on that glass stay
 * legible in light and dark.
 */
@Composable
fun glassColorScheme(spec: GlassSpec): ColorScheme {
    val base = MaterialTheme.colorScheme
    return remember(base, spec) {
        base.copy(
            onSurface = spec.textPrimary, onSurfaceVariant = spec.textSecondary,
            outlineVariant = spec.separator, surfaceVariant = spec.selection,
        )
    }
}

/** Content on [role] glass: M3 components inside take the glass's text and icon colours. */
@Composable
fun ProvideGlassColors(role: GlassRole, content: @Composable () -> Unit) {
    val spec = glassSpec(role)
    ProvideBackdropColors(glassColorScheme(spec)) {
        CompositionLocalProvider(LocalContentColor provides spec.textPrimary, content = content)
    }
}

/**
 * Content over a special backdrop (glass, a wallpaper) uses [scheme]. Menus and dialogs inside
 * that aren't glass still get the plain theme.
 */
@Composable
fun ProvideBackdropColors(scheme: ColorScheme, content: @Composable () -> Unit) {
    val plain = LocalPlainColorScheme.current ?: MaterialTheme.colorScheme
    CompositionLocalProvider(LocalPlainColorScheme provides plain) {
        MaterialTheme(colorScheme = scheme, shapes = MaterialTheme.shapes, typography = MaterialTheme.typography, content = content)
    }
}

/** Restores the plain theme for a non-glass surface that sits inside glass. */
@Composable
private fun PlainColors(content: @Composable () -> Unit) {
    val plain = LocalPlainColorScheme.current
    if (plain == null) content()
    else CompositionLocalProvider(LocalPlainColorScheme provides null) {
        MaterialTheme(colorScheme = plain, shapes = MaterialTheme.shapes, typography = MaterialTheme.typography, content = content)
    }
}

/**
 * [DropdownMenu] on a glass card (role MENU) that pops in on the settings' spring. Items take
 * the glass's colours; pass [glassMenuItemColors] to a [androidx.compose.material3.DropdownMenuItem]
 * to set them explicitly.
 */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!glassMenusOn()) {
        PlainColors { DropdownMenu(expanded, onDismissRequest, modifier, offset, scrollState, properties, content = content) }
        return
    }
    val settings = LocalGlassSettings.current
    // Starts small in the frame it opens, then springs to full size.
    val pop = remember(expanded) { Animatable(if (expanded) 0.9f else 1f) }
    LaunchedEffect(pop) { pop.animateTo(1f, GlassMotion.spring(settings, Spring.StiffnessMedium)) }
    ProvideGlassColors(GlassRole.MENU) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            // The margin also sets the glass a little in from the anchor's edge, as macOS menus sit.
            modifier = Modifier
                .padding(MenuMargin)
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value; transformOrigin = TransformOrigin(0.5f, 0f) }
                .glassSurface(GlassMenuShape, GlassRole.MENU, elevation = MenuElevation)
                .then(modifier),
            offset = offset,
            scrollState = scrollState,
            properties = properties,
            shape = RectangleShape,
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = content,
        )
    }
}

/** Menu item colours for a [GlassDropdownMenu]; M3's defaults when menus aren't glass. */
@Composable
fun glassMenuItemColors(): MenuItemColors {
    if (!glassMenusOn()) return MenuDefaults.itemColors()
    val spec = glassSpec(GlassRole.MENU)
    return MenuDefaults.itemColors(
        textColor = spec.textPrimary,
        leadingIconColor = spec.textSecondary,
        trailingIconColor = spec.textSecondary,
        disabledTextColor = spec.textTertiary,
        disabledLeadingIconColor = spec.textTertiary,
        disabledTrailingIconColor = spec.textTertiary,
    )
}

/**
 * [AlertDialog] on glass (role DIALOG), with the same slots and buttons. It springs in from
 * slightly smaller. Body text uses the glass's primary colour, since dialogs hold the most text.
 */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    if (!glassDialogsOn()) {
        PlainColors {
            AlertDialog(onDismissRequest, confirmButton, modifier, dismissButton, icon, title, text, properties = properties)
        }
        return
    }
    val settings = LocalGlassSettings.current
    val pop = remember { Animatable(0.92f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, GlassMotion.spring(settings, Spring.StiffnessMedium)) }
    val spec = glassSpec(GlassRole.DIALOG)
    ProvideGlassColors(GlassRole.DIALOG) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                .glassSurface(GlassDialogShape, GlassRole.DIALOG),
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = GlassDialogShape,
            containerColor = Color.Transparent,
            iconContentColor = AlertDialogDefaults.iconContentColor,
            titleContentColor = spec.textPrimary,
            textContentColor = spec.textPrimary,
            tonalElevation = 0.dp,
            properties = properties,
        )
    }
}
