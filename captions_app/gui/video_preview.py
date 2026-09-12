"""Playable video preview with a live-rendered caption overlay.

The overlay re-implements the same word-fill/outline/shadow/entrance-
animation logic as ass_writer.py, but with QPainter instead of libass, so
scrubbing/playing here is a faithful (if not pixel-identical) preview of
what Export will actually burn in.
"""

from __future__ import annotations

from PySide6.QtCore import QUrl, Qt, Signal
from PySide6.QtGui import QColor, QFont, QFontMetrics, QPainter, QPainterPath, QPen
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer
from PySide6.QtMultimediaWidgets import QVideoWidget
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSlider,
    QStackedLayout,
    QVBoxLayout,
    QWidget,
)

from ..core.caption_builder import CaptionCard
from ..core.style import AnimationStyle


def _qcolor(r: float, g: float, b: float) -> QColor:
    return QColor(round(r * 255), round(g * 255), round(b * 255))


def _fmt_time(seconds: float) -> str:
    m = int(seconds // 60)
    s = int(seconds % 60)
    return f"{m}:{s:02d}"


class CaptionOverlay(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setAttribute(Qt.WA_TransparentForMouseEvents)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.cards: list[CaptionCard] = []
        self.style = AnimationStyle()
        self.current_time = 0.0

    def set_cards(self, cards: list[CaptionCard]) -> None:
        self.cards = cards
        self.update()

    def set_style(self, style: AnimationStyle) -> None:
        self.style = style
        self.update()

    def set_time(self, t: float) -> None:
        self.current_time = t
        self.update()

    def _active_card(self) -> CaptionCard | None:
        for c in self.cards:
            if c.start <= self.current_time <= c.end:
                return c
        return None

    def paintEvent(self, event) -> None:  # noqa: N802 (Qt override)
        card = self._active_card()
        if card is None:
            return

        style = self.style
        w, h = self.width(), self.height()
        if w <= 0 or h <= 0:
            return

        font = QFont(style.Font)
        font_px = max(1, round(style.TextSize * h))
        font.setPixelSize(font_px)
        font.setBold("bold" in style.Style.lower())
        font.setItalic("italic" in style.Style.lower())
        metrics = QFontMetrics(font)
        line_height = metrics.height()

        x_center = style.TextPosition[0] * w
        y_center = (1.0 - style.TextPosition[1]) * h
        local_t = self.current_time - card.start
        anim_len = max(style.AnimationLength, 1e-6)
        progress = max(0.0, min(local_t / anim_len, 1.0))

        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        painter.setRenderHint(QPainter.TextAntialiasing)

        opacity = progress if style.FadeEnabled else 1.0
        scale = (0.6 + 0.4 * progress) if style.PopInEnabled else 1.0
        slide_offset = ((1.0 - progress) * 0.08 * h) if style.SlideUpEnabled else 0.0

        painter.setOpacity(opacity)
        painter.translate(x_center, y_center + slide_offset)
        painter.scale(scale, scale)
        painter.translate(-x_center, -y_center)

        total_height = len(card.lines) * line_height
        start_y = y_center - total_height / 2 + metrics.ascent()

        fill_color = _qcolor(style.FillColorRed, style.FillColorGreen, style.FillColorBlue)
        highlight_color = _qcolor(style.HighlightColorRed, style.HighlightColorGreen, style.HighlightColorBlue)
        outline_color = _qcolor(style.OutlineColorRed, style.OutlineColorGreen, style.OutlineColorBlue)
        shadow_color = _qcolor(style.ShadowColorRed, style.ShadowColorGreen, style.ShadowColorBlue)
        outline_px = max(1.0, style.OutlineThickness * font_px) if style.OutlineEnabled else 0.0

        for row, line in enumerate(card.lines):
            baseline_y = start_y + row * line_height
            space_w = metrics.horizontalAdvance(" ")
            line_width = sum(metrics.horizontalAdvance(w_.text) for w_ in line) + space_w * max(len(line) - 1, 0)
            word_x = x_center - line_width / 2

            for word in line:
                word_w = metrics.horizontalAdvance(word.text)
                if local_t <= word.start - card.start:
                    fraction = 0.0
                elif local_t >= word.end - card.start:
                    fraction = 1.0
                else:
                    fraction = (local_t - (word.start - card.start)) / max(word.end - word.start, 1e-6)

                if style.ShadowEnabled:
                    painter.setPen(shadow_color)
                    painter.drawText(round(word_x + 2), round(baseline_y + 2), word.text)

                if outline_px > 0:
                    path = QPainterPath()
                    path.addText(word_x, baseline_y, font, word.text)
                    painter.strokePath(path, QPen(outline_color, outline_px))

                painter.setFont(font)
                painter.setPen(fill_color)
                painter.drawText(round(word_x), round(baseline_y), word.text)

                if fraction > 0:
                    painter.save()
                    clip_w = round(word_w * fraction)
                    painter.setClipRect(round(word_x), round(baseline_y - metrics.ascent()), clip_w, line_height)
                    painter.setPen(highlight_color)
                    painter.drawText(round(word_x), round(baseline_y), word.text)
                    painter.restore()

                word_x += word_w + space_w

        painter.end()


class VideoPreviewWidget(QWidget):
    position_changed = Signal(float)
    duration_changed = Signal(float)

    def __init__(self, parent=None):
        super().__init__(parent)

        self.player = QMediaPlayer(self)
        self.audio_output = QAudioOutput(self)
        self.player.setAudioOutput(self.audio_output)

        self.video_widget = QVideoWidget()
        self.player.setVideoOutput(self.video_widget)

        self.overlay = CaptionOverlay()

        stack_container = QWidget()
        stack = QStackedLayout(stack_container)
        stack.setStackingMode(QStackedLayout.StackAll)
        stack.addWidget(self.video_widget)
        stack.addWidget(self.overlay)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(stack_container, 1)

        controls = QHBoxLayout()
        self.play_btn = QPushButton("Play")
        self.play_btn.clicked.connect(self._toggle_play)
        controls.addWidget(self.play_btn)

        self.seek_slider = QSlider(Qt.Horizontal)
        self.seek_slider.setRange(0, 0)
        self.seek_slider.sliderMoved.connect(self._on_slider_moved)
        controls.addWidget(self.seek_slider, 1)

        self.time_label = QLabel("0:00 / 0:00")
        controls.addWidget(self.time_label)
        layout.addLayout(controls)

        self.player.positionChanged.connect(self._on_position_changed)
        self.player.durationChanged.connect(self._on_duration_changed)
        self.player.playbackStateChanged.connect(self._on_playback_state_changed)

        self._duration_s = 0.0

    def load_video(self, path: str) -> None:
        self.player.setSource(QUrl.fromLocalFile(path))

    def set_cards(self, cards: list[CaptionCard]) -> None:
        self.overlay.set_cards(cards)

    def set_style(self, style: AnimationStyle) -> None:
        self.overlay.set_style(style)

    def seek(self, seconds: float) -> None:
        self.player.setPosition(round(seconds * 1000))

    def _toggle_play(self) -> None:
        if self.player.playbackState() == QMediaPlayer.PlayingState:
            self.player.pause()
        else:
            self.player.play()

    def _on_playback_state_changed(self, state) -> None:
        self.play_btn.setText("Pause" if state == QMediaPlayer.PlayingState else "Play")

    def _on_slider_moved(self, value: int) -> None:
        self.player.setPosition(value)

    def _on_position_changed(self, position_ms: int) -> None:
        t = position_ms / 1000.0
        self.overlay.set_time(t)
        self.seek_slider.blockSignals(True)
        self.seek_slider.setValue(position_ms)
        self.seek_slider.blockSignals(False)
        self.time_label.setText(f"{_fmt_time(t)} / {_fmt_time(self._duration_s)}")
        self.position_changed.emit(t)

    def _on_duration_changed(self, duration_ms: int) -> None:
        self._duration_s = duration_ms / 1000.0
        self.seek_slider.setRange(0, duration_ms)
        self.duration_changed.emit(self._duration_s)
