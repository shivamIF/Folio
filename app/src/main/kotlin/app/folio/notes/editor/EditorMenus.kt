package app.folio.notes.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.folio.core.model.Argb
import app.folio.core.model.Folder
import app.folio.core.model.PaperDensity
import app.folio.core.model.PaperTemplate
import app.folio.notes.editor.oneui.FIcon
import app.folio.notes.editor.oneui.FolioIcon
import app.folio.notes.editor.oneui.LocalOneUi
import app.folio.notes.editor.oneui.MenuCard
import app.folio.notes.editor.oneui.MenuEntry
import app.folio.notes.editor.oneui.OneUiDialog
import app.folio.notes.editor.oneui.OneUiPopup
import app.folio.notes.editor.oneui.OneUiType
import app.folio.notes.editor.oneui.PopupHeader
import app.folio.notes.editor.oneui.PopupPlacement
import app.folio.notes.editor.oneui.RadioRow
import app.folio.notes.editor.oneui.SectionLabel
import app.folio.notes.editor.oneui.SwitchRow

/** What the Insert (+) menu can add (teardown §18). */
class InsertActions(
    val image: () -> Unit,
    val camera: (() -> Unit)? = null,
    val pdf: (() -> Unit)? = null,
    val voice: (() -> Unit)? = null,
    val audioFile: (() -> Unit)? = null,
    val scan: (() -> Unit)? = null,
    val drawing: (() -> Unit)? = null,
    val sticky: (() -> Unit)? = null,
    val table: (() -> Unit)? = null,
)

@Composable
fun InsertMenu(onDismiss: () -> Unit, onImage: () -> Unit, more: InsertActions? = null) {
    val a = more ?: InsertActions(onImage)
    MenuCard(
        listOf(
            listOfNotNull(
                a.pdf?.let { MenuEntry("PDF", FIcon.PDF, onClick = it) },
                a.voice?.let { MenuEntry("Voice recording", FIcon.MIC, onClick = it) },
            ),
            listOfNotNull(
                MenuEntry("Image", FIcon.IMAGE, onClick = a.image),
                a.camera?.let { MenuEntry("Camera", FIcon.CAMERA, onClick = it) },
                a.scan?.let { MenuEntry("Document scan", FIcon.SCAN, onClick = it) },
                a.audioFile?.let { MenuEntry("Audio file", FIcon.AUDIO, onClick = it) },
                a.drawing?.let { MenuEntry("Drawing", FIcon.DRAWING, onClick = it) },
                a.sticky?.let { MenuEntry("Sticky note", FIcon.STICKY, onClick = it) },
                a.table?.let { MenuEntry("Table", FIcon.TABLE, onClick = it) },
            ),
        ),
        onDismiss = onDismiss, width = 240.dp, placement = PopupPlacement.BELOW_END,
    )
}

/** The sheets the ⋮ menu opens; they're anchored to the ⋮ button too. */
enum class MoreSheet { NOTE_STYLE, PAGE_SETTINGS, MOVE_TO_FOLDER, DELETE, GLASS }

/**
 * The app bar's ⋮ menu (teardown §18, in Samsung Notes' order): Note style, Page settings ┄
 * Move to folder, Save as PDF, finger drawing, Add page ┄ icon row: favourite, Share, Print,
 * Delete. Search, tags, Lock and the maths solver aren't offered yet (KNOWN_LIMITATIONS).
 */
@Composable
fun MoreMenu(vm: EditorViewModel, state: EditorUiState, onDismiss: () -> Unit, onSheet: (MoreSheet) -> Unit) {
    val tools = state.tools
    val fav = state.meta?.favorite == true
    MenuCard(
        listOf(
            listOf(
                MenuEntry("Note style", FIcon.NOTE_STYLE) { onSheet(MoreSheet.NOTE_STYLE) },
                MenuEntry("Page settings", FIcon.PAGE_SETTINGS) { onSheet(MoreSheet.PAGE_SETTINGS) },
                MenuEntry("Glass & toolbar", FIcon.CUSTOMISE) { onSheet(MoreSheet.GLASS) },
            ),
            listOf(
                MenuEntry("Move to folder", FIcon.FOLDER, trailing = "›") { onSheet(MoreSheet.MOVE_TO_FOLDER) },
                MenuEntry("Save as PDF", FIcon.SAVE_AS, enabled = !state.exporting) { vm.exportPdf() },
                MenuEntry(if (tools.penOnly) "Turn on finger drawing" else "Turn off finger drawing", FIcon.FINGER) {
                    vm.updateTools { it.copy(penOnly = !it.penOnly) }
                },
                MenuEntry("Add page", FIcon.ADD_PAGE) { vm.addPage() },
            ),
        ),
        footer = listOf(
            MenuEntry(if (fav) "Remove from favourites" else "Add to favourites", if (fav) FIcon.STAR_FILLED else FIcon.STAR) { vm.toggleFavorite() },
            MenuEntry("Share", FIcon.SHARE, enabled = !state.pageExporting) { vm.shareNotebook() },
            MenuEntry("Print", FIcon.PRINT, enabled = !state.pageExporting) { vm.printNotebook() },
            MenuEntry("Move to Trash", FIcon.DELETE) { onSheet(MoreSheet.DELETE) },
        ),
        onDismiss = onDismiss, width = 280.dp, placement = PopupPlacement.BELOW_END,
    )
}

