package com.dpx.captions.render

import com.dpx.captions.model.AnimationStyle
import com.dpx.captions.model.CaptionCard
import com.dpx.captions.model.Word
import kotlin.math.max
import kotlin.math.roundToInt

/** Text measurement, abstracted so layout can be unit tested on the JVM without Android. */
interface TextMetrics {
    fun width(text: String): Float

    /** Distance from baseline up to the top of the line, positive. */
    val ascent: Float

    /** Distance from baseline down to the bottom of the line, positive. */
    val descent: Float
}

data class PositionedWord(
    val word: Word,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
)

/**
 * Everything about where captions go and how they move. The preview and the exported video both draw
 * from this, which is what keeps the two identical.
 */
object CaptionLayout {
    fun fontPx(style: AnimationStyle, canvasHeight: Int): Int =
        max(1, (style.textSize * canvasHeight).roundToInt())

    fun activeCard(cards: List<CaptionCard>, t: Double): CaptionCard? =
        cards.firstOrNull { t >= it.start && t <= it.end }

    fun layout(
        card: CaptionCard,
        style: AnimationStyle,
        canvasWidth: Int,
        canvasHeight: Int,
        metrics: TextMetrics,
    ): List<PositionedWord> {
        val lineHeight = metrics.ascent + metrics.descent
        val spaceWidth = metrics.width(" ")

        val centerX = (style.positionX * canvasWidth).toFloat()
        // AutoSubs measures Y upward from the bottom of the frame; Canvas measures down from the top.
        val centerY = ((1.0 - style.positionY) * canvasHeight).toFloat()

        val totalHeight = lineHeight * card.lines.size
        val firstLineCenter = centerY - totalHeight / 2 + lineHeight / 2

        val result = ArrayList<PositionedWord>()
        for ((row, line) in card.lines.withIndex()) {
            val lineCenterY = firstLineCenter + row * lineHeight
            val widths = line.map { metrics.width(it.text) }
            val lineWidth = widths.sum() + spaceWidth * max(line.size - 1, 0)
            var penX = centerX - lineWidth / 2

            for ((i, word) in line.withIndex()) {
                result += PositionedWord(word, penX + widths[i] / 2, lineCenterY, widths[i], lineHeight)
                penX += widths[i] + spaceWidth
            }
        }
        return result
    }

    /** 0..1 progress of the card's entrance animation (fade / slide / pop-in). */
    fun entranceProgress(style: AnimationStyle, card: CaptionCard, t: Double): Double {
        if (style.animationLength <= 0.0) return 1.0
        return ((t - card.start) / style.animationLength).coerceIn(0.0, 1.0)
    }

    /**
     * Scale of the spoken-word pop: the word snaps to the highlight colour when it starts and briefly
     * grows then settles, rather than a colour sweeping across its letters.
     */
    fun popScale(style: AnimationStyle, word: Word, t: Double): Double {
        val duration = max(style.animationLength, 1e-6)
        val elapsed = t - word.start
        if (elapsed < 0 || elapsed > duration) return 1.0

        val peak = style.highlightPopScale
        val half = duration / 2
        return if (elapsed <= half) {
            1.0 + (peak - 1.0) * (elapsed / half)
        } else {
            peak - (peak - 1.0) * ((elapsed - half) / half)
        }
    }
}
