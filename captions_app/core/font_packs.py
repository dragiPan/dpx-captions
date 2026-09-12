"""Downloadable caption fonts.

Google no longer ships "Open Sans Condensed" as a static family (it became
a width axis of the Open Sans variable font, which neither Qt nor libass
can select from an ASS style), so the pack offers static condensed faces
that render identically in the preview and the burned-in export.
"""

from __future__ import annotations

import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Callable

from .fonts import app_fonts_dir

_RAW = "https://raw.githubusercontent.com/google/fonts/main/ofl"


@dataclass(frozen=True)
class FontPack:
    name: str
    description: str
    files: dict[str, str]  # saved filename -> url


FONT_PACKS: list[FontPack] = [
    FontPack(
        "Barlow Condensed",
        "Closest match to Open Sans Condensed Bold",
        {
            "BarlowCondensed-Bold.ttf": f"{_RAW}/barlowcondensed/BarlowCondensed-Bold.ttf",
            "BarlowCondensed-ExtraBold.ttf": f"{_RAW}/barlowcondensed/BarlowCondensed-ExtraBold.ttf",
            "BarlowCondensed-Black.ttf": f"{_RAW}/barlowcondensed/BarlowCondensed-Black.ttf",
        },
    ),
    FontPack(
        "Anton",
        "Heavy condensed, very common for short-form captions",
        {"Anton-Regular.ttf": f"{_RAW}/anton/Anton-Regular.ttf"},
    ),
    FontPack(
        "Bebas Neue",
        "All-caps condensed display face",
        {"BebasNeue-Regular.ttf": f"{_RAW}/bebasneue/BebasNeue-Regular.ttf"},
    ),
    FontPack(
        "Fjalla One",
        "Medium-contrast condensed sans",
        {"FjallaOne-Regular.ttf": f"{_RAW}/fjallaone/FjallaOne-Regular.ttf"},
    ),
    FontPack(
        "Open Sans",
        "Variable Open Sans (upright width only)",
        {"OpenSans.ttf": f"{_RAW}/opensans/OpenSans%5Bwdth,wght%5D.ttf"},
    ),
]


def download_packs(
    packs: list[FontPack],
    progress_cb: Callable[[str], None] | None = None,
) -> list[str]:
    """Downloads each pack into the app fonts folder. Returns saved paths."""
    target_dir = app_fonts_dir()
    saved: list[str] = []

    for pack in packs:
        for filename, url in pack.files.items():
            if progress_cb:
                progress_cb(f"Downloading {pack.name} — {filename}")
            destination = target_dir / filename
            try:
                with urllib.request.urlopen(url, timeout=30) as response:
                    data = response.read()
            except (urllib.error.URLError, TimeoutError) as exc:
                raise RuntimeError(f"Could not download {filename}: {exc}") from exc
            destination.write_bytes(data)
            saved.append(str(destination))

    return saved
