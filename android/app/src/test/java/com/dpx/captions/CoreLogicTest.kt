package com.dpx.captions

import com.dpx.captions.core.CaptionBuilder
import com.dpx.captions.core.CaptionOps
import com.dpx.captions.core.Presets
import com.dpx.captions.core.ProjectFile
import com.dpx.captions.core.Serbian
import com.dpx.captions.model.Aspect
import com.dpx.captions.model.AnimationStyle
import com.dpx.captions.model.CaptionCard
import com.dpx.captions.model.CaptionFormatting
import com.dpx.captions.model.Density
import com.dpx.captions.model.TextCase
import com.dpx.captions.model.Word
import com.dpx.captions.render.CaptionLayout
import com.dpx.captions.render.TextMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun card(start: Double, end: Double, text: String): CaptionCard {
    val parts = text.split(" ")
    val step = (end - start) / parts.size
    return CaptionCard(
        listOf(parts.mapIndexed { i, t -> Word(t, start + i * step, start + (i + 1) * step) }),
        start, end,
    )
}

private fun CaptionCard.texts() = words.map { it.text }

class SerbianTest {
    @Test
    fun transliteratesDigraphsAndDiacritics() {
        assertEquals("Ovo je test sa Nj i Dž i Lj.", Serbian.cyrillicToLatin("Ово је тест са Њ и Џ и Љ."))
        assertEquals("čćšžđ", Serbian.cyrillicToLatin("чћшжђ"))
    }

    @Test
    fun leavesLatinUntouched() {
        assertEquals("Već je latinica, 123!", Serbian.cyrillicToLatin("Već je latinica, 123!"))
    }
}

class CaptionBuilderTest {
    private val words = listOf(
        Word("Danas", 0.0, 0.4), Word("radimo", 0.4, 0.9), Word("noge", 0.9, 1.3),
        Word("i", 1.3, 1.4), Word("ledja", 1.4, 1.9), Word("u", 1.9, 2.0), Word("teretani", 2.0, 2.6),
    )

    @Test
    fun customDensityMatchesDesktopOutput() {
        // Same input and expectations as the desktop (Python) implementation's smoke test.
        val cards = CaptionBuilder.build(words, CaptionFormatting(Density.CUSTOM, 17, 1, TextCase.UPPERCASE))
        assertEquals(listOf("DANAS", "RADIMO", "NOGE"), cards[0].texts())
        assertEquals(listOf("I", "LEDJA", "U"), cards[1].texts())
        assertEquals(listOf("TERETANI"), cards[2].texts())
        assertEquals(0.0, cards[0].start, 1e-9)
        assertEquals(1.3, cards[0].end, 1e-9)
        assertEquals(3, cards.size)
    }

    @Test
    fun singleWordDensityGivesOneWordPerCard() {
        val cards = CaptionBuilder.build(words, CaptionFormatting(density = Density.SINGLE_WORD, textCase = TextCase.NORMAL))
        assertEquals(words.size, cards.size)
        assertEquals(words.map { it.text }, cards.map { it.texts().single() })
    }

    @Test
    fun removesPunctuationButKeepsSerbianLetters() {
        val input = listOf(Word("čovek,", 0.0, 0.5), Word("šta!", 0.5, 1.0), Word("ćao.", 1.0, 1.5))
        val cards = CaptionBuilder.build(
            input,
            CaptionFormatting(density = Density.CUSTOM, maxCharsPerLine = 40, removePunctuation = true, textCase = TextCase.NORMAL),
        )
        assertEquals(listOf("čovek", "šta", "ćao"), cards.single().texts())
    }

    @Test
    fun casesAreApplied() {
        val input = listOf(Word("kreatin", 0.0, 0.5))
        fun with(case: TextCase) = CaptionBuilder.build(input, CaptionFormatting(textCase = case)).single().texts().single()
        assertEquals("KREATIN", with(TextCase.UPPERCASE))
        assertEquals("kreatin", with(TextCase.LOWERCASE))
        assertEquals("Kreatin", with(TextCase.TITLE_CASE))
    }

    @Test
    fun censorsListedWords() {
        val input = listOf(Word("jebiga", 0.0, 0.5), Word("ok", 0.5, 1.0))
        val cards = CaptionBuilder.build(
            input,
            CaptionFormatting(censorWords = true, censorWordList = listOf("jebiga"), textCase = TextCase.NORMAL, maxCharsPerLine = 40),
        )
        assertEquals(listOf("j*****", "ok"), cards.single().texts())
    }

    @Test
    fun standardDensityBreaksOnLongPause() {
        val input = listOf(Word("jedan", 0.0, 0.4), Word("dva", 0.4, 0.8), Word("tri", 3.0, 3.4))
        val cards = CaptionBuilder.build(input, CaptionFormatting(density = Density.STANDARD))
        assertEquals(2, cards.size)
    }
}

