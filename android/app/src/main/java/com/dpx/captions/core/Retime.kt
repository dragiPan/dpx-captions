package com.dpx.captions.core

import com.dpx.captions.model.CaptionCard
import com.dpx.captions.model.CaptionFormatting
import com.dpx.captions.model.Word

/**
 * Keeps word timings tied to the audio when a card's text is edited - the same logic as the desktop
 * app's `dpx_captions/core/retime.py`.
 *
 * The edited text is aligned with the card's previous words: a kept word keeps its time, a fixed,
 * merged or split word takes the time of the words it replaces, and a deleted word simply goes. A word
 * that wasn't in the card is looked up in the original Whisper words around the card; only when it
 * isn't there is it fitted into the free time between its neighbours.
 *
 * Text typed with "|" splits the card at those points, each new card starting when its first word is
 * spoken, so the highlight never lags behind the audio.
 */
object Retime {
    const val SPLIT_MARK = "|"
    const val REFERENCE_PAD = 1.5
    const val REFERENCE_MIN_SIMILARITY = 0.75
    const val MIN_WORD_DURATION = 0.04
    const val MIN_CARD_DURATION = 0.1

    private fun spread(tokens: List<String>, start: Double, end: Double): List<Pair<Double, Double>> {
        val weights = tokens.map { it.length + 1 }
        val total = weights.sum().toDouble()
        var t = start
        return weights.map { w ->
            val next = t + (end - start) * w / total
            (t to next).also { t = next }
        }
    }

    private fun prevAssigned(times: Array<Pair<Double, Double>?>, j: Int): Int? = (j - 1 downTo 0).firstOrNull { times[it] != null }

    private fun nextAssigned(times: Array<Pair<Double, Double>?>, j: Int): Int? = (j + 1 until times.size).firstOrNull { times[it] != null }

    private fun fillFromReference(tokens: List<String>, times: Array<Pair<Double, Double>?>, start: Double, end: Double, reference: List<Word>) {
        for (j in tokens.indices) {
            if (times[j] != null) continue
            val norm = Corrections.normalize(tokens[j])
            if (norm.isEmpty()) continue
            val p = prevAssigned(times, j)
            val n = nextAssigned(times, j)
            val after = p?.let { times[it]!!.first } ?: (start - REFERENCE_PAD)
            val before = n?.let { times[it]!!.first } ?: (end + REFERENCE_PAD)

            var best: Word? = null
            var bestSimilarity = -1.0
            for (w in reference) {
                if (w.start <= after || w.start >= before) continue
                val ref = Corrections.normalize(w.text)
                val similarity = if (ref == norm) 1.0 else SequenceMatcher.ratio(ref, norm)
                if (similarity < REFERENCE_MIN_SIMILARITY) continue
                // Most similar, then earliest.
                if (similarity > bestSimilarity || (similarity == bestSimilarity && w.start < best!!.start)) {
                    best = w
                    bestSimilarity = similarity
                }
            }
            if (best != null) times[j] = best.start to best.end
        }
    }

    private fun interpolate(tokens: List<String>, times: Array<Pair<Double, Double>?>, start: Double, end: Double) {
        var j = 0
        while (j < tokens.size) {
            if (times[j] != null) {
                j++
                continue
            }
            var k = j
            while (k < tokens.size && times[k] == null) k++
            var run = (j until k).toList()
            val prev = if (j > 0) j - 1 else null
            val next = if (k < tokens.size) k else null
            var lo = prev?.let { times[it]!!.second } ?: start
            var hi = next?.let { times[it]!!.first } ?: end
            if (hi - lo < MIN_WORD_DURATION * run.size) {
                // No free time: share the neighbour's instead of piling up.
                if (prev != null) {
                    lo = times[prev]!!.first
                    run = listOf(prev) + run
                } else if (next != null) {
                    hi = times[next]!!.second
                    run = run + next
                }
                hi = maxOf(hi, lo + MIN_WORD_DURATION * run.size)
            }
            spread(run.map { tokens[it] }, lo, hi).forEachIndexed { idx, span -> times[run[idx]] = span }
            j = k
        }
    }

