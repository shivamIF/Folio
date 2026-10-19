package app.folio.notes

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import app.folio.notes.editor.InkCanvasView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.folio.notes.editor.EditorScreen
import app.folio.notes.library.LibraryScreen
import app.folio.notes.ui.FolioTheme

class MainActivity : ComponentActivity() {
    private val debugInput by lazy { (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }

    /** True while a side-button pen gesture is being routed straight to the canvas. */
    private var routingToCanvas = false

    /** PDF handed to us by another app ("Open with" / "Share"), waiting to be imported. */
    private val importRequest = androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)

    private fun takeImport(intent: android.content.Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else -> null
        }
        if (uri != null) importRequest.value = uri
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Debug builds: `am start -f 0x20000000 -a app.folio.notes.debug.PERF_ZOOM` runs the zoom benchmark.
        if (debugInput && intent.action == "app.folio.notes.debug.PERF_ZOOM") { InkCanvasView.attached?.get()?.runPerfScript(intent.getStringExtra("skip")); return }
        takeImport(intent)
    }

    /**
     * On the Galaxy Tab S6 Lite the system delivers S Pen touches made with the
     * side button held to this window, but they never reach the canvas inside
     * the Compose hierarchy. Route those gestures to the canvas directly.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val masked = ev.actionMasked
        if (debugInput && masked != MotionEvent.ACTION_MOVE && masked != 213) {
            Log.d("FolioInput", "window action=$masked tool=${ev.getToolType(0)} buttons=${ev.buttonState} routing=$routingToCanvas")
        }
        val canvas = InkCanvasView.attached?.get()
        if (masked == MotionEvent.ACTION_DOWN || masked == 211) {
            routingToCanvas = canvas != null && InkCanvasView.isSideButtonGesture(ev) && canvas.containsWindowPoint(ev.x, ev.y)
        }
        if (routingToCanvas && canvas != null) {
            canvas.dispatchFromWindow(ev)
            if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL || masked == 212 || masked == 214) {
                routingToCanvas = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeImport(intent)
        setContent {
            FolioTheme {
                // Saved across rotation and process death, so the open notebook is restored.
                var openNotebook by rememberSaveable { mutableStateOfNullableString() }
                val id = openNotebook
                val pendingImport = importRequest.value
                // An incoming PDF always lands in the library, which imports and then opens it.
                if (pendingImport != null && id != null) openNotebook = null
                if (id == null) LibraryScreen(
                    onOpenNotebook = { openNotebook = it },
                    importRequest = pendingImport,
                    onImportHandled = { importRequest.value = null },
                )
                else EditorScreen(notebookId = id, onClose = { openNotebook = null })
            }
        }
    }
}

private fun mutableStateOfNullableString() = androidx.compose.runtime.mutableStateOf<String?>(null)
