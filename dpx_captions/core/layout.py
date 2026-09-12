"""Shared caption layout.

Both the live preview and the ASS export lay out words through this module
so the two cannot drift apart. Each word gets an absolute centre point:
the export emits one `\\an5\\pos(...)` event per word, which means a word
can pop/scale without reflowing the rest of the line — and it guarantees
word placement is identical in preview and burned-in output.
"""

from __future__ import annotations

from dataclasses import dataclass

from PySide6.QtGui import QFont, QFontMetricsF

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
