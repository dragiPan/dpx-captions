"""Keeps word timings tied to the audio when a card's text is edited.

Rewriting a card used to spread its words evenly across the card, so the
highlight drifted away from the speech as soon as a word was fixed or
removed ("ovo je zaista tako" -> "ovo je" stretched "ovo je" over the
whole card). Instead the edited text is aligned with the card's previous
words: a kept word keeps its time, a fixed, merged or split word takes the
time of the words it replaces, and a deleted word simply goes. A word that
wasn't in the card is looked up in the original Whisper words around the
card; only when it isn't there is it fitted into the free time between
its neighbours.

Text typed with "|" splits the card at those points, each new card
starting when its first word is spoken.
"""

from __future__ import annotations

from difflib import SequenceMatcher
from typing import Sequence

from .caption_builder import CaptionCard
from .corrections import _align, normalize
from .style import CaptionFormatting
from .transcribe import Word

SPLIT_MARK = "|"
# How far outside the card to look for a word in the original transcript.
REFERENCE_PAD = 1.5
REFERENCE_MIN_SIMILARITY = 0.75
MIN_WORD_DURATION = 0.04
MIN_CARD_DURATION = 0.1


def _spread(tokens: Sequence[str], start: float, end: float) -> list[tuple[float, float]]:
    """Splits [start, end] between tokens in proportion to their length."""
    weights = [len(t) + 1 for t in tokens]
    total = sum(weights)
    spans, t = [], start
    for weight in weights:
        nxt = t + (end - start) * weight / total
        spans.append((t, nxt))
        t = nxt
    return spans


def _prev_assigned(times: list, j: int) -> int | None:
    for i in range(j - 1, -1, -1):
        if times[i] is not None:
            return i
    return None


def _next_assigned(times: list, j: int) -> int | None:
    for i in range(j + 1, len(times)):
        if times[i] is not None:
            return i
    return None


def _fill_from_reference(tokens, times, start, end, reference: Sequence[Word]) -> None:
    for j, token in enumerate(tokens):
        if times[j] is not None:
            continue
        norm = normalize(token)
        if not norm:
            continue
        p, n = _prev_assigned(times, j), _next_assigned(times, j)
        after = times[p][0] if p is not None else start - REFERENCE_PAD
        before = times[n][0] if n is not None else end + REFERENCE_PAD

        best: tuple[float, float, Word] | None = None
        for w in reference:
            if not (after < w.start < before):
                continue
            ref = normalize(w.text)
            similarity = 1.0 if ref == norm else SequenceMatcher(None, ref, norm, autojunk=False).ratio()
            if similarity < REFERENCE_MIN_SIMILARITY:
                continue
            key = (similarity, -w.start)  # most similar, then earliest
            if best is None or key > best[:2]:
                best = (similarity, -w.start, w)
        if best is not None:
            times[j] = (best[2].start, best[2].end)


def _interpolate(tokens, times, start, end) -> None:
    j = 0
    while j < len(tokens):
        if times[j] is not None:
            j += 1
            continue
        k = j
        while k < len(tokens) and times[k] is None:
            k += 1
        run = list(range(j, k))
        prev = j - 1 if j > 0 else None
        nxt = k if k < len(tokens) else None
        lo = times[prev][1] if prev is not None else start
        hi = times[nxt][0] if nxt is not None else end
        if hi - lo < MIN_WORD_DURATION * len(run):
            # No free time: share the neighbour's instead of piling up.
            if prev is not None:
                lo = times[prev][0]
                run = [prev] + run
            elif nxt is not None:
                hi = times[nxt][1]
                run = run + [nxt]
            hi = max(hi, lo + MIN_WORD_DURATION * len(run))
        for i, span in zip(run, _spread([tokens[i] for i in run], lo, hi)):
            times[i] = span
        j = k


