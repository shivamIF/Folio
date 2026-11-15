package app.folio.notes.editor

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.folio.notes.editor.oneui.ColorMath
import app.folio.notes.editor.oneui.FIcon
import app.folio.notes.editor.oneui.FolioIcon
import app.folio.notes.editor.oneui.IconAction
import app.folio.notes.editor.oneui.LocalOneUi
import app.folio.notes.editor.oneui.OneUiDialog
import app.folio.notes.editor.oneui.OneUiType
import app.folio.notes.editor.oneui.StepSlider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.delay
import java.io.File

/**
 * Wires the Insert (+) menu (teardown §18) to the system pickers, the camera, the microphone,
 * ML Kit's document scanner and Folio's own dialogs. Everything lands on the page in view.
 */
class InsertHost(
    val actions: InsertActions,
    /** True while the voice recorder bar is showing. */
    val recording: Boolean,
    val tableDialog: Boolean,
    val drawing: Boolean,
)

@Composable
fun rememberInsertHost(vm: EditorViewModel, canvas: () -> InkCanvasView?, onImage: () -> Unit): InsertHost {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var tableDialog by remember { mutableStateOf(false) }
    var drawing by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    fun area() = canvas()?.visiblePageArea()

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        val a = area()
        if (ok && f != null && a != null) vm.insertImage(Uri.fromFile(f), a.first, a.second)
        else f?.delete()
    }
    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.insertPdf(uri) }
    val openAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val a = area()
        if (uri != null && a != null) vm.insertAudio(uri, a.first, a.second, displayName(context, uri) ?: "Audio", 0)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recording = true else vm.reportError("Folio needs the microphone to record. You can allow it in Settings > Apps > Folio > Permissions.")
    }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r: ActivityResult ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val result = GmsDocumentScanningResult.fromActivityResultIntent(r.data) ?: return@rememberLauncherForActivityResult
        val a = area() ?: return@rememberLauncherForActivityResult
        // Each scanned page is placed as a picture, one under another.
        result.pages?.forEachIndexed { i, page ->
            val shifted = app.folio.core.model.Rect(a.second.left, a.second.top + i * 60f, a.second.right, a.second.bottom + i * 60f)
            vm.insertImage(page.imageUri, a.first, shifted)
        }
    }
    val actions = InsertActions(
        image = onImage,
        camera = {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "photo-${System.currentTimeMillis()}.jpg")
            cameraFile = f
            try {
                takePicture.launch(FileProvider.getUriForFile(context, "${context.packageName}.exports", f))
            } catch (e: android.content.ActivityNotFoundException) {
                vm.reportError("No camera app is available.")
            }
        },
        pdf = { openPdf.launch(arrayOf("application/pdf")) },
        voice = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recording = true
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
        audioFile = { openAudio.launch(arrayOf("audio/*")) },
        scan = {
            val activity = context as? Activity
            if (activity == null) return@InsertActions
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true).setPageLimit(10)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                .addOnSuccessListener { sender -> scanner.launch(IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener { e -> vm.reportError("Document scan isn't available on this device: ${e.message}") }
        },
        drawing = { drawing = true },
        sticky = { area()?.let { (pid, r) -> vm.insertSticky(pid, r) } },
        table = { tableDialog = true },
    )

    if (recording) VoiceRecorderBar(
        onDone = { file, ms ->
            recording = false
            val a = area()
            if (file != null && a != null) vm.insertAudio(Uri.fromFile(file), a.first, a.second, "Voice recording ${recordingLabel()}", ms, deleteSource = true)
            else file?.delete()
        },
    )
    if (tableDialog) TableDialog(onDismiss = { tableDialog = false }) { rows, cols -> area()?.let { (pid, r) -> vm.insertTable(pid, r, rows, cols) } }
    if (drawing) DrawingScreen(
        onDone = { file ->
            drawing = false
            val a = area()
            if (file != null && a != null) vm.insertImage(Uri.fromFile(file), a.first, a.second)
        },
    )
    AudioPlayback(vm)
    return InsertHost(actions, recording, tableDialog, drawing)
}

private fun recordingLabel(): String = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
    }
}.getOrNull()

// ------------------------------------------------------------------ voice recording

/**
 * A floating recorder: a red dot and the elapsed time while recording (AAC in M4A), Stop to
 * insert it, Cancel to drop it.
 */
@Composable
private fun VoiceRecorderBar(onDone: (File?, Long) -> Unit) {
    val c = LocalOneUi.current
    val context = LocalContext.current
    val file = remember { File(File(context.cacheDir, "recordings").apply { mkdirs() }, "rec-${System.currentTimeMillis()}.m4a") }
    var started by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    DisposableEffect(Unit) {
        val r = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(96_000)
            setOutputFile(file.path)
        }
        try {
            r.prepare(); r.start()
            recorder = r
            started = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            r.release(); onDone(null, 0)
        }
        onDispose { runCatching { recorder?.release() } }
    }
    LaunchedEffect(started) { while (started > 0) { elapsed = SystemClock.elapsedRealtime() - started; delay(250) } }
    fun stop(keep: Boolean) {
        val r = recorder ?: return
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        onDone(if (keep && ok) file else null.also { file.delete() }, elapsed)
    }
    androidx.compose.ui.window.Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -120)) {
        Row(
            Modifier.shadow(10.dp, RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp)).background(c.popup).padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("voice-recorder"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(start = 8.dp).size(12.dp).clip(CircleShape).background(Color(0xFFE5392F)))
            val s = elapsed / 1000
            Text("%d:%02d".format(s / 60, s % 60), style = OneUiType.bodyStrong, color = c.text, modifier = Modifier.padding(horizontal = 12.dp).width(52.dp))
            IconAction(FIcon.CLOSE, "Cancel recording", { stop(false) })
            IconAction(FIcon.STOP, "Stop and insert recording", { stop(true) }, tint = Color(0xFFE5392F))
        }
    }
}

