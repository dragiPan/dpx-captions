"""Learned transcript corrections.

After an export, the captions as they were generated (the "baseline") are
compared with the captions as the user left them. Each word the user
rewrote becomes a rule "Whisper wrote X -> it should be Y" (for example
"zahtjeva" -> "zahteva", or "dobit ces" -> "dobices"). The next time
captions are generated, the rules rewrite Whisper's words before they are
grouped into cards, so the editor opens with those fixes already made.

Spelling fixes and merges become general rules. Edits that are only right
in their sentence - splitting "koji" into "ko i", say - are learned with
the whole card around them ("retko koji" -> "retko ko i"), so they only
fire where the same words come up again.

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

# Longest phrase (in words) a general rule can match, on either side.
MAX_PHRASE_WORDS = 3
# Longest card kept whole as the context of a context-dependent edit; in
# longer cards the context is the edit plus one word on either side.
MAX_CONTEXT_WORDS = 8
# A one-to-one fix of a word this short ("je" -> "jer") is too likely to be
# right only in its sentence to apply everywhere.
SHORT_WORD = 3
# A neighbour this similar to what Whisper wrote counts as kept ("redko"
# respelled "retko"), so it can still anchor a context rule.
REFERENCE_SIMILARITY = 0.75
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
    # Keys of rules learned with their surrounding words. Their keys are
    # written with the general rules already applied ("retko koji", even if
    # Whisper wrote "redko koji"), and they run after the general rules.
    context: set[str] = field(default_factory=set)

    @classmethod
    def load(cls, path: Path | None = None) -> CorrectionStore:
        path = path or corrections_path()
        rules: dict[str, dict[str, int]] = {}
        context: set[str] = set()
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            for key, targets in data.get("rules", {}).items():
                clean = {str(t): int(n) for t, n in targets.items() if int(n) > 0}
                if key and clean:
                    rules[key] = clean
            context = {k for k in data.get("context", []) if k in rules}
        except (OSError, ValueError, TypeError, AttributeError):
            pass
        return cls(rules=rules, path=path, context=context)

    def save(self) -> None:
        path = self.path or corrections_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "version": 1,
            "rules": dict(sorted(self.rules.items())),
            "context": sorted(k for k in self.context if k in self.rules),
        }
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

    def learn(self, key: str, target: str, context: bool = False) -> None:
        targets = self.rules.setdefault(key, {})
        targets[target] = targets.get(target, 0) + 1
        if context:
            self.context.add(key)

    def is_context(self, key: str) -> bool:
        return key in self.context

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
            self.context.discard(key)
        return target

    def remove(self, key: str) -> None:
        self.rules.pop(key, None)
        self.context.discard(key)

    def max_words(self, context: bool | None = None) -> int:
        keys = [k for k in self.rules if context is None or (k in self.context) == context]
        return max((len(key.split()) for key in keys), default=0)


# --------------------------------------------------------------------- apply
def _rewrite(src: list[Word], key: str, target: str) -> list[Word]:
    from .retime import retime  # retime builds on this module's alignment

    lead = _split_edges(src[0].text)[0]
    trail = _split_edges(src[-1].text)[2]
    source_display = " ".join(_core(w.text) for w in src)
    tokens = _apply_case(target, source_display).split()
    # Words the rule leaves alone ("retko" in "retko koji" -> "retko ko i")
    # keep their exact time; changed ones take the time of what they replace.
    timed = retime(tokens, [replace(w, text=_core(w.text)) for w in src], src[0].start, src[-1].end)

    tokens[0] = lead + tokens[0]
    tokens[-1] = tokens[-1] + trail
    return [
        Word(text=tok, start=w.start, end=w.end, probability=w.probability, source=key)
        for tok, w in zip(tokens, timed)
    ]


def apply_corrections(words: list[Word], store: CorrectionStore) -> tuple[list[Word], list[tuple[str, str]]]:
    """Rewrites Whisper's words with the learned rules, longest phrase first:
    general rules, then the context rules over their result.

    Returns the new word list and (whisper phrase, replacement) per rewrite.
    """
    words, applied = _apply_pass(words, store, context=False)
    words, more = _apply_pass(words, store, context=True)
    return words, applied + more


def _apply_pass(words: list[Word], store: CorrectionStore, context: bool) -> tuple[list[Word], list[tuple[str, str]]]:
    max_words = store.max_words(context)
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
            if store.is_context(key) != context:
                continue
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


def _is_general(src: list[str], dst: list[str]) -> bool:
    """Whether an edit is safe to apply wherever Whisper writes the same thing.

    A spelling fix of one word ("zahtjeva" -> "zahteva") or a merge into one
    word ("dobit ćeš" -> "dobićeš") is. Splitting a word ("koji" -> "ko i"),
    swapping in an unrelated word, or touching a short word ("je" -> "jer")
    is only right in its sentence, so those are learned with their card.
    """
    if len(dst) != 1:
        return False
    if len(src) > 1:
        return True
    return len(src[0]) > SHORT_WORD and SequenceMatcher(None, src[0], dst[0], autojunk=False).ratio() >= 0.5


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

    base_index = {id(w): i for i, w in enumerate(base)}

    def context(src: list[Word], dst: list[tuple[Word, CaptionCard]]) -> tuple[list[Word], list[Word]] | None:
        """The card's words around a context-dependent edit, as (generated
        words, edited words). The context grows over neighbours the user
        kept (or only re-spelled) and stops at anything moved or deleted,
        so the rule never deletes a word that merely went to the next card.
        None when nothing anchors the edit (a one-word card, say)."""
        card = dst[0][1]
        if any(c is not card for _, c in dst):
            return None  # spread over several cards
        card_words = [w for w in card.words if normalize(w.text)]
        positions = {id(w): k for k, w in enumerate(card_words)}
        i0, i1 = base_index[id(src[0])], base_index[id(src[-1])]
        k0, k1 = positions[id(dst[0][0])], positions[id(dst[-1][0])]

        def kept(i: int, k: int) -> bool:
            w = base[i]
            if not card.start - TIME_TOLERANCE <= (w.start + w.end) / 2 <= card.end + TIME_TOLERANCE:
                return False
            s, t = a[i], normalize(card_words[k].text)
            return s == t or SequenceMatcher(None, s, t, autojunk=False).ratio() >= REFERENCE_SIMILARITY

        s0, d0, s1, d1 = i0, k0, i1, k1
        while s0 > 0 and d0 > 0 and kept(s0 - 1, d0 - 1):
            s0, d0 = s0 - 1, d0 - 1
        while s1 + 1 < len(base) and d1 + 1 < len(card_words) and kept(s1 + 1, d1 + 1):
            s1, d1 = s1 + 1, d1 + 1
        if (s0, s1) == (i0, i1):
            return None
        if s1 - s0 + 1 > MAX_CONTEXT_WORDS or d1 - d0 + 1 > MAX_CONTEXT_WORDS:
            # Long card: keep just one neighbour on each side that exists.
            s0, d0 = (i0 - 1, k0 - 1) if s0 < i0 else (i0, k0)
            s1, d1 = (i1 + 1, k1 + 1) if s1 > i1 else (i1, k1)
        return base[s0:s1 + 1], card_words[d0:d1 + 1]

    pending_context: list[tuple[list[Word], list[Word], list[Word]]] = []

    def record(src: list[Word], dst: list[tuple[Word, CaptionCard]]) -> None:
        dst_words = [w for w, _ in dst]
        if not _is_general([normalize(w.text) for w in src], [normalize(w.text) for w in dst_words]):
            found = context(src, dst)
            if found is not None:
                # Written once every general rule of this export is known,
                # so its key can be stated with those applied.
                pending_context.append((found[0], found[1], src))
            return
        commit(src, dst_words, MAX_PHRASE_WORDS, context_rule=False)

    def commit(src: list[Word], dst_words: list[Word], limit: int, context_rule: bool,
               edited: list[Word] | None = None) -> None:
        """`edited` is the part of `src` the user actually changed (all of it
        unless `src` includes context): only automatic fixes there were undone."""
        # Starred words are censoring ("PIČ****"), not a transcription fix;
        # stripping the stars would learn a truncated word.
        if any("*" in w.text for w in src) or any("*" in w.text for w in dst_words):
            return
        edited_ids = {id(w) for w in (edited if edited is not None else src)}
        original: list[str] = []
        undone: list[str] = []
        prev = None
        for w in src:
            if w.source is None:
                original.append(normalize(w.text))
            elif w.source != prev:
                original.extend(w.source.split())
                if id(w) in edited_ids:
                    undone.append(w.source)
            prev = w.source

        target_tokens = [_core(w.text) for w in dst_words]
        target_tokens = [t for t in target_tokens if t]
        if not target_tokens:
            return
        if context_rule:
            # State the key as it reads after the general rules, which run
            # first: "redko koji" is stored as "retko koji".
            fixed, _ = _apply_pass([Word(t, 0.0, 0.0) for t in original], store, context=False)
            original = [n for n in (normalize(w.text) for w in fixed) if n]
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

        if target_norm != source_key and len(original) <= limit:
            source_display = " ".join(_core(w.text) for w in src)
            target = _stored_target(" ".join(target_tokens), source_display, case_meaningful)
            store.learn(source_key, target, context=context_rule)
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

    for src, dst_words, edited in pending_context:
        commit(src, dst_words, MAX_CONTEXT_WORDS, context_rule=True, edited=edited)
    return report


def baseline_from_cards(cards: list[CaptionCard]) -> list[Word]:
    return [replace(w) for card in cards for w in card.words]
