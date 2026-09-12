"""Playable video preview with captions composited over the frame.

Rendering goes through QGraphicsVideoItem rather than converting every
decoded frame to a QImage: at 4K the per-frame RGB conversion alone was
enough to stall playback. Qt now renders video on its own accelerated
path, and the captions are a sibling graphics item drawn above it, so only
the caption region repaints per frame.

The scene is a *canvas* of the chosen target aspect with the source video
centred inside it, which mirrors what the export does when padding to a
different ratio.
"""

from __future__ import annotations

from PySide6.QtCore import QRectF, QSizeF, Qt, QUrl, Signal
from PySide6.QtGui import QColor, QPainter
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer
from PySide6.QtMultimediaWidgets import QGraphicsVideoItem
from PySide6.QtWidgets import (
    QComboBox,
    QGraphicsItem,
    QGraphicsScene,
    QGraphicsView,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSlider,
    QVBoxLayout,
    QWidget,
)

from ..core.aspect import Aspect, canvas_size
from ..core.caption_builder import CaptionCard
from ..core.style import AnimationStyle
from .caption_render import draw_captions

# The timeline playhead doesn't need per-frame precision; repainting a long
# timeline 60 times a second is pure waste.
PLAYHEAD_EMIT_INTERVAL = 1 / 15


def _fmt_time(seconds: float) -> str:
    m = int(seconds // 60)
    s = int(seconds % 60)
    return f"{m}:{s:02d}"


class _CaptionItem(QGraphicsItem):
    def __init__(self):
        super().__init__()
        self.setZValue(10)
        self._rect = QRectF(0, 0, 1080, 1920)
        self.cards: list[CaptionCard] = []
        self.style = AnimationStyle()
        self.current_time = 0.0

    def set_canvas(self, width: float, height: float) -> None:
        self.prepareGeometryChange()
        self._rect = QRectF(0, 0, width, height)
        self.update()

    def set_time(self, t: float) -> None:
        self.current_time = t
        self.update()

    def boundingRect(self) -> QRectF:  # noqa: N802
        return self._rect

    def paint(self, painter: QPainter, option, widget=None) -> None:
        draw_captions(painter, self._rect.toRect(), self.cards, self.style, self.current_time)


class VideoPreviewWidget(QWidget):
    position_changed = Signal(float)
    duration_changed = Signal(float)

    def __init__(self, parent=None):
        super().__init__(parent)

        self.player = QMediaPlayer(self)
        self.audio_output = QAudioOutput(self)
        self.player.setAudioOutput(self.audio_output)

        self.scene = QGraphicsScene(self)
        self.scene.setBackgroundBrush(QColor(0, 0, 0))
        self.video_item = QGraphicsVideoItem()
        self.video_item.setAspectRatioMode(Qt.KeepAspectRatio)
        self.scene.addItem(self.video_item)
        self.caption_item = _CaptionItem()
        self.scene.addItem(self.caption_item)
        self.player.setVideoOutput(self.video_item)

        self.view = QGraphicsView(self.scene)
        self.view.setFrameShape(QGraphicsView.NoFrame)
        self.view.setRenderHints(QPainter.Antialiasing | QPainter.TextAntialiasing | QPainter.SmoothPixmapTransform)
        self.view.setViewportUpdateMode(QGraphicsView.MinimalViewportUpdate)
        self.view.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.view.setVerticalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.view.setBackgroundBrush(QColor(12, 12, 12))
        self.view.setMinimumHeight(240)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self.view, 1)

        controls = QHBoxLayout()
        self.play_btn = QPushButton("Play")
        self.play_btn.setFixedWidth(70)
        self.play_btn.clicked.connect(self.toggle_play)
        controls.addWidget(self.play_btn)

        self.seek_slider = QSlider(Qt.Horizontal)
        self.seek_slider.setRange(0, 0)
        self.seek_slider.sliderMoved.connect(self.player.setPosition)
        controls.addWidget(self.seek_slider, 1)

        self.time_label = QLabel("0:00 / 0:00")
        controls.addWidget(self.time_label)

        controls.addWidget(QLabel("Aspect"))
        self.aspect_combo = QComboBox()
        for aspect in Aspect:
            self.aspect_combo.addItem(aspect.label, aspect)
        self.aspect_combo.currentIndexChanged.connect(self._on_aspect_changed)
        controls.addWidget(self.aspect_combo)
        layout.addLayout(controls)

        self.player.durationChanged.connect(self._on_duration_changed)
        self.player.playbackStateChanged.connect(self._on_playback_state_changed)
        self.player.positionChanged.connect(self._on_position_changed)
        self.video_item.nativeSizeChanged.connect(self._on_native_size_changed)
        self.video_item.videoSink().videoFrameChanged.connect(self._on_frame)

        self._duration_s = 0.0
        self._source_size = (1080, 1920)
        self._last_emit_t = -1.0
        self._laid_out = False

    # -------------------------------------------------------------------- API
    def load_video(self, path: str) -> None:
        self._laid_out = False
        self.player.setSource(QUrl.fromLocalFile(path))
        self.player.play()
        self.player.pause()
        self.player.setPosition(0)

    def set_cards(self, cards: list[CaptionCard]) -> None:
        self.caption_item.cards = cards
        self.caption_item.update()

    def set_style(self, style: AnimationStyle) -> None:
        self.caption_item.style = style
        self.caption_item.update()

    def aspect(self) -> Aspect:
        return self.aspect_combo.currentData()

    def set_aspect(self, aspect: Aspect) -> None:
        index = self.aspect_combo.findData(aspect)
        if index >= 0:
            self.aspect_combo.setCurrentIndex(index)
        self._relayout_scene()

    def seek(self, seconds: float) -> None:
        self.player.setPosition(round(seconds * 1000))
        self.caption_item.set_time(seconds)

    def toggle_play(self) -> None:
        if self.player.playbackState() == QMediaPlayer.PlayingState:
            self.player.pause()
        else:
            self.player.play()

    # ----------------------------------------------------------------- layout
    def _on_native_size_changed(self, size: QSizeF) -> None:
        if size.width() > 0 and size.height() > 0:
            self._source_size = (int(size.width()), int(size.height()))
        self._relayout_scene()

    def _on_aspect_changed(self) -> None:
        self._relayout_scene()

    def _relayout_scene(self) -> None:
        sw, sh = self._source_size
        cw, ch = canvas_size(sw, sh, self.aspect())

        # Mirrors the export's scale=decrease + pad, so preview framing and
        # caption placement match the burned-in output.
        scale = min(cw / sw, ch / sh)
        vw, vh = sw * scale, sh * scale
        self.video_item.setSize(QSizeF(vw, vh))
        self.video_item.setPos((cw - vw) / 2, (ch - vh) / 2)
        self.caption_item.set_canvas(cw, ch)
        self.scene.setSceneRect(QRectF(0, 0, cw, ch))
        self._fit()

    def _fit(self) -> None:
        self.view.fitInView(self.scene.sceneRect(), Qt.KeepAspectRatio)

    def resizeEvent(self, event) -> None:  # noqa: N802
        super().resizeEvent(event)
        self._fit()

    # ----------------------------------------------------------------- events
    def _on_frame(self, frame) -> None:
        if not self._laid_out and frame.isValid():
            # The item reports its placeholder size until it has a frame.
            self._laid_out = True
            self._relayout_scene()

        start_us = frame.startTime()
        if start_us < 0:
            return
        t = start_us / 1_000_000.0
        self.caption_item.set_time(t)
        if abs(t - self._last_emit_t) >= PLAYHEAD_EMIT_INTERVAL:
            self._last_emit_t = t
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
