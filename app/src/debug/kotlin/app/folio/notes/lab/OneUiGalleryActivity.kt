package app.folio.notes.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.folio.notes.editor.oneui.ChoicePill
import app.folio.notes.editor.oneui.ColorMath
import app.folio.notes.editor.oneui.ColorPickerPanel
import app.folio.notes.editor.oneui.DottedDivider
import app.folio.notes.editor.oneui.FIcon
import app.folio.notes.editor.oneui.FolioIcon
import app.folio.notes.editor.oneui.LocalOneUi
import app.folio.notes.editor.oneui.MenuCard
import app.folio.notes.editor.oneui.MenuEntry
import app.folio.notes.editor.oneui.OneUiDialog
import app.folio.notes.editor.oneui.OpacitySlider
import app.folio.notes.editor.oneui.PalettePager
import app.folio.notes.editor.oneui.PenArt
import app.folio.notes.editor.oneui.PenStand
import app.folio.notes.editor.oneui.PillTabs
import app.folio.notes.editor.oneui.PopupHeader
import app.folio.notes.editor.oneui.ProvideOneUi
import app.folio.notes.editor.oneui.RadioRow
import app.folio.notes.editor.oneui.StepSlider
import app.folio.notes.editor.oneui.SwitchRow
import app.folio.notes.editor.oneui.IconAction
import androidx.compose.ui.geometry.Offset

/**
 * Debug builds only: every editor control in dark and light, side by side, for checking
 * against the teardown spec on a device.
 * `adb shell am start -n app.folio.notes/.lab.OneUiGalleryActivity`
 */
class OneUiGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Row(Modifier.fillMaxSize()) {
                ProvideOneUi(dark = true) { Gallery(Modifier.weight(1f)) }
                ProvideOneUi(dark = false) { Gallery(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Gallery(modifier: Modifier) {
    val c = LocalOneUi.current
    var penSize by remember { mutableStateOf(5) }
    var opacity by remember { mutableStateOf(33) }
    var straighten by remember { mutableStateOf(false) }
    var radio by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(0) }
    var pill by remember { mutableStateOf(1) }
    var pen by remember { mutableStateOf(2) }
    var color by remember { mutableStateOf(0xFFE8473FL) }
    var picker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    Column(modifier.fillMaxHeight().background(c.desk).verticalScroll(rememberScrollState()).padding(12.dp)) {
        // Toolbar look.
        Row(Modifier.background(c.toolbar).horizontalScroll(rememberScrollState()).padding(6.dp)) {
            listOf(FIcon.TEXT_MODE, FIcon.FOUNTAIN, FIcon.HIGHLIGHTER, FIcon.ERASER, FIcon.LASSO, FIcon.UNDO, FIcon.REDO,
                FIcon.SPEN_TO_TEXT, FIcon.CHANGE_STYLE, FIcon.TAPE, FIcon.EASY_PAD, FIcon.LASER, FIcon.CUSTOMISE).forEachIndexed { i, ic ->
                IconAction(ic, ic.name, {}, selected = i == 1, ink = Color(ColorMath.adaptForDark(color).takeIf { c.dark } ?: color))
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(Modifier.background(c.popup).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FIcon.entries.filter { it != FIcon.PALETTE_GRID }.forEach { ic ->
                FolioIcon(ic, ic.name, ink = Color(0xFF3E91FF), size = 26.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.width(300.dp).clip(RoundedCornerShape(26.dp)).background(c.popup)) {
            PopupHeader(onClose = {}, leading = {
                IconAction(FIcon.FAV_PENS, "Favourite pens", {})
                IconAction(FIcon.STAR, "Add to favourites", {})
            }, trailing = { IconAction(FIcon.SHRINK, "Shrink", {}) })
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                listOf(PenArt.FOUNTAIN, PenArt.BALLPOINT, PenArt.FINELINER, PenArt.BRUSH, PenArt.MARKER, PenArt.AIRBRUSH, PenArt.PENCIL)
                    .forEachIndexed { i, a -> PenStand(a, Color(color), i == pen, a.name, { pen = i }) }
            }
            Box(Modifier.height(1.dp).padding(0.dp).background(c.divider))
            StepSlider(penSize, 1..50, { penSize = it }, "Pen size")
            Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { i ->
                    ChoicePill(pill == i, "Style $i", { pill = i }) {
                        val y = size.height / 2
                        drawLine(c.text, Offset(size.width * 0.2f, y), Offset(size.width * 0.8f, y), 3.dp.toPx())
                    }
                }
            }
            SwitchRow("Straighten lines", straighten, { straighten = it })
            OpacitySlider(opacity, Color(color), { opacity = it })
            PalettePager(color, listOf(0xFF1B1B1F, 0xFF2E7DD7, 0xFFE8701A), { color = it }, {}, { picker = true })
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.width(300.dp).clip(RoundedCornerShape(26.dp)).background(c.popup)) {
            PopupHeader("Handwriting eraser", onClose = {})
            RadioRow("Stroke eraser", radio == 0, { radio = 0 })
            RadioRow("Area eraser", radio == 1, { radio = 1 })
            DottedDivider()
            PillTabs(listOf("Colour", "Pattern"), tab, { tab = it }, Modifier.padding(16.dp))
            Row {
                Text("Menu", color = c.accent, modifier = Modifier.padding(16.dp).clickable { menu = true })
                Text("Dialog", color = c.accent, modifier = Modifier.padding(16.dp).clickable { dialog = true })
                Text("Picker", color = c.accent, modifier = Modifier.padding(16.dp).clickable { picker = true })
            }
            if (menu) MenuCard(listOf(listOf(MenuEntry("PDF", FIcon.PDF) {}, MenuEntry("Voice recording", FIcon.MIC) {}), listOf(MenuEntry("Image", FIcon.IMAGE) {}, MenuEntry("Camera", FIcon.CAMERA) {})), { menu = false })
        }
    }
    if (picker) ColorPickerPanel(color, listOf(0xFFE8701A, 0xFFFFFFFF, 0xFF000000), { color = it; picker = false }, { picker = false }, {})
    if (dialog) OneUiDialog({ dialog = false }, "Erase all handwriting?", "Everything you've written on this page will be erased.", "Erase", {}, destructive = true)
}

