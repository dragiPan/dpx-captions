"""Groups flat word-level transcript output into caption cards.

A "card" is one on-screen caption event (what AutoSubs would create as a
single subtitle clip): one or more lines of words, shown together, that get
burned in with the fill/highlight animation. How words are packed into
cards and lines is controlled by `CaptionFormatting.density`.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, replace

from .style import CaptionFormatting, Density, TextCase
from .transcribe import Word

_SENTENCE_END = re.compile(r"[.!?]$")
_PUNCT = re.compile(r"[^\w\sÀ-ſ']", re.UNICODE)

# (max_chars_per_line, max_lines, pause-break threshold in seconds or None)
_DENSITY_PARAMS: dict[Density, tuple[int, int, float | None]] = {
    Density.SINGLE_WORD: (999, 1, None),
    Density.STANDARD: (25, 1, 0.7),
    Density.MORE: (40, 2, 0.9),
}


@dataclass
class CaptionCard:
    lines: list[list[Word]]
    start: float
    end: float

    @property
    def words(self) -> list[Word]:
        return [w for line in self.lines for w in line]


def _params(formatting: CaptionFormatting) -> tuple[int, int, float | None]:
    if formatting.density == Density.CUSTOM:
        return formatting.max_chars_per_line, max(1, formatting.line_count), None
    return _DENSITY_PARAMS[formatting.density]


def _apply_case(text: str, case: TextCase) -> str:
    if case == TextCase.UPPERCASE:
        return text.upper()
    if case == TextCase.LOWERCASE:
        return text.lower()
    if case == TextCase.TITLE_CASE:
        return text.capitalize()
    return text


def _strip_punct(text: str) -> str:
    return _PUNCT.sub("", text)


def _censor(text: str, word_list: list[str]) -> str:
    lowered = {w.lower().strip() for w in word_list}
    bare = _PUNCT.sub("", text).lower()
    if bare in lowered:
        return text[0] + "*" * max(len(text) - 1, 1) if text else text
    return text


def _display_word(w: Word, formatting: CaptionFormatting) -> Word:
    text = w.text
    if formatting.censor_words and formatting.censor_word_list:
        text = _censor(text, formatting.censor_word_list)
    if formatting.remove_punctuation:
        text = _strip_punct(text)
    text = _apply_case(text, formatting.text_case)
    return replace(w, text=text)


def _line_len(line: list[Word]) -> int:
    return sum(len(w.text) for w in line) + max(len(line) - 1, 0)


def build_captions(words: list[Word], formatting: CaptionFormatting) -> list[CaptionCard]:
    max_chars, max_lines, pause_break = _params(formatting)

    cards: list[CaptionCard] = []
    lines: list[list[Word]] = [[]]
    raw_words_in_card: list[Word] = []  # pre-formatting, to check gaps/sentence end

    def flush():
        nonlocal lines, raw_words_in_card
        non_empty = [l for l in lines if l]
        if non_empty:
            flat = [w for l in non_empty for w in l]
            cards.append(CaptionCard(lines=non_empty, start=flat[0].start, end=flat[-1].end))
        lines = [[]]
        raw_words_in_card = []

    prev_word: Word | None = None
    for raw_word in words:
        disp = _display_word(raw_word, formatting)
        if not disp.text:
            prev_word = raw_word
            continue

        if prev_word is not None and pause_break is not None and raw_words_in_card:
            gap = raw_word.start - prev_word.end
            if gap > pause_break:
                flush()

        current_line = lines[-1]
        tentative = _line_len(current_line + [disp])
        if current_line and tentative > max_chars:
            if len(lines) < max_lines:
                lines.append([disp])
            else:
                flush()
                lines[-1].append(disp)
        else:
            current_line.append(disp)

        raw_words_in_card.append(raw_word)

        if pause_break is not None and _SENTENCE_END.search(raw_word.text):
            flush()

        prev_word = raw_word

    flush()
    return cards