/** Opens the sheet [sheet] (or nothing), anchored where it's called. */
@Composable
fun MoreSheetHost(sheet: MoreSheet?, vm: EditorViewModel, state: EditorUiState, onDismiss: () -> Unit, onTrashed: () -> Unit) {
    val meta = state.meta ?: return
    when (sheet) {
        null -> Unit
        MoreSheet.NOTE_STYLE -> NoteStylePopup(meta.paper, meta.density, darkPages = LocalOneUi.current.dark && meta.darkAdjust, pageColor = meta.pageColor, onApply = vm::applyNoteStyle, onDismiss = onDismiss)
        MoreSheet.PAGE_SETTINGS -> PageSettingsPopup(meta.pageColor, meta.darkAdjust, LocalOneUi.current.dark && meta.darkAdjust, vm, onDismiss)
        MoreSheet.MOVE_TO_FOLDER -> MoveToFolderDialog(vm, meta.folderId, onDismiss)
        MoreSheet.GLASS -> app.folio.notes.ui.glass.GlassSettingsPanel(onDismiss, preview = { DockPreview() })
        MoreSheet.DELETE -> OneUiDialog(
            onDismiss = onDismiss, title = "Move this note to Trash?",
            message = "You can restore it from Trash in the library.",
            confirmLabel = "Move to Trash", destructive = true, onConfirm = { vm.moveToTrash(onTrashed) },
        )
    }
}

// ---- Page settings ----

/**
 * Page background colours (Page settings). Folio's own soft tints, light enough for dark ink;
 * in dark mode they're shown as deep versions of themselves (PageRenderer.drawPaper).
 */
val PAGE_COLORS: List<Pair<String, Argb>> = listOf(
    "Stone" to 0xFFE9E8E4, "Cream" to 0xFFF7F0DE, "Peach" to 0xFFFBE6DA, "Blush" to 0xFFF7E3E6, "Lilac" to 0xFFEDE6F4,
    "Mist" to 0xFFE0EBF2, "Mint" to 0xFFE2F1EA, "Sage" to 0xFFE6ECDC, "Butter" to 0xFFFAF3CF, "Sand" to 0xFFEFE5D3,
)

