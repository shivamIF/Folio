package app.folio.notes.editor

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.folio.notes.export.PageExportFormat
import app.folio.notes.export.PageExportSpec
import kotlin.math.roundToInt

/** Asks before deleting a page; Undo is offered afterwards as well. */
@Composable
internal fun DeletePageDialog(number: Int, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete page $number?") },
        text = { Text("The page and everything on it will be removed from this notebook. You can undo this right after.") },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks a new position (1-based in the UI) for page [number]; [onMove] gets the 0-based index. */
@Composable
internal fun MovePageDialog(number: Int, count: Int, onMove: (toIndex: Int) -> Unit, onDismiss: () -> Unit) {
    var target by rememberSaveable { mutableIntStateOf(number) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move page $number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { target = (target - 1).coerceAtLeast(1) }, enabled = target > 1) {
                        Icon(Icons.Outlined.Remove, contentDescription = "Earlier")
                    }
                    Text("Position $target of $count", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
                    IconButton(onClick = { target = (target + 1).coerceAtMost(count) }, enabled = target < count) {
                        Icon(Icons.Outlined.Add, contentDescription = "Later")
                    }
                }
                if (count > 2) Slider(
                    value = target.toFloat(), onValueChange = { target = it.roundToInt().coerceIn(1, count) },
                    valueRange = 1f..count.toFloat(), steps = (count - 2).coerceAtLeast(0),
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { target = 1 }, enabled = target != 1) { Text("To the start") }
                    TextButton(onClick = { target = count }, enabled = target != count) { Text("To the end") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onMove(target - 1) }, enabled = target != number) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Export options for one page: format, resolution for images, then Save to device (system
 * file picker) or Share (share sheet). [pageSize] (page units) shows the image size.
 */
@Composable
internal fun ExportPageDialog(
    number: Int, pageSize: Pair<Float, Float>?,
    onSave: (PageExportSpec) -> Unit, onShare: (PageExportSpec) -> Unit, onDismiss: () -> Unit,
) {
    var format by rememberSaveable { mutableStateOf(PageExportFormat.PDF) }
    var dpi by rememberSaveable { mutableIntStateOf(PageExportSpec.DEFAULT_DPI) }
    val spec = PageExportSpec(format, dpi)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export page $number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.selectableGroup()) {
                    PageExportFormat.entries.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().selectable(f == format, role = Role.RadioButton, onClick = { format = f }).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(f == format, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(f.label)
                                Text(
                                    when (f) {
                                        PageExportFormat.PDF -> "One-page PDF at the page's real size; ink stays sharp"
                                        PageExportFormat.PNG -> "Image, lossless"
                                        PageExportFormat.JPEG -> "Image, smaller file"
                                    },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (format.raster) {
                    Text("Resolution", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PageExportSpec.DPI_CHOICES.forEach { d ->
                            FilterChip(selected = d == dpi, onClick = { dpi = d }, label = { Text("$d dpi") })
                        }
                    }
                    pageSize?.let { (w, h) ->
                        val (pw, ph) = PageExportSpec.rasterSize(w, h, dpi)
                        Text("$pw × $ph pixels", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "Only this page is exported. The notebook isn't changed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onDismiss(); onShare(spec) }) { Text("Share") }
                TextButton(onClick = { onDismiss(); onSave(spec) }) { Text("Save to device") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The system "save as" picker for a file of any type: input is (MIME type, suggested name).
 * The user picks the folder and name; the picker resolves name clashes. Null if cancelled.
 */
internal class CreateDocumentAs : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.first)
            .putExtra(Intent.EXTRA_TITLE, input.second)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Opens the share sheet for an exported page or notebook. */
internal fun sharePage(context: Context, e: PageEvent.Share) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = e.mime
        putExtra(Intent.EXTRA_STREAM, e.uri)
        putExtra(Intent.EXTRA_TITLE, e.fileName)
        clipData = ClipData.newRawUri(e.fileName, e.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share"))
}
