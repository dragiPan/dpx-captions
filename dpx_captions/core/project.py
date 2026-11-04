"""Save/load a project's editable state (source video, style, formatting,
and edited caption cards) as a single JSON file, so a session can be closed
and resumed without re-transcribing."""

from __future__ import annotations

import json
import os
from dataclasses import asdict
from pathlib import Path

from .aspect import Aspect
from .caption_builder import CaptionCard
from .style import AnimationStyle, CaptionFormatting
from .transcribe import Word


_WORD_FIELDS = set(Word.__dataclass_fields__)


def word_to_dict(w: Word) -> dict:
    d = asdict(w)
    if d.get("source") is None:
        d.pop("source", None)
    return d


def word_from_dict(d: dict) -> Word:
    return Word(**{k: v for k, v in d.items() if k in _WORD_FIELDS})


def cards_to_dict(cards: list[CaptionCard]) -> list[dict]:
    return [
        {
            "start": c.start,
            "end": c.end,
            "lines": [[word_to_dict(w) for w in line] for line in c.lines],
        }
        for c in cards
    ]


def cards_from_dict(data: list[dict]) -> list[CaptionCard]:
    cards = []
    for c in data:
        lines = [[word_from_dict(w) for w in line] for line in c["lines"]]
        cards.append(CaptionCard(lines=lines, start=c["start"], end=c["end"]))
    return cards


PROJECT_SUFFIX = ".dpxproj"


def save_project(
    path: str | Path,
    video_path: str,
    model_size: str,
    formatting: CaptionFormatting,
    style: AnimationStyle,
    cards: list[CaptionCard],
    aspect: Aspect = Aspect.AUTO,
    baseline: list[Word] | None = None,
    learned: set[str] | None = None,
    playhead: float = 0.0,
) -> None:
    """`baseline` is the word list as generated, before the user's edits, and
    `learned` the edits already counted from it (see core.corrections).

    Written to a temporary file first, so a crash mid-write (autosave runs
    often) never leaves a half-written project behind."""
    data = {
        "version": 1,
        "video_path": video_path,
        "model_size": model_size,
        "formatting": formatting.to_dict(),
        "style": asdict(style),
        "aspect": aspect.value,
        "cards": cards_to_dict(cards),
        "baseline": [word_to_dict(w) for w in baseline or []],
        "learned": sorted(learned or ()),
        "playhead": playhead,
    }
    path = Path(path)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


def load_project(path: str | Path) -> dict:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    known_style = {f for f in AnimationStyle.__dataclass_fields__}
    style_data = {k: v for k, v in data["style"].items() if k in known_style}
    return {
        "video_path": data["video_path"],
        "model_size": data["model_size"],
        "formatting": CaptionFormatting.from_dict(data["formatting"]),
        "style": AnimationStyle(**style_data),
        "aspect": Aspect(data.get("aspect", Aspect.AUTO.value)),
        "cards": cards_from_dict(data["cards"]),
        "baseline": [word_from_dict(w) for w in data.get("baseline", [])],
        "learned": set(data.get("learned", [])),
        "playhead": float(data.get("playhead", 0.0)),
    }


def session_path() -> Path:
    """The autosaved last session, restored when the app starts."""
    base = os.environ.get("APPDATA") or str(Path.home())
    return Path(base) / "DPX Captions" / f"session{PROJECT_SUFFIX}"
