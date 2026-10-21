package app.folio.notes.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.folio.notes.editor.oneui.FIcon
import app.folio.notes.editor.oneui.IconAction
import app.folio.notes.editor.oneui.LocalOneUi
import app.folio.notes.editor.oneui.MenuCard
import app.folio.notes.editor.oneui.MenuEntry
import app.folio.notes.editor.oneui.OneUiType
import app.folio.notes.editor.oneui.PopupPlacement

/**
 * S Pen to text's floating bar (teardown §15): Settings (handwriting language), Keyboard, Space,
 * Backspace and Enter, the last four acting on the text box the handwriting just became.
 * Shown while S Pen to text is on.
 */
@Composable
fun SpenToTextBar(vm: EditorViewModel, state: EditorUiState, modifier: Modifier = Modifier) {
    if (!state.spenToText || state.reading) return
    val c = LocalOneUi.current
    var settings by remember { mutableStateOf(false) }
    val hasBox = state.spenBox != null
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.shadow(8.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(c.popup).padding(horizontal = 6.dp)
                .testTag("spen-bar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconAction(FIcon.GEAR, "Handwriting language", { settings = true })
                if (settings) MenuCard(
                    listOf(HandwritingRecognizer.LANGUAGES.map { (tag, name) ->
                        MenuEntry(name, trailing = if (tag == state.spenLanguage) "✓" else null) { vm.setSpenLanguage(tag) }
                    }),
                    onDismiss = { settings = false }, width = 240.dp, placement = PopupPlacement.BELOW_START, gap = 6.dp,
                )
            }
            IconAction(FIcon.TEXT_MODE, "Keyboard", vm::spenKeyboard, enabled = hasBox)
            IconAction(FIcon.SPACE, "Space", { vm.spenKey(" ") }, enabled = hasBox)
            IconAction(FIcon.BACKSPACE, "Backspace", { vm.spenKey("\b") }, enabled = hasBox)
            IconAction(FIcon.ENTER, "Enter", { vm.spenKey("\n") }, enabled = hasBox)
        }
        val status = state.spenStatus
        if (status != null) Text(
            status, style = OneUiType.small, color = c.text,
            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.popup).padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("spen-status"),
        )
    }
}
