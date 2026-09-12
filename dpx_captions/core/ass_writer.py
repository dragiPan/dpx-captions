"""Builds an .ass subtitle file that libass burns into the exported video.

Each word is emitted as its own `\\an5\\pos(...)` event, using the word
positions from core.layout. That buys two things: the spoken word can
scale (pop) without reflowing the rest of the line, which karaoke tags
cannot do, and the preview renders from the exact same layout, so what
you see is what gets burned in.

The highlight is an instant colour switch plus a short pop, not the
left-to-right `\\kf` sweep libass karaoke would give.

Only HighlightStyle 0 (text-colour highlight) is implemented; HighlightStyle 1
(a box behind the active word) falls back to it.
"""

from __future__ import annotations

from pathlib import Path

from .caption_builder import CaptionCard
from .layout import caption_font, layout_card
from .style import AnimationStyle


def _color(r: float, g: float, b: float) -> str:
    def c(v: float) -> int:
        return max(0, min(255, round(v * 255)))

    return f"&H00{c(b):02X}{c(g):02X}{c(r):02X}&"


def _fmt_time(seconds: float) -> str:
    seconds = max(0.0, seconds)
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = int(seconds % 60)
    cs = round((seconds - int(seconds)) * 100)
    if cs == 100:
        cs = 0
        s += 1
    return f"{h}:{m:02d}:{s:02d}.{cs:02d}"


def _build_style_line(style: AnimationStyle, font_size: int) -> str:
    outline_px = round(style.OutlineThickness * font_size) if style.OutlineEnabled else 0
    shadow_px = round(style.ShadowOffset * font_size) if style.ShadowEnabled else 0
    bold = -1 if style.Bold else 0
    italic = -1 if "italic" in style.Style.lower() else 0

    fill = _color(style.FillColorRed, style.FillColorGreen, style.FillColorBlue)
    outline = _color(style.OutlineColorRed, style.OutlineColorGreen, style.OutlineColorBlue)
    shadow = _color(style.ShadowColorRed, style.ShadowColorGreen, style.ShadowColorBlue)

    return (
        "Style: Caption,"
        f"{style.Font},{font_size},{fill},{fill},{outline},{shadow},"
        f"{bold},{italic},0,0,100,100,0,0,"
        f"1,{outline_px},{shadow_px},5,10,10,10,1"
    )


def _entrance_tags(style: AnimationStyle, x: float, y: float) -> str:
    anim_ms = max(1, round(style.AnimationLength * 1000))
    tags = []

    if style.SlideUpEnabled:
        rise = round(0.08 * y) or 20
        tags.append(f"\\move({round(x)},{round(y + rise)},{round(x)},{round(y)},0,{anim_ms})")
    else:
        tags.append(f"\\pos({round(x)},{round(y)})")

    if style.FadeEnabled:
        tags.append(f"\\fad({anim_ms},{anim_ms})")

    return "".join(tags)


def _highlight_tags(style: AnimationStyle, word_start_ms: int) -> str:
    highlight = _color(style.HighlightColorRed, style.HighlightColorGreen, style.HighlightColorBlue)
    start = max(0, word_start_ms)
    # 1ms transition == instant switch, deliberately not a \kf letter sweep.
    tags = [f"\\t({start},{start + 1},\\1c{highlight})"]

    pop = round(style.AnimationLength * 1000)
    if pop > 0 and style.HighlightPopScale > 1.0:
        half = max(1, pop // 2)
        peak = round(style.HighlightPopScale * 100)
        tags.append(f"\\t({start},{start + half},\\fscx{peak}\\fscy{peak})")
        tags.append(f"\\t({start + half},{start + 2 * half},\\fscx100\\fscy100)")

    return "".join(tags)


def write_ass(
    cards: list[CaptionCard],
    style: AnimationStyle,
    width: int,
    height: int,
    output_path: str | Path,
) -> Path:
    font_size = caption_font(style, height).pixelSize()

    lines = [
        "\n".join(
            [
                "[Script Info]",
                "ScriptType: v4.00+",
                f"PlayResX: {width}",
                f"PlayResY: {height}",
                "WrapStyle: 2",
                "ScaledBorderAndShadow: yes",
                "",
                "[V4+ Styles]",
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
                "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, "
                "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
                _build_style_line(style, font_size),
                "",
                "[Events]",
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
            ]
        )
    ]

    for card in cards:
        start = _fmt_time(card.start)
        end = _fmt_time(card.end)
        for item in layout_card(card, style, width, height):
            tags = "\\an5" + _entrance_tags(style, item.center_x, item.center_y)
            if style.PopInEnabled:
                anim_ms = max(1, round(style.AnimationLength * 1000))
                tags += f"\\fscx60\\fscy60\\t(0,{anim_ms},\\fscx100\\fscy100)"
            if style.HighlightEnabled:
                tags += _highlight_tags(style, round((item.word.start - card.start) * 1000))
            lines.append(
                f"Dialogue: 0,{start},{end},Caption,,0,0,0,,{{{tags}}}{item.word.text}"
            )

    output_path = Path(output_path)
    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return output_path
