"""Shared caption layout.

Both the live preview and the ASS export lay out words through this module
so the two cannot drift apart. Each word gets an absolute centre point:
the export emits one `\\an5\\pos(...)` event per word, which means a word
can pop/scale without reflowing the rest of the line — and it guarantees
word placement is identical in preview and burned-in output.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from functools import lru_cache

from PySide6.QtGui import QFont, QFontMetricsF, QRawFont

from .caption_builder import CaptionCard
from .style import AnimationStyle
from .transcribe import Word


@dataclass
class PositionedWord:
    word: Word
    center_x: float
    center_y: float
    width: float
    height: float


def caption_font(style: AnimationStyle, canvas_height: int) -> QFont:
    font = QFont(style.Font)
    font.setPixelSize(max(1, round(style.TextSize * canvas_height)))
    font.setBold(bool(style.Bold))
    font.setItalic("italic" in style.Style.lower())
    return font


@lru_cache(maxsize=64)
def _libass_metrics(family: str, bold: bool, italic: bool) -> tuple[float, float]:
    font = QFont(family)
    font.setPixelSize(1000)
    font.setBold(bold)
    font.setItalic(italic)
    raw = QRawFont.fromFont(font)
    head = bytes(raw.fontTable("head"))
    os2 = bytes(raw.fontTable("OS/2"))
    hhea = bytes(raw.fontTable("hhea"))
    units = struct.unpack(">H", head[18:20])[0] if len(head) >= 20 else 0

    ascent = descent = 0
    if len(os2) >= 78:  # usWinAscent / usWinDescent, read as signed like libass does
        ascent, descent = struct.unpack(">hh", os2[74:78])
    if ascent + descent == 0 and len(hhea) >= 8:  # FreeType's default: hhea
        ascent, neg_descent = struct.unpack(">hh", hhea[4:8])
        descent = -neg_descent
    if ascent + descent == 0 and len(os2) >= 72:  # sTypoAscender / sTypoDescender
        ascent, neg_descent = struct.unpack(">hh", os2[68:72])
        descent = -neg_descent

    if units <= 0 or ascent + descent <= 0:
        metrics = QFontMetricsF(font)
        return metrics.ascent() / 1000, metrics.descent() / 1000
    return ascent / units, descent / units


def libass_metrics(font: QFont) -> tuple[float, float]:
    """(ascent, descent) as libass sees this font, as fractions of the Qt pixel size.

    An ASS font size is not an em size: libass scales the font so that
    usWinAscent + usWinDescent from the OS/2 table equals the requested
    size (falling back to hhea, then typo metrics). Qt's pixel size is the
    em, and for fonts flagged USE_TYPO_METRICS Qt's own ascent/descent
    differ from libass's too, so the tables are read directly.
    """
    return _libass_metrics(font.family(), font.bold(), font.italic())


def ass_font_size(font: QFont) -> float:
    """The ASS Fontsize that makes libass draw glyphs the size Qt draws `font`."""
    ascent, descent = libass_metrics(font)
    return font.pixelSize() * (ascent + descent)


def layout_card(
    card: CaptionCard,
    style: AnimationStyle,
    canvas_width: int,
    canvas_height: int,
) -> list[PositionedWord]:
    font = caption_font(style, canvas_height)
    metrics = QFontMetricsF(font)
    line_height = metrics.height()
    space_width = metrics.horizontalAdvance(" ")

    x_center = style.TextPosition[0] * canvas_width
    y_center = (1.0 - style.TextPosition[1]) * canvas_height

    total_height = line_height * len(card.lines)
    first_line_center = y_center - total_height / 2 + line_height / 2

    positioned: list[PositionedWord] = []
    for row, line in enumerate(card.lines):
        line_center_y = first_line_center + row * line_height
        widths = [metrics.horizontalAdvance(w.text) for w in line]
        line_width = sum(widths) + space_width * max(len(line) - 1, 0)
        pen_x = x_center - line_width / 2

        for word, width in zip(line, widths):
            positioned.append(
                PositionedWord(
                    word=word,
                    center_x=pen_x + width / 2,
                    center_y=line_center_y,
                    width=width,
                    height=line_height,
                )
            )
            pen_x += width + space_width

    return positioned


def pop_scale_at(style: AnimationStyle, word: Word, t: float) -> float:
    """Scale factor for the spoken-word pop at time `t`.

    The word snaps to the highlight colour when it starts and briefly
    scales up and back, rather than the colour sweeping across letters.
    """
    duration = max(style.AnimationLength, 1e-6)
    elapsed = t - word.start
    if elapsed < 0 or elapsed > duration:
        return 1.0
    peak = style.HighlightPopScale
    half = duration / 2
    if elapsed <= half:
        return 1.0 + (peak - 1.0) * (elapsed / half)
    return peak - (peak - 1.0) * ((elapsed - half) / half)
