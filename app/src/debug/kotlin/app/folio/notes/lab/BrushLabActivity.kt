package app.folio.notes.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Brush Lab (debug builds only): draw once with the S Pen, then compare the same input
 * drawn by Folio's current engine (v1), the proposed engine (v2) and AndroidX Ink.
 * No launcher icon (the app's own UI stays as it is); start it with
 * `adb shell am start -n app.folio.notes/.lab.BrushLabActivity`.
 */
class BrushLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Hooks for screenshots and scripts: `--ez sample true --es preset fountain --ef zoom 6`.
        // `--es set tilt` starts with the tilt/overlap strokes instead.
        val sample = intent.getBooleanExtra("sample", false)
        val tiltSet = intent.getStringExtra("set") == "tilt"
        val presetId = intent.getStringExtra("preset")
        val zoom = intent.getFloatExtra("zoom", 1f)
        setContent { MaterialTheme { Surface { BrushLab(sample, tiltSet, presetId, zoom) } } }
    }
}

@Composable
private fun BrushLab(startWithSample: Boolean, tiltSet: Boolean, startPreset: String?, startZoom: Float) {
    val raw = remember { ArrayList<RawStroke>().apply { if (startWithSample) addAll(if (tiltSet) LabSamples.tiltSet() else LabSamples.all()) } }
    var version by remember { mutableIntStateOf(0) }
    var preset by remember { mutableStateOf(LAB_PRESETS.firstOrNull { it.id == startPreset } ?: LAB_PRESETS.first()) }
    var widthScale by remember { mutableFloatStateOf(1f) }
    var fitRequest by remember { mutableIntStateOf(0) }

    // Each capture is rebuilt for the chosen preset, so one recording replays through every tool.
    val strokes = remember(version, preset, widthScale) {
        raw.map { it.toStroke(preset.tool, preset.color, preset.width * widthScale) }
    }
    val viewport = remember { LabViewport() }
    val engines = remember { listOf(V1Engine(), V2Engine(), InkEngine()) }
    val panels = remember { arrayOfNulls<ComparePanel>(engines.size) }
    var zoomPending by remember { mutableStateOf(startZoom > 1f) }
    LaunchedEffect(version, fitRequest) {
        panels.firstOrNull()?.let { p ->
            p.post {
                p.fit()
                if (zoomPending) { viewport.zoomBy(startZoom, p.width / 2f, p.height / 2f); zoomPending = false }
            }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Brush Lab", style = MaterialTheme.typography.titleMedium)
            for (p in LAB_PRESETS) FilterChip(selected = p == preset, onClick = { preset = p }, label = { Text(p.label) })
            Text("Width ×%.1f".format(widthScale))
            Slider(value = widthScale, onValueChange = { widthScale = it }, valueRange = 0.5f..3f, modifier = Modifier.width(180.dp))
            OutlinedButton(onClick = { raw.addAll(LabSamples.all()); version++ }) { Text("Sample strokes") }
            OutlinedButton(onClick = { raw.addAll(LabSamples.tiltSet()); version++ }) { Text("Tilt + overlap") }
            OutlinedButton(onClick = { if (raw.isNotEmpty()) { raw.removeAt(raw.lastIndex); version++ } }) { Text("Undo") }
            OutlinedButton(onClick = { raw.clear(); version++ }) { Text("Clear") }
            OutlinedButton(onClick = { fitRequest++ }) { Text("Fit") }
        }
        Text(
            preset.note ?: "Write in the top pad. Below, the same input in each engine: drag to pan, pinch to zoom, double tap to fit.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        AndroidView(
            factory = { ctx -> CapturePad(ctx) { s -> raw += s; version++ } },
            update = { pad -> pad.preset = preset; pad.widthScale = widthScale; pad.strokes = strokes },
            modifier = Modifier.fillMaxWidth().weight(0.42f),
        )
        Row(Modifier.fillMaxWidth().weight(0.58f)) {
            engines.forEachIndexed { i, engine ->
                AndroidView(
                    factory = { ctx -> ComparePanel(ctx, engine, viewport).also { panels[i] = it } },
                    update = { panel -> panel.preset = preset; panel.strokes = strokes },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
    }
}