class CaptionOpsTest {
    private val formatting = CaptionFormatting()

    @Test
    fun rewriteKeepsTimingWhenWordCountIsUnchanged() {
        val original = card(0.0, 2.0, "STO SAT")
        val before = original.words.map { it.start }
        val rewritten = CaptionOps.rewriteText(original, "STA SAT", formatting)!!
        assertEquals(listOf("STA", "SAT"), rewritten.texts())
        assertEquals(before, rewritten.words.map { it.start })
    }

    @Test
    fun rewriteRedistributesWhenWordCountChanges() {
        val rewritten = CaptionOps.rewriteText(card(0.0, 3.0, "A"), "JEDAN DVA TRI", formatting)!!
        assertEquals(listOf(0.0, 1.0, 2.0), rewritten.words.map { it.start })
    }

    @Test
    fun rewrittenTextFollowsTheProjectsCaseAndPunctuationSettings() {
        val original = card(0.0, 2.0, "A B")
        val upper = CaptionOps.rewriteText(original, "kreatin, test", CaptionFormatting(removePunctuation = true))!!
        assertEquals(listOf("KREATIN", "TEST"), upper.texts())

        val untouched = CaptionOps.rewriteText(original, "kreatin, test", CaptionFormatting(textCase = TextCase.NORMAL))!!
        assertEquals(listOf("kreatin,", "test"), untouched.texts())
    }

    @Test
    fun blankTextRemovesTheCard() {
        assertNull(CaptionOps.rewriteText(card(0.0, 1.0, "X"), "   ", formatting))
    }

    @Test
    fun overwriteTrimsPartialAndRemovesCoveredCards() {
        val cards = listOf(card(0.0, 1.0, "AAA"), card(1.0, 2.0, "BBB"), card(2.0, 3.0, "CCC"), card(3.0, 4.0, "DDD"))

        // Same sequence as the desktop test: stretch card 0 to 0.5-1.5 ...
        val stretched = cards.toMutableList().also { it[0] = it[0].copy(start = 0.5, end = 1.5) }
        val partial = CaptionOps.applyOverwrite(stretched, 0)
        assertEquals(listOf(0.5 to 1.5, 1.5 to 2.0, 2.0 to 3.0, 3.0 to 4.0), partial.cards.map { it.start to it.end })

        // ... then drag it over BBB and CCC entirely.
        val moved = partial.cards.toMutableList().also { it[partial.selected!!] = it[partial.selected!!].copy(start = 1.9, end = 3.1) }
        val full = CaptionOps.applyOverwrite(moved, partial.selected!!)
        assertEquals(listOf("BBB", "AAA", "DDD"), full.cards.map { it.texts().single() })
        assertEquals(listOf(1.5 to 1.9, 1.9 to 3.1, 3.1 to 4.0), full.cards.map { it.start to it.end })
    }

    @Test
    fun splitSharesWordsAtThePlayhead() {
        val result = CaptionOps.splitAt(listOf(card(0.0, 4.0, "JEDAN DVA TRI CETIRI")), 2.0)!!
        assertEquals(listOf("JEDAN", "DVA"), result.cards[0].texts())
        assertEquals(listOf("TRI", "CETIRI"), result.cards[1].texts())
        assertEquals(2.0, result.cards[0].end, 1e-9)
        assertEquals(2.0, result.cards[1].start, 1e-9)
    }

    @Test
    fun splitOutsideACardDoesNothing() {
        assertNull(CaptionOps.splitAt(listOf(card(0.0, 1.0, "A B")), 5.0))
    }

    @Test
    fun addAtOverwritesWhatItLandsOn() {
        val result = CaptionOps.addAt(listOf(card(0.0, 2.0, "STARI")), 1.0, 10.0, "NOVI")!!
        assertEquals(2, result.cards.size)
        assertEquals(0.0 to 1.0, result.cards[0].start to result.cards[0].end)
        assertEquals(1.0 to 2.0, result.cards[1].start to result.cards[1].end)
        assertEquals(1, result.selected)
    }

    @Test
    fun findReplaceKeepsEveryWordsTiming() {
        val cards = listOf(card(0.0, 2.0, "kreatin je kreatin"), card(2.0, 3.0, "KREATIN"))
        val (updated, count) = CaptionOps.findReplace(cards, "kreatin", "Kreatin", wholeWord = true)
        assertEquals(3, count)
        assertEquals(listOf("Kreatin", "je", "Kreatin"), updated[0].texts())
        assertEquals(cards[0].words.map { it.start }, updated[0].words.map { it.start })
    }

