"""NLE-style caption timeline: drag to move, drag edges to trim, Ctrl+B to
cut at the playhead, Delete to remove, mouse-wheel zoom, waveform track.

Timing is edited here with the mouse; the subtitles list is only for text.
"""

from __future__ import annotations

from dataclasses import replace

import numpy as np
from PySide6.QtCore import QPoint, QRect, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPen, QPixmap, QPolygon
from PySide6.QtWidgets import QLineEdit, QWidget

from ..core.caption_builder import CaptionCard
from ..core.style import CaptionFormatting
from ..core.waveform import BUCKETS_PER_SECOND
from .caption_editor import rebuild_card_text

RULER_H = 22
WAVE_H = 58
CARD_H = 52
TOTAL_H = RULER_H + WAVE_H + CARD_H + 8

EDGE_GRAB_PX = 7
SNAP_PX = 8
MIN_CARD_DURATION = 0.1
MIN_PPS = 4.0
MAX_PPS = 400.0


class TimelineWidget(QWidget):
    cards_changed = Signal()
    seek_requested = Signal(float)
    card_selected = Signal(int)
    play_pause_requested = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFixedHeight(TOTAL_H)
        self.setMouseTracking(True)
        self.setFocusPolicy(Qt.StrongFocus)

        self.cards: list[CaptionCard] = []
        self.formatting = CaptionFormatting()
        self.duration = 0.0
        self.pixels_per_second = 60.0
        self.playhead_t = 0.0
        self.selected_index: int | None = None

        self._peaks: np.ndarray | None = None
        self._wave_pixmap: QPixmap | None = None
        self._wave_key: tuple | None = None

        self._drag_mode: str | None = None
        self._drag_index: int | None = None
        self._drag_start_x = 0.0
        self._drag_orig_start = 0.0
        self._drag_orig_end = 0.0
        self._drag_moved = False

        self._inline_editor: QLineEdit | None = None
        self._inline_index: int | None = None
        self.scroll_area = None  # set by the main window, for cursor-anchored zoom

    # -------------------------------------------------------------------- API
    def set_cards(self, cards: list[CaptionCard], formatting: CaptionFormatting) -> None:
        self.cards = cards
        self.formatting = formatting
        if self.selected_index is not None and self.selected_index >= len(cards):
            self.selected_index = None
        self.update()

    def set_duration(self, duration: float) -> None:
        self.duration = duration
        self.zoom_fit()

    def set_peaks(self, peaks: np.ndarray) -> None:
        self._peaks = peaks
        self._wave_key = None
        self.update()

    def set_playhead(self, t: float) -> None:
        if abs(t - self.playhead_t) * self.pixels_per_second < 1.0:
            self.playhead_t = t
            return
        self.playhead_t = t
        self.update()

    def zoom_fit(self) -> None:
        viewport = self.parentWidget().width() if self.parentWidget() else self.width()
        if self.duration > 0 and viewport > 0:
            self.pixels_per_second = max(MIN_PPS, (viewport - 4) / self.duration)
        self._apply_width()

    def zoom_by(self, factor: float, anchor_t: float | None = None) -> None:
        self.pixels_per_second = max(MIN_PPS, min(self.pixels_per_second * factor, MAX_PPS))
        self._apply_width()
        if anchor_t is not None:
            self.update()

    def _apply_width(self) -> None:
        width = max(200, int(self.duration * self.pixels_per_second) + 4)
        self.setMinimumWidth(width)
        self.resize(width, self.height())
        self._wave_key = None
        self.update()

    # ---------------------------------------------------------------- helpers
    def _x_of(self, t: float) -> float:
        return t * self.pixels_per_second

    def _t_of(self, x: float) -> float:
        return max(0.0, x / self.pixels_per_second)

    def _cards_top(self) -> int:
        return RULER_H + WAVE_H

    def _card_rect(self, index: int) -> QRect:
        card = self.cards[index]
        x0 = round(self._x_of(card.start))
        x1 = round(self._x_of(card.end))
        return QRect(x0, self._cards_top(), max(x1 - x0, 2), CARD_H)

    def _card_at(self, x: float, y: float) -> int | None:
        if y < self._cards_top() or y > self._cards_top() + CARD_H:
            return None
        for i in range(len(self.cards)):
            rect = self._card_rect(i)
            if rect.left() - EDGE_GRAB_PX <= x <= rect.right() + EDGE_GRAB_PX:
                return i
        return None

    def _hit_test(self, x: float, y: float) -> tuple[int | None, str | None]:
        """Which card and which drag mode the cursor is over.

        Adjacent cards share a boundary pixel, so the side the cursor sits
        on decides whether the left card's end or the right card's start is
        grabbed - otherwise the left card always wins and the right one can
        never be trimmed.
        """
        if y < self._cards_top() or y > self._cards_top() + CARD_H:
            return None, None

        best: tuple[float, int, str] | None = None
        for i, card in enumerate(self.cards):
            for edge_t, mode in ((card.start, "resize_left"), (card.end, "resize_right")):
                edge_x = self._x_of(edge_t)
                distance = abs(x - edge_x)
                if distance > EDGE_GRAB_PX:
                    continue
                on_matching_side = (mode == "resize_left" and x >= edge_x) or (
                    mode == "resize_right" and x <= edge_x
                )
                score = distance - (EDGE_GRAB_PX if on_matching_side else 0)
                if best is None or score < best[0]:
                    best = (score, i, mode)

        if best is not None:
            return best[1], best[2]

        index = self._card_at(x, y)
        return (index, "move") if index is not None else (None, None)

    def _snap(self, t: float, ignore_index: int | None) -> float:
        targets = [0.0, self.duration, self.playhead_t]
        for i, card in enumerate(self.cards):
            if i == ignore_index:
                continue
            targets.extend((card.start, card.end))
        threshold = SNAP_PX / self.pixels_per_second
        best = min(targets, key=lambda target: abs(target - t))
        return best if abs(best - t) <= threshold else t

    # ------------------------------------------------------------------ paint
    def paintEvent(self, event) -> None:  # noqa: N802
        painter = QPainter(self)
        w, h = self.width(), self.height()

        painter.fillRect(0, 0, w, RULER_H, QColor(38, 38, 40))
        painter.fillRect(0, RULER_H, w, WAVE_H, QColor(22, 26, 24))
        painter.fillRect(0, self._cards_top(), w, h - self._cards_top(), QColor(26, 26, 28))

        self._paint_ruler(painter)
        self._paint_waveform(painter)
        self._paint_cards(painter)

        px = round(self._x_of(self.playhead_t))
        painter.setPen(QPen(QColor(235, 70, 70), 2))
        painter.drawLine(px, 0, px, h)
        painter.setBrush(QColor(235, 70, 70))
        painter.setPen(Qt.NoPen)
        painter.drawPolygon(QPolygon([QPoint(px - 6, 0), QPoint(px + 6, 0), QPoint(px, 9)]))
        painter.end()

    def _paint_ruler(self, painter: QPainter) -> None:
        painter.setPen(QColor(140, 140, 140))
        step = self._ruler_step()
        t = 0.0
        while t <= self.duration + step:
            x = round(self._x_of(t))
            painter.drawLine(x, RULER_H - 7, x, RULER_H)
            painter.drawText(x + 3, RULER_H - 8, self._fmt_tick(t))
            t += step

    def _ruler_step(self) -> float:
        for step in (0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300):
            if step * self.pixels_per_second >= 60:
                return float(step)
        return 600.0

    @staticmethod
    def _fmt_tick(t: float) -> str:
        m = int(t // 60)
        s = t % 60
        return f"{m}:{s:04.1f}" if t % 1 else f"{m}:{int(s):02d}"

    def _paint_waveform(self, painter: QPainter) -> None:
        if self._peaks is None or self._peaks.size == 0:
            return
        key = (self.width(), self.pixels_per_second, self._peaks.size)
        if self._wave_key != key or self._wave_pixmap is None:
            self._wave_pixmap = self._render_waveform()
            self._wave_key = key
        painter.drawPixmap(0, RULER_H, self._wave_pixmap)

    def _render_waveform(self) -> QPixmap:
        w = self.width()
        pixmap = QPixmap(max(w, 1), WAVE_H)
        pixmap.fill(QColor(22, 26, 24))
        p = QPainter(pixmap)
        p.setPen(QPen(QColor(90, 190, 130), 1))
        mid = WAVE_H / 2
        peaks = self._peaks
        for x in range(w):
            t0 = self._t_of(x)
            t1 = self._t_of(x + 1)
            i0 = int(t0 * BUCKETS_PER_SECOND)
            i1 = max(i0 + 1, int(t1 * BUCKETS_PER_SECOND))
            if i0 >= peaks.size:
                break
            amp = float(peaks[i0:min(i1, peaks.size)].max())
            half = amp * (WAVE_H / 2 - 2)
            p.drawLine(x, round(mid - half), x, round(mid + half))
        p.end()
        return pixmap

    def _paint_cards(self, painter: QPainter) -> None:
        for i, card in enumerate(self.cards):
            rect = self._card_rect(i)
            selected = i == self.selected_index
            painter.setBrush(QColor(72, 116, 205) if selected else QColor(52, 82, 140))
            painter.setPen(QPen(QColor(170, 205, 255) if selected else QColor(80, 110, 160), 2 if selected else 1))
            painter.drawRoundedRect(rect, 4, 4)

            if rect.width() > 24:
                painter.setPen(QColor(245, 245, 245))
                text = " ".join(w.text for line in card.lines for w in line)
                painter.drawText(rect.adjusted(6, 0, -6, 0), Qt.AlignVCenter | Qt.AlignLeft, text)

    # ------------------------------------------------------------------ mouse
    def mousePressEvent(self, event) -> None:  # noqa: N802
        self._commit_inline_editor()
        self.setFocus()
        x, y = event.position().x(), event.position().y()

        if y <= RULER_H + WAVE_H:
            self._drag_mode = "seek"
            self._seek_to(x)
            return

        idx, mode = self._hit_test(x, y)
        if idx is None:
            self.selected_index = None
            self._drag_mode = None
            self.update()
            return

        self.selected_index = idx
        self.card_selected.emit(idx)
        self._drag_index = idx
        self._drag_start_x = x
        self._drag_orig_start = self.cards[idx].start
        self._drag_orig_end = self.cards[idx].end
        self._drag_moved = False
        self._drag_mode = mode
        self.update()

    def mouseMoveEvent(self, event) -> None:  # noqa: N802
        x, y = event.position().x(), event.position().y()

        if self._drag_mode is None:
            _, mode = self._hit_test(x, y)
            if mode in ("resize_left", "resize_right"):
                self.setCursor(Qt.SizeHorCursor)
            elif mode == "move":
                self.setCursor(Qt.OpenHandCursor)
            else:
                self.setCursor(Qt.ArrowCursor)
            return

        if self._drag_mode == "seek":
            self._seek_to(x)
            return

        i = self._drag_index
        if i is None:
            return
        self._drag_moved = True

        limit = max(self.duration, self._drag_orig_end)
        delta_t = (x - self._drag_start_x) / self.pixels_per_second

        # Neighbours are deliberately not treated as walls: dragging past a
        # card overwrites it on release, the way a timeline overwrite edit
        # behaves. Snapping still provides the magnet feel until you push
        # beyond the snap threshold.
        if self._drag_mode == "move":
            span = self._drag_orig_end - self._drag_orig_start
            new_start = max(0.0, min(self._snap(self._drag_orig_start + delta_t, i), limit - span))
            new_end = new_start + span
        elif self._drag_mode == "resize_left":
            new_start = max(0.0, min(self._snap(self._drag_orig_start + delta_t, i),
                                     self.cards[i].end - MIN_CARD_DURATION))
            new_end = self.cards[i].end
        else:
            new_start = self.cards[i].start
            new_end = min(limit, max(self._snap(self._drag_orig_end + delta_t, i),
                                     self.cards[i].start + MIN_CARD_DURATION))

        self.cards[i] = replace(self.cards[i], start=new_start, end=new_end)
        self.update()

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if self._drag_mode in ("move", "resize_left", "resize_right") and self._drag_moved:
            if self._drag_index is not None:
                self._apply_overwrite(self._drag_index)
            self.cards_changed.emit()
        self._drag_mode = None
        self._drag_index = None
        self.setCursor(Qt.ArrowCursor)

    def _apply_overwrite(self, index: int) -> None:
        """Trims or removes whatever the edited card now covers."""
        edited = self.cards[index]
        kept = []
        for i, card in enumerate(self.cards):
            if i == index:
                continue
            if card.end <= edited.start or card.start >= edited.end:
                kept.append(card)
            elif card.start >= edited.start and card.end <= edited.end:
                continue  # fully covered
            elif card.start < edited.start:
                kept.append(replace(card, end=edited.start))
            else:
                kept.append(replace(card, start=edited.end))

        kept.append(edited)
        kept.sort(key=lambda c: c.start)
        self.cards[:] = kept
        self.selected_index = kept.index(edited)

    def mouseDoubleClickEvent(self, event) -> None:  # noqa: N802
        x, y = event.position().x(), event.position().y()
        idx = self._card_at(x, y)
        if idx is not None:
            self._open_inline_editor(idx)

    def wheelEvent(self, event) -> None:  # noqa: N802
        if not event.modifiers() & (Qt.AltModifier | Qt.ControlModifier):
            event.ignore()
            return

        cursor_x = event.position().x()
        anchor_t = self._t_of(cursor_x)
        scroll_bar = self.scroll_area.horizontalScrollBar() if self.scroll_area else None
        # Where the cursor sits inside the visible strip, so that the same
        # instant stays under the pointer instead of jumping to clip start.
        viewport_x = cursor_x - (scroll_bar.value() if scroll_bar else 0)

        self.zoom_by(1.15 if event.angleDelta().y() > 0 else 1 / 1.15)

        if scroll_bar:
            scroll_bar.setValue(round(anchor_t * self.pixels_per_second - viewport_x))
        event.accept()

    def _seek_to(self, x: float) -> None:
        t = min(self._t_of(x), self.duration if self.duration else self._t_of(x))
        self.playhead_t = t
        self.seek_requested.emit(t)
        self.update()

    # --------------------------------------------------------------- keyboard
    def keyPressEvent(self, event) -> None:  # noqa: N802
        if event.key() == Qt.Key_B and event.modifiers() & Qt.ControlModifier:
            self.split_at_playhead()
        elif event.key() in (Qt.Key_Delete, Qt.Key_Backspace):
            self.delete_selected()
        elif event.key() == Qt.Key_Space:
            self.play_pause_requested.emit()
        else:
            super().keyPressEvent(event)

    def split_at_playhead(self) -> None:
        t = self.playhead_t
        for i, card in enumerate(self.cards):
            if not (card.start < t < card.end):
                continue
            words = [w for line in card.lines for w in line]
            left = [w for w in words if (w.start + w.end) / 2 < t]
            right = [w for w in words if (w.start + w.end) / 2 >= t]
            if not left or not right:
                return
            first = CaptionCard(lines=[left], start=card.start, end=t)
            second = CaptionCard(lines=[right], start=t, end=card.end)
            self.cards[i:i + 1] = [first, second]
            self.selected_index = i
            self.cards_changed.emit()
            self.update()
            return

    def delete_selected(self) -> None:
        if self.selected_index is None or self.selected_index >= len(self.cards):
            return
        del self.cards[self.selected_index]
        self.selected_index = None
        self.cards_changed.emit()
        self.update()

    # ------------------------------------------------------------ inline edit
    def _open_inline_editor(self, index: int) -> None:
        self._commit_inline_editor()
        rect = self._card_rect(index)
        card = self.cards[index]
        editor = QLineEdit(self)
        editor.setGeometry(rect.adjusted(2, 8, -2, -8))
        editor.setText(" ".join(w.text for line in card.lines for w in line))
        editor.selectAll()
        editor.show()
        editor.setFocus()
        editor.editingFinished.connect(self._commit_inline_editor)
        self._inline_editor = editor
        self._inline_index = index

    def _commit_inline_editor(self) -> None:
        editor = self._inline_editor
        if editor is None:
            return
        self._inline_editor = None
        index = self._inline_index
        if index is not None and index < len(self.cards):
            self.cards[index] = rebuild_card_text(self.cards[index], editor.text(), self.formatting)
            self.cards_changed.emit()
        editor.deleteLater()
        self.update()
