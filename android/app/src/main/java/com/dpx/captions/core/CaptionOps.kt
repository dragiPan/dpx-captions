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
     * Rewrites a card's text, keeping every word on its spoken time (see [Retime]): kept words keep
     * their timing, fixed ones take the time of what they replace, and a word that wasn't in the card
     * is looked up in [reference] (the original transcript). Typing "|" between words splits the card
     * there. Returns the resulting card(s); an empty list means the text was blank (remove the card).
     */
    fun rewriteText(
        card: CaptionCard,
        newText: String,
        f: CaptionFormatting,
        reference: List<Word> = emptyList(),
    ): List<CaptionCard> {
        // Typed text gets the same case / punctuation / censoring treatment as generated captions, so a
        // caption typed in lowercase doesn't stand out in a project set to uppercase.
        val parts = newText.split(Retime.SPLIT_MARK).map { part ->
            part.trim().split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .map { CaptionBuilder.displayWord(Word(it, 0.0, 0.0), f).text }
                .filter { it.isNotEmpty() }
        }
        return Retime.rebuild(card, parts, f, reference)
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

    /**
     * Cuts the card under [time] in two between words, sharing them by their midpoint. Every word keeps
     * its own time, and the cut lands on the first word of the second card, so it appears exactly when
     * that word is spoken instead of mid-word at the playhead.
     */
    fun splitAt(cards: List<CaptionCard>, time: Double, f: CaptionFormatting = CaptionFormatting()): Result? {
        val i = cards.indexOfFirst { it.start < time && time < it.end }
        if (i < 0) return null

        val card = cards[i]
        val words = card.words
        val left = words.filter { (it.start + it.end) / 2 < time }
        val right = words.filter { (it.start + it.end) / 2 >= time }
        if (left.isEmpty() || right.isEmpty()) return null

        val snapped = right.first().start
        val cut = if (snapped >= card.start + MIN_CARD_DURATION && snapped <= card.end - MIN_CARD_DURATION) snapped else time
        val result = cards.toMutableList()
        result[i] = CaptionCard(Retime.wrapWords(left, f), card.start, cut)
        result.add(i + 1, CaptionCard(Retime.wrapWords(right, f), cut, card.end))
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

    /** Words keep their times; lines are re-wrapped to the project's layout. */
    fun mergeWithNext(cards: List<CaptionCard>, index: Int, f: CaptionFormatting = CaptionFormatting()): Result? {
        val a = cards.getOrNull(index) ?: return null
        val b = cards.getOrNull(index + 1) ?: return null
        val merged = CaptionCard(Retime.wrapWords(a.words + b.words, f), a.start, b.end)
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
