package app.folio.notes.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NotInterested
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.folio.core.model.Cover
import app.folio.core.model.CoverStyle
import app.folio.core.model.Folder
import app.folio.core.model.FolderIcon
import app.folio.core.model.LibraryIndex
import app.folio.core.model.NotebookMeta
import app.folio.core.model.PaperTemplate
import app.folio.notes.ui.CoverColors
import app.folio.notes.ui.DefaultFolderColor
import app.folio.notes.ui.FolderColors
import app.folio.notes.ui.label
import app.folio.notes.ui.vector
import app.folio.notes.ui.glass.GlassAlertDialog
import app.folio.notes.ui.glass.GlassSettingsPanel

private fun Long.argbHex() = "%08X".format(this)
private fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

@Composable
private fun Swatch(color: Color, selected: Boolean, description: String, onPick: () -> Unit, brush: Brush? = null) {
    Box(
        Modifier.size(38.dp).clip(CircleShape)
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(color))
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .clickable(onClick = onPick)
            .semantics { contentDescription = description; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) { if (selected && brush == null) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun ColorRow(colors: List<Long>, selected: Long, onPick: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                    .then(if (c == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable { onPick(c) }
                    .semantics { contentDescription = "Colour ${c.argbHex()}" + if (c == selected) ", selected" else "" },
            )
        }
    }
}

/**
 * New folder or folder customisation: name, a Color tab (presets plus a
 * custom hue/shade picker) and an Icon tab, with a live preview.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FolderDialog(existing: Folder?, onDismiss: () -> Unit, onDone: (name: String, color: Long, icon: FolderIcon) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var color by remember { mutableStateOf(existing?.color ?: DefaultFolderColor) }
    var icon by remember { mutableStateOf(existing?.icon ?: FolderIcon.NONE) }
    var tab by remember { mutableIntStateOf(0) }
    val presets = FolderColors.map { it.second }
    var custom by remember { mutableStateOf(color !in presets) }
    val confirm = { onDone(name, color, icon) }
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New folder" else "Customize folder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FolderArt(color, icon, Modifier.width(104.dp).height(84.dp), iconSize = 30.dp)
                    OutlinedTextField(
                        name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.testTag("folder-name"),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Words),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                    )
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Color", "Icon").forEachIndexed { i, label ->
                        SegmentedButton(tab == i, { tab = i }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
                if (tab == 0) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FolderColors.forEach { (label, c) ->
                            Swatch(Color(c), !custom && c == color, label + if (!custom && c == color) ", selected" else "", { custom = false; color = c })
                        }
                        Swatch(
                            Color.Unspecified, custom, "Custom colour", { custom = true },
                            brush = Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)),
                        )
                    }
                    if (custom) CustomColorPicker(color) { color = it }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FolderIcon.entries.forEach { ic ->
                            val sel = ic == icon
                            Box(
                                Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                    .then(if (sel) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                                    .clickable { icon = ic }
                                    .semantics { contentDescription = ic.label; selected = sel },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    ic.vector ?: Icons.Filled.NotInterested, contentDescription = null,
                                    tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirm() }, modifier = Modifier.testTag("folder-done")) { Text(if (existing == null) "Create" else "Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Two bars: hue across the spectrum, then shade from deep to light. */
@Composable
private fun CustomColorPicker(color: Long, onChange: (Long) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(color.toInt(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var shade by remember { mutableFloatStateOf(hsv[2].coerceIn(0.35f, 0.98f)) }
    fun emit() = onChange(Color.hsv(hue, 0.66f, shade).toArgbLong())
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GradientBar(
            (0..6).map { Color.hsv(it * 60f % 360f, 0.66f, 0.92f) }, hue / 360f, "Hue",
        ) { hue = it * 359.9f; emit() }
        GradientBar(
            listOf(Color.hsv(hue, 0.66f, 0.35f), Color.hsv(hue, 0.66f, 0.98f)), (shade - 0.35f) / 0.63f, "Shade",
        ) { shade = 0.35f + it * 0.63f; emit() }
    }
}

@Composable
private fun GradientBar(colors: List<Color>, fraction: Float, label: String, onChange: (Float) -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    Canvas(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(15.dp))
            .semantics { contentDescription = "$label ${(fraction * 100).toInt()} percent" }
            .pointerInput(Unit) { detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> onChange((change.position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        drawRect(Brush.horizontalGradient(colors))
        val x = fraction.coerceIn(0f, 1f) * size.width
        val c = Offset(x.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2)
        drawCircle(Color.White, radius = size.height / 2 - 3f, center = c, style = Stroke(width = 5f))
        drawCircle(ring.copy(alpha = 0.4f), radius = size.height / 2 - 1f, center = c, style = Stroke(width = 1.5f))
    }
}

/** "Move to" picker: the top level plus every live folder, indented by depth. */
@Composable
fun MoveDialog(index: LibraryIndex, keys: Set<String>, currentFolderId: String?, onDismiss: () -> Unit, onMove: (String?) -> Unit) {
    val movingFolders = keys.filter { it.startsWith("f:") }.map { it.substring(2) }.toSet()
    val tree = remember(index, keys) { index.folderTree(excluded = movingFolders) }
    // null is the top level, so "nothing picked yet" needs its own flag.
    var picked by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<String?>(null) }
    fun pick(id: String?) { picked = true; target = id }
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (keys.size == 1) "Move to…" else "Move ${keys.size} items to…") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item(key = "root") { MoveRow("Documents", null, 0, picked && target == null, currentFolderId == null) { pick(null) } }
                items(tree, key = { it.folder.id }) { n ->
                    MoveRow(n.folder.name, n.folder, n.depth + 1, picked && target == n.folder.id, currentFolderId == n.folder.id) { pick(n.folder.id) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMove(target) }, enabled = picked && target != currentFolderId) { Text("Move here") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveRow(name: String, folder: Folder?, depth: Int, selected: Boolean, current: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onPick).padding(start = (12 + depth * 22).dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            .semantics { contentDescription = "Move to $name"; this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (folder != null) FolderArt(folder.color, folder.icon, Modifier.width(34.dp).height(28.dp), iconSize = 12.dp)
        else Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else null)
        if (current) Text("Current", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NotebookDialog(
    existing: NotebookMeta?, defaults: NotebookDefaults, onDismiss: () -> Unit, onDone: (String, Cover, PaperTemplate) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var color by remember { mutableStateOf(existing?.cover?.color ?: CoverColors.first()) }
    var style by remember { mutableStateOf(existing?.cover?.style ?: CoverStyle.PLAIN) }
    var paper by remember { mutableStateOf(existing?.paper ?: defaults.paper) }
    val confirm = { onDone(title, Cover(color, style), paper) }
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New notebook" else "Edit notebook") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CoverArt(Cover(color, style), title.ifBlank { "Untitled" }, Modifier.width(90.dp).aspectRatio(0.75f))
                    OutlinedTextField(
                        title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.testTag("title-field"),
                        // On a landscape tablet the keyboard can cover the dialog buttons; "Done" confirms directly.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                    )
                }
                Text("Cover", style = MaterialTheme.typography.labelLarge)
                ColorRow(CoverColors.take(5), color) { color = it }
                ColorRow(CoverColors.drop(5), color) { color = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverStyle.entries.forEach { s -> FilterChip(s == style, { style = s }, label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                }
                if (existing == null) {
                    Text("Paper", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaperTemplate.entries.forEach { p -> FilterChip(p == paper, { paper = p }, label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                    }
                    Text(
                        "Page: ${defaults.describe()} · change the default in Settings",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirm() }, modifier = Modifier.testTag("create")) { Text(if (existing == null) "Create" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    GlassAlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SettingsDialog(current: NotebookDefaults, onDismiss: () -> Unit, onSave: (NotebookDefaults) -> Unit) {
    var size by remember { mutableStateOf(current.size) }
    var landscape by remember { mutableStateOf(current.landscape) }
    var paper by remember { mutableStateOf(current.paper) }
    var preview by remember { mutableStateOf(current.libraryPreview) }
    var glassPanel by remember { mutableStateOf(false) }
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val folio = androidx.compose.ui.platform.LocalContext.current.applicationContext as app.folio.notes.FolioApp
                app.folio.notes.sync.AutoSyncRow(folio.sync)
                androidx.compose.material3.HorizontalDivider()
                GlassSettingsRow { glassPanel = true }
                androidx.compose.material3.HorizontalDivider()
                Text("Default page size for new notebooks", style = MaterialTheme.typography.labelLarge)
                PageSizeChoice.entries.forEach { c ->
                    FilterChip(c == size, { size = c }, label = { Text(c.label) }, modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!landscape, { landscape = false }, label = { Text("Portrait") })
                    FilterChip(landscape, { landscape = true }, label = { Text("Landscape") })
                }
                Text("Library shows", style = MaterialTheme.typography.labelLarge)
                LibraryPreview.entries.forEach { p ->
                    FilterChip(p == preview, { preview = p }, label = { Text(p.label) }, modifier = Modifier.fillMaxWidth())
                }
                Text("Default paper", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperTemplate.entries.forEach { p -> FilterChip(p == paper, { paper = p }, label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Existing notebooks keep their size. Auto shapes the page like this screen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(NotebookDefaults(size, landscape, paper, preview)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (glassPanel) GlassSettingsPanel(onDismiss = { glassPanel = false })
}

/** Settings row that opens Glass & toolbar (how glass looks and moves, and the editor toolbar). */
@Composable
private fun GlassSettingsRow(onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(vertical = 4.dp).testTag("glass-settings"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Glass & toolbar…", style = MaterialTheme.typography.titleSmall)
            Text("How glass looks and moves, and the editor toolbar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