/** Plays the recording whose chip was tapped; tapping it again pauses. */
@Composable
private fun AudioPlayback(vm: EditorViewModel) {
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    val playingId = remember { arrayOfNulls<String>(1) }
    DisposableEffect(vm) {
        vm.objectTapHandler = ObjectTapHandler { _, audio ->
            val p = player[0]
            if (p != null && playingId[0] == audio.id) {
                if (p.isPlaying) { p.pause(); vm.setPlaying(null) } else { p.start(); vm.setPlaying(audio.id) }
                return@ObjectTapHandler
            }
            p?.release()
            player[0] = null
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(vm.audioFile(audio.attachmentId).path)
                    setOnCompletionListener { vm.setPlaying(null) }
                    prepare(); start()
                }
                player[0] = mp; playingId[0] = audio.id
                vm.setPlaying(audio.id)
            } catch (e: Exception) {
                vm.reportError("The recording can't be played: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        onDispose {
            vm.objectTapHandler = null
            player[0]?.release(); player[0] = null
            vm.setPlaying(null)
        }
    }
}

// ------------------------------------------------------------------ tables

/** "Table properties": rows × columns (default 2 × 3), then Done. */
@Composable
private fun TableDialog(onDismiss: () -> Unit, onDone: (Int, Int) -> Unit) {
    var rows by remember { mutableStateOf(2) }
    var cols by remember { mutableStateOf(3) }
    OneUiDialog(onDismiss, "Table properties", confirmLabel = "Done", onConfirm = { onDone(rows, cols) }) {
        Text("Rows", style = OneUiType.label, color = LocalOneUi.current.textSoft, modifier = Modifier.padding(start = 22.dp, top = 8.dp))
        StepSlider(rows, 1..10, { rows = it }, "Rows")
        Text("Columns", style = OneUiType.label, color = LocalOneUi.current.textSoft, modifier = Modifier.padding(start = 22.dp))
        StepSlider(cols, 1..10, { cols = it }, "Columns")
    }
}

/** The table cell being typed, placed over its cell at the current zoom. Enter moves to the next cell. */
@Composable
fun CellField(vm: EditorViewModel, edit: CellEdit, canvas: InkCanvasView, @Suppress("UNUSED_PARAMETER") viewportTick: Int) {
    val c = LocalOneUi.current
    val density = LocalDensity.current
    val t = vm.tableOf(edit.pageId, edit.tableId) ?: return
    val scale = canvas.pageScale
    val (ox, oy) = canvas.pageOrigin(edit.pageId) ?: return
    val cw = t.width / t.cols; val rh = t.height / t.rows
    val focus = remember(edit.tableId, edit.row, edit.col) { FocusRequester() }
    val shown = if (canvas.pageLook.dark) ColorMath.adaptForDark(t.color) else t.color
    // The field keeps its own text (a round trip through the view model per key drops fast typing).
    var text by remember(edit.tableId, edit.row, edit.col) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(edit.text, androidx.compose.ui.text.TextRange(edit.text.length))) }
    BasicTextField(
        text, { v ->
            // A hardware Tab moves on to the next cell rather than typing a tab.
            if ('\t' in v.text) { vm.updateCellText(v.text.replace("\t", "")); vm.commitCell(next = true) }
            else { text = v; vm.updateCellText(v.text) }
        },
        textStyle = TextStyle(color = Color(shown.toInt()), fontSize = with(density) { (t.fontSize * scale).toSp() }),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { vm.commitCell(next = true) }),
        modifier = Modifier
            .offset { IntOffset((ox + (t.x + edit.col * cw) * scale).toInt(), (oy + (t.y + edit.row * rh) * scale).toInt()) }
            .size(with(density) { (cw * scale).toDp() }, with(density) { (rh * scale).toDp() })
            .background(Color(0x223E91FF)).padding(with(density) { (t.fontSize * 0.35f * scale).toDp() })
            .focusRequester(focus).testTag("table-cell-field").semantics { contentDescription = "Table cell ${edit.row + 1}, ${edit.col + 1}" },
    )
    LaunchedEffect(edit.tableId, edit.row, edit.col) { focus.requestFocus() }
}

@Composable
fun CellEditBar(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val c = LocalOneUi.current
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.popup).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Table", style = OneUiType.label, color = c.text, modifier = Modifier.padding(horizontal = 10.dp))
        IconAction(FIcon.CHEVRON_RIGHT, "Next cell", { vm.commitCell(next = true) })
        IconAction(FIcon.CHECK, "Done typing", { vm.commitCell() })
    }
}

// ------------------------------------------------------------------ sticky notes

/** Extra actions on the selection menu when one sticky note is selected: colour, minimise. */
@Composable
fun StickyActions(vm: EditorViewModel, current: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        EditorViewModel.STICKY_COLORS.forEach { col ->
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable { vm.updateSticky { it.copy(color = col) } }
                    .semantics { contentDescription = "Sticky note colour" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (col == current) 22.dp else 18.dp).clip(CircleShape).background(Color(col)))
            }
        }
        IconAction(FIcon.SHRINK, "Minimise sticky note", { vm.selectedSticky()?.let { n -> vm.state.value.selectionPage?.let { vm.collapseSticky(it, n.id) } } }, tint = Color.White, size = 40.dp, iconSize = 20.dp)
    }
}
