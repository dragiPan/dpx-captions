"""App-local font support.

Caption fonts must be resolvable by *both* Qt (preview) and libass (export).
Fonts like "Open Sans Condensed" ship with editors such as DaVinci rather
than being installed system-wide, so any .ttf/.otf dropped into the app's
`fonts/` folder is registered with Qt and handed to libass via `fontsdir`,
keeping preview and exported video on the same typeface.
"""

from __future__ import annotations

import sys
from pathlib import Path

FONT_SUFFIXES = {".ttf", ".otf", ".ttc"}


def app_fonts_dir() -> Path:
    if getattr(sys, "frozen", False):
        base = Path(sys.executable).parent
    else:
        base = Path(__file__).resolve().parents[2]
    path = base / "fonts"
    path.mkdir(parents=True, exist_ok=True)
    return path


def font_files() -> list[Path]:
    return [p for p in app_fonts_dir().iterdir() if p.suffix.lower() in FONT_SUFFIXES]
