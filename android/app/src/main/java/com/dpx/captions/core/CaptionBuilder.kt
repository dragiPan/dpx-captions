package com.dpx.captions.core

import com.dpx.captions.model.CaptionCard
import com.dpx.captions.model.CaptionFormatting
import com.dpx.captions.model.Density
import com.dpx.captions.model.TextCase
import com.dpx.captions.model.Word
import java.util.Locale
import java.util.regex.Pattern

/**
 * Groups the flat word list from transcription into caption cards - one card is one on-screen
 * caption event. How words are packed into cards and lines is driven by [CaptionFormatting].
 */
object CaptionBuilder {
    private val sentenceEnd = Regex("[.!?]$")

    // Anything that isn't a letter, combining mark, digit, underscore, whitespace or apostrophe.
    // Spelled with explicit \p{..} classes rather than \w + UNICODE_CHARACTER_CLASS: Android's regex
    // engine throws on that flag (the desktop JVM accepts it, so unit tests can't catch it), and its
    // \w is Unicode-aware while the JVM's is ASCII-only - the explicit classes behave the same on both.
    private val punctuation: Pattern = Pattern.compile("[^\\p{L}\\p{M}\\p{N}_\\s']")

    private data class Params(val maxChars: Int, val maxLines: Int, val pauseBreak: Double?)

    private fun params(f: CaptionFormatting): Params = when (f.density) {
        Density.CUSTOM -> Params(f.maxCharsPerLine, maxOf(1, f.lineCount), null)
        // A one-character limit means any second word overflows the line, so every word gets its own card.
        Density.SINGLE_WORD -> Params(1, 1, null)
        Density.STANDARD -> Params(25, 1, 0.7)
        Density.MORE -> Params(40, 2, 0.9)
    }

    fun applyCase(text: String, case: TextCase): String = when (case) {
        TextCase.UPPERCASE -> text.uppercase(Locale.ROOT)
        TextCase.LOWERCASE -> text.lowercase(Locale.ROOT)
        TextCase.TITLE_CASE ->
            if (text.isEmpty()) text
            else text.substring(0, 1).uppercase(Locale.ROOT) + text.substring(1).lowercase(Locale.ROOT)
        TextCase.NORMAL -> text
    }

    fun stripPunctuation(text: String): String = punctuation.matcher(text).replaceAll("")

    private fun censor(text: String, list: List<String>): String {
        val lowered = list.map { it.lowercase(Locale.ROOT).trim() }.toSet()
        val bare = stripPunctuation(text).lowercase(Locale.ROOT)
        if (bare in lowered) {
            return if (text.isEmpty()) text else text.substring(0, 1) + "*".repeat(maxOf(text.length - 1, 1))
        }
        return text
    }

    fun displayWord(word: Word, f: CaptionFormatting): Word {
        var text = word.text
        if (f.censorWords && f.censorWordList.isNotEmpty()) text = censor(text, f.censorWordList)
        if (f.removePunctuation) text = stripPunctuation(text)
        text = applyCase(text, f.textCase)
        return word.copy(text = text)
    }

    private fun lineLength(line: List<Word>): Int =
        line.sumOf { it.text.length } + maxOf(line.size - 1, 0)

    fun build(words: List<Word>, f: CaptionFormatting): List<CaptionCard> {
        val (maxChars, maxLines, pauseBreak) = params(f)

        val cards = mutableListOf<CaptionCard>()
        var lines = mutableListOf(mutableListOf<Word>())
        var rawInCard = 0

        fun flush() {
            val nonEmpty = lines.filter { it.isNotEmpty() }
            if (nonEmpty.isNotEmpty()) {
                val flat = nonEmpty.flatten()
                cards += CaptionCard(nonEmpty.map { it.toList() }, flat.first().start, flat.last().end)
            }
            lines = mutableListOf(mutableListOf())
            rawInCard = 0
        }

        var previous: Word? = null
        for (raw in words) {
            val shown = displayWord(raw, f)
            if (shown.text.isEmpty()) {
                previous = raw
                continue
            }

            if (previous != null && pauseBreak != null && rawInCard > 0) {
                if (raw.start - previous.end > pauseBreak) flush()
            }

            val current = lines.last()
            if (current.isNotEmpty() && lineLength(current + shown) > maxChars) {
                if (lines.size < maxLines) {
                    lines.add(mutableListOf(shown))
                } else {
                    flush()
                    lines.last().add(shown)
                }
            } else {
                current.add(shown)
            }

            rawInCard++
            if (pauseBreak != null && sentenceEnd.containsMatchIn(raw.text)) flush()
            previous = raw
        }

        flush()
        return cards
    }
}
