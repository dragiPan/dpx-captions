package com.dpx.captions.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** AutoSubs presets store flags as 0/1 ints; keep them that way on disk but as Booleans in code. */
object IntBool : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IntBool", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeInt(if (value) 1 else 0)

    override fun deserialize(decoder: Decoder): Boolean {
        val primitive = (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive
        return primitive.booleanOrNull ?: ((primitive.intOrNull ?: 0) != 0)
    }
}

@Serializable
data class Word(
    val text: String,
    val start: Double,
    val end: Double,
    val probability: Double = 1.0,
)

@Serializable
data class CaptionCard(
    val lines: List<List<Word>>,
    val start: Double,
    val end: Double,
) {
    val words: List<Word> get() = lines.flatten()
    val text: String get() = words.joinToString(" ") { it.text }
}

data class Rgb(val r: Double, val g: Double, val b: Double)

/**
 * Field names deliberately mirror the AutoSubs macroSettings block (and the desktop app's schema),
 * so presets exported from either load here unchanged.
 */
@Serializable
data class AnimationStyle(
    // Entrance animation
    @SerialName("AnimationLength") val animationLength: Double = 0.2,
    @SerialName("AnimationLevel") val animationLevel: Int = 0,
    @SerialName("AnimationMode") val animationMode: Int = 0,
    @SerialName("PopInEnabled") @Serializable(with = IntBool::class) val popInEnabled: Boolean = false,
    @SerialName("SlideUpEnabled") @Serializable(with = IntBool::class) val slideUpEnabled: Boolean = false,
    @SerialName("FadeEnabled") @Serializable(with = IntBool::class) val fadeEnabled: Boolean = false,

    // Font
    @SerialName("Font") val font: String = "Open Sans Condensed",
    @SerialName("Style") val styleName: String = "Bold",
    @SerialName("Bold") @Serializable(with = IntBool::class) val bold: Boolean = true,
    // 7% of the frame height keeps ~17 characters of a condensed face inside a 9:16 frame; AutoSubs presets
    // ship 0.1 because Fusion measures text size differently, and they still load at their own value.
    @SerialName("TextSize") val textSize: Double = 0.07,
    @SerialName("TextPosition") val textPosition: List<Double> = listOf(0.5, 0.2, 0.0),

    // Fill (base colour, before the word is spoken)
    @SerialName("FillEnabled") @Serializable(with = IntBool::class) val fillEnabled: Boolean = true,
    @SerialName("FillColorRed") val fillColorRed: Double = 1.0,
    @SerialName("FillColorGreen") val fillColorGreen: Double = 1.0,
    @SerialName("FillColorBlue") val fillColorBlue: Double = 1.0,

    // Highlight (spoken word)
    @SerialName("HighlightEnabled") @Serializable(with = IntBool::class) val highlightEnabled: Boolean = true,
    @SerialName("HighlightStyle") val highlightStyle: Int = 0,
    @SerialName("HighlightPopScale") val highlightPopScale: Double = 1.1,
    @SerialName("HighlightColorRed") val highlightColorRed: Double = 1.0,
    @SerialName("HighlightColorGreen") val highlightColorGreen: Double = 1.0,
    @SerialName("HighlightColorBlue") val highlightColorBlue: Double = 0.164,
    @SerialName("HighlightExtendHorizontal") val highlightExtendHorizontal: Double = -0.04,
    @SerialName("HighlightExtendVertical") val highlightExtendVertical: Double = 0.0,
    @SerialName("HighlightRound") val highlightRound: Double = 0.0,

    // Outline
    @SerialName("OutlineEnabled") @Serializable(with = IntBool::class) val outlineEnabled: Boolean = true,
    @SerialName("OutlineThickness") val outlineThickness: Double = 0.08,
    @SerialName("OutlineColorRed") val outlineColorRed: Double = 0.0,
    @SerialName("OutlineColorGreen") val outlineColorGreen: Double = 0.0,
    @SerialName("OutlineColorBlue") val outlineColorBlue: Double = 0.0,

    // Shadow
    @SerialName("ShadowEnabled") @Serializable(with = IntBool::class) val shadowEnabled: Boolean = true,
    @SerialName("ShadowOffset") val shadowOffset: Double = 0.03,
    @SerialName("ShadowColorRed") val shadowColorRed: Double = 0.0,
    @SerialName("ShadowColorGreen") val shadowColorGreen: Double = 0.0,
    @SerialName("ShadowColorBlue") val shadowColorBlue: Double = 0.0,
) {
    val fill: Rgb get() = Rgb(fillColorRed, fillColorGreen, fillColorBlue)
    val highlight: Rgb get() = Rgb(highlightColorRed, highlightColorGreen, highlightColorBlue)
    val outline: Rgb get() = Rgb(outlineColorRed, outlineColorGreen, outlineColorBlue)
    val shadow: Rgb get() = Rgb(shadowColorRed, shadowColorGreen, shadowColorBlue)

    val positionX: Double get() = textPosition.getOrElse(0) { 0.5 }
    val positionY: Double get() = textPosition.getOrElse(1) { 0.2 }

    fun withFill(c: Rgb) = copy(fillColorRed = c.r, fillColorGreen = c.g, fillColorBlue = c.b)
    fun withHighlight(c: Rgb) = copy(highlightColorRed = c.r, highlightColorGreen = c.g, highlightColorBlue = c.b)
    fun withOutline(c: Rgb) = copy(outlineColorRed = c.r, outlineColorGreen = c.g, outlineColorBlue = c.b)
    fun withShadow(c: Rgb) = copy(shadowColorRed = c.r, shadowColorGreen = c.g, shadowColorBlue = c.b)
    fun withPosition(x: Double, y: Double) = copy(textPosition = listOf(x, y, 0.0))
}

