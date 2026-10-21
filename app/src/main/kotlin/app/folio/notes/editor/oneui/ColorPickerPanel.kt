package app.folio.notes.editor.oneui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The full colour picker (teardown §9): Swatches (a generated 13 × 13 grid) or Spectrum (hue
 * across, saturation down, plus a brightness slider), an old | new preview, editable Hex and
 * R/G/B values, five recent colours and an eyedropper, then Cancel | Done.
 */
@Composable
fun ColorPickerPanel(
    initial: Long,
    recent: List<Long>,
    onDone: (Long) -> Unit,
    onCancel: () -> Unit,
    onEyedropper: (() -> Unit)?,
    display: (Long) -> Long = { it },
    anchorBottomPx: Int? = null,
) {
    val c = LocalOneUi.current
    var tab by rememberSaveable { mutableStateOf(0) }
    // Hue/saturation/value are the source of truth, so grey colours keep their hue while dragging.
    var hsv by remember { mutableStateOf(ColorMath.toHsv(initial).toList()) }
    val color = ColorMath.fromHsv(hsv[0], hsv[1], hsv[2])
    fun setColor(col: Long) { hsv = ColorMath.toHsv(col).toList() }
    OneUiPopup(onCancel, width = 360.dp, gap = 8.dp, anchorBottomPx = anchorBottomPx) {
        Spacer(Modifier.height(18.dp))
        PillTabs(listOf("Swatches", "Spectrum"), tab, { tab = it }, Modifier.padding(horizontal = 22.dp))
        Spacer(Modifier.height(18.dp))
        if (tab == 0) SwatchGrid(color, display) { setColor(it) }
        else SpectrumField(hsv, display, onChange = { hsv = it })
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            // Old | new.
            Canvas(Modifier.size(width = 56.dp, height = 36.dp).semantics { contentDescription = "Current and new colour" }) {
                val clip = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(8.dp.toPx())))
                }
                clipPath(clip) {
                    drawRect(Color(display(initial)), size = Size(size.width / 2, size.height))
                    drawRect(Color(display(color)), Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
                }
                drawLine(Color(0x55000000), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
            }
            Spacer(Modifier.width(10.dp))
            ValueField("Hex", ColorMath.toHex(color), 1.6f, KeyboardType.Ascii) { t -> ColorMath.parseHex(t)?.let { setColor(it or 0xFF000000) } }
            ValueField("Red", ColorMath.red(color).toString(), 1f, KeyboardType.Number) { t -> t.toIntOrNull()?.let { setColor(ColorMath.rgb(it, ColorMath.green(color), ColorMath.blue(color))) } }
            ValueField("Green", ColorMath.green(color).toString(), 1f, KeyboardType.Number) { t -> t.toIntOrNull()?.let { setColor(ColorMath.rgb(ColorMath.red(color), it, ColorMath.blue(color))) } }
            ValueField("Blue", ColorMath.blue(color).toString(), 1f, KeyboardType.Number) { t -> t.toIntOrNull()?.let { setColor(ColorMath.rgb(ColorMath.red(color), ColorMath.green(color), it)) } }
        }
        Spacer(Modifier.height(14.dp))
        DottedDivider()
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            recent.take(5).forEach { col ->
                Box(
                    Modifier.size(46.dp).clip(CircleShape).clickable { setColor(col) }.semantics { contentDescription = "Recent colour ${ColorMath.toHex(col)}"; selected = col == color },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(42.dp)) {
                        drawCircle(Color(display(col)))
                        drawCircle(if (c.dark) Color(0x40FFFFFF) else Color(0x26000000), style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
            repeat(5 - recent.take(5).size) { Spacer(Modifier.size(46.dp)) }
            if (onEyedropper != null) IconAction(FIcon.EYEDROPPER, "Pick a colour from the page", onEyedropper, size = 46.dp)
        }
        Spacer(Modifier.height(6.dp))
        TextActions("Cancel" to onCancel, "Done" to { onDone(color) })
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SwatchGrid(current: Long, display: (Long) -> Long, onPick: (Long) -> Unit) {
    val grid = remember { ColorMath.swatchGrid() }
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.width((24 * 13).dp).height((24 * 13).dp).clip(RoundedCornerShape(6.dp))
                .semantics { contentDescription = "Colour swatches" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val d = awaitFirstDown()
                        fun pick(o: Offset) {
                            val col = (o.x / (size.width / 13f)).toInt().coerceIn(0, 12)
                            val row = (o.y / (size.height / 13f)).toInt().coerceIn(0, 12)
                            onPick(grid[row][col])
                        }
                        pick(d.position)
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            pick(ch.position); ch.consume()
                        }
                    }
                },
        ) {
            val cw = size.width / 13f; val ch = size.height / 13f
            var sel: Offset? = null
            for (r in 0 until 13) for (k in 0 until 13) {
                val v = grid[r][k]
                drawRect(Color(display(v)), Offset(k * cw, r * ch), Size(cw + 0.5f, ch + 0.5f))
                if (v == current) sel = Offset(k * cw, r * ch)
            }
            sel?.let {
                drawRect(Color.White, it, Size(cw, ch), style = Stroke(2.5.dp.toPx()))
                drawRect(Color(0x99000000), Offset(it.x - 1.dp.toPx(), it.y - 1.dp.toPx()), Size(cw + 2.dp.toPx(), ch + 2.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
        }
    }
}

@Composable
private fun SpectrumField(hsv: List<Float>, display: (Long) -> Long, onChange: (List<Float>) -> Unit) {
    val c = LocalOneUi.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1.28f).clip(RoundedCornerShape(6.dp))
                .semantics { contentDescription = "Colour spectrum: hue across, saturation down" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val d = awaitFirstDown()
                        fun at(o: Offset) {
                            val h = (o.x / size.width).coerceIn(0f, 1f) * 360f
                            val s = (o.y / size.height).coerceIn(0f, 1f)
                            onChange(listOf(h.coerceAtMost(359.9f), s, hsv[2].coerceAtLeast(0.05f)))
                        }
                        at(d.position)
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            at(ch.position); ch.consume()
                        }
                    }
                },
        ) {
            val hues = List(13) { Color(ColorMath.fromHsv(it * 30f, 1f, 1f)) }
            drawRect(Brush.horizontalGradient(hues))
            drawRect(Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0f))))
            val m = Offset(hsv[0] / 360f * size.width, hsv[1] * size.height)
            drawCircle(Color(display(ColorMath.fromHsv(hsv[0], hsv[1], 1f))), 12.dp.toPx(), m)
            drawCircle(Color.White, 12.dp.toPx(), m, style = Stroke(2.5.dp.toPx()))
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val full = Color(ColorMath.fromHsv(hsv[0], hsv[1], 1f))
            Canvas(
                Modifier.weight(1f).height(28.dp)
                    .semantics { contentDescription = "Brightness ${(hsv[2] * 100).toInt()} percent" }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val d = awaitFirstDown()
                            fun at(o: Offset) = onChange(listOf(hsv[0], hsv[1], (o.x / size.width).coerceIn(0f, 1f)))
                            at(d.position)
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                at(ch.position); ch.consume()
                            }
                        }
                    },
            ) {
                val h = 18.dp.toPx(); val top = (size.height - h) / 2
                drawRoundRect(Brush.horizontalGradient(listOf(Color.Black, full)), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
                val x = (hsv[2] * size.width).coerceIn(h / 2, size.width - h / 2)
                drawCircle(Color.White, 9.dp.toPx(), Offset(x, size.height / 2))
            }
            Spacer(Modifier.width(12.dp))
            var text by remember(hsv[2]) { mutableStateOf("${(hsv[2] * 100).toInt()}") }
            BasicTextField(
                text, { t -> text = t.filter(Char::isDigit).take(3); text.toIntOrNull()?.let { if (it in 0..100) onChange(listOf(hsv[0], hsv[1], it / 100f)) } },
                textStyle = TextStyle(color = c.text, fontSize = 16.sp, textAlign = TextAlign.End),
                singleLine = true, cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.width(40.dp).semantics { contentDescription = "Brightness percent" },
            )
            Text(" %", color = c.text, fontSize = 16.sp)
        }
    }
}

/** A labelled value that can be typed over; applied on Done or when focus leaves. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ValueField(label: String, value: String, weight: Float, type: KeyboardType, onCommit: (String) -> Unit) {
    val c = LocalOneUi.current
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) text = value }
    Column(Modifier.weight(weight), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = OneUiType.label, color = c.text)
        BasicTextField(
            text, { text = it.take(9) },
            textStyle = TextStyle(color = c.text, fontSize = 16.sp, textAlign = TextAlign.Center),
            singleLine = true, cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                .onFocusChanged { f -> if (focused && !f.isFocused) onCommit(text); focused = f.isFocused }
                .semantics { contentDescription = label },
        )
    }
}

/** Colour preview chip used in menus (e.g. sticky note colours). */
@Composable
fun ColorChip(color: Long, selected: Boolean, label: String, onClick: () -> Unit) {
    val c = LocalOneUi.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color(color))
            .border(if (selected) 2.5.dp else 1.dp, if (selected) c.accent else c.popupRim, CircleShape)
            .clickable(onClick = onClick).semantics { contentDescription = label; this.selected = selected },
    )
}
