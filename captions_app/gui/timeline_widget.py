"""Visual drag timeline: move/trim caption cards, drag the playhead to seek,
double-click a card to edit its text inline."""

from __future__ import annotations

from dataclasses import replace

from PySide6.QtCore import QRect, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPen
from PySide6.QtWidgets import QLineEdit, QWidget

from ..core.caption_builder import CaptionCard
from ..core.style import CaptionFormatting
from .caption_editor import rebuild_card_text

RULER_HEIGHT = 20
EDGE_GRAB_PX = 6
MIN_CARD_DURATION = 0.15


class TimelineWidget(QWidget):
    cards_changed = Signal()
    seek_requested = Signal(float)
    card_selected = Signal(int)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumHeight(90)
        self.setMouseTracking(True)

        self.cards: list[CaptionCard] = []
        self.formatting = CaptionFormatting()
        self.duration = 0.0
        self.pixels_per_second = 60.0
        self.playhead_t = 0.0
        self.selected_index: int | None = None

        self._drag_mode: str | None = None
        self._drag_index: int | None = None
        self._drag_start_x = 0
        self._drag_orig_start = 0.0
        self._drag_orig_end = 0.0

        self._inline_editor: QLineEdit | None = None
        self._inline_editor_index: int | None = None

    # ------------------------------------------------------------------ API
    def set_cards(self, cards: list[CaptionCard], formatting: CaptionFormatting) -> None:
        self.cards = cards
        self.formatting = formatting
        self._relayout_width()
        self.update()

    def set_duration(self, duration: float) -> None:
        self.duration = duration
        self._relayout_width()
        self.update()

    def set_playhead(self, t: float) -> None:
        self.playhead_t = t
        self.update()

    def _relayout_width(self) -> None:
        width = max(self.parentWidget().width() if self.parentWidget() else 400,
                     int(self.duration * self.pixels_per_second) + 40)
        self.setMinimumWidth(width)

    # ---------------------------------------------------------------- paint
    def paintEvent(self, event) -> None:  # noqa: N802
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        w, h = self.width(), self.height()

        painter.fillRect(0, 0, w, RULER_HEIGHT, QColor(40, 40, 40))
        painter.fillRect(0, RULER_HEIGHT, w, h - RULER_HEIGHT, QColor(24, 24, 24))

        painter.setPen(QColor(90, 90, 90))
        step = self._ruler_step()
        t = 0.0
        while t <= self.duration + step:
            x = round(t * self.pixels_per_second)
            painter.drawLine(x, RULER_HEIGHT - 6, x, RULER_HEIGHT)
            painter.drawText(x + 2, RULER_HEIGHT - 7, f"{t:.0f}s")
            t += step

        for i, card in enumerate(self.cards):
            x0 = round(card.start * self.pixels_per_second)
            x1 = round(card.end * self.pixels_per_second)
            rect = QRect(x0, RULER_HEIGHT + 6, max(x1 - x0, 2), h - RULER_HEIGHT - 12)

            selected = i == self.selected_index
            painter.setBrush(QColor(70, 110, 200) if selected else QColor(60, 90, 150))
            painter.setPen(QPen(QColor(150, 190, 255) if selected else QColor(90, 120, 170), 1.5))
            painter.drawRoundedRect(rect, 4, 4)

            text = " ".join(w_.text for line in card.lines for w_ in line)
            painter.setPen(QColor(230, 230, 230))
            painter.drawText(rect.adjusted(4, 0, -4, 0), Qt.AlignVCenter | Qt.AlignLeft, text)

        px = round(self.playhead_t * self.pixels_per_second)
        painter.setPen(QPen(QColor(230, 60, 60), 2))
        painter.drawLine(px, 0, px, h)

    def _ruler_step(self) -> float:
        for step in (1, 2, 5, 10, 30, 60):
            if step * self.pixels_per_second >= 50:
                return step
        return 60

    # ---------------------------------------------------------------- input
    def _card_rect(self, index: int) -> QRect:
        card = self.cards[index]
        x0 = round(card.start * self.pixels_per_second)
        x1 = round(card.end * self.pixels_per_second)
        return QRect(x0, RULER_HEIGHT, max(x1 - x0, 2), self.height() - RULER_HEIGHT)

    def _card_at(self, x: int) -> int | None:
        for i, card in enumerate(self.cards):
            x0 = round(card.start * self.pixels_per_second)
            x1 = round(card.end * self.pixels_per_second)
            if x0 <= x <= x1:
                return i
        return None

    def mousePressEvent(self, event) -> None:  # noqa: N802
        self._close_inline_editor(commit=True)
        x, y = event.position().x(), event.position().y()

        if y <= RULER_HEIGHT:
            self._drag_mode = "seek"
            t = max(0.0, x / self.pixels_per_second)
            self.playhead_t = t
            self.seek_requested.emit(t)
            self.update()
            return

        idx = self._card_at(int(x))
        if idx is None:
            self.selected_index = None
            self.update()
            return

        self.selected_index = idx
        self.card_selected.emit(idx)
        rect = self._card_rect(idx)

        self._drag_index = idx
        self._drag_start_x = x
        self._drag_orig_start = self.cards[idx].start
        self._drag_orig_end = self.cards[idx].end

        if abs(x - rect.left()) <= EDGE_GRAB_PX:
            self._drag_mode = "resize_left"
        elif abs(x - rect.right()) <= EDGE_GRAB_PX:
            self._drag_mode = "resize_right"
        else:
            self._drag_mode = "move"
        self.update()

    def mouseMoveEvent(self, event) -> None:  # noqa: N802
        x = event.position().x()

        if self._drag_mode == "seek":
            t = max(0.0, min(x / self.pixels_per_second, self.duration))
            self.playhead_t = t
            self.seek_requested.emit(t)
            self.update()
            return

        if self._drag_mode is None or self._drag_index is None:
            return

        i = self._drag_index
        prev_end = self.cards[i - 1].end if i > 0 else 0.0
        next_start = self.cards[i + 1].start if i + 1 < len(self.cards) else self.duration
        delta_t = (x - self._drag_start_x) / self.pixels_per_second

        if self._drag_mode == "move":
            span = self._drag_orig_end - self._drag_orig_start
            new_start = max(prev_end, min(self._drag_orig_start + delta_t, next_start - span))
            new_end = new_start + span
        elif self._drag_mode == "resize_left":
            new_start = max(prev_end, min(self._drag_orig_start + delta_t, self._drag_orig_end - MIN_CARD_DURATION))
            new_end = self.cards[i].end
        else:  # resize_right
            new_start = self.cards[i].start
            new_end = min(next_start, max(self._drag_orig_end + delta_t, self._drag_orig_start + MIN_CARD_DURATION))

        self.cards[i] = replace(self.cards[i], start=new_start, end=new_end)
        self.update()

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if self._drag_mode in ("move", "resize_left", "resize_right"):
            self.cards_changed.emit()
        self._drag_mode = None
        self._drag_index = None

    def mouseDoubleClickEvent(self, event) -> None:  # noqa: N802
        x, y = event.position().x(), event.position().y()
        if y <= RULER_HEIGHT:
            return
        idx = self._card_at(int(x))
        if idx is None:
            return
        self._open_inline_editor(idx)

    # --------------------------------------------------------- inline edit
    def _open_inline_editor(self, index: int) -> None:
        self._close_inline_editor(commit=False)
        rect = self._card_rect(index)
        card = self.cards[index]
        text = " ".join(w_.text for line in card.lines for w_ in line)

        editor = QLineEdit(self)
        editor.setGeometry(rect)
        editor.setText(text)
        editor.selectAll()
        editor.show()
        editor.setFocus()
        editor.editingFinished.connect(lambda: self._close_inline_editor(commit=True))

        self._inline_editor = editor
        self._inline_editor_index = index

    def _close_inline_editor(self, commit: bool) -> None:
        editor = self._inline_editor
        if editor is None:
            return
        self._inline_editor = None
        if commit:
            idx = self._inline_editor_index
            if idx is not None and idx < len(self.cards):
                new_text = editor.text()
                self.cards[idx] = rebuild_card_text(self.cards[idx], new_text, self.formatting)
                self.cards_changed.emit()
                self.update()
        editor.deleteLater()
