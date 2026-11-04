package com.dpx.captions

import com.dpx.captions.core.CaptionBuilder
import com.dpx.captions.core.CaptionOps
import com.dpx.captions.core.CorrectionStore
import com.dpx.captions.core.Corrections
import com.dpx.captions.core.Retime
import com.dpx.captions.core.SequenceMatcher
import com.dpx.captions.model.CaptionCard
import com.dpx.captions.model.CaptionFormatting
import com.dpx.captions.model.Density
import com.dpx.captions.model.TextCase
import com.dpx.captions.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun words(text: String, t0: Double = 0.0, step: Double = 0.4) =
    text.split(" ").mapIndexed { i, t -> Word(t, t0 + i * step, t0 + i * step + 0.35, 0.9) }

private fun List<CaptionCard>.texts() = map { c -> c.words.joinToString(" ") { it.text } }

private fun List<CaptionCard>.indexOfText(needle: String) = indexOfFirst { c -> c.words.joinToString(" ") { it.text }.contains(needle) }
    .also { require(it >= 0) { "'$needle' not in ${texts()}" } }

/** Edits a card's text the way the editor does (Retime keeps the timing). */
private fun List<CaptionCard>.edit(needle: String, transform: (String) -> String, f: CaptionFormatting): List<CaptionCard> {
    val i = indexOfText(needle)
    val text = this[i].words.joinToString(" ") { it.text }
    return toMutableList().also { it.removeAt(i); it.addAll(i, CaptionOps.rewriteText(this[i], transform(text), f)) }
}

class SequenceMatcherTest {
    @Test
    fun matchesPythonDifflib() {
        // Values from Python: SequenceMatcher(None, "abcd", "bcde").ratio() == 0.75
        assertEquals(0.75, SequenceMatcher.ratio("abcd", "bcde"), 1e-9)
        // The difflib documentation example.
        val ops = SequenceMatcher("qabxcd".toList(), "abycdf".toList()).opcodes.map { "${it.tag} ${it.i1}:${it.i2} ${it.j1}:${it.j2}" }
        assertEquals(listOf("DELETE 0:1 0:0", "EQUAL 1:3 0:2", "REPLACE 3:4 2:3", "EQUAL 4:6 3:5", "INSERT 6:6 5:6"), ops)
    }
}

class LearningTest {
    private val fmt = CaptionFormatting(density = Density.CUSTOM, maxCharsPerLine = 17, lineCount = 1, textCase = TextCase.UPPERCASE)

    private fun store() = CorrectionStore(File.createTempFile("corrections", ".json").also { it.delete() })

    @Test
    fun learnsFixesMergesAcrossCardsAndFindReplaceButNotDeletions() {
        val store = store()
        val cards0 = CaptionBuilder.build(words("Ovo je trening koji zahtjeva napor, a dobit ćeš rezultate. Pa zahtjeva vrijeme i ovaj strpljenje."), fmt)
        assertEquals(listOf("OVO JE TRENING", "KOJI ZAHTJEVA", "NAPOR, A DOBIT", "ĆEŠ REZULTATE. PA", "ZAHTJEVA VRIJEME", "I OVAJ", "STRPLJENJE."), cards0.texts())
        val baseline = Corrections.baselineFromCards(cards0)

        var cards = cards0
            .edit("KOJI ZAHTJEVA", { it.replace("ZAHTJEVA", "ZAHTEVA") }, fmt)
            .edit("DOBIT", { it.replace("DOBIT", "DOBIĆEŠ") }, fmt)
            .edit("ĆEŠ REZULTATE", { it.replace("ĆEŠ ", "") }, fmt)
            .edit("REZULTATE. PA", { "REZULTATE." }, fmt)
            .edit("ZAHTJEVA VRIJEME", { "ZAHTEVA VRIJEME" }, fmt)
        cards = CaptionOps.findReplace(cards, "VRIJEME", "VREME", wholeWord = true).first
        cards = cards.filterIndexed { i, _ -> i != cards.indexOfText("OVAJ") }

        val seen = mutableSetOf<String>()
        Corrections.learn(baseline, cards, store, seen = seen)
        assertEquals("zahteva", store.best("zahtjeva"))
        assertEquals(2, store.votes("zahtjeva"))
        assertEquals("dobićeš", store.best("dobit ćeš"))
        assertEquals("vreme", store.best("vrijeme"))
        assertFalse("deleted filler folded into the fix", "pa zahtjeva" in store.rules)
        assertFalse("deleted card learned", store.rules.keys.any { "ovaj" in it })

        // Exporting again must not count anything twice.
        val again = Corrections.learn(baseline, cards, store, seen = seen)
        assertTrue(again.learned.isEmpty())
        assertEquals(2, store.votes("zahtjeva"))
    }

