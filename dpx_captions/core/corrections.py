"""Learned transcript corrections.

After an export, the captions as they were generated (the "baseline") are
compared with the captions as the user left them. Each word the user
rewrote becomes a rule "Whisper wrote X -> it should be Y" (for example
"zahtjeva" -> "zahteva", or "dobit ces" -> "dobices"). The next time
captions are generated, the rules rewrite Whisper's words before they are
grouped into cards, so the editor opens with those fixes already made.

Matching ignores case and punctuation. An edit only counts when the edited
words sit in the same time range as the generated ones, so moving or
deleting cards is not mistaken for a correction. If the user undoes an
automatic correction, that rule loses a vote and is dropped once it has
none left.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field, replace
from difflib import SequenceMatcher
from pathlib import Path
from typing import TYPE_CHECKING

from .serbian import cyrillic_to_latin
from .transcribe import Word

if TYPE_CHECKING:
    from .caption_builder import CaptionCard

# Longest phrase (in words) a rule can match, on either side.
MAX_PHRASE_WORDS = 3
# Seconds of slack when checking that an edit sits where the generated words were.
TIME_TOLERANCE = 0.35
# In a block where several neighbouring words changed, a pairing must be at
# least this similar to count; a lone changed word or phrase needs no similarity.
MIN_PAIR_SIMILARITY = 0.5
# Extra alignment cost per additional word in a group, so a deleted word next
# to a fixed one isn't folded into the fix ("pa zahtjeva" -> "zahteva").
_GROUP_PENALTY = 1

_NON_WORD = re.compile(r"\W+", re.UNICODE)
_EDGES = re.compile(r"^(\W*)(.*?)(\W*)$", re.UNICODE | re.DOTALL)


def normalize(text: str) -> str:
    """Case- and punctuation-insensitive form used to compare words."""
    return _NON_WORD.sub("", cyrillic_to_latin(text).casefold())


def _split_edges(text: str) -> tuple[str, str, str]:
    lead, core, trail = _EDGES.match(text).groups()
    return lead, core, trail


def _core(text: str) -> str:
    return _split_edges(text)[1]


def _case_pattern(text: str) -> str:
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return "none"
    if all(c.islower() for c in letters):
        return "lower"
    if len(letters) > 1 and all(c.isupper() for c in letters):
        return "upper"
    if letters[0].isupper() and all(c.islower() for c in letters[1:]):
        return "title"
    return "mixed"


def _stored_target(target: str, source_display: str, case_meaningful: bool) -> str:
    """Lowercases the target unless its casing is deliberate.

    Casing is deliberate only when captions were shown in normal case and the
    user's casing isn't just inherited from the source (a capital at the start
    of a sentence); "dpx" -> "DPX" keeps its capitals, "Zahtjeva" -> "Zahteva"
    does not, so the rule still works mid-sentence.
    """
    if not case_meaningful:
        return target.lower()
    pattern = _case_pattern(target)
    if pattern in ("lower", "none") or pattern == _case_pattern(source_display):
        return target.lower()
    return target


def _apply_case(target: str, source_display: str) -> str:
    if any(c.isupper() for c in target):
        return target  # deliberate casing, learned as-is
    pattern = _case_pattern(source_display)
    if pattern == "upper":
        return target.upper()
    if pattern == "title":
        return target[:1].upper() + target[1:]
    return target


# --------------------------------------------------------------------- store
def corrections_path() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    return Path(base) / "DPX Captions" / "corrections.json"


@dataclass
class CorrectionStore:
    """Rules keyed by the normalized Whisper phrase. Each rule keeps a vote
    count per target, so a phrase corrected two different ways resolves to the
    more frequent one."""

    rules: dict[str, dict[str, int]] = field(default_factory=dict)
    path: Path | None = None

    @classmethod
    def load(cls, path: Path | None = None) -> CorrectionStore:
        path = path or corrections_path()
        rules: dict[str, dict[str, int]] = {}
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            for key, targets in data.get("rules", {}).items():
                clean = {str(t): int(n) for t, n in targets.items() if int(n) > 0}
                if key and clean:
                    rules[key] = clean
        except (OSError, ValueError, TypeError, AttributeError):
            pass
        return cls(rules=rules, path=path)

    def save(self) -> None:
        path = self.path or corrections_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        data = {"version": 1, "rules": dict(sorted(self.rules.items()))}
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.replace(path)

    def best(self, key: str) -> str | None:
        targets = self.rules.get(key)
        if not targets:
            return None
        return max(targets.items(), key=lambda item: item[1])[0]

    def votes(self, key: str) -> int:
        target = self.best(key)
        return self.rules[key][target] if target is not None else 0

    def learn(self, key: str, target: str) -> None:
        targets = self.rules.setdefault(key, {})
        targets[target] = targets.get(target, 0) + 1

    def confirm(self, key: str) -> None:
        target = self.best(key)
        if target is not None:
            self.rules[key][target] += 1

    def reject(self, key: str) -> str | None:
        """Takes a vote from the target currently applied for `key`."""
        target = self.best(key)
        if target is None:
            return None
        targets = self.rules[key]
        targets[target] -= 1
        if targets[target] <= 0:
            del targets[target]
        if not targets:
            del self.rules[key]
        return target

    def remove(self, key: str) -> None:
        self.rules.pop(key, None)

    def max_words(self) -> int:
        return max((len(key.split()) for key in self.rules), default=0)


# --------------------------------------------------------------------- apply
def _rewrite(src: list[Word], key: str, target: str) -> list[Word]:
    lead = _split_edges(src[0].text)[0]
    trail = _split_edges(src[-1].text)[2]
    source_display = " ".join(_core(w.text) for w in src)
    tokens = _apply_case(target, source_display).split()
    probability = min(w.probability for w in src)

    if len(tokens) == len(src):
        spans = [(w.start, w.end) for w in src]
    else:
        start, end = src[0].start, src[-1].end
        weights = [len(t) + 1 for t in tokens]
        total = sum(weights)
        spans, t = [], start
        for weight in weights:
            nxt = t + (end - start) * weight / total
            spans.append((t, nxt))
            t = nxt

    tokens[0] = lead + tokens[0]
    tokens[-1] = tokens[-1] + trail
    return [
        Word(text=tok, start=s, end=e, probability=probability, source=key)
        for tok, (s, e) in zip(tokens, spans)
    ]


def apply_corrections(words: list[Word], store: CorrectionStore) -> tuple[list[Word], list[tuple[str, str]]]:
    """Rewrites Whisper's words with the learned rules, longest phrase first.

    Returns the new word list and (whisper phrase, replacement) per rewrite.
    """
    max_words = store.max_words()
    if not max_words:
        return list(words), []

    norms = [normalize(w.text) for w in words]
    matchable = [i for i, n in enumerate(norms) if n]
    out: list[Word] = []
    applied: list[tuple[str, str]] = []
    copied_until = 0
    k = 0
    while k < len(matchable):
        match = None
        for length in range(min(max_words, len(matchable) - k), 0, -1):
            key = " ".join(norms[matchable[k + m]] for m in range(length))
            target = store.best(key)
            if target is not None and " ".join(normalize(t) for t in target.split()) != key:
                match = (length, key, target)
                break
        if match is None:
            k += 1
            continue

        length, key, target = match
        first, last = matchable[k], matchable[k + length - 1]
        out.extend(words[copied_until:first])
        src = [words[i] for i in matchable[k:k + length]]
        new_words = _rewrite(src, key, target)
        out.extend(new_words)
        applied.append((" ".join(w.text for w in src), " ".join(w.text for w in new_words)))
        copied_until = last + 1
        k += length

    out.extend(words[copied_until:])
    return out, applied


# --------------------------------------------------------------------- learn
@dataclass
class LearnReport:
    learned: list[tuple[str, str]] = field(default_factory=list)   # (whisper phrase, correction)
    rejected: list[tuple[str, str]] = field(default_factory=list)  # (whisper phrase, undone correction)
    confirmed: int = 0

    @property
    def changed(self) -> bool:
        return bool(self.learned or self.rejected or self.confirmed)


def _align(a: list[str], b: list[str]) -> list[tuple[int, int, int, int]]:
    """Pairs up groups of 0-3 words in `a` with 0-3 words in `b`, minimizing
    the number of characters that don't line up. This is what lets one
    changed block hold a merge ("dobit ces" -> "dobices"), a fix and a
    deletion side by side. Returns (a_start, a_end, b_start, b_end) per group."""
    n, m = len(a), len(b)
    inf = float("inf")
    cost = [[inf] * (m + 1) for _ in range(n + 1)]
    back: list[list[tuple[int, int] | None]] = [[None] * (m + 1) for _ in range(n + 1)]
    cost[0][0] = 0

    def relax(i: int, j: int, value: float, frm: tuple[int, int]) -> None:
        if value < cost[i][j]:
            cost[i][j] = value
            back[i][j] = frm

    for i in range(n + 1):
        for j in range(m + 1):
            c = cost[i][j]
            if c == inf:
                continue
            if i < n:
                relax(i + 1, j, c + len(a[i]), (i, j))
            if j < m:
                relax(i, j + 1, c + len(b[j]), (i, j))
            for k in range(1, MAX_PHRASE_WORDS + 1):
                if i + k > n:
                    break
                s = "".join(a[i:i + k])
                for l in range(1, MAX_PHRASE_WORDS + 1):
                    if j + l > m:
                        break
                    t = "".join(b[j:j + l])
                    matched = sum(block.size for block in SequenceMatcher(None, s, t, autojunk=False).get_matching_blocks())
                    relax(i + k, j + l, c + len(s) + len(t) - 2 * matched + _GROUP_PENALTY * (k + l - 2), (i, j))

    groups = []
    i, j = n, m
    while (i, j) != (0, 0):
        pi, pj = back[i][j]
        groups.append((pi, i, pj, j))
        i, j = pi, pj
    groups.reverse()
    return groups


def _overlaps(a_start: float, a_end: float, b_start: float, b_end: float) -> bool:
    return a_start < b_end + TIME_TOLERANCE and b_start < a_end + TIME_TOLERANCE


def _applications(words: list[Word]) -> list[tuple[str, float]]:
    """Runs of consecutive words produced by the same rule application."""
    runs, prev = [], None
    for w in words:
        if w.source is not None and w.source != prev:
            runs.append((w.source, w.start))
        prev = w.source
    return runs


def learn_from_edits(
    baseline: list[Word],
    cards: list[CaptionCard],
    store: CorrectionStore,
    case_meaningful: bool = False,
    seen: set[str] | None = None,
) -> LearnReport:
    """Updates `store` from the difference between the generated words and
    the edited cards. `seen` holds signatures of edits already counted for
    this baseline, so exporting the same project twice doesn't double-count."""
    seen = seen if seen is not None else set()
    report = LearnReport()

    base = [w for w in baseline if normalize(w.text)]
    cur = [
        (w, card)
        for card in sorted(cards, key=lambda c: c.start)
        for w in card.words
        if normalize(w.text)
    ]
    a = [normalize(w.text) for w in base]
    b = [normalize(w.text) for w, _ in cur]

    def confirm(words: list[Word]) -> None:
        for key, start in _applications(words):
            sig = f"confirm|{key}|{start:.2f}"
            if sig not in seen and store.best(key) is not None:
                seen.add(sig)
                store.confirm(key)
                report.confirmed += 1

    def record(src: list[Word], dst: list[tuple[Word, CaptionCard]]) -> None:
        # Starred words are censoring ("PIČ****"), not a transcription fix;
        # stripping the stars would learn a truncated word.
        if any("*" in w.text for w in src) or any("*" in w.text for w, _ in dst):
            return
        original: list[str] = []
        undone: list[str] = []
        prev = None
        for w in src:
            if w.source is None:
                original.append(normalize(w.text))
            elif w.source != prev:
                original.extend(w.source.split())
                undone.append(w.source)
            prev = w.source

        target_tokens = [_core(w.text) for w, _ in dst]
        target_tokens = [t for t in target_tokens if t]
        if not target_tokens:
            return
        source_key = " ".join(original)
        target_norm = " ".join(normalize(t) for t in target_tokens)

        sig = f"edit|{source_key}|{target_norm}|{src[0].start:.2f}"
        if sig in seen:
            return
        seen.add(sig)

        for key in undone:
            old = store.reject(key)
            if old is not None:
                report.rejected.append((key, old))

        if target_norm != source_key and len(original) <= MAX_PHRASE_WORDS:
            source_display = " ".join(_core(w.text) for w in src)
            target = _stored_target(" ".join(target_tokens), source_display, case_meaningful)
            store.learn(source_key, target)
            report.learned.append((source_key, target))

    matcher = SequenceMatcher(None, a, b, autojunk=False)
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            confirm(base[i1:i2])
        elif tag == "replace":
            groups = _align(a[i1:i2], b[j1:j2])
            lone = len(groups) == 1
            for ga, gb, ha, hb in groups:
                if ga == gb or ha == hb:
                    continue  # pure deletion or insertion: nothing to learn
                src = base[i1 + ga:i1 + gb]
                dst = cur[j1 + ha:j1 + hb]
                if a[i1 + ga:i1 + gb] == b[j1 + ha:j1 + hb]:
                    confirm(src)
                    continue
                if not _overlaps(src[0].start, src[-1].end,
                                 min(card.start for _, card in dst), max(card.end for _, card in dst)):
                    continue
                if not lone:
                    s, t = "".join(a[i1 + ga:i1 + gb]), "".join(b[j1 + ha:j1 + hb])
                    if SequenceMatcher(None, s, t, autojunk=False).ratio() < MIN_PAIR_SIMILARITY:
                        continue
                record(src, dst)

    return report


def baseline_from_cards(cards: list[CaptionCard]) -> list[Word]:
    return [replace(w) for card in cards for w in card.words]
