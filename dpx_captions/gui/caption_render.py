"""QPainter equivalent of the ASS burn-in, used for the live preview.

Word positions come from core.layout, the same module the ASS writer uses,
so preview and export cannot drift apart. Two details matter for matching
libass: its border is drawn entirely *outside* the glyph while a QPen is
centred on the outline (hence the doubled pen width), and weight is taken
from the explicit Bold flag rather than being synthesised differently on
each side.
"""

from __future__ import annotations

from PySide6.QtCore import QRect, Qt
from PySide6.QtGui import QColor, QFontMetricsF, QPainter, QPainterPath, QPen

from ..core.caption_builder import CaptionCard
from ..core.layout import caption_font, layout_card, pop_scale_at
from ..core.style import AnimationStyle


def qcolor(r: float, g: float, b: float) -> QColor:
    return QColor(round(r * 255), round(g * 255), round(b * 255))


def active_card(cards: list[CaptionCard], t: float) -> CaptionCard | None:
    for card in cards:
        if card.start <= t <= card.end:
            return card
    return None


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

    canvas_w, canvas_h = video_rect.width(), video_rect.height()
    font = caption_font(style, canvas_h)
    font_px = font.pixelSize()
    metrics = QFontMetricsF(font)
    baseline_offset = (metrics.ascent() - metrics.descent()) / 2

    positioned = layout_card(card, style, canvas_w, canvas_h)
    if not positioned:
        return

    local_t = t - card.start
    anim_len = max(style.AnimationLength, 1e-6)
    entrance = max(0.0, min(local_t / anim_len, 1.0)) if style.AnimationLength > 0 else 1.0

    fill_color = qcolor(style.FillColorRed, style.FillColorGreen, style.FillColorBlue)
    highlight_color = qcolor(style.HighlightColorRed, style.HighlightColorGreen, style.HighlightColorBlue)
    outline_color = qcolor(style.OutlineColorRed, style.OutlineColorGreen, style.OutlineColorBlue)
    shadow_color = qcolor(style.ShadowColorRed, style.ShadowColorGreen, style.ShadowColorBlue)
    # libass draws its border outside the glyph; a QPen straddles the path.
    outline_pen_px = style.OutlineThickness * font_px * 2 if style.OutlineEnabled else 0.0
    shadow_offset = style.ShadowOffset * font_px

    painter.save()
    painter.setClipRect(video_rect)
    painter.setRenderHint(QPainter.Antialiasing)
    painter.setRenderHint(QPainter.TextAntialiasing)
    painter.translate(video_rect.left(), video_rect.top())
    painter.setFont(font)

    if style.FadeEnabled:
        painter.setOpacity(entrance)
    if style.SlideUpEnabled:
        painter.translate(0, (1.0 - entrance) * 0.08 * canvas_h)

    for item in positioned:
        word = item.word
        spoken = t >= word.start
        scale = pop_scale_at(style, word, t) if (spoken and style.HighlightEnabled) else 1.0
        if style.PopInEnabled:
            scale *= 0.6 + 0.4 * entrance

        painter.save()
        painter.translate(item.center_x, item.center_y)
        if scale != 1.0:
            painter.scale(scale, scale)

        x = -item.width / 2
        baseline = baseline_offset

        if style.ShadowEnabled:
            painter.setPen(shadow_color)
            painter.drawText(x + shadow_offset, baseline + shadow_offset, word.text)

        if outline_pen_px > 0:
            path = QPainterPath()
            path.addText(x, baseline, font, word.text)
            pen = QPen(outline_color, outline_pen_px)
            pen.setJoinStyle(Qt.RoundJoin)
            painter.strokePath(path, pen)

        painter.setPen(highlight_color if (spoken and style.HighlightEnabled) else fill_color)
        painter.drawText(x, baseline, word.text)
        painter.restore()

    painter.restore()