    @Test
    fun appliesRulesKeepingPunctuationCaseAndTiming() {
        val store = store().apply {
            learn("zahtjeva", "zahteva"); learn("dobit ćeš", "dobićeš"); learn("vrijeme", "vreme")
        }
        val input = words("Zahtjeva puno rada, ali dobit ćeš sve. Vrijeme je, zahtjeva.", t0 = 10.0)
        val out = Corrections.apply(input, store).words
        assertEquals(listOf("Zahteva", "puno", "rada,", "ali", "dobićeš", "sve.", "Vreme", "je,", "zahteva."), out.map { it.text })
        assertEquals(input[4].start, out[4].start, 1e-9)
        assertEquals(input[5].end, out[4].end, 1e-9)
    }

    @Test
    fun undoingAnAutomaticFixWeakensIt() {
        val store = store().apply { learn("vrijeme", "vreme") }
        val applied = Corrections.apply(words("Vrijeme je"), store).words
        val cards = CaptionBuilder.build(applied, fmt)
        val baseline = Corrections.baselineFromCards(cards)
        val undone = CaptionOps.findReplace(cards, "VREME", "VRIJEME", wholeWord = true).first
        val report = Corrections.learn(baseline, undone, store)
        assertEquals(listOf("vrijeme" to "vreme"), report.rejected)
        assertNull(store.best("vrijeme"))
    }

    @Test
    fun contextDependentEditsAreLearnedWithTheirCard() {
        // "RETKO KOJI" -> "RETKO KO I" must not turn every "koji" into "ko i"; "MI" moved on is not deleted.
        val store = store()
        val cards0 = CaptionBuilder.build(words("redko koji ispetnih žila mi"), CaptionFormatting(maxCharsPerLine = 12, textCase = TextCase.UPPERCASE))
        assertEquals(listOf("REDKO KOJI", "ISPETNIH", "ŽILA MI"), cards0.texts())
        val merged = CaptionOps.mergeWithNext(cards0, 1, CaptionFormatting(maxCharsPerLine = 40))!!.cards
        val baseline = Corrections.baselineFromCards(merged)
        var cards = merged
            .edit("REDKO KOJI", { "RETKO KO I" }, fmt)
            .edit("ISPETNIH", { "IZ PETNIH ŽILA" }, CaptionFormatting(maxCharsPerLine = 40))
        Corrections.learn(baseline, cards, store)

        assertEquals("retko", store.best("redko"))
        assertFalse(store.isContext("redko"))
        assertNull("bare 'koji' must not become a rule", store.best("koji"))
        assertEquals("retko ko i", store.best("retko koji"))
        assertTrue(store.isContext("retko koji"))
        assertEquals("iz petnih žila", store.best("ispetnih žila"))
        assertFalse(store.rules.keys.any { it.endsWith(" mi") })

        fun run(text: String) = Corrections.apply(words(text), store).words.joinToString(" ") { it.text }
        assertEquals("Retko ko i to može.", run("Redko koji to može."))
        assertEquals("Retko ko i to može.", run("Retko koji to može."))
        assertEquals("Koji god hoćeš, koji?", run("Koji god hoćeš, koji?"))
        assertEquals("Iz petnih žila mi je stalo.", run("Ispetnih žila mi je stalo."))
    }

    @Test
    fun censoredWordsAreNotLearned() {
        val store = store()
        val cards0 = CaptionBuilder.build(words("piča u ja"), fmt)
        val edited = cards0.edit("PIČA", { "PIČ**** U JA" }, fmt)
        Corrections.learn(Corrections.baselineFromCards(cards0), edited, store)
        assertTrue(store.rules.isEmpty())
    }

