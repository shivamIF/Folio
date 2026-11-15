package app.folio.notes.editor

import app.folio.core.model.Rect
import app.folio.core.model.Stroke
import app.folio.core.model.TextBox
import app.folio.core.model.ToolType
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea

/**
 * S Pen to text's pure rules (unit-tested): which strokes are recognised together, and the text
 * box the words become (placed where they were written, sized to the handwriting).
 */
object SpenText {
    /** Strokes that turn into text: pens, not highlighter or tape. */
    fun recognisable(s: Stroke) = s.tool != ToolType.HIGHLIGHTER && s.tool != ToolType.TAPE && s.size > 0

    /** The box for [text] recognised from strokes covering [ink], on a page [pageW] wide. */
    fun boxFor(text: String, ink: Rect, pageW: Float, color: Long): TextBox {
        // Handwriting is usually written about 1.4× the size of the type that reads the same.
        val font = (ink.height / 1.4f).coerceIn(MIN_FONT, MAX_FONT)
        val w = maxOf(ink.width + font, font * 4f).coerceAtMost(pageW - ink.left - 8f).coerceAtLeast(font * 2f)
        return TextBox(x = ink.left, y = ink.top + (ink.height - font * 1.3f) / 2f, width = w, height = font * 1.3f, text = text, fontSize = font, color = color)
    }

    /** Union of the strokes' bounds, or null. */
    fun bounds(strokes: List<Stroke>): Rect? = strokes.map { it.bounds() }.reduceOrNull(Rect::union)

    /**
     * Whether new [ink] carries on the line whose handwriting so far covered [last] (read into a
     * box with font [font]): at least half of the shorter of the two overlaps vertically, and it
     * starts after that writing (a little overlap allowed), at most [MAX_GAP] em past its end.
     * Slow writing is then still read into one box instead of a box per pause.
     */
    fun continues(last: Rect, ink: Rect, font: Float): Boolean {
        val overlap = minOf(last.bottom, ink.bottom) - maxOf(last.top, ink.top)
        if (overlap < 0.5f * minOf(last.height, ink.height)) return false
        val gap = ink.left - last.right
        return gap >= -font && gap <= MAX_GAP * font
    }

    /** What joins the earlier words and the new ones: a space for a gap between words, nothing inside a word. */
    fun joiner(last: Rect, ink: Rect, font: Float): String = if (ink.left - last.right > WORD_GAP * font) " " else ""

    /** [prev] with [more] text read from [ink] appended, widened to cover the new writing. */
    fun appended(prev: TextBox, more: String, ink: Rect, pageW: Float): TextBox {
        val w = maxOf(prev.width, (ink.right - prev.x + prev.fontSize).coerceAtMost(pageW - prev.x - 8f))
        return prev.copy(text = prev.text + more, width = w)
    }

    const val MIN_FONT = 16f
    const val MAX_FONT = 72f
    /** Recognition runs once the pen has rested this long. */
    const val PAUSE_MS = 900L
    /** New writing further than this many em past the earlier words starts a new box. */
    const val MAX_GAP = 3f
    /** A gap wider than this many em is a space between words. */
    const val WORD_GAP = 0.5f
}

/**
 * ML Kit Digital Ink Recognition, on device (approved by the user, 2026-10-20): one model per
 * language, downloaded once over the network, then recognition runs offline.
 * All calls block; run them off the main thread.
 */
class HandwritingRecognizer(val languageTag: String) {
    private val model: DigitalInkRecognitionModel? = runCatching {
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
            ?: DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
        id?.let { DigitalInkRecognitionModel.builder(it).build() }
    }.getOrNull()
    private var recognizer: DigitalInkRecognizer? = null
    private val manager = RemoteModelManager.getInstance()

    val supported: Boolean get() = model != null

    fun isDownloaded(): Boolean = model?.let { Tasks.await(manager.isModelDownloaded(it)) } == true

    /** Downloads the language model (Wi-Fi or mobile data). Throws on failure. */
    fun download() {
        val m = model ?: error("handwriting in $languageTag isn't supported")
        Tasks.await(manager.download(m, DownloadConditions.Builder().build()))
    }

    /** The best reading of [strokes] (page units; times from each stroke's start), or null. */
    fun recognize(strokes: List<Stroke>, preContext: String = ""): String? {
        val m = model ?: return null
        val r = recognizer ?: DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build()).also { recognizer = it }
        val ink = Ink.builder()
        for (s in strokes) {
            val b = Ink.Stroke.builder()
            for (i in 0 until s.size) b.addPoint(Ink.Point.create(s.x[i], s.y[i], s.startedAt + s.t[i]))
            ink.addStroke(b.build())
        }
        val area = SpenText.bounds(strokes)
        val ctx = RecognitionContext.builder().setPreContext(preContext.takeLast(20))
            .apply { if (area != null) setWritingArea(WritingArea(area.width * 1.4f, area.height * 1.6f)) }.build()
        val result = Tasks.await(r.recognize(ink.build(), ctx))
        return result.candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() }
    }

    fun close() { recognizer?.close(); recognizer = null }

    companion object {
        /** Languages offered in the settings (tags ML Kit knows). */
        val LANGUAGES = listOf(
            "en-US" to "English (US)", "en-GB" to "English (UK)", "en-IN" to "English (India)", "hi-IN" to "हिन्दी",
            "de-DE" to "Deutsch", "fr-FR" to "Français", "es-ES" to "Español", "it-IT" to "Italiano", "pt-BR" to "Português",
            "ja-JP" to "日本語", "ko-KR" to "한국어", "zh-CN" to "中文",
        )

        fun defaultTag(): String {
            val l = java.util.Locale.getDefault()
            val tag = "${l.language}-${l.country}"
            return LANGUAGES.firstOrNull { it.first == tag }?.first ?: LANGUAGES.firstOrNull { it.first.startsWith(l.language) }?.first ?: "en-US"
        }
    }
}