    /** Timed words for [tokens], the new text of a card spanning [start, end] that used to hold [oldWords]. */
    fun retime(tokens: List<String>, oldWords: List<Word>, start: Double, end: Double, reference: List<Word> = emptyList()): List<Word> {
        if (tokens.isEmpty()) return emptyList()
        val times = arrayOfNulls<Pair<Double, Double>>(tokens.size)
        val probability = DoubleArray(tokens.size) { 1.0 }
        val source = arrayOfNulls<String>(tokens.size)
        // Pairings between words that share almost no letters ("NOVI" -> "ZAISTA") are only a fallback:
        // the original transcript gets the first say.
        val fallback = HashMap<Int, Pair<Double, Double>>()

        val old = oldWords.filter { it.text.isNotBlank() }
        if (old.isNotEmpty()) {
            val a = old.map { Corrections.normalize(it.text) }
            val b = tokens.map { Corrections.normalize(it) }
            for ((i0, i1, j0, j1) in Corrections.align(a, b).map { it.toList() }) {
                if (i0 == i1 || j0 == j1) continue
                val src = old.subList(i0, i1)
                val oneToOne = i1 - i0 == j1 - j0
                val spans = if (oneToOne) src.map { it.start to it.end } else spread(tokens.subList(j0, j1), src.first().start, src.last().end)
                val similar = SequenceMatcher.ratio(a.subList(i0, i1).joinToString(""), b.subList(j0, j1).joinToString(""))
                for ((k, span) in spans.withIndex()) {
                    val j = j0 + k
                    probability[j] = if (oneToOne) src[k].probability else src.minOf { it.probability }
                    if (oneToOne && a[i0 + k] == b[j]) source[j] = src[k].source
                    if (similar >= REFERENCE_MIN_SIMILARITY / 2) times[j] = span else fallback[j] = span
                }
            }
        }

        fillFromReference(tokens, times, start, end, reference)
        for ((j, span) in fallback) if (times[j] == null) times[j] = span
        interpolate(tokens, times, start, end)
        return tokens.mapIndexed { i, t -> Word(t, times[i]!!.first, times[i]!!.second, probability[i], source[i]) }
    }

    /** Packs words into at most `lineCount` lines of `maxCharsPerLine`. */
    fun wrapWords(words: List<Word>, f: CaptionFormatting): List<List<Word>> {
        val maxChars = if (f.maxCharsPerLine > 0) f.maxCharsPerLine else 17
        val maxLines = maxOf(1, f.lineCount)
        val lines = mutableListOf(mutableListOf<Word>())
        for (w in words) {
            val line = lines.last()
            val tentative = line.sumOf { it.text.length } + w.text.length + line.size
            if (line.isNotEmpty() && tentative > maxChars && lines.size < maxLines) lines += mutableListOf(w) else line += w
        }
        return lines.filter { it.isNotEmpty() }.map { it.toList() }
    }

    /** Boundaries for a card split at [cuts], kept in order and at least MIN_CARD_DURATION apart. */
    fun splitBounds(start: Double, end: Double, cuts: List<Double>): List<Double> {
        val n = cuts.size + 1
        if (end - start < MIN_CARD_DURATION * n) {
            val step = (end - start) / n
            return (0 until n).map { start + it * step } + end
        }
        val bounds = mutableListOf(start)
        for ((k, cut) in cuts.withIndex()) {
            val remaining = n - 1 - k
            val low = bounds.last() + MIN_CARD_DURATION
            val high = end - MIN_CARD_DURATION * remaining
            bounds += cut.coerceIn(low, high)
        }
        bounds += end
        return bounds
    }

    fun referenceWindow(reference: List<Word>, start: Double, end: Double): List<Word> =
        reference.filter { it.end > start - REFERENCE_PAD && it.start < end + REFERENCE_PAD }

    /**
     * The card(s) that [parts] (the new text, already split at "|" and shaped for display) turn [card]
     * into. Card edges stay where they were; an empty result means the text was blank.
     */
    fun rebuild(card: CaptionCard, parts: List<List<String>>, f: CaptionFormatting, reference: List<Word> = emptyList()): List<CaptionCard> {
        val nonEmpty = parts.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return emptyList()
        val words = retime(nonEmpty.flatten(), card.words, card.start, card.end, referenceWindow(reference, card.start, card.end))
        val groups = mutableListOf<List<Word>>()
        var i = 0
        for (part in nonEmpty) {
            groups += words.subList(i, i + part.size)
            i += part.size
        }
        val bounds = splitBounds(card.start, card.end, groups.drop(1).map { it.first().start })
        return groups.mapIndexed { k, g -> CaptionCard(wrapWords(g, f), bounds[k], bounds[k + 1]) }
    }
}