    @Test
    fun storeRoundTripsInTheDesktopFormat() {
        val file = File.createTempFile("corrections", ".json")
        CorrectionStore(file).apply { learn("zahtjeva", "zahteva"); learn("retko koji", "retko ko i", context = true); save() }
        val text = file.readText()
        assertTrue(text.contains("\"rules\"") && text.contains("\"context\""))
        val loaded = CorrectionStore(file)
        assertEquals("zahteva", loaded.best("zahtjeva"))
        assertTrue(loaded.isContext("retko koji"))
        assertFalse(loaded.isContext("zahtjeva"))
    }
}

class RetimeTest {
    private val fmt = CaptionFormatting(density = Density.CUSTOM, maxCharsPerLine = 17, lineCount = 1, textCase = TextCase.UPPERCASE)
    // Uneven pacing and a pause before "ZAISTA", as Whisper reports it.
    private val w = listOf(Word("OVO", 10.00, 10.18), Word("JE", 10.18, 10.30), Word("ZAISTA", 10.95, 11.40), Word("TAKO", 11.40, 11.80))
    private val card = CaptionCard(listOf(w), 10.0, 12.0)

    private fun times(c: CaptionCard) = c.words.map { Triple(it.text, Math.round(it.start * 100) / 100.0, Math.round(it.end * 100) / 100.0) }

    @Test
    fun splittingWithAPipeStartsEachCardOnItsFirstWord() {
        val out = CaptionOps.rewriteText(card, "OVO JE | ZAISTA TAKO", fmt, w)
        assertEquals(2, out.size)
        assertEquals(listOf(Triple("OVO", 10.0, 10.18), Triple("JE", 10.18, 10.3)), times(out[0]))
        assertEquals(listOf(Triple("ZAISTA", 10.95, 11.4), Triple("TAKO", 11.4, 11.8)), times(out[1]))
        assertEquals(listOf(10.0 to 10.95, 10.95 to 12.0), out.map { it.start to it.end })

        val three = CaptionOps.rewriteText(card, "OVO JE | ZAISTA | TAKO", fmt, w)
        assertEquals(listOf(10.0, 10.95, 11.4), three.map { it.start })
    }

    @Test
    fun fixingOrDeletingWordsKeepsEveryoneElsesTime() {
        assertEquals(
            listOf(Triple("OVO", 10.0, 10.18), Triple("JE", 10.18, 10.3), Triple("ZAISTA", 10.95, 11.4), Triple("TAKAV", 11.4, 11.8)),
            times(CaptionOps.rewriteText(card, "OVO JE ZAISTA TAKAV", fmt, w).single()),
        )
        assertEquals(
            listOf(Triple("ZAISTA", 10.95, 11.4), Triple("TAKO", 11.4, 11.8)),
            times(CaptionOps.rewriteText(card, "ZAISTA TAKO", fmt, w).single()),
        )
        assertEquals(Triple("OVOJE", 10.0, 10.3), times(CaptionOps.rewriteText(card, "OVOJE ZAISTA TAKO", fmt, w).single()).first())
    }

    @Test
    fun aNewCardFindsItsWordsInTheTranscript() {
        val blank = CaptionCard(listOf(listOf(Word("NOVI", 10.8, 11.8))), 10.8, 11.8)
        assertEquals(
            listOf(Triple("ZAISTA", 10.95, 11.4), Triple("TAKO", 11.4, 11.8)),
            times(CaptionOps.rewriteText(blank, "ZAISTA TAKO", fmt, w).single()),
        )
    }

    @Test
    fun anUnspokenInsertedWordFillsTheGapInOrder() {
        val out = CaptionOps.rewriteText(card, "OVO JE STVARNO ZAISTA TAKO", fmt, w).single()
        val starts = out.words.map { it.start }
        assertEquals(starts.sorted(), starts)
        assertTrue(out.words[2].start >= 10.3 && out.words[2].start < 10.95)
    }

    @Test
    fun splitAtThePlayheadSnapsToTheNextWord() {
        val result = CaptionOps.splitAt(listOf(card), 10.6, fmt)!!
        assertEquals(listOf(10.0, 10.95), result.cards.map { it.start })
        assertEquals(listOf("ZAISTA", "TAKO"), result.cards[1].words.map { it.text })
    }

    @Test
    fun splitBoundsStayOrdered() {
        assertEquals(listOf(0.0, 0.1, 0.2, 1.0), Retime.splitBounds(0.0, 1.0, listOf(0.0, 0.0)))
    }
}
