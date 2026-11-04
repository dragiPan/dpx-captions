"""Measures how libass really draws a caption font, so the export matches the preview.

Reading the font's metric tables is not enough: Qt and libass may pick
*different files* for the same family name (an app-bundled variable
"Open Sans" vs. an installed static "Open Sans Condensed Bold", say), and
those files disagree on their metrics, so captions came out larger or
smaller than the preview and the gaps between words opened or closed.

Instead, the same probe text is drawn once by libass (through the ffmpeg
that burns the captions) and once by Qt, and the ink is compared:

* `size_ratio`: ASS Fontsize per Qt pixel size, so capital letters
  come out exactly as tall as in the preview;
* `scale_x`: the ASS ScaleX that makes a line exactly as wide;
* `baseline`: where libass puts the baseline below an \\an5 anchor, per
  Qt pixel size, so the preview can draw its text at the same height.

Results are cached per font. If the probe fails, the font tables are
used instead (see core.layout).
"""

from __future__ import annotations

import subprocess
import tempfile
import threading
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QFont, QImage, QPainter

PROBE_PX = 200
CANVAS_W, CANVAS_H = 3200, 1000
WIDTH_SAMPLE = "HAMBURG ŠĐČ hamburg"  # fits the canvas even in wide fonts
ANCHOR_X, ANCHOR_Y = CANVAS_W // 2, 750  # \an5 anchor of the "H" probe
INK = 128  # a pixel at least half covered counts as ink

_NO_WINDOW = subprocess.CREATE_NO_WINDOW if hasattr(subprocess, "CREATE_NO_WINDOW") else 0


@dataclass(frozen=True)
class LibassFit:
    size_ratio: float
    scale_x: float
    baseline: float


_cache: dict[tuple[str, bool, bool], LibassFit] = {}
_lock = threading.Lock()


def _ink_box(gray: np.ndarray) -> tuple[int, int, int, int] | None:
    ink = gray >= INK
    rows = np.flatnonzero(ink.any(axis=1))
    cols = np.flatnonzero(ink.any(axis=0))
    if rows.size == 0:
        return None
    return int(cols[0]), int(rows[0]), int(cols[-1]) + 1, int(rows[-1]) + 1  # x0, y0, x1, y1


def _escape(path: Path) -> str:
    return str(path.resolve()).replace("\\", "/").replace(":", "\\:")


def _libass_probe(family: str, bold: bool, italic: bool) -> np.ndarray:
    from .ffmpeg_util import ffmpeg_path
    from .fonts import app_fonts_dir, font_files

    ass = "\n".join([
        "[Script Info]", "ScriptType: v4.00+", f"PlayResX: {CANVAS_W}", f"PlayResY: {CANVAS_H}",
        "WrapStyle: 2", "ScaledBorderAndShadow: yes", "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, "
        "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
        f"Style: P,{family},{PROBE_PX},&H00FFFFFF&,&H00FFFFFF&,&H00000000&,&H00000000&,"
        f"{-1 if bold else 0},{-1 if italic else 0},0,0,100,100,0,0,1,0,0,7,0,0,0,1",
        "", "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
        f"Dialogue: 0,0:00:00.00,0:00:05.00,P,,0,0,0,,{{\\an7\\pos(40,40)}}{WIDTH_SAMPLE}",
        f"Dialogue: 0,0:00:00.00,0:00:05.00,P,,0,0,0,,{{\\an5\\pos({ANCHOR_X},{ANCHOR_Y})}}H",
        "",
    ])
    with tempfile.TemporaryDirectory() as tmp:
        ass_path = Path(tmp) / "probe.ass"
        ass_path.write_text(ass, encoding="utf-8")
        vf = f"ass='{_escape(ass_path)}'"
        if font_files():
            vf += f":fontsdir='{_escape(app_fonts_dir())}'"
        result = subprocess.run(
            [ffmpeg_path(), "-v", "error", "-f", "lavfi", "-i", f"color=c=black:s={CANVAS_W}x{CANVAS_H}:d=1",
             "-vf", vf, "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "gray", "-"],
            capture_output=True, timeout=30, creationflags=_NO_WINDOW,
        )
    if result.returncode != 0 or len(result.stdout) < CANVAS_W * CANVAS_H:
        raise RuntimeError(result.stderr.decode("utf-8", "replace")[-300:])
    return np.frombuffer(result.stdout[:CANVAS_W * CANVAS_H], dtype=np.uint8).reshape(CANVAS_H, CANVAS_W)


def _qt_probe(family: str, bold: bool, italic: bool) -> np.ndarray:
    """The probe drawn the way the preview draws captions (QPainter.drawText),
    so synthetic bold and hinting are measured as the viewer sees them."""
    font = QFont(family)
    font.setPixelSize(PROBE_PX)
    font.setBold(bold)
    font.setItalic(italic)
    image = QImage(CANVAS_W, CANVAS_H, QImage.Format_Grayscale8)
    image.fill(QColor(0, 0, 0))
    painter = QPainter(image)
    painter.setRenderHint(QPainter.Antialiasing)
    painter.setRenderHint(QPainter.TextAntialiasing)
    painter.setFont(font)
    painter.setPen(QColor(255, 255, 255))
    painter.drawText(40, 40 + PROBE_PX, WIDTH_SAMPLE)
    painter.drawText(ANCHOR_X, ANCHOR_Y + PROBE_PX // 2, "H")
    painter.end()
    buf = np.frombuffer(image.constBits(), dtype=np.uint8, count=image.sizeInBytes())
    # Copied: the view would dangle once `image` is freed on return.
    return buf.reshape(CANVAS_H, image.bytesPerLine())[:, :CANVAS_W].copy()


def _measure(family: str, bold: bool, italic: bool) -> LibassFit:
    split = 500  # the width sample sits above this row, the "H" below it
    lib, qt = _libass_probe(family, bold, italic), _qt_probe(family, bold, italic)
    lib_line, lib_h = _ink_box(lib[:split]), _ink_box(lib[split:])
    qt_line, qt_h = _ink_box(qt[:split]), _ink_box(qt[split:])
    if not (lib_line and lib_h and qt_line and qt_h):
        raise RuntimeError("probe text was not drawn")

    size_ratio = (qt_h[3] - qt_h[1]) / (lib_h[3] - lib_h[1])
    scale_x = (qt_line[2] - qt_line[0]) / ((lib_line[2] - lib_line[0]) * size_ratio)
    baseline_px = (lib_h[3] + split) - ANCHOR_Y  # H sits on the baseline
    return LibassFit(size_ratio=size_ratio, scale_x=scale_x, baseline=baseline_px * size_ratio / PROBE_PX)


def _from_tables(font: QFont) -> LibassFit:
    from .layout import libass_metrics

    ascent, descent = libass_metrics(font)
    return LibassFit(size_ratio=ascent + descent, scale_x=1.0, baseline=(ascent - descent) / 2)


def libass_fit(font: QFont) -> LibassFit:
    key = (font.family(), font.bold(), font.italic())
    with _lock:
        if key in _cache:
            return _cache[key]
    try:
        fit = _measure(*key)
        # Implausible numbers mean the probe misfired; trust the tables then.
        if not (0.5 < fit.size_ratio < 3 and 0.7 < fit.scale_x < 1.3):
            fit = _from_tables(font)
    except Exception:  # noqa: BLE001 - ffmpeg missing, libass missing, timeout...
        fit = _from_tables(font)
    with _lock:
        _cache[key] = fit
    return fit


def forget() -> None:
    """Drops cached measurements, e.g. after new font files were installed."""
    with _lock:
        _cache.clear()
