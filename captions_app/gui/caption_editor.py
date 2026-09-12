"""Editable table of generated caption cards: fix transcription errors,
nudge timing, merge/split/delete cards.

v1 scope: text + timing editing (per the "Text + timing only" vs "Full
editing" choice, this covers text/timing; on-screen repositioning is a
global style control in the style panel, not per-card yet).
"""

from __future__ import annotations

from dataclasses import replace

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QAbstractItemView,
    QHBoxLayout,
    QHeaderView,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from ..core.caption_builder import CaptionCard
from ..core.style import CaptionFormatting
from ..core.transcribe import Word

COL_START, COL_END, COL_TEXT, COL_ACTIONS = range(4)


def _card_text(card: CaptionCard) -> str:
    return " ".join(w.text for line in card.lines for w in line)


def rebuild_card_text(card: CaptionCard, new_text: str, formatting: CaptionFormatting) -> CaptionCard:
    """Re-tokenizes edited text and evenly redistributes timing across the
    card's original start/end span, then re-wraps into lines using the
    current density settings."""
    tokens = new_text.split()
    if not tokens:
        return replace(card, lines=[[]])

    span = max(card.end - card.start, 0.01)
    step = span / len(tokens)
    words = [
        Word(text=tok, start=card.start + i * step, end=card.start + (i + 1) * step)
        for i, tok in enumerate(tokens)
    ]

    max_chars = formatting.max_chars_per_line if formatting.max_chars_per_line else 17
    max_lines = max(1, formatting.line_count)
    lines: list[list[Word]] = [[]]
    for w in words:
        line = lines[-1]
        tentative_len = sum(len(x.text) for x in line + [w]) + len(line)
        if line and tentative_len > max_chars and len(lines) < max_lines:
            lines.append([w])
        else:
            line.append(w)

    return CaptionCard(lines=[l for l in lines if l], start=words[0].start, end=words[-1].end)


class CaptionEditorWidget(QWidget):
    cards_changed = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.formatting = CaptionFormatting()
        self.cards: list[CaptionCard] = []

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)

        self.table = QTableWidget(0, 4, self)
        self.table.setHorizontalHeaderLabels(["Start (s)", "End (s)", "Text", ""])
        self.table.horizontalHeader().setSectionResizeMode(COL_TEXT, QHeaderView.Stretch)
        self.table.verticalHeader().setVisible(False)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        layout.addWidget(self.table)

        btn_row = QHBoxLayout()
        self.add_btn = QPushButton("Add Card")
        self.merge_btn = QPushButton("Merge Selected")
        self.delete_btn = QPushButton("Delete Selected")
        for b in (self.add_btn, self.merge_btn, self.delete_btn):
            btn_row.addWidget(b)
        btn_row.addStretch(1)
        layout.addLayout(btn_row)

        self.add_btn.clicked.connect(self._add_card)
        self.merge_btn.clicked.connect(self._merge_selected)
        self.delete_btn.clicked.connect(self._delete_selected)
        self.table.itemChanged.connect(self._on_text_changed)

    def set_cards(self, cards: list[CaptionCard], formatting: CaptionFormatting) -> None:
        self.cards = list(cards)
        self.formatting = formatting
        self._refresh_table()

    def _refresh_table(self) -> None:
        self.table.blockSignals(True)
        self.table.setRowCount(len(self.cards))
        for row, card in enumerate(self.cards):
            self._populate_row(row, card)
        self.table.blockSignals(False)

    def _populate_row(self, row: int, card: CaptionCard) -> None:
        # Times are read-only here on purpose: they're edited by dragging on
        # the timeline, and editable spin boxes were being changed by stray
        # mouse-wheel scrolls over the list.
        for col, value in ((COL_START, card.start), (COL_END, card.end)):
            item = QTableWidgetItem(f"{value:.2f}")
            item.setFlags(Qt.ItemIsSelectable | Qt.ItemIsEnabled)
            self.table.setItem(row, col, item)

        text_item = QTableWidgetItem(_card_text(card))
        self.table.setItem(row, COL_TEXT, text_item)

        delete_btn = QPushButton("✕")
        delete_btn.setFixedWidth(28)
        delete_btn.clicked.connect(lambda _, r=row: self._delete_row(r))
        self.table.setCellWidget(row, COL_ACTIONS, delete_btn)

    def _on_text_changed(self, item: QTableWidgetItem) -> None:
        if item.column() != COL_TEXT:
            return
        row = item.row()
        if row >= len(self.cards):
            return
        self.cards[row] = rebuild_card_text(self.cards[row], item.text(), self.formatting)
        self.cards_changed.emit()

    def _selected_rows(self) -> list[int]:
        return sorted({idx.row() for idx in self.table.selectedIndexes()})

    def _delete_row(self, row: int) -> None:
        if 0 <= row < len(self.cards):
            del self.cards[row]
            self._refresh_table()
            self.cards_changed.emit()

    def _delete_selected(self) -> None:
        for row in reversed(self._selected_rows()):
            del self.cards[row]
        self._refresh_table()
        self.cards_changed.emit()

    def _merge_selected(self) -> None:
        rows = self._selected_rows()
        if len(rows) < 2:
            return
        merged_lines = []
        for r in rows:
            merged_lines.extend(self.cards[r].lines)
        merged = CaptionCard(
            lines=merged_lines,
            start=self.cards[rows[0]].start,
            end=self.cards[rows[-1]].end,
        )
        for r in reversed(rows):
            del self.cards[r]
        self.cards.insert(rows[0], merged)
        self._refresh_table()
        self.cards_changed.emit()

    def _add_card(self) -> None:
        last_end = self.cards[-1].end if self.cards else 0.0
        w = Word(text="new", start=last_end, end=last_end + 1.0)
        self.cards.append(CaptionCard(lines=[[w]], start=w.start, end=w.end))
        self._refresh_table()
        self.cards_changed.emit()