    @Test
    fun findReplaceTreatsReplacementLiterally() {
        val (updated, _) = CaptionOps.findReplace(listOf(card(0.0, 1.0, "A")), "A", "\$1\\n")
        assertEquals("\$1\\n", updated[0].texts().single())
    }

    @Test
    fun wholeWordDoesNotMatchInsideAWord() {
        val (_, count) = CaptionOps.findReplace(listOf(card(0.0, 1.0, "kreatinin")), "kreatin", "X", wholeWord = true)
        assertEquals(0, count)
    }

    @Test
    fun duplicateAndMerge() {
        val dup = CaptionOps.duplicate(listOf(card(0.0, 1.0, "A B")), 0, 10.0)!!
        assertEquals(2, dup.cards.size)
        assertEquals(1.0, dup.cards[1].start, 1e-9)

        val merged = CaptionOps.mergeWithNext(listOf(card(0.0, 1.0, "A"), card(1.0, 2.0, "B")), 0)!!
        assertEquals(1, merged.cards.size)
        assertEquals(listOf("A", "B"), merged.cards[0].texts())
        assertEquals(2.0, merged.cards[0].end, 1e-9)
    }
}

class PersistenceTest {
    private val autosubsPreset = """
        {
          "description": "OpenSans Condensed Bold, 0.1 size, text color fill highlight where the word itself changes color.",
          "macroSettings": {
            "AnimationLength": 0.2, "OutlineColorGreen": 0, "OutlineColorRed": 0, "OutlineEnabled": 1,
            "AnimationLevel": 0, "AnimationMode": 0, "Style": "Bold", "PopInEnabled": 0,
            "FillColorBlue": 1, "ShadowColorGreen": 0, "ShadowColorRed": 0, "FillColorGreen": 1,
            "SlideUpEnabled": 0, "FillEnabled": 1, "OutlineColorBlue": 0, "HighlightStyle": 0,
            "TextPosition": [0.5, 0.2, 0], "HighlightColorBlue": 0.164, "ShadowColorBlue": 0,
            "ShadowEnabled": 1, "HighlightColorGreen": 1, "HighlightColorRed": 1, "HighlightEnabled": 1,
            "TextSize": 0.1, "OutlineThickness": 0.08, "FillColorRed": 1,
            "HighlightExtendHorizontal": -0.04, "FadeEnabled": 0, "HighlightExtendVertical": 0,
            "HighlightRound": 0, "Font": "Open Sans Condensed"
          },
          "name": "OpenSans Condensed Yellow Text Fill",
          "version": 1
        }
    """.trimIndent()

    @Test
    fun loadsTheRealAutoSubsPreset() {
        val style = Presets.parse(autosubsPreset)
        assertEquals("Open Sans Condensed", style.font)
        assertEquals(0.164, style.highlightColorBlue, 1e-9)
        assertEquals(0.08, style.outlineThickness, 1e-9)
        assertTrue(style.outlineEnabled)
        assertTrue(style.shadowEnabled)
        assertFalse(style.popInEnabled)
        // No Bold flag in the preset: weight comes from the Style string.
        assertTrue(style.bold)
        assertEquals(0.5, style.positionX, 1e-9)
        assertEquals(0.2, style.positionY, 1e-9)
    }

    @Test
    fun presetRoundTripsAndKeepsAutoSubsFieldNames() {
        val style = AnimationStyle(font = "Anton", bold = false, textSize = 0.07).withHighlight(com.dpx.captions.model.Rgb(0.2, 0.9, 0.1))
        val json = Presets.serialize(style, "Mine")
        assertTrue(json.contains("\"macroSettings\""))
        assertTrue(json.contains("\"HighlightColorGreen\""))
        assertEquals("Mine", Presets.nameOf(json))
        assertEquals(style, Presets.parse(json))
    }

    @Test
    fun flagsAreWrittenAsIntsLikeAutoSubs() {
        val json = Presets.serialize(AnimationStyle(popInEnabled = true), "x")
        assertTrue(json.contains("\"PopInEnabled\": 1"))
        assertTrue(json.contains("\"FadeEnabled\": 0"))
    }

    @Test
    fun projectRoundTrips() {
        val project = ProjectFile(
            id = "abc", name = "Kreatin 101", videoPath = "/data/x.mp4", modelSize = "large-v3-turbo-q5_0",
            formatting = CaptionFormatting(vocabularyContext = "Serbian latin only"),
            style = AnimationStyle(font = "Franklin Gothic Demi"),
            aspect = Aspect.VERTICAL_9_16,
            cards = listOf(card(0.0, 1.0, "KREATIN 101")),
            width = 1080, height = 1920, durationSeconds = 89.5, fps = 30.0,
        )
        val restored = ProjectFile.fromJson(project.toJson())
        assertEquals(project, restored)
    }

