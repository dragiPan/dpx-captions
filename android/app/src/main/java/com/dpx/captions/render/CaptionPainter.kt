package com.dpx.captions.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.dpx.captions.model.AnimationStyle
import com.dpx.captions.model.CaptionCard
import com.dpx.captions.model.Rgb
import java.util.Objects
import kotlin.math.roundToInt

/**
 * Draws the active caption onto a Canvas. The same instance logic serves the on-screen preview and the
 * bitmap overlay used for export, so what you edit is what gets burned in.
 */
class CaptionPainter(private val fonts: FontRegistry) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun color(c: Rgb): Int = Color.rgb(
        (c.r * 255).roundToInt().coerceIn(0, 255),
        (c.g * 255).roundToInt().coerceIn(0, 255),
        (c.b * 255).roundToInt().coerceIn(0, 255),
    )

    private fun prepare(style: AnimationStyle, fontPx: Int) {
        val resolved = fonts.resolve(style.font, style.bold)
        for (paint in arrayOf(fill, stroke)) {
            paint.typeface = resolved.typeface
            paint.textSize = fontPx.toFloat()
            paint.isFakeBoldText = resolved.fakeBold
        }
    }

    private fun metrics(): TextMetrics {
        val fm = fill.fontMetrics
        return object : TextMetrics {
            override fun width(text: String) = fill.measureText(text)
            override val ascent = -fm.ascent
            override val descent = fm.descent
        }
    }

    /** Returns true if anything was drawn. */
    fun draw(
        canvas: Canvas,
        canvasWidth: Int,
        canvasHeight: Int,
        cards: List<CaptionCard>,
        style: AnimationStyle,
        t: Double,
    ): Boolean {
        val card = CaptionLayout.activeCard(cards, t) ?: return false
        val fontPx = CaptionLayout.fontPx(style, canvasHeight)
        prepare(style, fontPx)

        val m = metrics()
        val words = CaptionLayout.layout(card, style, canvasWidth, canvasHeight, m)
        if (words.isEmpty()) return false

        val entrance = CaptionLayout.entranceProgress(style, card, t)
        val fillColor = color(style.fill)
        val highlightColor = color(style.highlight)
        val outlineColor = color(style.outline)
        val shadowColor = color(style.shadow)

        // A QPen/Paint stroke straddles the glyph edge while the intended outline sits outside it,
        // so the stroke is twice the visible thickness and the fill is drawn over its inner half.
        val strokeWidth = if (style.outlineEnabled) (style.outlineThickness * fontPx * 2).toFloat() else 0f
        val shadowOffset = (style.shadowOffset * fontPx).toFloat()
        val baseline = (m.ascent - m.descent) / 2

        val layer = if (style.fadeEnabled) {
            canvas.saveLayerAlpha(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat(), (entrance * 255).roundToInt())
        } else {
            canvas.save()
        }
        if (style.slideUpEnabled) canvas.translate(0f, ((1.0 - entrance) * 0.08 * canvasHeight).toFloat())

        for (item in words) {
            val word = item.word
            val spoken = style.highlightEnabled && t >= word.start
            var scale = if (spoken) CaptionLayout.popScale(style, word, t) else 1.0
            if (style.popInEnabled) scale *= 0.6 + 0.4 * entrance

            canvas.save()
            canvas.translate(item.centerX, item.centerY)
            if (scale != 1.0) canvas.scale(scale.toFloat(), scale.toFloat())

            val x = -item.width / 2

            if (style.shadowEnabled) {
                // Like libass, the shadow is the whole outlined word offset, not a bare copy of the glyphs.
                if (strokeWidth > 0f) {
                    stroke.strokeWidth = strokeWidth
                    stroke.color = shadowColor
                    canvas.drawText(word.text, x + shadowOffset, baseline + shadowOffset, stroke)
                }
                fill.color = shadowColor
                canvas.drawText(word.text, x + shadowOffset, baseline + shadowOffset, fill)
            }

            if (strokeWidth > 0f) {
                stroke.strokeWidth = strokeWidth
                stroke.color = outlineColor
                canvas.drawText(word.text, x, baseline, stroke)
            }

            fill.color = if (spoken) highlightColor else fillColor
            canvas.drawText(word.text, x, baseline, fill)
            canvas.restore()
        }

        canvas.restoreToCount(layer)
        return true
    }

    /** Where the active caption sits on the canvas, padded for easy touching; null if none is showing. */
    fun bounds(
        canvasWidth: Int,
        canvasHeight: Int,
        cards: List<CaptionCard>,
        style: AnimationStyle,
        t: Double,
    ): android.graphics.RectF? {
        val card = CaptionLayout.activeCard(cards, t) ?: return null
        val fontPx = CaptionLayout.fontPx(style, canvasHeight)
        prepare(style, fontPx)
        val words = CaptionLayout.layout(card, style, canvasWidth, canvasHeight, metrics())
        if (words.isEmpty()) return null

        val rect = android.graphics.RectF(
            words.minOf { it.centerX - it.width / 2 },
            words.minOf { it.centerY - it.height / 2 },
            words.maxOf { it.centerX + it.width / 2 },
            words.maxOf { it.centerY + it.height / 2 },
        )
        rect.inset(-fontPx * 0.5f, -fontPx * 0.4f)
        return rect
    }

    /**
     * A cheap fingerprint of what [draw] would produce at time [t]. Export uploads a new texture only
     * when this changes, which is almost never between word boundaries.
     */
    fun visualState(cards: List<CaptionCard>, style: AnimationStyle, canvasHeight: Int, t: Double): Int? {
        val card = CaptionLayout.activeCard(cards, t) ?: return null
        val entrance = CaptionLayout.entranceProgress(style, card, t)
        val words = card.words.map { word ->
            val spoken = style.highlightEnabled && t >= word.start
            Objects.hash(word.text, spoken, (if (spoken) CaptionLayout.popScale(style, word, t) else 1.0).times(400).roundToInt())
        }
        return Objects.hash(card.start, card.end, words, (entrance * 400).roundToInt(), style.hashCode(), canvasHeight)
    }
}
