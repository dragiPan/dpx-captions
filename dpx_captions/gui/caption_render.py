"""QPainter equivalent of the ASS burn-in, used for the live preview.

Kept in sync with ass_writer.py on purpose: same word-fill sweep, outline,
shadow and entrance animations, so the preview matches the exported video.
Everything is drawn relative to the *video* rect (not the whole widget), so
letterboxing doesn't shift the captions.
"""

from __future__ import annotations

from PySide6.QtCore import QRect, Qt
from PySide6.QtGui import QColor, QFont, QFontMetrics, QPainter, QPainterPath, QPen

from ..core.caption_builder import CaptionCard
from ..core.style import AnimationStyle


def qcolor(r: float, g: float, b: float) -> QColor:
    return QColor(round(r * 255), round(g * 255), round(b * 255))


def active_card(cards: list[CaptionCard], t: float) -> CaptionCard | None:
    for card in cards:
        if card.start <= t <= card.end:
            return card
    return None


def caption_font(style: AnimationStyle, video_height: int) -> QFont:
    font = QFont(style.Font)
    font.setPixelSize(max(1, round(style.TextSize * video_height)))
    font.setBold("bold" in style.Style.lower())
    font.setItalic("italic" in style.Style.lower())
    return font


def draw_captions(
    painter: QPainter,
    video_rect: QRect,
    cards: list[CaptionCard],
    style: AnimationStyle,
    t: float,
) -> None:
    card = active_card(cards, t)
    if card is None or video_rect.width() <= 0 or video_rect.height() <= 0:
        return

    vw, vh = video_rect.width(), video_rect.height()
    font = caption_font(style, vh)
    font_px = font.pixelSize()
    metrics = QFontMetrics(font)
    line_height = metrics.height()

    x_center = video_rect.left() + style.TextPosition[0] * vw
    y_center = video_rect.top() + (1.0 - style.TextPosition[1]) * vh

    local_t = t - card.start
    anim_len = max(style.AnimationLength, 1e-6)
    progress = max(0.0, min(local_t / anim_len, 1.0)) if style.AnimationLength > 0 else 1.0

    painter.save()
    painter.setClipRect(video_rect)
    painter.setRenderHint(QPainter.Antialiasing)
    painter.setRenderHint(QPainter.TextAntialiasing)

    if style.FadeEnabled:
        painter.setOpacity(progress)
    if style.SlideUpEnabled:
        painter.translate(0, (1.0 - progress) * 0.08 * vh)
    if style.PopInEnabled:
        scale = 0.6 + 0.4 * progress
        painter.translate(x_center, y_center)
        painter.scale(scale, scale)
        painter.translate(-x_center, -y_center)

    fill_color = qcolor(style.FillColorRed, style.FillColorGreen, style.FillColorBlue)
    highlight_color = qcolor(style.HighlightColorRed, style.HighlightColorGreen, style.HighlightColorBlue)
    outline_color = qcolor(style.OutlineColorRed, style.OutlineColorGreen, style.OutlineColorBlue)
    shadow_color = qcolor(style.ShadowColorRed, style.ShadowColorGreen, style.ShadowColorBlue)
    outline_px = max(1.0, style.OutlineThickness * font_px) if style.OutlineEnabled else 0.0
    shadow_offset = max(1, round(font_px * 0.04))

    painter.setFont(font)
    total_height = len(card.lines) * line_height
    first_baseline = y_center - total_height / 2 + metrics.ascent()
    space_w = metrics.horizontalAdvance(" ")

    for row, line in enumerate(card.lines):
        baseline = first_baseline + row * line_height
        line_width = sum(metrics.horizontalAdvance(w.text) for w in line) + space_w * max(len(line) - 1, 0)
        pen_x = x_center - line_width / 2

        for word in line:
            word_w = metrics.horizontalAdvance(word.text)

            if style.ShadowEnabled:
                painter.setPen(shadow_color)
                painter.drawText(round(pen_x + shadow_offset), round(baseline + shadow_offset), word.text)

            if outline_px > 0:
                path = QPainterPath()
                path.addText(pen_x, baseline, font, word.text)
                pen = QPen(outline_color, outline_px)
                pen.setJoinStyle(Qt.RoundJoin)
                painter.strokePath(path, pen)

            painter.setPen(fill_color)
            painter.drawText(round(pen_x), round(baseline), word.text)

            if style.HighlightEnabled:
                if t <= word.start:
                    fraction = 0.0
                elif t >= word.end:
                    fraction = 1.0
                else:
                    fraction = (t - word.start) / max(word.end - word.start, 1e-6)

                if fraction > 0:
                    painter.save()
                    painter.setClipRect(
                        round(pen_x),
                        round(baseline - metrics.ascent()),
                        max(1, round(word_w * fraction)),
                        line_height,
                    )
                    painter.setPen(highlight_color)
                    painter.drawText(round(pen_x), round(baseline), word.text)
                    painter.restore()

            pen_x += word_w + space_w

    painter.restore()