def retime(
    tokens: Sequence[str],
    old_words: Sequence[Word],
    start: float,
    end: float,
    reference: Sequence[Word] = (),
) -> list[Word]:
    """Timed words for `tokens`, the new text of a card spanning [start, end]
    that used to hold `old_words`. `reference` is the original transcript."""
    if not tokens:
        return []
    times: list[tuple[float, float] | None] = [None] * len(tokens)
    probability = [1.0] * len(tokens)
    source: list[str | None] = [None] * len(tokens)

    # Pairings between words that share almost no letters ("NEW" -> "ZAISTA")
    # are only a fallback: the original transcript gets the first say.
    fallback: dict[int, tuple[float, float]] = {}

    old = [w for w in old_words if w.text.strip()]
    if old:
        a = [normalize(w.text) for w in old]
        b = [normalize(t) for t in tokens]
        for i0, i1, j0, j1 in _align(a, b):
            if i0 == i1 or j0 == j1:
                continue
            src = old[i0:i1]
            if i1 - i0 == j1 - j0:
                spans = [(w.start, w.end) for w in src]
            else:
                spans = _spread(tokens[j0:j1], src[0].start, src[-1].end)
            similar = SequenceMatcher(None, "".join(a[i0:i1]), "".join(b[j0:j1]), autojunk=False).ratio()
            for k, span in enumerate(spans):
                j = j0 + k
                probability[j] = src[k].probability if i1 - i0 == j1 - j0 else min(w.probability for w in src)
                if i1 - i0 == j1 - j0 and a[i0 + k] == b[j]:
                    source[j] = src[k].source
                if similar >= REFERENCE_MIN_SIMILARITY / 2:
                    times[j] = span
                else:
                    fallback[j] = span

    _fill_from_reference(tokens, times, start, end, reference)
    for j, span in fallback.items():
        if times[j] is None:
            times[j] = span
    _interpolate(tokens, times, start, end)
    return [
        Word(text=t, start=s, end=e, probability=p, source=src)
        for t, (s, e), p, src in zip(tokens, times, probability, source)
    ]


def wrap_words(words: list[Word], formatting: CaptionFormatting) -> list[list[Word]]:
    """Packs words into at most `line_count` lines of `max_chars_per_line`."""
    max_chars = formatting.max_chars_per_line or 17
    max_lines = max(1, formatting.line_count)
    lines: list[list[Word]] = [[]]
    for w in words:
        line = lines[-1]
        tentative = sum(len(x.text) for x in line + [w]) + len(line)
        if line and tentative > max_chars and len(lines) < max_lines:
            lines.append([w])
        else:
            line.append(w)
    return [line for line in lines if line]


def split_bounds(start: float, end: float, cuts: Sequence[float]) -> list[float]:
    """Card boundaries for a card split at `cuts`, kept in order and at
    least MIN_CARD_DURATION apart."""
    n = len(cuts) + 1
    if end - start < MIN_CARD_DURATION * n:
        step = (end - start) / n
        return [start + i * step for i in range(n)] + [end]
    bounds = [start]
    for k, cut in enumerate(cuts):
        remaining = n - 1 - k
        low = bounds[-1] + MIN_CARD_DURATION
        high = end - MIN_CARD_DURATION * remaining
        bounds.append(min(max(cut, low), high))
    bounds.append(end)
    return bounds


def rebuild_card(
    card: CaptionCard,
    new_text: str,
    formatting: CaptionFormatting,
    reference: Sequence[Word] = (),
) -> list[CaptionCard]:
    """The card(s) that `new_text` turns `card` into; more than one when the
    text contains SPLIT_MARK. Card edges stay where they were."""
    parts = [p.split() for p in new_text.split(SPLIT_MARK)]
    parts = [p for p in parts if p]
    if not parts:
        return [CaptionCard(lines=[[]], start=card.start, end=card.end)]

    nearby = reference_window(reference, card.start, card.end)
    words = retime([t for p in parts for t in p], card.words, card.start, card.end, nearby)
    groups, i = [], 0
    for part in parts:
        groups.append(words[i:i + len(part)])
        i += len(part)

    bounds = split_bounds(card.start, card.end, [g[0].start for g in groups[1:]])
    return [
        CaptionCard(lines=wrap_words(g, formatting), start=bounds[k], end=bounds[k + 1])
        for k, g in enumerate(groups)
    ]


def reference_window(reference: Sequence[Word], start: float, end: float) -> list[Word]:
    return [w for w in reference if w.end > start - REFERENCE_PAD and w.start < end + REFERENCE_PAD]