    @Test
    fun projectJsonUsesTheDesktopFieldNames() {
        val json = ProjectFile(
            id = "a", name = "n", videoPath = "v", modelSize = "m",
            formatting = CaptionFormatting(), style = AnimationStyle(), cards = emptyList(),
        ).toJson()
        for (key in listOf("\"video_path\"", "\"model_size\"", "\"max_chars_per_line\"", "\"text_case\"", "\"cards\"")) {
            assertTrue("missing $key", json.contains(key))
        }
    }
}

class AspectTest {
    @Test
    fun canvasKeepsTheLongSide() {
        assertEquals(1080 to 1920, Aspect.AUTO.canvasSize(1080, 1920))
        assertEquals(1080 to 1920, Aspect.VERTICAL_9_16.canvasSize(1080, 1920))
        assertEquals(1920 to 1080, Aspect.LANDSCAPE_16_9.canvasSize(1080, 1920))
        assertEquals(1920 to 1920, Aspect.SQUARE_1_1.canvasSize(1080, 1920))
        assertEquals(1536 to 1920, Aspect.PORTRAIT_4_5.canvasSize(1080, 1920))
        assertEquals(1080 to 1920, Aspect.VERTICAL_9_16.canvasSize(1920, 1080))
    }

    @Test
    fun fourKStaysFourK() {
        val (w, h) = Aspect.LANDSCAPE_16_9.canvasSize(2160, 3840)
        assertEquals(3840, w)
        assertEquals(2160, h)
    }

    @Test
    fun dimensionsAreEven() {
        for (aspect in Aspect.entries) {
            val (w, h) = aspect.canvasSize(1079, 1919)
            assertEquals(0, w % 2)
            assertEquals(0, h % 2)
        }
    }
}

class CaptionLayoutTest {
    // 10 px per character, 40 px line.
    private val metrics = object : TextMetrics {
        override fun width(text: String) = text.length * 10f
        override val ascent = 30f
        override val descent = 10f
    }
    private val style = AnimationStyle(textPosition = listOf(0.5, 0.2, 0.0), textSize = 0.1)

    @Test
    fun wordsAreCentredAroundThePositionAndSpacedByASpace() {
        val c = card(0.0, 2.0, "AB CD")
        val placed = CaptionLayout.layout(c, style, 1000, 2000, metrics)
        // widths 20 + 10 (space) + 20 = 50, centred on x=500 -> starts at 475.
        assertEquals(485f, placed[0].centerX, 1e-3f)
        assertEquals(515f, placed[1].centerX, 1e-3f)
        // Y is measured up from the bottom: 0.2 -> 80% of the way down.
        assertEquals(1600f, placed[0].centerY, 1e-3f)
    }

    @Test
    fun multiLineBlockIsCentredVertically() {
        val two = CaptionCard(
            listOf(listOf(Word("AA", 0.0, 1.0)), listOf(Word("BB", 1.0, 2.0))), 0.0, 2.0,
        )
        val placed = CaptionLayout.layout(two, style, 1000, 2000, metrics)
        assertEquals(1600f - 20f, placed[0].centerY, 1e-3f)
        assertEquals(1600f + 20f, placed[1].centerY, 1e-3f)
    }

    @Test
    fun popPeaksMidAnimationAndSettlesBack() {
        val word = Word("X", 1.0, 2.0)
        val s = AnimationStyle(animationLength = 0.2, highlightPopScale = 1.1)
        assertEquals(1.0, CaptionLayout.popScale(s, word, 0.9), 1e-9)
        assertEquals(1.0, CaptionLayout.popScale(s, word, 1.0), 1e-9)
        assertEquals(1.1, CaptionLayout.popScale(s, word, 1.1), 1e-9)
        assertEquals(1.0, CaptionLayout.popScale(s, word, 1.2), 1e-9)
        assertEquals(1.0, CaptionLayout.popScale(s, word, 1.5), 1e-9)
    }

    @Test
    fun entranceProgressIsClamped() {
        val c = card(1.0, 3.0, "A")
        val s = AnimationStyle(animationLength = 0.2)
        assertEquals(0.0, CaptionLayout.entranceProgress(s, c, 0.5), 1e-9)
        assertEquals(0.5, CaptionLayout.entranceProgress(s, c, 1.1), 1e-9)
        assertEquals(1.0, CaptionLayout.entranceProgress(s, c, 2.0), 1e-9)
    }

    @Test
    fun activeCardIsFoundByTime() {
        val cards = listOf(card(0.0, 1.0, "A"), card(2.0, 3.0, "B"))
        assertNotNull(CaptionLayout.activeCard(cards, 0.5))
        assertNull(CaptionLayout.activeCard(cards, 1.5))
        assertEquals("B", CaptionLayout.activeCard(cards, 2.5)!!.texts().single())
    }
}
