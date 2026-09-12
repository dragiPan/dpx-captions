"""Playable video preview with captions drawn on top of the decoded frame.

Frames are pulled through a QVideoSink and painted manually rather than
using QVideoWidget: on Windows QVideoWidget owns a native window, so any
overlay widget stacked above it is not composited and the captions stay
invisible. Painting the frame and the captions in one paintEvent also lets
us position captions against the real video rect instead of the widget's
letterboxed bounds.
"""

from __future__ import annotations

from PySide6.QtCore import QRect, Qt, QUrl, Signal
from PySide6.QtGui import QColor, QImage, QPainter
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer, QVideoSink
from PySide6.QtWidgets import QHBoxLayout, QLabel, QPushButton, QSizePolicy, QSlider, QVBoxLayout, QWidget

from ..core.caption_builder import CaptionCard
from ..core.style import AnimationStyle
from .caption_render import draw_captions


def _fmt_time(seconds: float) -> str:
    m = int(seconds // 60)
    s = int(seconds % 60)
    return f"{m}:{s:02d}"


class _VideoSurface(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        self.setMinimumHeight(240)
        self._image: QImage | None = None
        self.cards: list[CaptionCard] = []
        self.style = AnimationStyle()
        self.current_time = 0.0

    def set_frame(self, image: QImage, t: float) -> None:
        self._image = image
        self.current_time = t
        self.update()

    def set_time(self, t: float) -> None:
        self.current_time = t
        self.update()

    def video_rect(self) -> QRect:
        if self._image is None or self._image.isNull():
            return self.rect()
        iw, ih = self._image.width(), self._image.height()
        if iw <= 0 or ih <= 0:
            return self.rect()
        scale = min(self.width() / iw, self.height() / ih)
        w, h = round(iw * scale), round(ih * scale)
        return QRect((self.width() - w) // 2, (self.height() - h) // 2, w, h)

    def paintEvent(self, event) -> None:  # noqa: N802
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor(12, 12, 12))
        if self._image is not None and not self._image.isNull():
            rect = self.video_rect()
            painter.setRenderHint(QPainter.SmoothPixmapTransform)
            painter.drawImage(rect, self._image)
            draw_captions(painter, rect, self.cards, self.style, self.current_time)
        painter.end()


class VideoPreviewWidget(QWidget):
    position_changed = Signal(float)
    duration_changed = Signal(float)

    def __init__(self, parent=None):
        super().__init__(parent)

        self.player = QMediaPlayer(self)
        self.audio_output = QAudioOutput(self)
        self.player.setAudioOutput(self.audio_output)

        self.video_sink = QVideoSink(self)
        self.player.setVideoSink(self.video_sink)
        self.video_sink.videoFrameChanged.connect(self._on_frame)

        self.surface = _VideoSurface()

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self.surface, 1)

        controls = QHBoxLayout()
        self.play_btn = QPushButton("Play")
        self.play_btn.setFixedWidth(70)
        self.play_btn.clicked.connect(self.toggle_play)
        controls.addWidget(self.play_btn)

        self.seek_slider = QSlider(Qt.Horizontal)
        self.seek_slider.setRange(0, 0)
        self.seek_slider.sliderMoved.connect(lambda ms: self.player.setPosition(ms))
        controls.addWidget(self.seek_slider, 1)

        self.time_label = QLabel("0:00 / 0:00")
        controls.addWidget(self.time_label)
        layout.addLayout(controls)

        self.player.positionChanged.connect(self._on_position_changed)
        self.player.durationChanged.connect(self._on_duration_changed)
        self.player.playbackStateChanged.connect(self._on_playback_state_changed)

        self._duration_s = 0.0

    # -------------------------------------------------------------------- API
    def load_video(self, path: str) -> None:
        self.player.setSource(QUrl.fromLocalFile(path))
        # Decode the first frame so the surface isn't blank before playback.
        self.player.play()
        self.player.pause()
        self.player.setPosition(0)

    def set_cards(self, cards: list[CaptionCard]) -> None:
        self.surface.cards = cards
        self.surface.update()

    def set_style(self, style: AnimationStyle) -> None:
        self.surface.style = style
        self.surface.update()

    def seek(self, seconds: float) -> None:
        self.player.setPosition(round(seconds * 1000))
        self.surface.set_time(seconds)

    def toggle_play(self) -> None:
        if self.player.playbackState() == QMediaPlayer.PlayingState:
            self.player.pause()
        else:
            self.player.play()

    # ----------------------------------------------------------------- events
    def _on_frame(self, frame) -> None:
        if not frame.isValid():
            return
        image = frame.toImage()
        if image.isNull():
            return
        start_us = frame.startTime()
        t = start_us / 1_000_000.0 if start_us >= 0 else self.player.position() / 1000.0
        self.surface.set_frame(image, t)
        self.position_changed.emit(t)

    def _on_playback_state_changed(self, state) -> None:
        self.play_btn.setText("Pause" if state == QMediaPlayer.PlayingState else "Play")

    def _on_position_changed(self, position_ms: int) -> None:
        self.seek_slider.blockSignals(True)
        self.seek_slider.setValue(position_ms)
        self.seek_slider.blockSignals(False)
        self.time_label.setText(f"{_fmt_time(position_ms / 1000.0)} / {_fmt_time(self._duration_s)}")

    def _on_duration_changed(self, duration_ms: int) -> None:
        self._duration_s = duration_ms / 1000.0
        self.seek_slider.setRange(0, duration_ms)
        self.duration_changed.emit(self._duration_s)
