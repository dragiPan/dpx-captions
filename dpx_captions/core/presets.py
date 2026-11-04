"""Named caption presets the user switches between from the style panel.

Each preset is an ordinary `.autosubs-preset.json` (so it also loads in
AutoSubs) with one extra top-level key holding the app's text-layout
options. Vocabulary and the censor list are per-user, not per-look, so a
preset never touches them.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import replace
from pathlib import Path

from .style import AnimationStyle, CaptionFormatting, Density, TextCase

SUFFIX = ".autosubs-preset.json"
_LAYOUT_KEY = "dpxFormatting"
_LAYOUT_FIELDS = ("density", "max_chars_per_line", "line_count", "text_case", "remove_punctuation")


def presets_dir() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    path = Path(base) / "DPX Captions" / "presets"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _path(name: str) -> Path:
    safe = re.sub(r'[<>:"/\\|?*]', "_", name).strip() or "Preset"
    return presets_dir() / f"{safe}{SUFFIX}"


def list_presets() -> list[str]:
    return sorted((p.name[: -len(SUFFIX)] for p in presets_dir().glob(f"*{SUFFIX}")), key=str.lower)


def layout_of(formatting: CaptionFormatting) -> dict:
    d = formatting.to_dict()
    return {k: d[k] for k in _LAYOUT_FIELDS}


def save_preset(name: str, style: AnimationStyle, formatting: CaptionFormatting) -> None:
    data = style.to_preset_dict(name=name)
    data[_LAYOUT_KEY] = layout_of(formatting)
    _path(name).write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def load_preset(name: str) -> tuple[AnimationStyle, dict]:
    """The preset's style and its text-layout options (possibly empty, for
    presets made in AutoSubs)."""
    path = _path(name)
    data = json.loads(path.read_text(encoding="utf-8"))
    return AnimationStyle.from_preset_file(path), dict(data.get(_LAYOUT_KEY, {}))


def apply_layout(formatting: CaptionFormatting, layout: dict) -> CaptionFormatting:
    """`formatting` with the preset's layout options, keeping vocabulary and censoring."""
    changes = {}
    for key, value in layout.items():
        if key == "density":
            changes[key] = Density(value)
        elif key == "text_case":
            changes[key] = TextCase(value)
        elif key in _LAYOUT_FIELDS:
            changes[key] = value
    return replace(formatting, **changes)


def delete_preset(name: str) -> None:
    _path(name).unlink(missing_ok=True)