@Composable
private fun PageSettingsPopup(pageColor: Argb?, darkAdjust: Boolean, darkPages: Boolean, vm: EditorViewModel, onDismiss: () -> Unit) {
    val c = LocalOneUi.current
    OneUiPopup(onDismiss, width = 360.dp, gap = 8.dp, placement = PopupPlacement.BELOW_END) {
        PopupHeader("Page settings", onClose = onDismiss)
        SectionLabel("Background colour")
        val choices: List<Pair<String, Argb?>> = listOf("None" to null) + PAGE_COLORS
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            choices.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (name, color) ->
                        val on = color == pageColor
                        Box(
                            Modifier.weight(1f).aspectRatio(1.35f).clip(RoundedCornerShape(12.dp))
                                .border(if (on) 2.5.dp else 1.dp, if (on) c.accent else c.divider, RoundedCornerShape(12.dp))
                                .clickable(role = Role.RadioButton, onClickLabel = name) { vm.updateMeta { it.copy(pageColor = color) } }
                                .semantics { contentDescription = "$name background"; selected = on },
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                if (color != null) drawRect(Color(color.toInt()))
                                else {
                                    // The paper's own colour, crossed through.
                                    drawRect(if (darkPages) Color(0xFF030303) else Color.White)
                                    drawLine(c.textSoft, Offset(size.width * 0.3f, size.height * 0.75f), Offset(size.width * 0.7f, size.height * 0.25f), strokeWidth = 2.dp.toPx())
                                }
                            }
                            if (on) Box(Modifier.size(26.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                                FolioIcon(FIcon.CHECK, null, tint = c.onAccent, size = 18.dp)
                            }
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        SwitchRow(
            "Adjust colour in Dark mode", darkAdjust, { on -> vm.updateMeta { it.copy(darkAdjust = on) } },
            subtitle = "Dark pages with lighter ink while the app is dark",
        )
        Spacer(Modifier.height(10.dp))
    }
}

// ---- Note style ----

/** One paper of the Note style grid. */
class NoteStyle(val label: String, val paper: PaperTemplate, val density: PaperDensity)

val NOTE_STYLES = listOf(
    NoteStyle("Blank", PaperTemplate.BLANK, PaperDensity.NORMAL),
    NoteStyle("Lined", PaperTemplate.RULED, PaperDensity.NORMAL),
    NoteStyle("Lined (narrow)", PaperTemplate.RULED, PaperDensity.NARROW),
    NoteStyle("Lined (wide)", PaperTemplate.RULED, PaperDensity.WIDE),
    NoteStyle("Grid", PaperTemplate.GRID, PaperDensity.NORMAL),
    NoteStyle("Grid (narrow)", PaperTemplate.GRID, PaperDensity.NARROW),
    NoteStyle("Grid (wide)", PaperTemplate.GRID, PaperDensity.WIDE),
    NoteStyle("Dot", PaperTemplate.DOTTED, PaperDensity.NORMAL),
    NoteStyle("Dot (narrow)", PaperTemplate.DOTTED, PaperDensity.NARROW),
    NoteStyle("Dot (wide)", PaperTemplate.DOTTED, PaperDensity.WIDE),
)

/**
 * Note style: a grid of papers drawn by the page renderer itself (so each preview is exactly
 * the page you'll get, in the note's colour and dark mode). Apply restyles every page.
 */
@Composable
private fun NoteStylePopup(
    paper: PaperTemplate, density: PaperDensity, darkPages: Boolean, pageColor: Argb?,
    onApply: (PaperTemplate, PaperDensity) -> Unit, onDismiss: () -> Unit,
) {
    val c = LocalOneUi.current
    val current = NOTE_STYLES.indexOfFirst { it.paper == paper && (it.density == density || paper == PaperTemplate.BLANK) }
    var chosen by remember { mutableStateOf(current.coerceAtLeast(0)) }
    val renderer = remember { PageRenderer() }
    OneUiPopup(onDismiss, width = 380.dp, gap = 8.dp, placement = PopupPlacement.BELOW_END) {
        PopupHeader("Note style", onClose = onDismiss)
        Text(
            "Every page of this note takes the paper you choose. PDF pages keep their PDF.",
            style = OneUiType.small, color = c.textSoft, modifier = Modifier.padding(horizontal = 22.dp),
        )
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NOTE_STYLES.withIndex().chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (i, style) ->
                        val on = i == chosen
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.RadioButton) { chosen = i }
                                .semantics { selected = on },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Canvas(
                                Modifier.fillMaxWidth().aspectRatio(1.4f).clip(RoundedCornerShape(8.dp))
                                    .border(if (on) 2.5.dp else 1.dp, if (on) c.accent else c.divider, RoundedCornerShape(8.dp)),
                            ) {
                                // A piece of a page near its top-left corner (margin and first lines), ruled a little heavier.
                                val shownW = 360f
                                val k = size.width / shownW
                                drawIntoCanvas { canvas ->
                                    val nc = canvas.nativeCanvas
                                    nc.save()
                                    nc.scale(k, k)
                                    nc.translate(-50f, -70f)
                                    renderer.dark = darkPages; renderer.pageColor = pageColor; renderer.density = style.density
                                    renderer.rulingWeight = (1.3f / (1.2f * k)).coerceIn(1f, 3f)
                                    renderer.drawPaper(nc, style.paper, shownW + 50f, shownW / 1.4f + 70f)
                                    nc.restore()
                                }
                            }
                            Text(
                                style.label, style = OneUiType.small, color = if (on) c.text else c.textSoft, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        val changed = chosen != current
        Box(
            Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp))
                .background(if (changed) c.accent else c.pill)
                .clickable(enabled = changed, role = Role.Button) { val s = NOTE_STYLES[chosen]; onApply(s.paper, s.density); onDismiss() },
            contentAlignment = Alignment.Center,
        ) { Text("Apply", style = OneUiType.bodyStrong, color = if (changed) c.onAccent else c.textSoft) }
    }
}

// ---- Move to folder ----

@Composable
private fun MoveToFolderDialog(vm: EditorViewModel, currentFolder: String?, onDismiss: () -> Unit) {
    var folders by remember { mutableStateOf<List<Pair<Folder, Int>>?>(null) }
    LaunchedEffect(Unit) { folders = vm.folderTree() }
    var target by remember { mutableStateOf(currentFolder) }
    val name = folders?.firstOrNull { it.first.id == target }?.first?.name ?: "Documents"
    OneUiDialog(
        onDismiss = onDismiss, title = "Move to folder", confirmLabel = "Move",
        onConfirm = { vm.moveToFolder(target, name) },
    ) {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            RadioRow("Documents", target == null, { target = null }, subtitle = "Not in a folder")
            folders?.forEach { (f, depth) ->
                RadioRow(f.name, target == f.id, { target = f.id }, modifier = Modifier.padding(start = (depth * 20).dp)) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(f.color.toInt())))
                }
            }
            if (folders?.isEmpty() == true) Text(
                "No folders yet. Create them in the library.", style = OneUiType.small, color = LocalOneUi.current.textSoft,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp).width(320.dp),
            )
        }
    }
}
