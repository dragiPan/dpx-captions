"""Save/load a project's editable state (source video, style, formatting,
and edited caption cards) as a single JSON file, so a session can be closed
and resumed without re-transcribing."""

from __future__ import annotations

import json
from dataclasses import asdict
from pathlib import Path

from .aspect import Aspect
from .caption_builder import CaptionCard
from .style import AnimationStyle, CaptionFormatting
from .transcribe import Word


def cards_to_dict(cards: list[CaptionCard]) -> list[dict]:
    return [
        {
            "start": c.start,
            "end": c.end,
            "lines": [[asdict(w) for w in line] for line in c.lines],
        }
        for c in cards
    ]


def cards_from_dict(data: list[dict]) -> list[CaptionCard]:
    cards = []
    for c in data:
        lines = [[Word(**w) for w in line] for line in c["lines"]]
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
) -> None:
    data = {
        "version": 1,
        "video_path": video_path,
        "model_size": model_size,
        "formatting": formatting.to_dict(),
        "style": asdict(style),
        "aspect": aspect.value,
        "cards": cards_to_dict(cards),
    }
    Path(path).write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


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
    }
