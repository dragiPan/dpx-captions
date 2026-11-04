package com.dpx.captions.core

import com.dpx.captions.model.CaptionCard
import com.dpx.captions.model.Word
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale

/**
 * Learned transcript corrections - the same logic and the same `corrections.json` format as the
 * desktop app (`dpx_captions/core/corrections.py`).
 *
 * After an export, the captions as they were generated (the baseline) are compared with the captions
 * as the user left them. Each rewritten word becomes a rule "Whisper wrote X -> it should be Y"
 * ("zahtjeva" -> "zahteva", "dobit ćeš" -> "dobićeš"), applied to Whisper's words the next time
 * captions are generated, before they are grouped into cards.
 *
 * Spelling fixes and merges become general rules. Edits that are only right in their sentence -
 * splitting "koji" into "ko i", say - are learned with the card's words around them ("retko koji" ->
 * "retko ko i"), so they only fire where the same words come up again. Matching ignores case and
 * punctuation, and an edit only counts when it sits where the generated words were, so moving or
 * deleting cards is never mistaken for a correction. Undoing an automatic fix takes a vote from it.
 */
object Corrections {
    /** Longest phrase (in words) a general rule can match, on either side. */
    const val MAX_PHRASE_WORDS = 3
    /** Longest context kept with a context-dependent edit. */
    const val MAX_CONTEXT_WORDS = 8
    /** A one-to-one fix of a word this short ("je" -> "jer") is applied only in its sentence. */
    const val SHORT_WORD = 3
    const val TIME_TOLERANCE = 0.35
    const val MIN_PAIR_SIMILARITY = 0.5
    /** A neighbour this similar to what Whisper wrote counts as kept, so it can anchor a context rule. */
    const val REFERENCE_SIMILARITY = 0.75
    private const val GROUP_PENALTY = 1

    // Explicit \p{..} classes: Android's regex engine rejects UNICODE_CHARACTER_CLASS (see CaptionBuilder).
    private const val WORD_CHARS = "\\p{L}\\p{M}\\p{N}_"
    private val nonWord = Regex("[^$WORD_CHARS]+")
    private val edges = Regex("^([^$WORD_CHARS]*)(.*?)([^$WORD_CHARS]*)$", RegexOption.DOT_MATCHES_ALL)

    /** Case- and punctuation-insensitive form used to compare words. */
    fun normalize(text: String): String = nonWord.replace(Serbian.cyrillicToLatin(text).lowercase(Locale.ROOT), "")

