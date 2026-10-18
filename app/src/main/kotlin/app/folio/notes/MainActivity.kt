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
        setContent {
            FolioTheme {
                // Saved across rotation and process death, so the open notebook is restored.
                var openNotebook by rememberSaveable { mutableStateOfNullableString() }
                val id = openNotebook
                if (id == null) LibraryScreen(onOpenNotebook = { openNotebook = it })
                else EditorScreen(notebookId = id, onClose = { openNotebook = null })
            }
        }
    }
}

private fun mutableStateOfNullableString() = androidx.compose.runtime.mutableStateOf<String?>(null)
