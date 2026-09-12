"""Builds an .ass subtitle file that reproduces the AutoSubs animation style.

We deliberately target ASS + libass (burned in via ffmpeg's `ass` filter)
instead of rendering caption frames ourselves: libass's karaoke tags
(`\\kf`) already implement exactly the "text fill highlight where the word
itself changes color" behavior described by the reference preset, so we
get GPU-friendly, crisp-at-any-resolution text for free instead of
rasterizing and compositing PNG overlays per word.

Only HighlightStyle 0 (text-color fill) is implemented. HighlightStyle 1
(box highlight behind the active word) is not yet supported and falls back
to text-fill, since we don't have a reference preset for its exact visual
behavior.
"""

from __future__ import annotations

from pathlib import Path

from .caption_builder import CaptionCard
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


def _build_style_line(style: AnimationStyle, width: int, height: int) -> str:
    font_size = max(1, round(style.TextSize * height))
    outline_px = round(style.OutlineThickness * font_size) if style.OutlineEnabled else 0
    shadow_px = max(1, round(font_size * 0.03)) if style.ShadowEnabled else 0
    bold = -1 if "bold" in style.Style.lower() else 0
    italic = -1 if "italic" in style.Style.lower() else 0

    primary = _color(style.HighlightColorRed, style.HighlightColorGreen, style.HighlightColorBlue)
    secondary = _color(style.FillColorRed, style.FillColorGreen, style.FillColorBlue)
    outline = _color(style.OutlineColorRed, style.OutlineColorGreen, style.OutlineColorBlue)
    shadow = _color(style.ShadowColorRed, style.ShadowColorGreen, style.ShadowColorBlue)

    return (
        "Style: Caption,"
        f"{style.Font},{font_size},{primary},{secondary},{outline},{shadow},"
        f"{bold},{italic},0,0,100,100,0,0,"
        f"1,{outline_px},{shadow_px},5,10,10,10,1"
    )


def _card_override_tags(style: AnimationStyle, x: int, y: int) -> str:
    anim_ms = max(1, round(style.AnimationLength * 1000))
    tags = []

    if style.SlideUpEnabled:
        rise_px = round(0.08 * y) or 20
        tags.append(f"\\move({x},{y + rise_px},{x},{y},0,{anim_ms})")
    else:
        tags.append(f"\\pos({x},{y})")

    if style.PopInEnabled:
        tags.append(f"\\fscx60\\fscy60\\t(0,{anim_ms},\\fscx100\\fscy100)")

    if style.FadeEnabled:
        tags.append(f"\\fad({anim_ms},{anim_ms})")

    return "{" + "".join(tags) + "}"


def write_ass(
    cards: list[CaptionCard],
    style: AnimationStyle,
    width: int,
    height: int,
    output_path: str | Path,
) -> Path:
    x = round(style.TextPosition[0] * width)
    y = round((1.0 - style.TextPosition[1]) * height)  # AutoSubs y=0 is bottom; ASS y=0 is top

    header = "\n".join(
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
            _build_style_line(style, width, height),
            "",
            "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
        ]
    )

    lines = [header]
    for card in cards:
        overrides = _card_override_tags(style, x, y)
        line_texts = ["".join(f"{{\\kf{max(1, round((w.end - w.start) * 100))}}}{w.text} " for w in line).rstrip()
                      for line in card.lines]
        text = overrides + "\\N".join(line_texts)
        lines.append(
            f"Dialogue: 0,{_fmt_time(card.start)},{_fmt_time(card.end)},Caption,,0,0,0,,{text}"
        )

    output_path = Path(output_path)
    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return output_path