    private fun splitEdges(text: String): Triple<String, String, String> {
        val m = edges.matchEntire(text) ?: return Triple("", text, "")
        return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    fun core(text: String): String = splitEdges(text).second

    private fun casePattern(text: String): String {
        val letters = text.filter { it.isLetter() }
        return when {
            letters.isEmpty() -> "none"
            letters.all { it.isLowerCase() } -> "lower"
            letters.length > 1 && letters.all { it.isUpperCase() } -> "upper"
            letters.first().isUpperCase() && letters.drop(1).all { it.isLowerCase() } -> "title"
            else -> "mixed"
        }
    }

    /** Lowercases the target unless its casing is deliberate ("dpx" -> "DPX" under normal case). */
    private fun storedTarget(target: String, sourceDisplay: String, caseMeaningful: Boolean): String {
        if (!caseMeaningful) return target.lowercase(Locale.ROOT)
        val pattern = casePattern(target)
        return if (pattern == "lower" || pattern == "none" || pattern == casePattern(sourceDisplay)) {
            target.lowercase(Locale.ROOT)
        } else target
    }

    private fun applyCase(target: String, sourceDisplay: String): String {
        if (target.any { it.isUpperCase() }) return target
        return when (casePattern(sourceDisplay)) {
            "upper" -> target.uppercase(Locale.ROOT)
            "title" -> target.take(1).uppercase(Locale.ROOT) + target.drop(1)
            else -> target
        }
    }

    // --------------------------------------------------------------------------------------- apply

    private fun rewrite(src: List<Word>, key: String, target: String): List<Word> {
        val lead = splitEdges(src.first().text).first
        val trail = splitEdges(src.last().text).third
        val sourceDisplay = src.joinToString(" ") { core(it.text) }
        val tokens = applyCase(target, sourceDisplay).split(" ").filter { it.isNotEmpty() }.toMutableList()
        // Words the rule leaves alone keep their exact time; changed ones take the time of what they replace.
        val timed = Retime.retime(tokens, src.map { it.copy(text = core(it.text)) }, src.first().start, src.last().end)
        tokens[0] = lead + tokens[0]
        tokens[tokens.lastIndex] = tokens.last() + trail
        return tokens.mapIndexed { i, t -> Word(t, timed[i].start, timed[i].end, timed[i].probability, source = key) }
    }

    data class Applied(val words: List<Word>, val rewrites: List<Pair<String, String>>)

    /** General rules first, then the context rules over their result. */
    fun apply(words: List<Word>, store: CorrectionStore): Applied {
        val general = applyPass(words, store, context = false)
        val context = applyPass(general.words, store, context = true)
        return Applied(context.words, general.rewrites + context.rewrites)
    }

    internal fun applyPass(words: List<Word>, store: CorrectionStore, context: Boolean): Applied {
        val maxWords = store.maxWords(context)
        if (maxWords == 0) return Applied(words, emptyList())

        val norms = words.map { normalize(it.text) }
        val matchable = norms.indices.filter { norms[it].isNotEmpty() }
        val out = mutableListOf<Word>()
        val applied = mutableListOf<Pair<String, String>>()
        var copiedUntil = 0
        var k = 0
        while (k < matchable.size) {
            var match: Triple<Int, String, String>? = null
            for (length in minOf(maxWords, matchable.size - k) downTo 1) {
                val key = (0 until length).joinToString(" ") { norms[matchable[k + it]] }
                if (store.isContext(key) != context) continue
                val target = store.best(key) ?: continue
                if (target.split(" ").joinToString(" ") { normalize(it) } != key) {
                    match = Triple(length, key, target)
                    break
                }
            }
            if (match == null) {
                k++
                continue
            }
            val (length, key, target) = match
            val first = matchable[k]
            val last = matchable[k + length - 1]
            out += words.subList(copiedUntil, first)
            val src = (0 until length).map { words[matchable[k + it]] }
            val rewritten = rewrite(src, key, target)
            out += rewritten
            applied += src.joinToString(" ") { it.text } to rewritten.joinToString(" ") { it.text }
            copiedUntil = last + 1
            k += length
        }
        out += words.subList(copiedUntil, words.size)
        return Applied(out, applied)
    }

    // --------------------------------------------------------------------------------------- learn

    data class Report(
        val learned: List<Pair<String, String>> = emptyList(),
        val rejected: List<Pair<String, String>> = emptyList(),
        val confirmed: Int = 0,
    ) {
        val changed: Boolean get() = learned.isNotEmpty() || rejected.isNotEmpty() || confirmed > 0

        fun summary(): String? {
            val lines = mutableListOf<String>()
            if (learned.isNotEmpty()) {
                lines += "Learned ${learned.size} correction(s):"
                val shown = learned.map { (a, b) -> "$a → $b" }.distinct()
                lines += shown.take(10).map { "  $it" }
                if (shown.size > 10) lines += "  … and ${shown.size - 10} more"
            }
            if (rejected.isNotEmpty()) lines += "Weakened ${rejected.size} correction(s) you undid."
            if (confirmed > 0) lines += "Confirmed $confirmed automatic correction(s)."
            return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }

    /**
     * Pairs up groups of 0-3 words in [a] with 0-3 words in [b], minimising the characters that don't
     * line up, so one changed block can hold a merge, a fix and a deletion side by side.
     * Returns (aStart, aEnd, bStart, bEnd) per group.
     */
    internal fun align(a: List<String>, b: List<String>): List<IntArray> {
        val n = a.size
        val m = b.size
        val inf = Double.POSITIVE_INFINITY
        val cost = Array(n + 1) { DoubleArray(m + 1) { inf } }
        val back = Array(n + 1) { arrayOfNulls<IntArray>(m + 1) }
        cost[0][0] = 0.0
        fun relax(i: Int, j: Int, value: Double, fi: Int, fj: Int) {
            if (value < cost[i][j]) {
                cost[i][j] = value
                back[i][j] = intArrayOf(fi, fj)
            }
        }
        for (i in 0..n) {
            for (j in 0..m) {
                val c = cost[i][j]
                if (c == inf) continue
                if (i < n) relax(i + 1, j, c + a[i].length, i, j)
                if (j < m) relax(i, j + 1, c + b[j].length, i, j)
                for (k in 1..MAX_PHRASE_WORDS) {
                    if (i + k > n) break
                    val s = a.subList(i, i + k).joinToString("")
                    for (l in 1..MAX_PHRASE_WORDS) {
                        if (j + l > m) break
                        val t = b.subList(j, j + l).joinToString("")
                        val matched = SequenceMatcher.matched(s, t)
                        relax(i + k, j + l, c + s.length + t.length - 2 * matched + GROUP_PENALTY * (k + l - 2), i, j)
                    }
                }
            }
        }
        val groups = mutableListOf<IntArray>()
        var i = n
        var j = m
        while (i != 0 || j != 0) {
            val (pi, pj) = back[i][j]!!
            groups += intArrayOf(pi, i, pj, j)
            i = pi
            j = pj
        }
        return groups.reversed()
    }

    private fun overlaps(aStart: Double, aEnd: Double, bStart: Double, bEnd: Double) =
        aStart < bEnd + TIME_TOLERANCE && bStart < aEnd + TIME_TOLERANCE

    /** Spelling fix of one word or a merge into one word: safe to apply wherever Whisper writes it. */
    private fun isGeneral(src: List<String>, dst: List<String>): Boolean {
        if (dst.size != 1) return false
        if (src.size > 1) return true
        return src[0].length > SHORT_WORD && SequenceMatcher.ratio(src[0], dst[0]) >= 0.5
    }

    /** Runs of consecutive words produced by the same rule application. */
    private fun applications(words: List<Word>): List<Pair<String, Double>> {
        val runs = mutableListOf<Pair<String, Double>>()
        var prev: String? = null
        for (w in words) {
            if (w.source != null && w.source != prev) runs += w.source to w.start
            prev = w.source
        }
        return runs
    }

    private class CurWord(val word: Word, val card: Int, val pos: Int)

    /**
     * Updates [store] from the difference between the generated words ([baseline]) and the edited
     * [cards]. [seen] holds signatures of edits already counted for this baseline, so exporting the
     * same project twice doesn't count them twice; it is updated in place.
     */
    fun learn(
        baseline: List<Word>,
        cards: List<CaptionCard>,
        store: CorrectionStore,
        caseMeaningful: Boolean = false,
        seen: MutableSet<String> = mutableSetOf(),
    ): Report {
        val learned = mutableListOf<Pair<String, String>>()
        val rejected = mutableListOf<Pair<String, String>>()
        var confirmed = 0

        val base = baseline.filter { normalize(it.text).isNotEmpty() }
        val sortedCards = cards.sortedBy { it.start }
        val cardWords = sortedCards.map { c -> c.words.filter { normalize(it.text).isNotEmpty() } }
        val cur = sortedCards.indices.flatMap { ci -> cardWords[ci].indices.map { p -> CurWord(cardWords[ci][p], ci, p) } }
        val a = base.map { normalize(it.text) }
        val b = cur.map { normalize(it.word.text) }

        fun confirm(words: List<Word>) {
            for ((key, start) in applications(words)) {
                val sig = "confirm|$key|${"%.2f".format(Locale.ROOT, start)}"
                if (sig !in seen && store.best(key) != null) {
                    seen += sig
                    store.confirm(key)
                    confirmed++
                }
            }
        }

        fun commit(src: List<Word>, dstWords: List<Word>, limit: Int, contextRule: Boolean, edited: List<Word> = src) {
            // Starred words are censoring ("PIČ****"), not a transcription fix.
            if (src.any { '*' in it.text } || dstWords.any { '*' in it.text }) return
            val editedSet = edited.toSet()
            var original = mutableListOf<String>()
            val undone = mutableListOf<String>()
            var prev: String? = null
            for (w in src) {
                val s = w.source
                if (s == null) {
                    original += normalize(w.text)
                } else if (s != prev) {
                    original += s.split(" ")
                    if (w in editedSet) undone += s
                }
                prev = s
            }
            val targetTokens = dstWords.map { core(it.text) }.filter { it.isNotEmpty() }
            if (targetTokens.isEmpty()) return
            if (contextRule) {
                // Stated as it reads after the general rules, which run first: "redko koji" -> "retko koji".
                val fixed = applyPass(original.map { Word(it, 0.0, 0.0) }, store, context = false).words
                original = fixed.map { normalize(it.text) }.filter { it.isNotEmpty() }.toMutableList()
            }
            val sourceKey = original.joinToString(" ")
            val targetNorm = targetTokens.joinToString(" ") { normalize(it) }

            val sig = "edit|$sourceKey|$targetNorm|${"%.2f".format(Locale.ROOT, src.first().start)}"
            if (sig in seen) return
            seen += sig

            for (key in undone) store.reject(key)?.let { rejected += key to it }

            if (targetNorm != sourceKey && original.size <= limit) {
                val sourceDisplay = src.joinToString(" ") { core(it.text) }
                val target = storedTarget(targetTokens.joinToString(" "), sourceDisplay, caseMeaningful)
                store.learn(sourceKey, target, context = contextRule)
                learned += sourceKey to target
            }
        }

        /** The card's kept words around a context-dependent edit, or null when nothing anchors it. */
        fun context(i0: Int, i1: Int, dst: List<CurWord>): Pair<List<Word>, List<Word>>? {
            val ci = dst.first().card
            if (dst.any { it.card != ci }) return null
            val card = sortedCards[ci]
            val words = cardWords[ci]
            val k0 = dst.first().pos
            val k1 = dst.last().pos
            fun kept(i: Int, k: Int): Boolean {
                val w = base[i]
                val mid = (w.start + w.end) / 2
                if (mid < card.start - TIME_TOLERANCE || mid > card.end + TIME_TOLERANCE) return false
                val s = a[i]
                val t = normalize(words[k].text)
                return s == t || SequenceMatcher.ratio(s, t) >= REFERENCE_SIMILARITY
            }
            var s0 = i0
            var d0 = k0
            var s1 = i1
            var d1 = k1
            while (s0 > 0 && d0 > 0 && kept(s0 - 1, d0 - 1)) { s0--; d0-- }
            while (s1 + 1 < base.size && d1 + 1 < words.size && kept(s1 + 1, d1 + 1)) { s1++; d1++ }
            if (s0 == i0 && s1 == i1) return null
            if (s1 - s0 + 1 > MAX_CONTEXT_WORDS || d1 - d0 + 1 > MAX_CONTEXT_WORDS) {
                if (s0 < i0) { s0 = i0 - 1; d0 = k0 - 1 } else { s0 = i0; d0 = k0 }
                if (s1 > i1) { s1 = i1 + 1; d1 = k1 + 1 } else { s1 = i1; d1 = k1 }
            }
            return base.subList(s0, s1 + 1) to words.subList(d0, d1 + 1)
        }

        val pending = mutableListOf<Triple<List<Word>, List<Word>, List<Word>>>()

        fun record(i0: Int, i1: Int, dst: List<CurWord>) {
            val src = base.subList(i0, i1 + 1)
            val dstWords = dst.map { it.word }
            if (!isGeneral(src.map { normalize(it.text) }, dstWords.map { normalize(it.text) })) {
                // Written once every general rule of this export is known.
                context(i0, i1, dst)?.let { (ctxSrc, ctxDst) -> pending += Triple(ctxSrc, ctxDst, src) }
                return
            }
            commit(src, dstWords, MAX_PHRASE_WORDS, contextRule = false)
        }

        for (op in SequenceMatcher(a, b).opcodes) {
            when (op.tag) {
                SequenceMatcher.Tag.EQUAL -> confirm(base.subList(op.i1, op.i2))
                SequenceMatcher.Tag.REPLACE -> {
                    val groups = align(a.subList(op.i1, op.i2), b.subList(op.j1, op.j2))
                    val lone = groups.size == 1
                    for ((ga, gb, ha, hb) in groups.map { it.toList() }) {
                        if (ga == gb || ha == hb) continue // pure deletion or insertion: nothing to learn
                        val src = base.subList(op.i1 + ga, op.i1 + gb)
                        val dst = cur.subList(op.j1 + ha, op.j1 + hb)
                        if (a.subList(op.i1 + ga, op.i1 + gb) == b.subList(op.j1 + ha, op.j1 + hb)) {
                            confirm(src)
                            continue
                        }
                        val dstCards = dst.map { sortedCards[it.card] }
                        if (!overlaps(src.first().start, src.last().end, dstCards.minOf { it.start }, dstCards.maxOf { it.end })) continue
                        if (!lone) {
                            val s = a.subList(op.i1 + ga, op.i1 + gb).joinToString("")
                            val t = b.subList(op.j1 + ha, op.j1 + hb).joinToString("")
                            if (SequenceMatcher.ratio(s, t) < MIN_PAIR_SIMILARITY) continue
                        }
                        record(op.i1 + ga, op.i1 + gb - 1, dst)
                    }
                }
                else -> Unit
            }
        }

        for ((src, dstWords, edited) in pending) commit(src, dstWords, MAX_CONTEXT_WORDS, contextRule = true, edited = edited)
        return Report(learned, rejected, confirmed)
    }

    fun baselineFromCards(cards: List<CaptionCard>): List<Word> = cards.flatMap { it.words }
}

/** Same file format as the desktop app's corrections.json. */
@Serializable
private data class CorrectionsFile(
    val version: Int = 1,
    val rules: Map<String, Map<String, Int>> = emptyMap(),
    val context: List<String> = emptyList(),
)

/**
 * Rules keyed by the normalised Whisper phrase, with a vote count per target so a phrase corrected two
 * different ways resolves to the more frequent one. Thread-confined to whoever holds it; [JobRunner]
 * loads a fresh copy per job.
 */
class CorrectionStore(private val file: File?) {
    val rules = LinkedHashMap<String, MutableMap<String, Int>>()
    private val contextKeys = mutableSetOf<String>()

    init {
        val source = file
        if (source != null && source.exists()) {
            runCatching {
                val data = AppJson.decodeFromString(CorrectionsFile.serializer(), source.readText())
                for ((key, targets) in data.rules) {
                    val clean = targets.filterValues { it > 0 }.toMutableMap()
                    if (key.isNotEmpty() && clean.isNotEmpty()) rules[key] = clean
                }
                contextKeys += data.context.filter { it in rules }
            }
        }
    }

    fun save() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val data = CorrectionsFile(1, rules.toSortedMap(), contextKeys.filter { it in rules }.sorted())
        f.writeAtomically(AppJson.encodeToString(CorrectionsFile.serializer(), data))
    }

    fun best(key: String): String? = rules[key]?.maxByOrNull { it.value }?.key

    fun votes(key: String): Int = best(key)?.let { rules[key]!![it] } ?: 0

    fun isContext(key: String): Boolean = key in contextKeys

    fun learn(key: String, target: String, context: Boolean = false) {
        val targets = rules.getOrPut(key) { mutableMapOf() }
        targets[target] = (targets[target] ?: 0) + 1
        if (context) contextKeys += key
    }

    fun confirm(key: String) {
        val target = best(key) ?: return
        rules[key]!![target] = rules[key]!![target]!! + 1
    }

    /** Takes a vote from the target currently applied for [key]. */
    fun reject(key: String): String? {
        val target = best(key) ?: return null
        val targets = rules[key]!!
        targets[target] = targets[target]!! - 1
        if (targets[target]!! <= 0) targets.remove(target)
        if (targets.isEmpty()) {
            rules.remove(key)
            contextKeys -= key
        }
        return target
    }

    fun remove(key: String) {
        rules.remove(key)
        contextKeys -= key
    }

    fun maxWords(context: Boolean? = null): Int =
        rules.keys.filter { context == null || (it in contextKeys) == context }.maxOfOrNull { it.split(" ").size } ?: 0
}
