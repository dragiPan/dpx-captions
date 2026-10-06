package com.dpx.captions.core

import com.dpx.captions.model.CaptionCard
import com.dpx.captions.model.CaptionFormatting
import com.dpx.captions.model.Word
import java.util.Locale

/**
 * Pure edit operations over the caption list. Everything returns a new list, so history snapshots
 * are just references and can never be mutated by a later edit.
 */
object CaptionOps {
    const val MIN_CARD_DURATION = 0.1

    data class Result(val cards: List<CaptionCard>, val selected: Int?)

    /**
     * Rewrites a card's text. When the word count is unchanged the original word timings are kept,
     * so fixing a misheard word doesn't disturb when its highlight lands. Otherwise timing is spread
     * evenly over the card. Returns null when the text is blank (the card should be removed).
     */
    fun rewriteText(card: CaptionCard, newText: String, f: CaptionFormatting): CaptionCard? {
        // Typed text gets the same case / punctuation / censoring treatment as generated captions, so a
        // caption typed in lowercase doesn't stand out in a project set to uppercase.
        val tokens = newText.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { CaptionBuilder.displayWord(Word(it, 0.0, 0.0), f).text }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val old = card.words
        val words: List<Word> = if (tokens.size == old.size) {
            old.mapIndexed { i, w -> w.copy(text = tokens[i]) }
        } else {
            val span = maxOf(card.end - card.start, 0.01)
            val step = span / tokens.size
            tokens.mapIndexed { i, t -> Word(t, card.start + i * step, card.start + (i + 1) * step) }
        }

        val maxChars = if (f.maxCharsPerLine > 0) f.maxCharsPerLine else 17
        val maxLines = maxOf(1, f.lineCount)
        val lines = mutableListOf(mutableListOf<Word>())
        for (w in words) {
            val line = lines.last()
            val tentative = line.sumOf { it.text.length } + w.text.length + line.size
            if (line.isNotEmpty() && tentative > maxChars && lines.size < maxLines) {
                lines.add(mutableListOf(w))
            } else {
                line.add(w)
            }
        }
        return CaptionCard(lines.filter { it.isNotEmpty() }.map { it.toList() }, card.start, card.end)
    }

    /**
     * Makes room for the card at [index], the way a timeline overwrite edit does: anything it now
     * covers is removed, anything it partly covers is trimmed.
     */
    fun applyOverwrite(cards: List<CaptionCard>, index: Int): Result {
        val edited = cards[index]
        val kept = mutableListOf<CaptionCard>()
        for ((i, card) in cards.withIndex()) {
            if (i == index) continue
            when {
                card.end <= edited.start || card.start >= edited.end -> kept += card
                card.start >= edited.start && card.end <= edited.end -> Unit
                card.start < edited.start -> kept += card.copy(end = edited.start)
                else -> kept += card.copy(start = edited.end)
            }
        }
        kept += edited
        kept.sortBy { it.start }
        return Result(kept, kept.indexOf(edited))
    }

    /** Cuts the card under [time] in two, sharing words by their midpoint. */
    fun splitAt(cards: List<CaptionCard>, time: Double): Result? {
        val i = cards.indexOfFirst { it.start < time && time < it.end }
        if (i < 0) return null

        val card = cards[i]
        val words = card.words
        val left = words.filter { (it.start + it.end) / 2 < time }
        val right = words.filter { (it.start + it.end) / 2 >= time }
        if (left.isEmpty() || right.isEmpty()) return null

        val result = cards.toMutableList()
        result[i] = CaptionCard(listOf(left), card.start, time)
        result.add(i + 1, CaptionCard(listOf(right), time, card.end))
        return Result(result, i)
    }

    fun delete(cards: List<CaptionCard>, index: Int): List<CaptionCard> =
        cards.filterIndexed { i, _ -> i != index }

    fun duplicate(cards: List<CaptionCard>, index: Int, videoDuration: Double): Result? {
        val card = cards.getOrNull(index) ?: return null
        val span = card.end - card.start
        val start = card.end
        val end = minOf(start + span, videoDuration)
        if (end - start < MIN_CARD_DURATION) return null

        val shift = start - card.start
        val copy = CaptionCard(
            card.lines.map { line -> line.map { it.copy(start = it.start + shift, end = minOf(it.end + shift, end)) } },
            start, end,
        )
        val withCopy = cards.toMutableList().apply { add(index + 1, copy) }
        return applyOverwrite(withCopy, index + 1)
    }

    fun mergeWithNext(cards: List<CaptionCard>, index: Int): Result? {
        val a = cards.getOrNull(index) ?: return null
        val b = cards.getOrNull(index + 1) ?: return null
        val merged = CaptionCard(a.lines + b.lines, a.start, b.end)
        val result = cards.toMutableList()
        result[index] = merged
        result.removeAt(index + 1)
        return Result(result, index)
    }

    /** Adds a one-second card at [time] that overwrites whatever it lands on. */
    fun addAt(cards: List<CaptionCard>, time: Double, videoDuration: Double, text: String = "NOVI"): Result? {
        val start = time.coerceIn(0.0, maxOf(videoDuration - MIN_CARD_DURATION, 0.0))
        val end = minOf(start + 1.0, videoDuration)
        if (end - start < MIN_CARD_DURATION) return null

        val parts = text.split(" ").filter { it.isNotEmpty() }.ifEmpty { listOf("NOVI") }
        val step = (end - start) / parts.size
        val words = parts.mapIndexed { i, t -> Word(t, start + i * step, start + (i + 1) * step) }
        val withNew = (cards + CaptionCard(listOf(words), start, end)).sortedBy { it.start }
        val index = withNew.indexOfFirst { it.start == start && it.words.firstOrNull()?.text == parts.first() }
        return applyOverwrite(withNew, index)
    }

    /** Replaces text word by word so every word keeps its own timing. Returns the new list and the number of words changed. */
    fun findReplace(
        cards: List<CaptionCard>,
        find: String,
        replacement: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false,
    ): Pair<List<CaptionCard>, Int> {
        if (find.isEmpty()) return cards to 0

        val escaped = Regex.escape(find)
        val pattern = Regex(
            if (wholeWord) "(?<![\\p{L}\\p{N}_])$escaped(?![\\p{L}\\p{N}_])" else escaped,
            if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE),
        )

        var changed = 0
        val result = cards.map { card ->
            var cardChanged = false
            val lines = card.lines.map { line ->
                line.map { word ->
                    val updated = pattern.replace(word.text) { replacement }
                    if (updated != word.text) {
                        changed++
                        cardChanged = true
                        word.copy(text = updated)
                    } else word
                }
            }
            if (cardChanged) card.copy(lines = lines) else card
        }
        return result to changed
    }

    fun wordCount(cards: List<CaptionCard>): Int = cards.sumOf { it.words.size }

    fun lowercaseKey(s: String): String = s.lowercase(Locale.ROOT)
}