@Serializable
enum class Density {
    @SerialName("single_word") SINGLE_WORD,
    @SerialName("standard") STANDARD,
    @SerialName("more") MORE,
    @SerialName("custom") CUSTOM,
}

@Serializable
enum class TextCase {
    @SerialName("uppercase") UPPERCASE,
    @SerialName("lowercase") LOWERCASE,
    @SerialName("title_case") TITLE_CASE,
    @SerialName("normal") NORMAL,
}

@Serializable
data class CaptionFormatting(
    val density: Density = Density.CUSTOM,
    @SerialName("max_chars_per_line") val maxCharsPerLine: Int = 17,
    @SerialName("line_count") val lineCount: Int = 1,
    @SerialName("text_case") val textCase: TextCase = TextCase.UPPERCASE,
    @SerialName("remove_punctuation") val removePunctuation: Boolean = false,
    @SerialName("censor_words") val censorWords: Boolean = false,
    @SerialName("censor_word_list") val censorWordList: List<String> = emptyList(),
    @SerialName("vocabulary_context") val vocabularyContext: String = "",
)

@Serializable
enum class Aspect(val label: String, val ratio: Double?) {
    @SerialName("auto") AUTO("Auto", null),
    @SerialName("9:16") VERTICAL_9_16("9:16", 9.0 / 16.0),
    @SerialName("16:9") LANDSCAPE_16_9("16:9", 16.0 / 9.0),
    @SerialName("1:1") SQUARE_1_1("1:1", 1.0),
    @SerialName("4:5") PORTRAIT_4_5("4:5", 4.0 / 5.0);

    /**
     * Canvas of this ratio whose long side matches the source's long side, so a vertical clip
     * delivered as 16:9 becomes 1920x1080 rather than an inflated 3414x1920, and 4K stays 4K.
     * Dimensions are rounded up to even, as H.264 requires.
     */
    fun canvasSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val r = ratio ?: return (sourceWidth + sourceWidth % 2) to (sourceHeight + sourceHeight % 2)
        val longSide = maxOf(sourceWidth, sourceHeight)
        val (w, h) = if (r >= 1.0) {
            longSide to Math.round(longSide / r).toInt()
        } else {
            Math.round(longSide * r).toInt() to longSide
        }
        return (w + w % 2) to (h + h % 2)
    }
}
